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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileWriter
import java.io.OutputStreamWriter
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
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
    private val remoteReader = RemoteReader()

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
        val seedHost = ArticleExtractor.seedHostOf(seed)
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
        val useRemoteReader = binding.swUseReader.isChecked

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
                workFile.writeText("")

                fun enqueue(link: String): Boolean {
                    if (link.isBlank() || !queued.add(link)) return false
                    if (ArticleExtractor.isPostUrl(link)) postQueue.add(link) else pageQueue.add(link)
                    return true
                }

                enqueue(ArticleExtractor.normalizeLink(seed, seed, seedHost) ?: seed)

                val maxPages = maxOf(60, maxPosts * 8)
                log("== MULAI CRAWL ==")
                log("Seed : $seed")
                log("Target: $maxPosts post | min. $minChars karakter | riwayat: ${if (useHistory) "ON" else "OFF"} | Jina Reader: ${if (useRemoteReader) "ON" else "OFF"}")

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
                    log("\n[Halaman $crawledPages | $collected/$maxPosts post] $url")
                    updateStatus(collected, postQueue.size + pageQueue.size)

                    try {
                        val res = processPage(url, seedHost, minChars, minimalClean, useRemoteReader)
                        if (stopRequested) break
                        if (res.rendered) renderedPages++

                        var newLinks = 0
                        for (link in res.links) {
                            if (enqueue(link)) newLinks++
                        }
                        log("   link baru: $newLinks | antrian post: ${postQueue.size} | antrian lain: ${pageQueue.size}")

                        val post = res.post
                        if (post != null && post.lines.isNotEmpty()) {
                            val duplicateCanonical = posts.any { it.url == post.url } ||
                                (useHistory && post.url in visited)
                            if (duplicateCanonical) {
                                log("   post duplikat tidak disimpan (URL kanonis sudah ada).")
                            } else {
                            collected++
                            posts.add(post)
                            try {
                                workFile.appendText(post.title + "\n" + post.lines.joinToString("\n") + "\n\n")
                            } catch (e: Exception) {
                                log("   Peringatan: file kerja gagal diperbarui: ${e.message}")
                            }
                            val chars = post.lines.sumOf { it.length }
                            log("   >>> POST DIAMBIL #$collected: ${post.title.take(60)} ($chars karakter)")
                            }
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
                        totalWords = finalText.split(Regex("\\s+")).count { it.isNotBlank() }
                        saveToDownload(fileName, finalText)
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
        binding.swUseReader.isEnabled = enabled
        binding.btnClearHistory.isEnabled = enabled
    }

    /** Jalantikus menyajikan halaman artikel biasa sebagai shell JavaScript; versi AMP berisi HTML artikel siap parse. */
    // ---------- Proses satu halaman ----------

    private suspend fun processPage(
        url: String,
        seedHost: String,
        minChars: Int,
        minimalClean: Boolean,
        allowRemoteReader: Boolean
    ): PageResult {
        var notFound = false
        val doc: Document? = try {
            Jsoup.parse(fetchHtml(url), url)
        } catch (e: Exception) {
            if (stopRequested) return PageResult(emptyList(), null, false)
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
                    val ampHtml = fetchHtml(ampUrl)
                    val ampResult = ArticleExtractor.analyze(
                        Jsoup.parse(ampHtml, url), url, seedHost, minChars, minimalClean, forcePost = true
                    )
                    best = chooseBetter(best, ampResult)
                    if (ampResult.post != null) log("   AMP terbaca: \${ampResult.post.lines.sumOf { it.length }} karakter.")
                } catch (e: Exception) {
                    log("   AMP tidak tersedia: \${e.message ?: e.javaClass.simpleName}")
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
                val html = renderWithWebView(url)
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
            allowRemoteReader = allowRemoteReader
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
        allowRemoteReader: Boolean
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

        while (nextUrl != null && pageCount < 12 && !stopRequested && seenPages.add(nextUrl)) {
            log("   menggabungkan halaman artikel \${pageCount + 1}: $nextUrl")
            val part = fetchArticlePart(nextUrl, seedHost, minChars, minimalClean, allowRemoteReader)
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
        allowRemoteReader: Boolean
    ): PageResult? {
        var best: PageResult? = try {
            ArticleExtractor.analyze(
                Jsoup.parse(fetchHtml(url), url), url, seedHost, minChars, minimalClean, forcePost = true
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
                        Jsoup.parse(fetchHtml(ampUrl), url), url, seedHost, minChars, minimalClean, forcePost = true
                    )
                    best = chooseBetter(best, amp)
                } catch (_: Exception) { }
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

                        cancelWebViewRender = { finish(null) }

                        wv.settings.javaScriptEnabled = true
                        wv.settings.domStorageEnabled = true
                        wv.settings.blockNetworkImage = true
                        wv.settings.mediaPlaybackRequiresUserGesture = true
                        wv.settings.userAgentString = desktopUa

                        wv.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                                val v = view ?: return
                                // Beri kesempatan JavaScript selesai merender tanpa menyentuh batas timeout 30 detik.
                                v.postDelayed({
                                    if (finished.get()) return@postDelayed
                                    v.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                                        finish(decodeJsString(raw))
                                    }
                                }, 3500)
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

    private fun decodeJsString(raw: String?): String? {
        if (raw == null || raw == "null" || raw.length < 2) return null
        return try {
            JSONTokener(raw).nextValue() as? String
        } catch (e: Exception) {
            null
        }
    }

    // ---------- Ekstraksi isi artikel ----------

    private fun buildFinalText(posts: List<PostData>): String {
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
