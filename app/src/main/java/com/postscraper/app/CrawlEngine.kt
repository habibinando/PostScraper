package com.postscraper.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

internal data class CrawlOptions(
    val seed: String,
    val seedHost: String,
    val maxPosts: Int,
    val minChars: Int,
    val useHistory: Boolean,
    val minimalClean: Boolean,
    val historySnapshot: Set<String>,
    val localModelPath: String = "",
    val useLocalModelCleaning: Boolean = false
)

internal data class CrawlSummary(
    val posts: List<PostData>,
    val pagesProcessed: Int,
    val skippedVisited: Int,
    val renderedPages: Int,
    val remainingQueue: Int,
    val wasStopped: Boolean
)

/** Crawl orchestration and pagination-aware article fetching, independent from UI state. */
internal class CrawlEngine(
    private val pageLoader: PageLoader,
    private val localModelCleaner: LocalModelCleaner
) {
    private var stopCheck: () -> Boolean = { false }
    private var logSink: (String) -> Unit = {}

    private fun log(message: String) = logSink(message)

    suspend fun crawl(
        options: CrawlOptions,
        shouldStop: () -> Boolean,
        onLog: (String) -> Unit,
        onProgress: (collected: Int, queueSize: Int) -> Unit
    ): CrawlSummary {
        stopCheck = shouldStop
        logSink = onLog

        val postQueue = ArrayDeque<String>()
        val pageQueue = ArrayDeque<String>()
        val queued = HashSet<String>()
        val posts = ArrayList<PostData>()
        var collected = 0
        var crawledPages = 0
        var skippedVisited = 0
        var renderedPages = 0

        fun enqueue(link: String): Boolean {
            if (link.isBlank() || !queued.add(link)) return false
            if (ArticleExtractor.isPostUrl(link)) postQueue.add(link) else pageQueue.add(link)
            return true
        }

        enqueue(ArticleExtractor.normalizeLink(options.seed, options.seed, options.seedHost) ?: options.seed)
        val maxPages = maxOf(60, options.maxPosts * 8)
        log("== MULAI CRAWL ==")
        log("Seed : ${options.seed}")
        log("Target: ${options.maxPosts} post | min. ${options.minChars} karakter | riwayat: ${if (options.useHistory) "ON" else "OFF"} | fallback: HTML → AMP → WebView → Qwen lokal")

        while ((postQueue.isNotEmpty() || pageQueue.isNotEmpty()) &&
            collected < options.maxPosts && !stopCheck()
        ) {
            if (crawledPages >= maxPages) {
                log("\nBatas $maxPages halaman tercapai, crawl dihentikan.")
                break
            }

            val url = if (postQueue.isNotEmpty()) postQueue.removeFirst() else pageQueue.removeFirst()
            if (options.useHistory && url in options.historySnapshot) {
                skippedVisited++
                continue
            }

            crawledPages++
            log("\n[Halaman $crawledPages | $collected/${options.maxPosts} post] $url")
            onProgress(collected, postQueue.size + pageQueue.size)

            try {
                val result = processPage(
                    url, options.seedHost, options.minChars, options.minimalClean,
                    options.localModelPath, options.useLocalModelCleaning
                )
                if (stopCheck()) break
                if (result.rendered) renderedPages++

                var newLinks = 0
                for (link in result.links) if (enqueue(link)) newLinks++
                log("   link baru: $newLinks | antrian post: ${postQueue.size} | antrian lain: ${pageQueue.size}")

                val post = result.post
                if (post != null && post.lines.isNotEmpty()) {
                    val duplicate = posts.any { it.url == post.url } ||
                        (options.useHistory && post.url in options.historySnapshot)
                    if (duplicate) {
                        log("   post duplikat tidak disimpan (URL kanonis sudah ada).")
                    } else {
                        collected++
                        posts.add(post)
                        val chars = post.lines.sumOf { it.length }
                        log("   >>> POST DIAMBIL #$collected: ${post.title.take(60)} ($chars karakter)")
                    }
                } else if (result.isPost) {
                    log("   (teks artikel tidak terbaca atau totalnya masih di bawah ${options.minChars} karakter)")
                } else {
                    log("   (halaman daftar/kategori — hanya diambil linknya)")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!stopCheck()) log("   ERROR: ${e.message ?: e.javaClass.simpleName}")
            }

            if (!stopCheck() && (postQueue.isNotEmpty() || pageQueue.isNotEmpty()) &&
                collected < options.maxPosts
            ) delay(800)
        }

        log("\n== HASIL CRAWL ==")
        log("Post terkumpul : $collected")
        log("Halaman diproses: $crawledPages")
        log("Halaman WebView: $renderedPages")
        log("Sudah di riwayat: $skippedVisited")
        if (stopCheck()) log("(crawling dihentikan; hasil yang sudah terkumpul dipertahankan)")
        if (posts.isEmpty()) log("Belum ada post yang berhasil diekstrak. Coba URL artikel langsung atau pastikan situs bisa diakses.")
        return CrawlSummary(
            posts = posts.toList(),
            pagesProcessed = crawledPages,
            skippedVisited = skippedVisited,
            renderedPages = renderedPages,
            remainingQueue = postQueue.size + pageQueue.size,
            wasStopped = stopCheck()
        )
    }

    private suspend fun processPage(
        url: String,
        seedHost: String,
        minChars: Int,
        minimalClean: Boolean,
        localModelPath: String,
        useLocalModelFallback: Boolean
    ): PageResult {
        var notFound = false
        var fallbackHtml: String? = null
        var sourceDoc: Document? = null

        val doc: Document? = try {
            val html = pageLoader.fetchHtml(url)
            fallbackHtml = html
            Jsoup.parse(html, url).also { sourceDoc = it }
        } catch (e: Exception) {
            if (stopCheck()) return PageResult(emptyList(), null, false)
            val msg = e.message ?: e.javaClass.simpleName
            if (msg.startsWith("HTTP 404") || msg.startsWith("HTTP 410")) notFound = true
            log("   fetch HTML biasa gagal: $msg")
            null
        }
        if (notFound || stopCheck()) return PageResult(emptyList(), null, false)

        var best: PageResult? = doc?.let {
            ArticleExtractor.analyze(it, url, seedHost, minChars, minimalClean)
        }
        val isArticleUrl = ArticleExtractor.isPostUrl(url) || best?.isPost == true
        val initialChars = best?.post?.lines?.sumOf { it.length } ?: 0
        val needFallback = best == null ||
            (isArticleUrl && (best.post == null || initialChars < minChars)) ||
            (!best.isPost && best.links.size < 5)

        if (needFallback && !stopCheck()) {
            val ampUrl = ArticleExtractor.ampFallbackUrl(url)
            if (ampUrl != null && (best?.isPost == true || isArticleUrl)) {
                try {
                    log("   mencoba versi AMP...")
                    val ampHtml = pageLoader.fetchHtml(ampUrl)
                    if (!stopCheck()) {
                        val ampDoc = Jsoup.parse(ampHtml, url)
                        val ampResult = ArticleExtractor.analyze(
                            ampDoc, url, seedHost, minChars, minimalClean, forcePost = true
                        )
                        val beforeChars = best?.post?.lines?.sumOf { it.length } ?: 0
                        val ampChars = ampResult.post?.lines?.sumOf { it.length } ?: 0
                        if (ampChars > beforeChars || fallbackHtml == null) {
                            fallbackHtml = ampHtml
                            sourceDoc = ampDoc
                        }
                        best = chooseBetter(best, ampResult)
                        if (ampResult.post != null) {
                            log("   AMP terbaca: \${ampResult.post.lines.sumOf { it.length }} karakter.")
                        }
                    }
                } catch (e: Exception) {
                    if (!stopCheck()) log("   AMP tidak tersedia: \${e.message ?: e.javaClass.simpleName}")
                }
            }

            val currentChars = best?.post?.lines?.sumOf { it.length } ?: 0
            val needsWebView = best == null ||
                ((best.isPost || isArticleUrl) && (best.post == null || currentChars < minChars)) ||
                (!best.isPost && best.links.size < 5)

            if (needsWebView && !stopCheck()) {
                log("   mencoba WebView JavaScript (batas tunggu 18 detik)...")
                val renderedHtml = pageLoader.renderWithWebView(url)
                if (renderedHtml != null && !stopCheck()) {
                    val renderedDoc = Jsoup.parse(renderedHtml, url)
                    val rendered = ArticleExtractor.analyze(
                        renderedDoc, url, seedHost, minChars, minimalClean,
                        forcePost = isArticleUrl
                    ).copy(rendered = true)
                    val previousChars = best?.post?.lines?.sumOf { it.length } ?: 0
                    val renderedChars = rendered.post?.lines?.sumOf { it.length } ?: 0
                    val renderedBodyChars = renderedDoc.body()?.text()?.length ?: 0
                    val previousBodyChars = fallbackHtml?.let {
                        Jsoup.parse(it, url).body()?.text()?.length ?: 0
                    } ?: 0
                    if (renderedChars > previousChars || fallbackHtml == null || renderedBodyChars > previousBodyChars) {
                        fallbackHtml = renderedHtml
                        sourceDoc = renderedDoc
                    }
                    best = chooseBetter(best, rendered)
                } else if (!stopCheck()) {
                    log("   render WebView gagal / timeout")
                }
            }
        }

        if (stopCheck()) return PageResult(best?.links.orEmpty(), null, isArticleUrl, rendered = best?.rendered ?: false)

        val bestChars = best?.post?.lines?.sumOf { it.length } ?: 0
        val needsLocalFallback = useLocalModelFallback && localModelPath.isNotBlank() &&
            isArticleUrl && (best == null || best.post == null || bestChars < minChars)
        if (needsLocalFallback && fallbackHtml != null && !stopCheck()) {
            try {
                log("   parser belum mendapat isi yang cukup; mencoba ekstraksi dengan Qwen lokal...")
                val fallbackTitle = best?.post?.title?.takeIf { it.isNotBlank() }
                    ?: sourceDoc?.title()?.takeIf { it.isNotBlank() }
                    ?: url.substringAfterLast('/').ifBlank { url }
                val extracted = localModelCleaner.extractArticle(
                    title = fallbackTitle,
                    html = fallbackHtml!!,
                    modelPath = localModelPath,
                    minChars = minChars
                )
                if (!stopCheck() && !extracted.isNullOrBlank() && extracted.length >= minChars) {
                    val discoveredLinks = sourceDoc?.let {
                        ArticleExtractor.analyze(it, url, seedHost, minChars, minimalClean).links
                    }.orEmpty()
                    val links = (best?.links.orEmpty() + discoveredLinks).distinct()
                    val canonical = best?.post?.url
                        ?: ArticleExtractor.normalizeLink(url, url, seedHost)
                        ?: url
                    val modelPost = PageResult(
                        links = links,
                        post = PostData(canonical, fallbackTitle, extracted.lineSequence()
                            .map { it.trim() }.filter { it.isNotBlank() }.toList()),
                        isPost = true,
                        rendered = best?.rendered ?: false,
                        nextPageUrl = best?.nextPageUrl
                    )
                    best = chooseBetter(best, modelPost)
                    log("   Qwen lokal berhasil mengambil isi artikel (\${extracted.length} karakter).")
                } else if (!stopCheck()) {
                    log("   Qwen lokal tidak menemukan teks artikel yang cukup; hasil asli dipertahankan.")
                }
            } catch (e: Exception) {
                if (!stopCheck()) log("   Qwen fallback gagal: \${e.message ?: e.javaClass.simpleName}")
            }
        }

        val selected = best ?: return PageResult(emptyList(), null, isArticleUrl)
        if (!selected.isPost && !isArticleUrl) return selected
        return combineArticlePages(
            first = selected,
            originalUrl = url,
            seedHost = seedHost,
            minChars = minChars,
            minimalClean = minimalClean,
            localModelPath = localModelPath,
            useLocalModelFallback = useLocalModelFallback
        )
    }

    private fun chooseBetter(current: PageResult?, candidate: PageResult): PageResult {
        if (current == null) return candidate
        val currentChars = current.post?.lines?.sumOf { it.length } ?: 0
        val candidateChars = candidate.post?.lines?.sumOf { it.length } ?: 0
        val chooseCandidate = candidateChars > currentChars ||
            (current.post == null && candidate.post != null) ||
            (current.links.isEmpty() && candidate.links.isNotEmpty())
        val chosen = if (chooseCandidate) candidate else current
        return chosen.copy(
            links = (current.links + candidate.links).distinct(),
            isPost = current.isPost || candidate.isPost,
            rendered = current.rendered || candidate.rendered,
            nextPageUrl = chosen.nextPageUrl ?: current.nextPageUrl ?: candidate.nextPageUrl
        )
    }

    private suspend fun combineArticlePages(
        first: PageResult,
        originalUrl: String,
        seedHost: String,
        minChars: Int,
        minimalClean: Boolean,
        localModelPath: String,
        useLocalModelFallback: Boolean
    ): PageResult {
        val root = first.post ?: return first
        val mergedLines = root.lines.toMutableList()
        val mergedLinks = LinkedHashSet(first.links)
        val seenPages = HashSet<String>().apply {
            add(originalUrl)
            add(root.url)
        }
        var nextUrl = first.nextPageUrl
        var pageCount = 1
        var usedWebView = first.rendered

        while (nextUrl != null && pageCount < 12 && !stopCheck() && seenPages.add(nextUrl)) {
            log("   menggabungkan halaman artikel \${pageCount + 1}: $nextUrl")
            val part = fetchArticlePart(
                nextUrl, seedHost, minChars, minimalClean, localModelPath, useLocalModelFallback
            )
            val fragment = part?.post
            if (fragment == null || !ArticleExtractor.sameArticle(root, fragment)) {
                log("   batas halaman artikel terdeteksi; berhenti menggabungkan.")
                break
            }
            for (line in fragment.lines) {
                if (line.isNotBlank() && mergedLines.lastOrNull() != line) mergedLines.add(line)
            }
            mergedLinks.addAll(part.links)
            usedWebView = usedWebView || part.rendered
            nextUrl = part.nextPageUrl
            pageCount++
        }

        if (pageCount > 1) {
            log("   artikel berseri digabung menjadi satu post ($pageCount halaman, \${mergedLines.sumOf { it.length }} karakter).")
        }
        val totalChars = mergedLines.sumOf { it.length }
        val combinedPost = if (totalChars >= minChars) PostData(root.url, root.title, mergedLines)
            else {
                log("   total artikel masih di bawah batas $minChars karakter setelah penggabungan.")
                null
            }
        return PageResult(
            links = mergedLinks.toList(),
            post = combinedPost,
            isPost = true,
            rendered = usedWebView,
            nextPageUrl = null
        )
    }

    private suspend fun fetchArticlePart(
        url: String,
        seedHost: String,
        minChars: Int,
        minimalClean: Boolean,
        localModelPath: String,
        useLocalModelFallback: Boolean
    ): PageResult? {
        var fallbackHtml: String? = null
        var sourceDoc: Document? = null
        var best: PageResult? = try {
            val html = pageLoader.fetchHtml(url)
            fallbackHtml = html
            Jsoup.parse(html, url).also { sourceDoc = it }.let {
                ArticleExtractor.analyze(it, url, seedHost, minChars, minimalClean, forcePost = true)
            }
        } catch (_: Exception) {
            null
        }

        var bestChars = best?.post?.lines?.sumOf { it.length } ?: 0
        if ((best == null || best.post == null || bestChars < minChars) && !stopCheck()) {
            val ampUrl = ArticleExtractor.ampFallbackUrl(url)
            if (ampUrl != null) {
                try {
                    val html = pageLoader.fetchHtml(ampUrl)
                    val ampDoc = Jsoup.parse(html, url)
                    val amp = ArticleExtractor.analyze(
                        ampDoc, url, seedHost, minChars, minimalClean, forcePost = true
                    )
                    val ampChars = amp.post?.lines?.sumOf { it.length } ?: 0
                    if (ampChars > bestChars || fallbackHtml == null) {
                        fallbackHtml = html
                        sourceDoc = ampDoc
                    }
                    best = chooseBetter(best, amp)
                    bestChars = best?.post?.lines?.sumOf { it.length } ?: 0
                } catch (_: Exception) { }
            }
        }

        if ((best == null || best.post == null || bestChars < minChars) && !stopCheck()) {
            val html = pageLoader.renderWithWebView(url)
            if (html != null && !stopCheck()) {
                val renderedDoc = Jsoup.parse(html, url)
                val rendered = ArticleExtractor.analyze(
                    renderedDoc, url, seedHost, minChars, minimalClean, forcePost = true
                ).copy(rendered = true)
                val renderedChars = rendered.post?.lines?.sumOf { it.length } ?: 0
                val renderedBodyChars = renderedDoc.body()?.text()?.length ?: 0
                val previousBodyChars = fallbackHtml?.let {
                    Jsoup.parse(it, url).body()?.text()?.length ?: 0
                } ?: 0
                if (renderedChars > bestChars || fallbackHtml == null || renderedBodyChars > previousBodyChars) {
                    fallbackHtml = html
                    sourceDoc = renderedDoc
                }
                best = chooseBetter(best, rendered)
                bestChars = best?.post?.lines?.sumOf { it.length } ?: 0
            }
        }

        if (useLocalModelFallback && localModelPath.isNotBlank() &&
            (best == null || best.post == null || bestChars < minChars) &&
            fallbackHtml != null && !stopCheck()
        ) {
            try {
                val title = best?.post?.title?.takeIf { it.isNotBlank() }
                    ?: sourceDoc?.title()?.takeIf { it.isNotBlank() }
                    ?: url.substringAfterLast('/').ifBlank { url }
                val extracted = localModelCleaner.extractArticle(title, fallbackHtml!!, localModelPath, minChars)
                if (!stopCheck() && !extracted.isNullOrBlank() && extracted.length >= minChars) {
                    val canonical = best?.post?.url ?: ArticleExtractor.normalizeLink(url, url, seedHost) ?: url
                    best = PageResult(
                        links = (best?.links.orEmpty() + sourceDoc?.let {
                            ArticleExtractor.analyze(it, url, seedHost, minChars, minimalClean, forcePost = true).links
                        }.orEmpty()).distinct(),
                        post = PostData(canonical, title, extracted.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()),
                        isPost = true,
                        rendered = best?.rendered ?: false,
                        nextPageUrl = best?.nextPageUrl
                    )
                }
            } catch (_: Exception) { }
        }
        return best
    }


}
