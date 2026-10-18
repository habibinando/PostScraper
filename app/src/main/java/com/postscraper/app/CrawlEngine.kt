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
    val useRemoteReader: Boolean,
    val historySnapshot: Set<String>,
    val firecrawlApiKey: String = "",
    val useFirecrawl: Boolean = false,
    val groqApiKey: String = "",
    val groqModel: String = "openai/gpt-oss-20b",
    val useGroqCleaning: Boolean = false
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
    private val remoteReader: RemoteReader,
    private val firecrawlReader: FirecrawlReader,
    private val groqCleaner: GroqCleaner
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
        log("Target: ${options.maxPosts} post | min. ${options.minChars} karakter | riwayat: ${if (options.useHistory) "ON" else "OFF"} | Jina Reader: ${if (options.useRemoteReader) "ON" else "OFF"}")

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
                    options.useRemoteReader, options.useFirecrawl, options.firecrawlApiKey
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
                        var finalPost = post
                        if (options.useGroqCleaning && options.groqApiKey.isNotBlank() &&
                            post.lines.sumOf { it.length } <= 10_000
                        ) {
                            try {
                                val cleaned = groqCleaner.clean(
                                    title = post.title,
                                    text = post.lines.joinToString("\n"),
                                    apiKey = options.groqApiKey,
                                    model = options.groqModel
                                )
                                if (!cleaned.isNullOrBlank()) {
                                    val cleanedLines = cleaned.lineSequence()
                                        .map { it.trim() }
                                        .filter { it.isNotBlank() }
                                        .distinct()
                                        .toList()
                                    val cleanedChars = cleanedLines.sumOf { it.length }
                                    val originalChars = post.lines.sumOf { it.length }
                                    if (cleanedLines.isNotEmpty() && cleanedChars >= minOf(120, originalChars / 3)) {
                                        finalPost = post.copy(lines = cleanedLines)
                                        log("   Groq merapikan teks artikel.")
                                    } else {
                                        log("   Hasil Groq terlalu pendek; teks hasil scraper dipertahankan.")
                                    }
                                }
                            } catch (e: Exception) {
                                log("   Groq tidak tersedia: " + (e.message ?: e.javaClass.simpleName) + ". Teks asli dipertahankan.")
                            }
                        } else if (options.useGroqCleaning && post.lines.sumOf { it.length } > 10_000) {
                            log("   Teks lebih dari 10.000 karakter; Groq dilewati agar isi artikel tidak terpotong.")
                        }
                        collected++
                        posts.add(finalPost)
                        val chars = finalPost.lines.sumOf { it.length }
                        log("   >>> POST DIAMBIL #$collected: ${finalPost.title.take(60)} ($chars karakter)")
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
        if (posts.isEmpty()) log("Tidak ada post yang terkumpul. Coba turunkan min. karakter atau aktifkan Jina Reader.")
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
        allowRemoteReader: Boolean,
        useFirecrawl: Boolean,
        firecrawlApiKey: String
    ): PageResult {
        var notFound = false
        val doc: Document? = try {
            Jsoup.parse(pageLoader.fetchHtml(url), url)
        } catch (e: Exception) {
            if (stopCheck()) return PageResult(emptyList(), null, false)
            val msg = e.message ?: e.javaClass.simpleName
            if (msg.startsWith("HTTP 404") || msg.startsWith("HTTP 410")) notFound = true
            log("   fetch HTML biasa gagal: $msg")
            null
        }
        if (notFound) return PageResult(emptyList(), null, false)

        var best: PageResult? = doc?.let {
            ArticleExtractor.analyze(it, url, seedHost, minChars, minimalClean)
        }
        val isArticleUrl = ArticleExtractor.isPostUrl(url) || best?.isPost == true
        val bestChars = best?.post?.lines?.sumOf { it.length } ?: 0
        val needFallback = best == null ||
            (best.isPost && (best.post == null || bestChars < minChars)) ||
            (!best.isPost && best.links.size < 5)

        if (needFallback) {
            val ampUrl = ArticleExtractor.ampFallbackUrl(url)
            if (ampUrl != null && (best?.isPost == true || isArticleUrl)) {
                try {
                    log("   mencoba versi AMP...")
                    val ampHtml = pageLoader.fetchHtml(ampUrl)
                    val ampResult = ArticleExtractor.analyze(
                        Jsoup.parse(ampHtml, url), url, seedHost, minChars, minimalClean, forcePost = true
                    )
                    best = chooseBetter(best, ampResult)
                    if (ampResult.post != null) log("   AMP terbaca: ${ampResult.post.lines.sumOf { it.length }} karakter.")
                } catch (e: Exception) {
                    log("   AMP tidak tersedia: ${e.message ?: e.javaClass.simpleName}")
                }
            }

            val beforeFirecrawl = best
            val beforeFirecrawlChars = beforeFirecrawl?.post?.lines?.sumOf { it.length } ?: 0
            val needsFirecrawl = beforeFirecrawl == null ||
                ((beforeFirecrawl.isPost || isArticleUrl) &&
                    (beforeFirecrawl.post == null || beforeFirecrawlChars < minChars))
            if (useFirecrawl && firecrawlApiKey.isNotBlank() && needsFirecrawl &&
                (isArticleUrl || beforeFirecrawl?.isPost == true)
            ) {
                try {
                    log("   mencoba Firecrawl sebagai fallback cloud...")
                    val markdown = firecrawlReader.fetchMarkdown(url, firecrawlApiKey)
                    if (markdown != null) {
                        val cloudResult = ArticleExtractor.fromReaderMarkdown(
                            markdown, url, seedHost, minChars, minimalClean
                        )
                        best = chooseBetter(best, cloudResult)
                        log("   Firecrawl mengembalikan teks (" +
                            (cloudResult.post?.lines?.sumOf { it.length } ?: 0) + " karakter).")
                    } else {
                        log("   Firecrawl tidak mengembalikan Markdown yang bisa dipakai.")
                    }
                } catch (e: Exception) {
                    log("   Firecrawl gagal: " + (e.message ?: e.javaClass.simpleName))
                }
            }

            val current = best
            val currentChars = current?.post?.lines?.sumOf { it.length } ?: 0
            val needsReader = current == null ||
                ((current.isPost || isArticleUrl) && (current.post == null || currentChars < minChars))
            if (allowRemoteReader && needsReader && (isArticleUrl || current?.isPost == true)) {
                log("   mencoba Jina Reader sebagai fallback jarak jauh...")
                val markdown = remoteReader.fetchMarkdown(url)
                if (markdown != null) {
                    val remote = ArticleExtractor.fromReaderMarkdown(
                        markdown, url, seedHost, minChars, minimalClean
                    )
                    best = chooseBetter(best, remote)
                    val chars = remote.post?.lines?.sumOf { it.length } ?: 0
                    log(if (chars >= minChars) "   Jina Reader berhasil membaca artikel ($chars karakter)."
                        else "   Jina Reader hanya menemukan $chars karakter.")
                } else {
                    log("   Jina Reader gagal, dibatasi layanan, atau tidak tersedia.")
                }
            }

            val afterRemote = best
            val afterRemoteChars = afterRemote?.post?.lines?.sumOf { it.length } ?: 0
            val needsWebView = afterRemote == null ||
                ((afterRemote.isPost || isArticleUrl) && (afterRemote.post == null || afterRemoteChars < minChars)) ||
                (!afterRemote.isPost && afterRemote.links.size < 5)
            if (needsWebView) {
                log("   mencoba render WebView (JavaScript)...")
                val html = pageLoader.renderWithWebView(url)
                if (html != null) {
                    val rendered = ArticleExtractor.analyze(
                        Jsoup.parse(html, url), url, seedHost, minChars, minimalClean,
                        forcePost = isArticleUrl
                    ).copy(rendered = true)
                    best = chooseBetter(best, rendered)
                } else {
                    log("   render WebView gagal / timeout")
                }
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
            allowRemoteReader = allowRemoteReader,
            useFirecrawl = useFirecrawl,
            firecrawlApiKey = firecrawlApiKey
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
        allowRemoteReader: Boolean,
        useFirecrawl: Boolean,
        firecrawlApiKey: String
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
            log("   menggabungkan halaman artikel ${pageCount + 1}: $nextUrl")
            val part = fetchArticlePart(
                nextUrl, seedHost, minChars, minimalClean, allowRemoteReader, useFirecrawl, firecrawlApiKey
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
            log("   artikel berseri digabung menjadi satu post ($pageCount halaman, ${mergedLines.sumOf { it.length }} karakter).")
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
        allowRemoteReader: Boolean,
        useFirecrawl: Boolean,
        firecrawlApiKey: String
    ): PageResult? {
        var best: PageResult? = try {
            ArticleExtractor.analyze(
                Jsoup.parse(pageLoader.fetchHtml(url), url), url, seedHost, minChars, minimalClean, forcePost = true
            )
        } catch (_: Exception) {
            null
        }
        val chars = best?.post?.lines?.sumOf { it.length } ?: 0
        if (best == null || best.post == null || chars < 100) {
            val ampUrl = ArticleExtractor.ampFallbackUrl(url)
            if (ampUrl != null) {
                try {
                    val amp = ArticleExtractor.analyze(
                        Jsoup.parse(pageLoader.fetchHtml(ampUrl), url), url, seedHost, minChars, minimalClean, forcePost = true
                    )
                    best = chooseBetter(best, amp)
                } catch (_: Exception) { }
            }
        }
        val preCloudChars = best?.post?.lines?.sumOf { it.length } ?: 0
        if (useFirecrawl && firecrawlApiKey.isNotBlank() &&
            (best == null || best.post == null || preCloudChars < 100)
        ) {
            try {
                val markdown = firecrawlReader.fetchMarkdown(url, firecrawlApiKey)
                if (markdown != null) {
                    best = chooseBetter(
                        best,
                        ArticleExtractor.fromReaderMarkdown(markdown, url, seedHost, minChars, minimalClean)
                    )
                }
            } catch (e: Exception) {
                log("   Firecrawl gagal pada halaman lanjutan: " + (e.message ?: e.javaClass.simpleName))
            }
        }
        val bestChars = best?.post?.lines?.sumOf { it.length } ?: 0
        if (allowRemoteReader && (best == null || best.post == null || bestChars < 100)) {
            val markdown = remoteReader.fetchMarkdown(url)
            if (markdown != null) {
                best = chooseBetter(
                    best,
                    ArticleExtractor.fromReaderMarkdown(markdown, url, seedHost, minChars, minimalClean)
                )
            }
        }
        return best
    }

}
