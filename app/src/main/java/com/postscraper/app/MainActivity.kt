package com.postscraper.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.postscraper.app.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.io.FileWriter
import java.io.OutputStreamWriter
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * PostScraper Crawler — native Android.
 *
 * Alur:
 *  1. User masukkan URL seed (mis. homepage blog) + jumlah post maksimal.
 *  2. Aplikasi fetch halaman, ekstrak SEMUA link, filter yang se-domain
 *     (host sama, www diabaikan), normalisasi URL, masukkan ke antrian BFS.
 *  3. Tiap halaman: jika berupa postingan (bukan index/search/label) dan teks
 *     cukup panjang → ambil teks, bersihkan minimal, tulis ke file kerja.
 *  4. Riwayat (visited) disimpan ke file agar post yang pernah diambil tidak
 *     diulang di run berikutnya (bisa di-toggle / dihapus).
 *  5. Setelah selesai / di-stop, file korpus disalin ke folder Download.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Riwayat URL yang pernah dikunjungi — persist ke file. */
    private val visited = HashSet<String>()
    private lateinit var historyFile: File

    @Volatile
    private var stopRequested = false

    private val storagePermCode = 101

    /** Parameter query yang diabaikan saat normalisasi (tracking/mobile). */
    private val junkParams = setOf(
        "m", "showcomment", "utm_source", "utm_medium", "utm_campaign",
        "utm_term", "utm_content", "fbclid", "gclid", "referrer", "m=1"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        historyFile = File(filesDir, "visited_history.txt")
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

        log("Siap. Seed: ${binding.etSeedUrl.text} | Riwayat: ${visited.size} URL")
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

    // ---------- Normalisasi URL ----------

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
     * Normalisasi link jadi bentuk kanonik. Return null jika bukan http/https
     * atau beda domain dengan seed.
     */
    private fun normalizeLink(href: String, baseUri: String, seedHost: String): String? {
        return try {
            // Resolve relatif ke absolut.
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

            val cleanQuery = uri.rawQuery
                ?.split("&")
                ?.filter { p -> p.substringBefore("=").lowercase() !in junkParams }
                ?.joinToString("&")

            "$scheme://$host$path${if (!cleanQuery.isNullOrEmpty()) "?$cleanQuery" else ""}"
        } catch (e: Exception) {
            null
        }
    }

    /** Heuristik: halaman index/search/label tidak dihitung sebagai post. */
    private fun isLikelyIndex(url: String): Boolean {
        return try {
            val u = URI(url)
            val path = u.path ?: "/"
            when {
                path == "/" -> true
                path.contains("/search", true) -> true
                path.contains("/label", true) -> true
                path.contains("/page/", true) -> true
                path.contains("/category", true) -> true
                path.contains("/tag/", true) -> true
                u.rawQuery != null && !path.contains(Regex("/\\d{4}/\\d{2}/")) -> true
                else -> false
            }
        } catch (e: Exception) {
            false
        }
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

            // File kerja incremental (anti hilang jika crash/stop).
            val workFile = File(filesDir, "corpus_work.txt")
            val writer = FileWriter(workFile, false)
            val dateReadable = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date())
            writer.write(
                "KORPUS TEKS — PostScraper Crawler\n" +
                        "Dibuat     : $dateReadable\n" +
                        "Seed       : $seed\n" +
                        "Domain     : $seedHost\n" +
                        "Target     : maks. $maxPosts post (min. $minChars karakter)\n" +
                        "Bersih     : ${if (minimalClean) "minimal" else "raw"}\n" +
                        "Encoding   : UTF-8\n" +
                        "=".repeat(64) + "\n\n"
            )
            writer.flush()

            val queue = ArrayDeque<String>()
            val seedNorm = normalizeLink(seed, seed, seedHost) ?: seed
            queue.add(seedNorm)

            var collected = 0
            var crawledPages = 0
            var skippedVisited = 0
            var totalChars = 0
            var totalWords = 0

            log("== MULAI CRAWL ==")
            log("Seed : $seed")
            log("Target: $maxPosts post | min. $minChars karakter | riwayat: ${if (useHistory) "ON" else "OFF"}")

            while (queue.isNotEmpty() && collected < maxPosts && !stopRequested) {
                val url = queue.removeFirst()

                if (useHistory && url in visited) { skippedVisited++; continue }
                markVisited(url)
                crawledPages++

                log("\n[$collected/$maxPosts] halaman ${crawledPages}: $url")
                updateStatus(collected, queue.size)

                try {
                    val html = fetchHtml(url)
                    val doc = Jsoup.parse(html, url)

                    // 1) Ekstrak & antrikan link se-domain (BFS).
                    var discovered = 0
                    doc.select("a[href]").eachAttr("abs:href").forEach { href ->
                        val norm = normalizeLink(href, url, seedHost)
                        if (norm != null && norm !in visited && norm !in queue) {
                            queue.add(norm)
                            discovered++
                        }
                    }
                    log("   link se-domain ditemukan: $discovered | antrian: ${queue.size}")

                    // 2) Jika halaman ini adalah post → ambil teks.
                    if (!isLikelyIndex(url)) {
                        val title = doc.title().ifBlank {
                            doc.selectFirst("h1, .post-title, .entry-title, .title")?.text() ?: url
                        }
                        val text = extractPostText(doc, minimalClean)

                        if (text.length >= minChars) {
                            collected++
                            val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
                            totalChars += text.length
                            totalWords += words

                            writer.write("\n\n===== POST #$collected =====\n")
                            writer.write("Judul : $title\n")
                            writer.write("URL   : $url\n")
                            writer.write("-".repeat(48) + "\n\n")
                            writer.write(text)
                            writer.write("\n")
                            writer.flush()

                            log("   >>> POST DIAMBIL #$collected: ${text.length} karakter, $words kata")
                        } else {
                            log("   (teks terlalu pendek: ${text.length} < $minChars — dilewati, tetap di-crawl linknya)")
                        }
                    } else {
                        log("   (halaman index/list — hanya diambil linknya)")
                    }
                } catch (e: Exception) {
                    log("   ERROR: ${e.message}")
                }

                // Jeda sopan antar request.
                if (queue.isNotEmpty() && collected < maxPosts) delay(800)
            }

            writer.close()

            // 3) Simpan ke Download.
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fname = "korpus_crawl_${seedHost.replace(".", "_")}_$timestamp.txt"

            var savedPath = "GAGAL"
            if (collected > 0) {
                try {
                    val footer = "\n\n" + "=".repeat(64) +
                            "\nRINGKASAN: $collected post diambil | $crawledPages halaman di-crawl | " +
                            "${skippedVisited} dilewati (riwayat) | total $totalChars karakter, $totalWords kata\n"
                    val content = workFile.readText(Charsets.UTF_8) + footer
                    saveToDownload(fname, content)
                    savedPath = "Download/$fname"
                    log("\n>>> DISIMPAN: $savedPath")
                } catch (e: Exception) {
                    log("\n>>> GAGAL simpan ke Download: ${e.message}")
                    log("File kerja tersimpan di internal app: ${workFile.absolutePath}")
                }
            } else {
                log("\nTidak ada post yang terkumpul (coba turunkan min. karakter atau periksa seed).")
            }

            log("\n== SELESAI ==")
            log("Post diambil : $collected")
            log("Halaman crawl: $crawledPages")
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

    // ---------- Fetch & Ekstraksi ----------

    private fun fetchHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            )
            .header("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            return resp.body?.string() ?: throw Exception("body kosong")
        }
    }

    /**
     * Ekstrak teks postingan. Coba selector artikel umum (blogspot/wordpress/
     * generic) lalu fallback ke body. Pembersihan minimal sesuai toggle.
     */
    private fun extractPostText(doc: Document, minimalClean: Boolean): String {
        val selectors = listOf(
            "article", "div.post-body", ".post-body", ".entry-content",
            "div.post-entry", "div.post", ".post", "main", "div#main", "body"
        )

        val element = selectors.asSequence()
            .map { doc.selectFirst(it) }
            .firstOrNull { el -> el != null && el.text().length >= 100 }
            ?: doc.body()
            ?: return ""

        if (minimalClean) {
            element.select("script, style, noscript, iframe, ins, form, button, nav, .comments, #comments").remove()
            return element.text()
                .replace(Regex("[ \\t]+"), " ")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
        } else {
            element.select("script, style, noscript").remove()
            return element.text().trim()
        }
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
                "Post terambil: $collected | Antrian: $queueSize | Riwayat: ${visited.size} URL"
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
