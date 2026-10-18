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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.postscraper.app.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
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
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // Semua pekerjaan crawl terikat ke lifecycle Activity; tidak membuat scope liar per tombol.
    private val activityJob = SupervisorJob()
    private val crawlerScope = CoroutineScope(activityJob + Dispatchers.IO)
    private var activeCrawlJob: Job? = null
    private var crawlRunning = false

    /** URL artikel yang sudah berhasil disimpan; dipersist setelah file output sukses. */
    private val visited = HashSet<String>()
    private lateinit var historyFile: File

    @Volatile
    private var stopRequested = false

    @Volatile
    private var activeCall: Call? = null

    @Volatile
    private var cancelWebViewRender: (() -> Unit)? = null

    /** URL halaman maksimum yang dibaca untuk menghindari OOM karena HTML sangat besar. */
    private val maxHtmlBytes = 5 * 1024 * 1024
    private val storagePermCode = 101

    private class PostData(val url: String, val title: String, val lines: List<String>)

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

        // Android 15+ mewajibkan edge-to-edge untuk target SDK modern.
        // Tambahkan inset agar kontrol tidak tertutup status bar / navigation bar.
        if (Build.VERSION.SDK_INT >= 35) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val left = binding.root.paddingLeft
            val top = binding.root.paddingTop
            val right = binding.root.paddingRight
            val bottom = binding.root.paddingBottom
            ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
                insets
            }
        }

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
            if (!crawlRunning) return@setOnClickListener
            stopRequested = true
            // Hentikan request aktif, supaya Stop tidak harus menunggu timeout penuh.
            activeCall?.cancel()
            cancelWebViewRender?.invoke()
            log(">> Stop diminta. Hasil yang sudah terkumpul tetap akan disimpan.")
        }
        binding.btnClearHistory.setOnClickListener {
            if (crawlRunning) {
                toast("Hentikan crawling sebelum menghapus riwayat")
                return@setOnClickListener
            }
            visited.clear()
            val deleted = try { !historyFile.exists() || historyFile.delete() } catch (_: Exception) { false }
            updateStatus(0, 0)
            log(if (deleted) "Riwayat dihapus." else "Riwayat gagal dihapus.")
            toast(if (deleted) "Riwayat dihapus" else "Gagal menghapus riwayat")
        }

        log("Siap. Seed: ${binding.etSeedUrl.text} | Riwayat: ${visited.size} post")
    }

    override fun onDestroy() {
        stopRequested = true
        activeCall?.cancel()
        cancelWebViewRender?.invoke()
        activityJob.cancel()
        super.onDestroy()
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
        if (crawlRunning) return

        val seedRaw = binding.etSeedUrl.text?.toString()?.trim().orEmpty()
        if (seedRaw.isBlank()) {
            binding.etSeedUrl.error = "URL seed wajib diisi"
            toast("Isi URL seed dulu")
            return
        }
        binding.etSeedUrl.error = null

        val seed = if (
            !seedRaw.startsWith("http://", true) &&
            !seedRaw.startsWith("https://", true)
        ) "https://$seedRaw" else seedRaw

        val parsedSeed = try { URI(seed).normalize() } catch (_: Exception) { null }
        val scheme = parsedSeed?.scheme?.lowercase(Locale.ROOT)
        val seedHost = seedHostOf(seed)
        if (parsedSeed == null || scheme == null ||
            (scheme != "http" && scheme != "https") ||
            seedHost.isNullOrBlank() || parsedSeed.userInfo != null
        ) {
            binding.etSeedUrl.error = "Masukkan URL HTTP/HTTPS yang valid"
            toast("URL seed tidak valid")
            return
        }
        binding.etSeedUrl.error = null

        val maxPosts = binding.etMaxPosts.text?.toString()?.toIntOrNull()
        if (maxPosts == null || maxPosts !in 1..1000) {
            binding.etMaxPosts.error = "Masukkan angka 1–1000"
            toast("Maks. post harus 1 sampai 1000")
            return
        }
        binding.etMaxPosts.error = null

        val minChars = binding.etMinChars.text?.toString()?.toIntOrNull()
        if (minChars == null || minChars !in 80..20000) {
            binding.etMinChars.error = "Masukkan angka 80–20000"
            toast("Min. karakter harus 80 sampai 20000")
            return
        }
        binding.etMinChars.error = null

        val useHistory = binding.swUseHistory.isChecked
        val minimalClean = binding.swClean.isChecked

        stopRequested = false
        crawlRunning = true
        setCrawlControls(enabled = false)
        binding.progressBar.visibility = View.VISIBLE
        binding.btnStop.isEnabled = true
        binding.btnStart.isEnabled = false

        activeCrawlJob = crawlerScope.launch {
            val workFile = File(filesDir, "corpus_work.txt")
            val postQueue = ArrayDeque<String>()
            val pageQueue = ArrayDeque<String>()
            val queued = HashSet<String>()
            val posts = ArrayList<PostData>()
            var collected = 0
            var crawledPages = 0
            var skippedVisited = 0
            var renderedPages = 0
            var savedPath = "Belum disimpan"
            var totalChars = 0
            var totalWords = 0
            var fileSaved = false

            try {
                // File kerja hanya di-reset saat run baru benar-benar dimulai.
                workFile.writeText("")

                fun enqueue(link: String): Boolean {
                    if (link.isBlank() || !queued.add(link)) return false
                    if (isPostUrl(link)) postQueue.add(link) else pageQueue.add(link)
                    return true
                }

                enqueue(normalizeLink(seed, seed, seedHost) ?: seed)

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
                    if (useHistory && url in visited) {
                        skippedVisited++
                        continue
                    }

                    crawledPages++
                    log("\n[$collected/$maxPosts] halaman $crawledPages: $url")
                    updateStatus(collected, postQueue.size + pageQueue.size)

                    try {
                        val res = processPage(url, seedHost, minChars, minimalClean)
                        if (stopRequested) break
                        if (res.rendered) renderedPages++

                        var newLinks = 0
                        for (link in res.links) {
                            if (enqueue(link)) newLinks++
                        }
                        log("   link baru: $newLinks | antrian post: ${postQueue.size} | antrian lain: ${pageQueue.size}")

                        val post = res.post
                        if (post != null && post.lines.isNotEmpty()) {
                            collected++
                            posts.add(post)
                            try {
                                workFile.appendText(post.title + "\n" + post.lines.joinToString("\n") + "\n\n")
                            } catch (e: Exception) {
                                log("   Peringatan: file kerja gagal diperbarui: ${e.message}")
                            }
                            val chars = post.lines.sumOf { it.length }
                            log("   >>> POST DIAMBIL #$collected: ${post.title.take(60)} ($chars karakter)")
                        } else if (res.isPost) {
                            log("   (halaman post, tapi teks tidak terbaca atau di bawah $minChars karakter — dilewati)")
                        } else {
                            log("   (halaman daftar/kategori — hanya diambil linknya)")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (!stopRequested) log("   ERROR: ${e.message ?: e.javaClass.simpleName}")
                    }

                    if (!stopRequested &&
                        (postQueue.isNotEmpty() || pageQueue.isNotEmpty()) &&
                        collected < maxPosts
                    ) delay(800)
                }

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val fileName = "korpus_crawl_${seedHost.replace(".", "_")}_$timestamp.txt"

                if (posts.isNotEmpty()) {
                    val finalText = buildFinalText(posts)
                    if (finalText.isBlank()) {
                        log("\n>>> GAGAL: hasil teks kosong. Riwayat tidak diubah.")
                    } else {
                        totalChars = finalText.length
                        totalWords = finalText.split(wsRegex).count { it.isNotBlank() }
                        saveToDownload(fileName, finalText)
                        // Riwayat hanya ditandai setelah berkas output berhasil disimpan.
                        posts.forEach { markVisited(it.url) }
                        savedPath = "Download/$fileName"
                        fileSaved = true
                        log("\n>>> DISIMPAN: $savedPath")
                    }
                } else {
                    log("\nTidak ada post yang terkumpul. Periksa URL, koneksi, atau turunkan min. karakter.")
                }

                log("\n== SELESAI ==")
                log("Post diambil : $collected")
                log("Halaman crawl: $crawledPages (dirender WebView: $renderedPages)")
                log("Dilewati     : $skippedVisited (sudah di riwayat)")
                log("Total        : $totalChars karakter, $totalWords kata")
                log("File         : $savedPath")
                if (stopRequested) log("(dihentikan manual; hasil yang sudah terkumpul dipertahankan)")
                if (!fileSaved && posts.isNotEmpty()) {
                    log("Riwayat tidak diperbarui karena file hasil belum berhasil disimpan.")
                }
            } catch (e: CancellationException) {
                log("Crawling dibatalkan.")
                throw e
            } catch (e: Exception) {
                log("ERROR proses crawling: ${e.message ?: e.javaClass.simpleName}")
                log("Hasil parsial: ${posts.size} post terkumpul.")
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    crawlRunning = false
                    activeCrawlJob = null
                    if (!isFinishing && !isDestroyed) {
                        binding.progressBar.visibility = View.GONE
                        setCrawlControls(enabled = true)
                        binding.btnStart.isEnabled = true
                        binding.btnStop.isEnabled = false
                        updateStatus(collected, 0)
                        if (!isFinishing) toast(if (fileSaved) "Selesai: $collected post disimpan" else "Crawling selesai; periksa log")
                    }
                }
            }
        }
    }

    private fun setCrawlControls(enabled: Boolean) {
        binding.etSeedUrl.isEnabled = enabled
        binding.etMaxPosts.isEnabled = enabled
        binding.etMinChars.isEnabled = enabled
        binding.swUseHistory.isEnabled = enabled
        binding.swClean.isEnabled = enabled
        binding.btnClearHistory.isEnabled = enabled
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
            if (stopRequested) return PageResult(emptyList(), null, false, false)
            val msg = e.message ?: e.javaClass.simpleName
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
            if (lines.sumOf { it.length } >= minChars) post = PostData(url, title, lines)
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

        val call = client.newCall(request)
        activeCall = call
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                val body = resp.body ?: throw Exception("body kosong")
                val mediaType = body.contentType()
                if (mediaType != null &&
                    !mediaType.type.equals("text", true) &&
                    !mediaType.subtype.contains("xml", true) &&
                    !mediaType.subtype.contains("html", true)
                ) {
                    throw Exception("bukan halaman HTML (${mediaType})")
                }

                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                body.byteStream().use { input ->
                    while (true) {
                        if (stopRequested) throw java.io.IOException("Crawl dihentikan")
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > maxHtmlBytes) {
                            throw Exception("halaman terlalu besar (maksimum 5 MB), dilewati")
                        }
                        output.write(buffer, 0, count)
                    }
                }
                val charset = mediaType?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                return output.toString(charset.name())
            }
        } finally {
            if (activeCall === call) activeCall = null
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
                        val wv = WebView(this@MainActivity)
                        val finished = AtomicBoolean(false)

                        fun finish(result: String?) {
                            if (!finished.compareAndSet(false, true)) return
                            cancelWebViewRender = null
                            try {
                                wv.stopLoading()
                                wv.destroy()
                            } catch (_: Exception) { }
                            if (cont.isActive) cont.resume(result)
                        }

                        // Callback ini dipanggil tombol Stop di main thread.
                        cancelWebViewRender = { finish(null) }

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
                            cancelWebViewRender = null
                            if (finished.compareAndSet(false, true)) {
                                wv.post {
                                    try {
                                        wv.stopLoading()
                                        wv.destroy()
                                    } catch (_: Exception) { }
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
            if (el.select(blockSel).isNotEmpty()) continue   // proses blok terdalam agar teks parent tidak terduplikasi
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
        // Jangan menghapus kalimat hanya karena muncul di banyak artikel:
        // hal itu bisa membuang isi penting dan menghasilkan file kosong.
        val sb = StringBuilder()
        for (post in posts) {
            val title = post.title.trim()
            val lines = post.lines.map { it.trim() }.filter { it.isNotBlank() }
            if (title.isBlank() || lines.isEmpty()) continue
            sb.append(title).append('\n')
            for (line in lines) sb.append(line).append('\n')
            sb.append('\n')
        }
        return sb.toString()
    }

    // ---------- Simpan ke Download ----------

    private fun saveToDownload(fileName: String, content: String) {
        if (content.isBlank()) throw Exception("Konten kosong; tidak ada file yang dibuat.")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("Gagal membuat file di Download")
            var saved = false
            try {
                contentResolver.openOutputStream(uri)?.use { os ->
                    OutputStreamWriter(os, Charsets.UTF_8).use { it.write(content) }
                } ?: throw Exception("Gagal membuka file output")
                saved = true
            } finally {
                if (!saved) {
                    try { contentResolver.delete(uri, null, null) } catch (_: Exception) { }
                }
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), storagePermCode
                )
                throw Exception("Izin penyimpanan belum diberikan. Berikan izin lalu jalankan ulang.")
            }
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists() && !dir.mkdirs()) throw Exception("Folder Download tidak bisa dibuat")
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
