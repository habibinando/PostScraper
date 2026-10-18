package com.postscraper.app

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.util.IdentityHashMap
import java.util.Locale

internal data class PostData(val url: String, val title: String, val lines: List<String>)

internal data class PageResult(
    val links: List<String>,
    val post: PostData?,
    val isPost: Boolean,
    val rendered: Boolean = false,
    val nextPageUrl: String? = null
)

/**
 * URL classification, article extraction and pagination detection.
 * This stays outside MainActivity so extraction rules can evolve independently.
 */
internal object ArticleExtractor {

    /** Parameter query yang diabaikan saat normalisasi (tracking/mobile). */
    private val junkParams = setOf(
        "m", "showcomment", "utm_source", "utm_medium", "utm_campaign",
        "utm_term", "utm_content", "fbclid", "gclid", "referrer"
    )

    /** Ekstensi file yang bukan halaman (tidak di-crawl). */
    private val assetExt = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "svg", "ico", "pdf", "zip", "rar",
        "apk", "exe", "mp3", "mp4", "avi", "mkv", "css", "js", "json", "xml",
        "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx"
    )

    /** Segmen path yang menandakan halaman index/non-post. */
    private val skipSegments = setOf(
        "search", "label", "labels", "tag", "tags", "category", "categories",
        "kategori", "page", "author", "authors", "feed", "archive", "archives",
        "topik", "topic", "topics", "user", "users", "login", "register",
        "contact", "kontak", "about", "tentang", "privacy", "privacy-policy",
        "terms", "sitemap", "amp", "wp-admin", "wp-content", "wp-json"
    )

    /** Elemen yang pasti bukan isi artikel. */
    private val hardNoise = "script, style, noscript, iframe, ins, nav, header, footer, aside, " +
            "svg, canvas, select, input, textarea, template, dialog, button, " +
            "[role=navigation], [role=banner], [role=contentinfo], [role=complementary], " +
            "[aria-hidden=true], [hidden]"

    /** Elemen sampah yang bisa ada DI DALAM kontainer artikel. */
    private val softNoise = ".sidebar, #sidebar, .related, #related, .comments, #comments, " +
            ".comments-area, .comment-list, .comment-respond, .share, .sharing, .social, " +
            ".breadcrumb, .breadcrumbs, .newsletter, .popup, .modal, .advert, .ads, .ad, " +
            ".adsbygoogle, .banner, .baca-juga, .read-more, .readmore, .author-box, " +
            ".post-meta, .entry-meta, .post-tags, .tags, .tag-list, " +
            "[class*=related-post], [class*=related_post], [class*=relatedpost], " +
            "[class*=share-], [class*=social-], [class*=advert], [class*=newsletter], " +
            "[class*=popup], [class*=breadcrumb]"

    private val noiseExact = setOf(
        "komentar", "share", "tweet", "iklan", "advertisement", "sponsored",
        "related", "tags", "tag", "bagikan", "subscribe"
    )

    private val noisePrefixes = listOf(
        "baca juga", "baca lainnya", "baca selengkapnya", "simak juga", "lihat juga",
        "lihat lainnya", "artikel terkait", "berita terkait", "postingan terkait",
        "related post", "related article", "related:", "read more", "read also",
        "see also", "follow us", "ikuti kami", "bagikan", "share this", "share on",
        "tinggalkan komentar", "leave a comment", "berlangganan", "scroll to continue",
        "sumber:", "source:", "tags:", "tag:", "kategori:", "category:", "penulis:", "editor:"
    )

    private val urlRegex = Regex("(https?://|www\\.)\\S+", RegexOption.IGNORE_CASE)
    private val emailRegex = Regex("\\S+@\\S+\\.\\S+")
    private val sepRegex = Regex("[=_~*#\\-]{3,}")
    private val emptyBracketRegex = Regex("[(\\[]\\s*[)\\]]")
    private val invisibleRegex = Regex("[\\u200B-\\u200D\\uFEFF]")
    private val wsRegex = Regex("\\s+")
    private val datedPathRegex = Regex("/\\d{4}/\\d{2}/")

    fun seedHostOf(seedUrl: String): String? {
        return try {
            val fixed = if (!seedUrl.startsWith("http://", true) &&
                !seedUrl.startsWith("https://", true)) "https://$seedUrl" else seedUrl
            URI(fixed).host?.lowercase()?.removePrefix("www.")
        } catch (e: Exception) {
            null
        }
    }

    fun normalizeLink(href: String, baseUri: String, seedHost: String): String? {
        return try {
            if (href.isBlank()) return null
            val abs = if (href.startsWith("http://", true) || href.startsWith("https://", true)) {
                href
            } else {
                URI(baseUri).resolve(href).toString()
            }
            val uri = URI(abs)
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
            if (host != seedHost) return null

            var path = uri.path ?: "/"
            if (path.isEmpty()) path = "/"
            if (path.length > 1 && path.endsWith("/")) path = path.dropLast(1)

            val lastSeg = path.substringAfterLast('/')
            val ext = if (lastSeg.contains('.')) lastSeg.substringAfterLast('.').lowercase() else ""
            if (ext in assetExt) return null

            val cleanQuery = uri.rawQuery
                ?.split("&")
                ?.filter { p -> p.substringBefore("=").lowercase() !in junkParams }
                ?.joinToString("&")

            "$scheme://$host$path${if (!cleanQuery.isNullOrEmpty()) "?$cleanQuery" else ""}"
        } catch (e: Exception) {
            null
        }
    }

    fun isPostUrl(url: String): Boolean {
        return try {
            val u = URI(url)
            val path = u.path ?: "/"
            val segs = path.split("/").filter { it.isNotEmpty() }
            if (segs.isEmpty()) return false
            if (segs.any { it.lowercase() in skipSegments }) return false
            val last = segs.last().lowercase()
            val dated = datedPathRegex.containsMatchIn(path) && last.any { it.isLetter() }
            if (u.rawQuery != null && !dated) return false
            dated || last.endsWith(".html") || last.endsWith(".htm") ||
                    last.count { it == '-' } >= 2
        } catch (e: Exception) {
            false
        }
    }

    private fun looksLikePost(url: String, doc: Document): Boolean {
        val ogType = doc.selectFirst("meta[property=og:type]")?.attr("content").orEmpty()
        return ogType.equals("article", true) || isPostUrl(url)
    }

    fun ampFallbackUrl(url: String): String? {
        return try {
            val uri = URI(url)
            val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
            if (host != "jalantikus.com") return null
            val path = uri.rawPath?.trimEnd('/') ?: return null
            if (path.isBlank() || path == "/" || path == "/amp" || path.startsWith("/amp/")) return null
            val scheme = uri.scheme?.lowercase() ?: "https"
            val authority = uri.rawAuthority ?: return null
            scheme + "://" + authority + "/amp" + path + "/"
        } catch (_: Exception) {
            null
        }
    }

    fun analyze(
        doc: Document,
        url: String,
        seedHost: String,
        minChars: Int,
        minimalClean: Boolean
    ): PageResult {
        val isPost = looksLikePost(url, doc)
        val pagination = if (isPost) findArticlePagination(doc, url, seedHost) else emptyList()
        val paginationUrls = pagination.map { it.first }.toSet()
        val nextPage = pagination.firstOrNull { isNextLabel(it.second) }?.first
            ?: pagination.firstOrNull { pageNumber(it.first) > pageNumber(url) }?.first

        val canonical = doc.selectFirst("link[rel=canonical][href]")
            ?.attr("abs:href")
            ?.let { normalizeLink(it, url, seedHost) }
            ?: normalizeLink(url, url, seedHost)
            ?: url

        val links = doc.select("a[href]")
            .mapNotNull { normalizeLink(it.attr("abs:href"), url, seedHost) }
            .filter { it !in paginationUrls }
            .distinct()

        var post: PostData? = null
        if (isPost) {
            val title = pickTitle(doc, url)
            val lines = extractLines(doc, minimalClean, minChars)
            // Keep short fragments so the caller can join pages before applying minChars.
            if (lines.sumOf { it.length } >= 40) post = PostData(canonical, title, lines)
        }
        return PageResult(links, post, isPost, rendered = false, nextPageUrl = nextPage)
    }

    private fun findArticlePagination(doc: Document, currentUrl: String, seedHost: String): List<Pair<String, String>> {
        val found = LinkedHashMap<String, String>()
        val anchors = doc.select(
            "link[rel=next][href], a[rel=next][href], a.next[href], a.next-page[href], " +
            ".pagination a[href], [class*=pagination] a[href], .page-links a[href], " +
            ".post-page-numbers[href], .pagenav a[href], .article-pagination a[href], " +
            "a[aria-label*=next i][href], a[title*=next i][href], a[href]"
        )
        for (el in anchors) {
            val href = el.attr("abs:href")
            val normalized = normalizeLink(href, currentUrl, seedHost) ?: continue
            if (normalized == normalizeLink(currentUrl, currentUrl, seedHost)) continue
            val label = listOf(el.text(), el.attr("aria-label"), el.attr("title"), el.attr("rel"))
                .firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            val relNext = el.attr("rel").split(Regex("\\s+")).any { it.equals("next", true) }
            val inPagination = el.parents().take(5).any { parent ->
                val clue = (parent.className() + " " + parent.id()).lowercase(Locale.ROOT)
                listOf("pagination", "page-links", "page-numbers", "pagenav", "post-pagination", "article-pagination")
                    .any { clue.contains(it) }
            }
            val explicit = relNext || el.hasClass("next") || el.hasClass("next-page") ||
                isNextLabel(label) ||
                Regex("^(halaman\\s*)?\\d+$", RegexOption.IGNORE_CASE).matches(label)
            if (!explicit && !inPagination) continue
            if (!isLikelyContinuation(currentUrl, normalized)) continue
            found.putIfAbsent(normalized, label)
        }
        return found.entries.map { it.key to it.value }.sortedBy { pageNumber(it.first) }
    }

    private fun isNextLabel(label: String): Boolean {
        val value = label.lowercase(Locale.ROOT).replace(Regex("[\\s›»→]+"), " ").trim()
        return value in setOf("next", "next page", "selanjutnya", "berikutnya", "halaman berikutnya", "lanjut", "»", "›", "→") ||
            value.startsWith("halaman berikutnya")
    }

    private fun pageNumber(url: String): Int {
        return try {
            val uri = URI(url)
            val queryPage = uri.rawQuery.orEmpty().split("&").mapNotNull { p ->
                val key = p.substringBefore("=").lowercase(Locale.ROOT)
                val value = p.substringAfter("=", "")
                if (key in setOf("page", "paged", "page_number", "pagenum", "pg") ||
                    (key == "p" && value.toIntOrNull() != null)
                ) value.toIntOrNull() else null
            }.firstOrNull()
            queryPage
                ?: Regex("(?:/page/|/)(\\d+)/?$", RegexOption.IGNORE_CASE)
                    .find(uri.path.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("-(?:page-)?(\\d+)(?:\\.[a-z]+)?$", RegexOption.IGNORE_CASE)
                    .find(uri.path.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: 1
        } catch (_: Exception) {
            1
        }
    }

    private fun continuationBase(url: String): String {
        return try {
            val uri = URI(url)
            val path = (uri.path ?: "/")
                .replace(Regex("/(?:page/)?\\d+/?$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("-(?:page-)?\\d+(?=\\.[a-z]+$|$)", RegexOption.IGNORE_CASE), "")
            (uri.host.orEmpty().lowercase(Locale.ROOT) + path).trimEnd('/')
        } catch (_: Exception) {
            url.substringBefore("?").trimEnd('/')
        }
    }

    private fun isLikelyContinuation(currentUrl: String, candidateUrl: String): Boolean {
        if (currentUrl == candidateUrl) return false
        val a = try { URI(currentUrl) } catch (_: Exception) { return false }
        val b = try { URI(candidateUrl) } catch (_: Exception) { return false }
        if (!a.host.equals(b.host, true)) return false
        val samePath = (a.path ?: "/").trimEnd('/') == (b.path ?: "/").trimEnd('/')
        val hasPageParam = Regex("(^|&)(page|paged|page_number|pagenum|pg|p)=\\d+", RegexOption.IGNORE_CASE)
        if (samePath && (hasPageParam.containsMatchIn(a.rawQuery.orEmpty()) ||
                hasPageParam.containsMatchIn(b.rawQuery.orEmpty())) &&
            pageNumber(candidateUrl) > pageNumber(currentUrl)
        ) return true
        return continuationBase(currentUrl) == continuationBase(candidateUrl) &&
            pageNumber(candidateUrl) > pageNumber(currentUrl)
    }

    fun sameArticle(root: PostData, candidate: PostData): Boolean {
        if (root.url == candidate.url) return true
        fun normalizedTitle(value: String): String = value.lowercase(Locale.ROOT)
            .replace(Regex("[|–—-]?\\s*(halaman|page)\\s*\\d+\\s*$"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
        val a = normalizedTitle(root.title)
        val b = normalizedTitle(candidate.title)
        return a.isNotBlank() && b.isNotBlank() && (a == b || a.startsWith(b) || b.startsWith(a))
    }

    fun fromReaderMarkdown(
        markdown: String,
        url: String,
        seedHost: String,
        minChars: Int,
        minimalClean: Boolean
    ): PageResult {
        val linksPattern = Regex("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)")
        val markdownLinks = linksPattern.findAll(markdown).mapNotNull { match ->
            val label = match.groupValues[1].trim()
            val href = match.groupValues[2].trimEnd('.', ',', ';')
            val normalized = normalizeLink(href, url, seedHost) ?: return@mapNotNull null
            Triple(normalized, label, isLikelyContinuation(url, normalized) &&
                (isNextLabel(label) ||
                    Regex("^(halaman\\s*)?\\d+$", RegexOption.IGNORE_CASE).matches(label)))
        }.toList()
        val paginationUrls = markdownLinks.filter { it.third }.map { it.first }.toSet()
        val nextPage = markdownLinks.firstOrNull { it.third && isNextLabel(it.second) }?.first
            ?: markdownLinks.firstOrNull { it.third && pageNumber(it.first) > pageNumber(url) }?.first
        val links = markdownLinks.map { it.first }.filter { it !in paginationUrls }.distinct()

        val lines = LinkedHashSet<String>()
        var title = ""
        for (rawLine in markdown.lineSequence()) {
            val raw = rawLine.trim()
            if (raw.isBlank()) continue
            if (raw.startsWith("URL Source:", true) || raw.startsWith("Title:", true) ||
                raw.startsWith("Markdown Content:", true) || raw.startsWith("Published Time:", true)
            ) {
                if (title.isBlank() && raw.startsWith("Title:", true)) title = raw.substringAfter(":").trim()
                continue
            }
            val heading = raw.matches(Regex("^#{1,2}\\s+.+"))
            val text = cleanLine(
                raw.replace(Regex("^#{1,6}\\s*"), "")
                    .replace(Regex("!\\[([^]]*)]\\([^)]+\\)"), "$1")
                    .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
                    .replace(Regex("^\\s*[-*+]\\s+"), "")
                    .replace(Regex("[\\x60*_~]"), "")
                    .replace(Regex("<[^>]+>"), " ")
            )
            if (text.isBlank() || text.none { it.isLetter() }) continue
            if (title.isBlank() && heading && text.length >= 8) {
                title = text
                continue
            }
            if (minimalClean && isNoiseLine(text)) continue
            if (minimalClean && text.split(' ').size < 2) continue
            lines.add(text)
        }
        if (title.isBlank()) title = cleanLine(url.trimEnd('/').substringAfterLast('/').replace('-', ' '))
        val canonical = normalizeLink(url, url, seedHost) ?: url
        val post = if (lines.sumOf { it.length } >= 40) PostData(canonical, title, lines.toList()) else null
        return PageResult(links, post, isPost = true, rendered = true, nextPageUrl = nextPage)
    }



    private fun pickTitle(doc: Document, url: String): String {
        val h1 = doc.select("h1").map { it.text().trim() }
            .filter { it.length >= 8 }
            .maxByOrNull { it.length }
            .orEmpty()
        val og = doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        val t = doc.title()
        val raw = listOf(h1, og, t).firstOrNull { it.isNotBlank() }
            ?: url.trimEnd('/').substringAfterLast('/')
        return cleanLine(raw)
    }

    // PERBAIKAN: Beri bobot pada div, tapi setengah dari p agar <p> tetap diprioritaskan
    private fun paragraphChars(el: Element): Int {
        val pChars = el.select("p").sumOf { it.text().length }
        val divChars = el.select("div").sumOf { it.text().length }
        return pChars + (divChars / 2)
    }

    // PERBAIKAN: Turunkan threshold dan sertakan div dalam scoring fallback
    private fun pickContentNode(doc: Document): Element? {
        val selectors = listOf(
            "[itemprop=articleBody]", "article", ".post-body", ".entry-content",
            ".article-content", ".article-body", ".post-content", ".td-post-content",
            ".single-content", "main"
        )
        for (sel in selectors) {
            var bestEl: Element? = null
            var bestScore = 0
            for (el in doc.select(sel)) {
                val score = paragraphChars(el)
                if (score > bestScore) {
                    bestEl = el
                    bestScore = score
                }
            }
            if (bestEl != null && bestScore >= 150) return bestEl
        }

        // Fallback: elemen yang membawahi paling banyak teks paragraf ATAU div berisi teks
        val scores = IdentityHashMap<Element, Int>()
        for (p in doc.select("p, div")) {
            val len = p.text().length
            if (len < 30) continue
            val parent = p.parent() ?: continue
            scores[parent] = (scores[parent] ?: 0) + len
            val grand = parent.parent()
            if (grand != null) scores[grand] = (scores[grand] ?: 0) + len / 2
        }
        return scores.maxByOrNull { it.value }?.key
    }

    // PERBAIKAN: Logika fallback yang benar (menggantikan replace("\\n", "\n") yang tidak bekerja di JSoup)
    private fun extractLines(doc: Document, minimalClean: Boolean, minChars: Int): List<String> {
        if (minimalClean) {
            doc.select(hardNoise).remove()
            doc.select("form").filter { it.text().length < 500 }.forEach { it.remove() }
        }

        val node = pickContentNode(doc) ?: doc.body() ?: return emptyList()

        if (minimalClean) {
            node.select(softNoise).filter { it !== node }.forEach { it.remove() }
        }

        val blockSel = "p, h2, h3, h4, h5, h6, li, blockquote, pre"
        val seen = LinkedHashSet<String>()
        for (el in node.select(blockSel)) {
            if (el.select(blockSel).isNotEmpty()) continue
            val raw = el.text()
            val t = cleanLine(raw)
            if (t.isEmpty() || t.none { it.isLetter() }) continue

            val tag = el.tagName()
            val heading = tag.matches(Regex("h[1-6]"))
            if (minimalClean) {
                if (!heading) {
                    // PERBAIKAN: Longgarkan dari < 4 kata menjadi < 2 kata
                    if (t.split(' ').size < 2) continue
                    val linkLen = el.select("a").sumOf { it.text().length }
                    if (raw.isNotEmpty() && linkLen * 10 > raw.length * 6) continue
                }
                if (isNoiseLine(t)) continue
            }
            seen.add(t)
        }

        // Tabel sering membawa spesifikasi/harga artikel; jadikan setiap baris sebagai satu baris teks.
        for (table in node.select("table")) {
            for (row in table.select("tr")) {
                val cells = row.select("th, td").map { cleanLine(it.text()) }.filter { it.isNotBlank() }
                if (cells.size < 2) continue
                val rowText = cells.joinToString(" | ")
                if (rowText.none { it.isLetter() }) continue
                if (minimalClean && isNoiseLine(rowText)) continue
                val linkLen = row.select("a").sumOf { it.text().length }
                if (minimalClean && rowText.isNotEmpty() && linkLen * 10 > rowText.length * 7) continue
                seen.add(rowText)
            }
        }
        var lines: List<String> = seen.toList()

        // Fallback untuk isi yang tidak memakai <p> (mis. blog lama: teks + <br> atau hanya <div>)
        if (lines.sumOf { it.length } < minChars) {
            val alt = LinkedHashSet<String>()
            for (el in node.select("p, div, h2, h3, h4, h5, h6, li, blockquote, pre")) {
                // Hindari duplikasi: jika elemen div ini punya anak blok, skip parent-nya
                if (el.select("p, h2, h3, h4, h5, h6, li, blockquote, pre").isNotEmpty() && el.tagName() == "div") {
                    continue
                }
                
                val t = cleanLine(el.text())
                if (t.none { it.isLetter() }) continue
                
                val isHeading = el.tagName().matches(Regex("h[2-6]"))
                if (minimalClean && !isHeading && t.split(' ').size < 2) continue
                if (minimalClean && !isHeading && isNoiseLine(t)) continue
                
                alt.add(t)
            }
            if (alt.sumOf { it.length } > lines.sumOf { it.length }) {
                lines = alt.toList()
            }
        }
        return lines
    }

    private fun isNoiseLine(t: String): Boolean {
        val l = t.lowercase()
        if (l in noiseExact) return true
        if (l.contains("©") || l.contains("all rights reserved") ||
            l.contains("all right reserved") || l.contains("hak cipta")
        ) return true
        return noisePrefixes.any { l.startsWith(it) }
    }

    private fun cleanLine(s: String): String {
        return s.replace('\u00A0', ' ')
            .replace(invisibleRegex, "")
            .replace("[email protected]", "")
            .replace(urlRegex, "")
            .replace(emailRegex, "")
            .replace(sepRegex, " ")
            .replace(emptyBracketRegex, "")
            .replace(wsRegex, " ")
            .trim()
    }
}
