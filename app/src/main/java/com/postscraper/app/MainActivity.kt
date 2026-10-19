package com.postscraper.app

import android.Manifest
import android.content.Intent
import android.app.ProgressDialog
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.provider.OpenableColumns
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
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
import java.io.IOException
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
    private val settingsStore by lazy(LazyThreadSafetyMode.NONE) { AppSettingsStore(applicationContext) }
    private val networkClients by lazy(LazyThreadSafetyMode.NONE) { NetworkClientFactory(settingsStore) }
    private val pageLoader by lazy(LazyThreadSafetyMode.NONE) { PageLoader(this, { stopRequested }, networkClients) }
    private val textExporter by lazy(LazyThreadSafetyMode.NONE) { TextExporter(this) }
    private val localModelCleaner by lazy(LazyThreadSafetyMode.NONE) { LocalModelCleaner { stopRequested } }
    private val crawlEngine by lazy(LazyThreadSafetyMode.NONE) {
        CrawlEngine(pageLoader, localModelCleaner)
    }
    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importLocalModel(uri)
    }

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

        binding.toolbar.setNavigationOnClickListener {
            if (crawlRunning) {
                toast("Hentikan crawling sebelum membuka menu utama.")
            } else {
                startActivity(
                    Intent(this, AssistantActivity::class.java)
                        .putExtra("start_screen", "settings")
                )
            }
        }

        intent.getStringExtra("seed_url")?.takeIf { it.isNotBlank() }?.let {
            binding.etSeedUrl.setText(it)
        }

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
            log(">> STOP: permintaan jaringan/WebView dibatalkan. Hasil yang sudah terkumpul tetap disimpan.")
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

        log("Siap. Seed: ${binding.etSeedUrl.text} | Riwayat: ${visited.size} post | fallback: HTML → AMP → WebView → Qwen lokal")
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
        val appSettings = settingsStore.load()
        if (appSettings.useLocalModelCleaning &&
            (appSettings.localModelPath.isBlank() || !File(appSettings.localModelPath).isFile)
        ) {
            toast("Pilih model GGUF di Pengaturan, atau nonaktifkan fallback Qwen lokal")
            return
        }

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
                    historySnapshot = visited.toSet(),
                    localModelPath = appSettings.localModelPath,
                    useLocalModelCleaning = appSettings.useLocalModelCleaning
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
                try {
                    withContext(NonCancellable + Dispatchers.IO) { localModelCleaner.release() }
                } catch (_: Exception) { }
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

    private fun importLocalModel(uri: Uri) {
        if (crawlRunning) {
            toast("Hentikan crawling sebelum mengganti model")
            return
        }

        val progress = ProgressDialog(this).apply {
            setTitle("Memasang model lokal")
            setMessage("Menyalin file GGUF ke ruang aplikasi. Model sekitar 2 GB memerlukan ruang kosong tambahan.")
            setCancelable(false)
            show()
        }

        lifecycleScope.launch {
            try {
                val modelPath = withContext(Dispatchers.IO) {
                    localModelCleaner.release()
                    val metadata = contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        if (!cursor.moveToFirst()) null else {
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                            val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                            val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                            Pair(name ?: "model.gguf", size)
                        }
                    } ?: Pair("model.gguf", null)

                    if (!metadata.first.endsWith(".gguf", ignoreCase = true)) {
                        throw IOException("Pilih file yang berakhiran .gguf")
                    }

                    val modelDir = getExternalFilesDir("models") ?: File(filesDir, "models")
                    if (!modelDir.exists() && !modelDir.mkdirs()) {
                        throw IOException("Folder penyimpanan model tidak bisa dibuat")
                    }
                    val requiredBytes = (metadata.second ?: 0L) + 128L * 1024L * 1024L
                    if (StatFs(modelDir.absolutePath).availableBytes < requiredBytes) {
                        throw IOException("Ruang kosong tidak cukup. Butuh file model plus ruang sementara untuk impor.")
                    }

                    val temporary = File(modelDir, "qwen-model.gguf.part")
                    if (temporary.exists()) temporary.delete()
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IOException("File model tidak bisa dibuka")
                    input.use { source ->
                        temporary.outputStream().buffered(1024 * 1024).use { output ->
                            source.copyTo(output, 1024 * 1024)
                        }
                    }

                    if (temporary.length() < 100L * 1024L * 1024L) {
                        temporary.delete()
                        throw IOException("File terlalu kecil atau bukan model GGUF lengkap")
                    }

                    val destination = File(modelDir, "qwen-model.gguf")
                    if (destination.exists() && !destination.delete()) {
                        temporary.delete()
                        throw IOException("Model lama tidak bisa diganti")
                    }
                    if (!temporary.renameTo(destination)) {
                        temporary.copyTo(destination, overwrite = true)
                        temporary.delete()
                    }

                    settingsStore.save(
                        settingsStore.load().copy(
                            localModelPath = destination.absolutePath,
                            useLocalModelCleaning = true
                        )
                    )
                    destination.absolutePath
                }

                log("Model lokal terpasang: " + File(modelPath).name)
                toast("Model lokal berhasil dipasang")
            } catch (e: Exception) {
                log("Gagal memasang model lokal: " + (e.message ?: e.javaClass.simpleName))
                toast("Gagal memasang model: " + (e.message ?: "periksa ruang penyimpanan"))
            } finally {
                try { progress.dismiss() } catch (_: Exception) { }
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
