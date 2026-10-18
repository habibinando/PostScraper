package com.postscraper.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private val pageLoader by lazy(LazyThreadSafetyMode.NONE) { PageLoader(this) { stopRequested } }
    private val textExporter by lazy(LazyThreadSafetyMode.NONE) { TextExporter(this) }
    private val remoteReader = RemoteReader()
    private val crawlEngine by lazy(LazyThreadSafetyMode.NONE) { CrawlEngine(pageLoader, remoteReader) }

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

    private val storagePermCode = 101

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
            pageLoader.cancelCurrent()
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
        pageLoader.cancelCurrent()
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
            var collected = 0
            var crawledPages = 0
            var skippedVisited = 0
            var renderedPages = 0
            var savedPath = "Belum disimpan"
            var totalChars = 0
            var totalWords = 0
            var fileSaved = false
            var posts: List<PostData> = emptyList()

            try {
                val options = CrawlOptions(
                    seed = seed,
                    seedHost = seedHost,
                    maxPosts = maxPosts,
                    minChars = minChars,
                    useHistory = useHistory,
                    minimalClean = minimalClean,
                    useRemoteReader = useRemoteReader,
                    historySnapshot = visited.toSet()
                )
                val result = crawlEngine.crawl(
                    options = options,
                    shouldStop = { stopRequested },
                    onLog = { message -> log(message) },
                    onProgress = { count, queue -> updateStatus(count, queue) }
                )
                posts = result.posts
                collected = posts.size
                crawledPages = result.pagesProcessed
                skippedVisited = result.skippedVisited
                renderedPages = result.renderedPages

                if (posts.isNotEmpty()) {
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    val fileName = "korpus_crawl_${seedHost.replace(".", "_")}_$timestamp.txt"
                    val finalText = textExporter.buildFinalText(posts)
                    if (finalText.isBlank()) {
                        log("\nHasil teks kosong. Riwayat tidak diubah.")
                    } else {
                        totalChars = finalText.length
                        totalWords = finalText.split(Regex("\\s+")).count { it.isNotBlank() }
                        try {
                            textExporter.saveToDownload(fileName, finalText)
                            posts.forEach { markVisited(it.url) }
                            savedPath = "Download/$fileName"
                            fileSaved = true
                            log("\n>>> DISIMPAN: $savedPath")
                        } catch (e: Exception) {
                            log("\nGAGAL menyimpan file: ${e.message ?: e.javaClass.simpleName}")
                            log("Post sudah dibaca: ${posts.size}. Riwayat tidak diperbarui.")
                        }
                    }
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
