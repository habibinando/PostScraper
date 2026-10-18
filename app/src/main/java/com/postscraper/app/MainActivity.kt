package com.postscraper.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.postscraper.app.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.File
import java.io.FileWriter
import java.io.OutputStreamWriter
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * PostScraper Crawler — native Android.
 *
 * Alur:
 *  1. User masukkan URL seed (mis. homepage blog) + jumlah post maksimal.
 *  2. Fetch halaman. Jika isinya kosong (situs dirender JavaScript), halaman
 *     dirender ulang lewat WebView lalu dibaca.
 *  3. Semua link se-domain dikumpulkan. Link yang mirip POST masuk antrian
 *     prioritas, link kategori/index masuk antrian cadangan.
 *  4. Tiap POST: ambil isi artikel saja (tanpa menu/footer/iklan/komentar),
 *     buang link dan garis pemisah (=====), simpan teks bersih.
 *  5. Dari post, link post lain ikut diantrikan -> lanjut ke post berikutnya.
 *  6. Baris yang berulang di banyak post (boilerplate) dibuang di akhir, lalu
 *     file .txt bersih disimpan ke folder Download.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val desktopUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** URL POST yang sudah berhasil diambil — persist ke file. */
    private val visited = HashSet<String>()
    private lateinit var historyFile: File

    @Volatile
    private var stopRequested = false

    private val storagePermCode = 101

    private class PostData(val title: String, val lines: List<String>)

    private class PageResult(
        val links: List<String>,
        val post: PostData?,
        val isPost: Boolean,
        val rendered: Boolean
    )

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Riwayat lama (versi sebelumnya mencatat semua halaman, termasuk yang gagal) dibuang.
        try { File(filesDir, "visited_history.txt").delete() } catch (_: Exception) { }
        historyFile = File(filesDir, "posts_history.txt")
        loadHistory()

        binding.logTextView.movementMethod = ScrollingMovementMethod()
        updateStatus(0, 0)

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), storagePermCode
            )
        }

        binding.btnStart.setOnClickListener { startCrawl() }
        binding.btnStop.setOnClickListener {
            stopRequested = true
            log(">> Stop diminta, akan menyelesaikan penyimpanan...")
        }
        binding.btnClearHistory.setOnClickListener {
            visited.clear()
            historyFile.delete()
            updateStatus(0, 0)
            log("Riwayat dihapus.")
            toast("Riwayat dihapus")
        }

        log("Siap. Seed: ${binding.etSeedUrl.text} | Riwayat: ${visited.size} post")
    }

    // ---------- Riwayat ----------

    private fun loadHistory() {
        if (!historyFile.exists()) return
        try {
            historyFile.forEachLine { if (it.isNotBlank()) visited.add(it.trim()) }
        } catch (_: Exception) { }
    }

    private fun markVisited(url: String) {
        visited.add(url)
        try {
            FileWriter(historyFile, true).use { it.write("$url\n") }
        } catch (_: Exception) { }
    }

    // ---------- Normalisasi & klasifikasi URL ----------

    /** Host dari seed (tanpa www, lowercase). */
    private fun seedHostOf(seedUrl: String): String? {
        return try {
            val fixed = if (!seedUrl.startsWith("http://", true) &&
                !seedUrl.startsWith("https://", true)) "https://$seedUrl" else seedUrl
            URI(fixed).host?.lowercase()?.removePrefix("www.")
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Normalisasi link jadi bentuk kanonik. Return null jika bukan http/https,
     * beda domain dengan seed, atau berupa file (gambar, pdf, apk, dst).
     */
    private fun normalizeLink(href: String, baseUri: String, seedHost: String): String? {
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

    /**
     * Heuristik URL: apakah ini kemungkinan halaman POST (artikel)?
     * Post = slug panjang (min. 2 tanda '-'), berakhiran .html, atau path bertanggal.
     * Halaman kategori seperti /tips atau /gadgets bukan post.
     */
    private fun isPostUrl(url: String): Boolean {
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

    // ---------- Inti Crawler ----------

    private fun startCrawl() {
        val seedRaw = binding.etSeedUrl.text.toString().trim()
        if (seedRaw.isEmpty()) { toast("Isi URL seed dulu"); return }

        val seed = if (!seedRaw.startsWith("http://", true) &&
            !seedRaw.startsWith("https://", true)) "https://$seedRaw" else seedRaw

        val seedHost = seedHostOf(seed)
        if (seedHost == null) { toast("URL seed tidak valid"); return }

        val maxPosts = binding.etMaxPosts.text.toString().toIntOrNull() ?: 10
        val minChars = binding.etMinChars.text.toString().toIntOrNull() ?: 400
        val useHistory = binding.swUseHistory.isChecked
        val minimalClean = binding.swClean.isChecked

        stopRequested = false

        CoroutineScope(Dispatchers.IO).launch {
            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.VISIBLE
                binding.btnStart.isEnabled = false
                binding.btnStop.isEnabled = true
            }

            // File kerja incremental (cadangan jika aplikasi tertutup di tengah jalan).
            val workFile = File(filesDir, "corpus_work.txt")
            try { workFile.writeText("") } catch (_: Exception) { }

            val postQueue = ArrayDeque<String>()   // link yang mirip post (prioritas)
            val pageQueue = ArrayDeque<String>()   // kategori/index (cadangan untuk cari link baru)
            val queued = HashSet<String>()
            val posts = ArrayList<PostData>()

            fun enqueue(link: String): Boolean {
                if (!queued.add(link)) return false
                if (isPostUrl(link)) postQueue.add(link) else pageQueue.add(link)
                return true
            }

            enqueue(normalizeLink(seed, seed, seedHost) ?: seed)

            var collected = 0
            var crawledPages = 0
            var skippedVisited = 0
            var renderedPages = 0
            val maxPages = maxOf(60, maxPosts * 8)

            log("== MULAI CRAWL ==")
            log("Seed : $seed")
            log("Target: $maxPosts post | min. $minChars karakter | riwayat: ${if (useHistory) "ON" else "OFF"}")

            while ((postQueue.isNotEmpty() || pageQueue.isNotEmpty()) &&
                collected < maxPosts && !stopRequested
            ) {
                if (crawledPages >= maxPages) {
                    log("\nBatas $maxPages halaman tercapai, crawl dihentikan.")
                    break
                }

                val url = if (postQueue.isNotEmpty()) postQueue.removeFirst() else pageQueue.removeFirst()

                if (useHistory && url in visited) { skippedVisited++; continue }
                crawledPages++

                log("\n[$collected/$maxPosts] halaman $crawledPages: $url")
                updateStatus(collected, postQueue.size + pageQueue.size)

                try {
                    val res = processPage(url, seedHost, minChars, minimalClean)
                    if (res.rendered) renderedPages++

                    var newLinks = 0
                    for (l in res.links) {
                        if (enqueue(l)) newLinks++
                    }
                    log("   link baru: $newLinks | antrian post: ${postQueue.size} | antrian lain: ${pageQueue.size}")

                    val p = res.post
                    if (p != null) {
                        collected++
                        posts.add(p)
                        markVisited(url)
                        try {
                            workFile.appendText(p.title + "\n" + p.lines.joinToString("\n") + "\n\n")
                        } catch (_: Exception) { }
                        val chars = p.lines.sumOf { it.length }
                        log("   >>> POST DIAMBIL #$collected: ${p.title.take(60)} ($chars karakter)")
                    } else if (res.isPost) {
                        log("   (halaman post, tapi teks tidak terbaca atau < $minChars karakter — dilewati)")
                    } else {
                        log("   (halaman daftar/kategori — hanya diambil linknya)")
                    }
                } catch (e: Exception) {
                    log("   ERROR: ${e.message}")
                }

                // Jeda sopan antar request.
                if ((postQueue.isNotEmpty() || pageQueue.isNotEmpty()) && collected < maxPosts) delay(800)
            }

            // Simpan ke Download (teks bersih, tanpa link / garis pemisah).
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fname = "korpus_crawl_${seedHost.replace(".", "_")}_$timestamp.txt"

            var savedPath = "GAGAL"
            var totalChars = 0
            var totalWords = 0
            if (posts.isNotEmpty()) {
                try {
                    val finalText = buildFinalText(posts)
                    totalChars = finalText.length
                    totalWords = finalText.split(wsRegex).count { it.isNotBlank() }
                    saveToDownload(fname, finalText)
                    savedPath = "Download/$fname"
                    log("\n>>> DISIMPAN: $savedPath")
                } catch (e: Exception) {
                    log("\n>>> GAGAL simpan ke Download: ${e.message}")
                    log("File kerja tersimpan di internal app: ${workFile.absolutePath}")
                }
            } else {
                log("\nTidak ada post yang terkumpul (coba turunkan min. karakter, atau seed bukan situs blog/artikel).")
            }

            log("\n== SELESAI ==")
            log("Post diambil : $collected")
            log("Halaman crawl: $crawledPages (dirender WebView: $renderedPages)")
            log("Dilewati     : $skippedVisited (sudah di riwayat)")
            log("Total        : $totalChars karakter, $totalWords kata")
            log("File         : $savedPath")
            if (stopRequested) log("(dihentikan manual oleh user)")

            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE
                binding.btnStart.isEnabled = true
                binding.btnStop.isEnabled = false
                updateStatus(collected, 0)
                toast("Selesai: $collected post diambil")
            }
        }
    }

    // ---------- Proses satu halaman ----------

    /**
     * Fetch biasa dulu (cepat). Jika halaman post kosong / halaman daftar tanpa
     * link (ciri situs yang dirender JavaScript), render ulang pakai WebView.
     */
    private suspend fun processPage(
        url: String,
        seedHost: String,
        minChars: Int,
        minimalClean: Boolean
    ): PageResult {
        var notFound = false
        val doc: Document? = try {
            Jsoup.parse(fetchHtml(url), url)
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.startsWith("HTTP 404") || msg.startsWith("HTTP 410")) notFound = true
            log("   fetch biasa gagal: $msg")
            null
        }
        if (notFound) return PageResult(emptyList(), null, false, false)

        var best: PageResult? =
            if (doc != null) analyze(doc, url, seedHost, minChars, minimalClean) else null

        val cur = best
        val needRender = cur == null ||
                (cur.isPost && cur.post == null) ||
                (!cur.isPost && cur.links.size < 5)

        if (needRender) {
            log("   isi belum terbaca di HTML biasa -> render dengan WebView (JavaScript)...")
            val html = renderWithWebView(url)
            if (html != null) {
                val r = analyze(Jsoup.parse(html, url), url, seedHost, minChars, minimalClean)
                val rr = PageResult(r.links, r.post, r.isPost, true)
                if (cur == null || rr.post != null || rr.links.size > cur.links.size) best = rr
            } else {
                log("   render WebView gagal / timeout")
            }
        }
        return best ?: PageResult(emptyList(), null, false, false)
    }

    /** Kumpulkan link, lalu (jika post) ambil judul + isi artikel bersih. */
    private fun analyze(
        doc: Document,
        url: String,
        seedHost: String,
        minChars: Int,
        minimalClean: Boolean
    ): PageResult {
        // 1) Link se-domain (diambil sebelum DOM dibersihkan).
        val links = ArrayList<String>()
        for (href in doc.select("a[href]").eachAttr("abs:href")) {
            val norm = normalizeLink(href, url, seedHost)
            if (norm != null) links.add(norm)
        }

        // 2) Post? ambil judul + isi.
        val isPost = looksLikePost(url, doc)
        var post: PostData? = null
        if (isPost) {
            val title = pickTitle(doc, url)
            val lines = extractLines(doc, minimalClean, minChars)
            if (lines.sumOf { it.length } >= minChars) post = PostData(title, lines)
        }
        return PageResult(links, post, isPost, false)
    }

    // ---------- Fetch ----------

    private fun fetchHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", desktopUa)
            .header("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            val type = resp.header("Content-Type")
            if (type != null && !type.contains("html", true) && !type.contains("xml", true)) {
                throw Exception("bukan halaman HTML ($type)")
            }
            return resp.body?.string() ?: throw Exception("body kosong")
        }
    }

    /**
     * Render halaman dengan WebView (menjalankan JavaScript) lalu ambil HTML hasil
     * render. Dipakai untuk situs yang isinya baru muncul setelah JS jalan.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun renderWithWebView(url: String): String? {
        return try {
            withTimeoutOrNull(30_000L) {
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine<String?> { cont ->
                        val wv = WebView(applicationContext)
                        val finished = AtomicBoolean(false)

                        fun finish(result: String?) {
                            if (!finished.compareAndSet(false, true)) return
                            try {
                                wv.stopLoading()
                                wv.destroy()
                            } catch (_: Exception) { }
                            if (cont.isActive) cont.resume(result)
                        }

                        wv.settings.javaScriptEnabled = true
                        wv.settings.domStorageEnabled = true
                        wv.settings.blockNetworkImage = true
                        wv.settings.mediaPlaybackRequiresUserGesture = true
                        wv.settings.userAgentString = desktopUa

                        wv.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                                val v = view ?: return
                                // Beri waktu JavaScript membangun isi halaman.
                                v.postDelayed({
                                    if (finished.get()) return@postDelayed
                                    v.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                                        finish(decodeJsString(raw))
                                    }
                                }, 3000)
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                if (request?.isForMainFrame == true) finish(null)
                            }
                        }

                        cont.invokeOnCancellation {
                            if (finished.compareAndSet(false, true)) {
                                wv.post {
                                    try { wv.destroy() } catch (_: Exception) { }
                                }
                            }
                        }

                        wv.loadUrl(url)
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Hasil evaluateJavascript berupa string JSON ("..." dengan escape) -> string asli. */
    private fun decodeJsString(raw: String?): String? {
        if (raw == null || raw == "null" || raw.length < 2) return null
        return try {
            JSONTokener(raw).nextValue() as? String
        } catch (e: Exception) {
            null
        }
    }

    // ---------- Ekstraksi isi artikel ----------

    /** Judul post: h1 terpanjang (>= 8 huruf), lalu og:title, lalu <title>. */
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

    /** Jumlah karakter paragraf (<p>) di dalam elemen — ukuran "seberapa mirip isi artikel". */
    private fun paragraphChars(el: Element): Int = el.select("p").sumOf { it.text().length }

    /** Cari kontainer isi artikel: selector umum dulu, lalu kepadatan paragraf. */
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
            if (bestEl != null && bestScore >= 200) return bestEl
        }

        // Fallback: elemen yang membawahi paling banyak teks paragraf.
        val scores = IdentityHashMap<Element, Int>()
        for (p in doc.select("p")) {
            val len = p.text().length
            if (len < 40) continue
            val parent = p.parent() ?: continue
            scores[parent] = (scores[parent] ?: 0) + len
            val grand = parent.parent()
            if (grand != null) scores[grand] = (scores[grand] ?: 0) + len / 2
        }
        return scores.maxByOrNull { it.value }?.key
    }

    /**
     * Ambil baris teks bersih dari isi artikel. Tanpa menu, footer, iklan,
     * komentar, link, dan garis pemisah.
     */
    private fun extractLines(doc: Document, minimalClean: Boolean, minChars: Int): List<String> {
        if (minimalClean) {
            doc.select(hardNoise).remove()
            // <form> sering membungkus seluruh halaman (ASP.NET) — hanya buang yang kecil.
            doc.select("form").filter { it.text().length < 500 }.forEach { it.remove() }
        }

        val node = pickContentNode(doc) ?: doc.body() ?: return emptyList()

        if (minimalClean) {
            node.select(softNoise).filter { it !== node }.forEach { it.remove() }
        }

        val blockSel = "p, h2, h3, h4, h5, h6, li, blockquote, pre"
        val seen = LinkedHashSet<String>()
        for (el in node.select(blockSel)) {
            if (el.select(blockSel).size > 1) continue   // kontainer: anak-anaknya diproses sendiri
            val raw = el.text()
            val t = cleanLine(raw)
            if (t.isEmpty() || t.none { it.isLetter() }) continue

            val tag = el.tagName()
            val heading = tag.length == 2 && tag[0] == 'h'
            if (minimalClean) {
                if (!heading) {
                    if (t.split(' ').size < 4) continue
                    // Baris yang hampir seluruhnya link = menu / daftar artikel terkait.
                    val linkLen = el.select("a").sumOf { it.text().length }
                    if (raw.isNotEmpty() && linkLen * 10 > raw.length * 6) continue
                }
                if (isNoiseLine(t)) continue
            }
            seen.add(t)
        }
        var lines: List<String> = seen.toList()

        // Fallback untuk isi yang tidak memakai <p> (mis. blog lama: teks + <br>).
        if (lines.sumOf { it.length } < minChars) {
            node.select("br").after("\\n")
            node.select("div").after("\\n")
            val flat = node.text().replace("\\n", "\n")
            val alt = LinkedHashSet<String>()
            for (part in flat.split("\n")) {
                val t = cleanLine(part)
                if (t.none { it.isLetter() }) continue
                if (minimalClean && (t.split(' ').size < 4 || isNoiseLine(t))) continue
                alt.add(t)
            }
            if (alt.sumOf { it.length } > lines.sumOf { it.length }) lines = alt.toList()
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

    /** Buang link, email, garis pemisah (=====, -----), karakter tak terlihat, rapikan spasi. */
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

    /**
     * Gabungkan semua post jadi satu teks bersih:
     *   judul
     *   paragraf
     *   paragraf
     *   (baris kosong)
     * Baris yang muncul di banyak post (boilerplate) dibuang.
     */
    private fun buildFinalText(posts: List<PostData>): String {
        val freq = HashMap<String, Int>()
        for (p in posts) {
            for (l in p.lines.toSet()) freq[l] = (freq[l] ?: 0) + 1
        }
        val applyFilter = posts.size >= 4
        val threshold = maxOf(3, (posts.size * 3 + 9) / 10)

        val sb = StringBuilder()
        for (p in posts) {
            val kept = p.lines.filter { !(applyFilter && (freq[it] ?: 0) >= threshold) }
            if (kept.isEmpty()) continue
            sb.append(p.title).append('\n')
            for (l in kept) sb.append(l).append('\n')
            sb.append('\n')
        }
        return sb.toString()
    }

    // ---------- Simpan ke Download ----------

    private fun saveToDownload(fileName: String, content: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("Gagal insert MediaStore")
            contentResolver.openOutputStream(uri)?.use { os ->
                OutputStreamWriter(os, Charsets.UTF_8).use { it.write(content) }
            } ?: throw Exception("Gagal buka output stream")
        } else {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), storagePermCode
                )
                throw Exception("Izin storage belum diberikan")
            }
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            File(dir, fileName).writeText(content, Charsets.UTF_8)
        }
    }

    // ---------- UI helpers ----------

    private fun updateStatus(collected: Int, queueSize: Int) {
        runOnUiThread {
            binding.tvStatus.text =
                "Post terambil: $collected | Antrian: $queueSize | Riwayat: ${visited.size} post"
        }
    }

    private fun log(msg: String) {
        runOnUiThread {
            binding.logTextView.append("$msg\n")
            val layout = binding.logTextView.layout
            if (layout != null) {
                val scroll = layout.getLineTop(binding.logTextView.lineCount) -
                        binding.logTextView.height
                if (scroll > 0) binding.logTextView.scrollTo(0, scroll)
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
