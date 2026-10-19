package com.postscraper.app

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.postscraper.app.databinding.ActivityAssistantBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main chat surface for the imported on-device GGUF model.
 * Scraping stays in MainActivity; no external chat API is used here.
 */
class AssistantActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAssistantBinding
    private val settingsStore by lazy(LazyThreadSafetyMode.NONE) { AppSettingsStore(applicationContext) }
    private val assistantModel by lazy(LazyThreadSafetyMode.NONE) { LocalModelCleaner() }

    private data class Turn(val role: String, val text: String)
    private val chatHistory = mutableListOf<Turn>()
    private var isGenerating = false
    private var attachedFileName: String? = null
    private var attachedFileText: String? = null
    private var attachedFileWasTruncated = false
    private var pendingSaveText: String? = null
    private var pendingSaveName = "hasil_diedit.txt"

    private val modelPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importModel(uri)
        }

    private val textPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) loadTextFile(uri)
        }

    private val saveTextPicker =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) saveResultTo(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAssistantBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= 35) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            ViewCompat.setOnApplyWindowInsetsListener(binding.drawerRoot) { view, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }

        binding.toolbar.setNavigationOnClickListener {
            binding.drawerRoot.openDrawer(GravityCompat.START)
        }
        binding.btnNavChat.setOnClickListener { showChat() }
        binding.btnNavSettings.setOnClickListener { showSettings() }
        binding.btnNavScraper.setOnClickListener {
            binding.drawerRoot.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, MainActivity::class.java))
        }
        binding.btnNewChat.setOnClickListener {
            startNewChat()
            binding.drawerRoot.closeDrawer(GravityCompat.START)
        }
        binding.btnOpenText.setOnClickListener {
            binding.drawerRoot.closeDrawer(GravityCompat.START)
            openTextPicker()
        }
        binding.btnAttachText.setOnClickListener { openTextPicker() }
        binding.btnRemoveAttachment.setOnClickListener { clearAttachment() }
        binding.btnSendChat.setOnClickListener { sendMessage() }
        binding.btnWelcomeSettings.setOnClickListener { showSettings() }
        binding.btnImportModel.setOnClickListener {
            if (isGenerating) {
                toast("Tunggu proses chat selesai sebelum mengganti model.")
            } else {
                modelPicker.launch(arrayOf("*/*"))
            }
        }
        binding.btnSaveSettings.setOnClickListener { saveSettings() }
        binding.btnSaveResult.setOnClickListener {
            if (pendingSaveText != null) saveTextPicker.launch(pendingSaveName)
        }
        binding.etChatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else {
                false
            }
        }

        loadSettingsIntoUi()
        if (intent.getStringExtra("start_screen") == "settings") showSettings() else showChat()
    }

    override fun onBackPressed() {
        when {
            binding.drawerRoot.isDrawerOpen(GravityCompat.START) ->
                binding.drawerRoot.closeDrawer(GravityCompat.START)
            binding.settingsScreen.visibility == View.VISIBLE -> showChat()
            else -> super.onBackPressed()
        }
    }

    private fun showChat() {
        binding.chatScreen.visibility = View.VISIBLE
        binding.settingsScreen.visibility = View.GONE
        binding.toolbar.title = "PostScraper AI"
        binding.drawerRoot.closeDrawer(GravityCompat.START)
        refreshModelStatus()
    }

    private fun showSettings() {
        loadSettingsIntoUi()
        binding.chatScreen.visibility = View.GONE
        binding.settingsScreen.visibility = View.VISIBLE
        binding.toolbar.title = "Pengaturan"
        binding.drawerRoot.closeDrawer(GravityCompat.START)
    }

    private fun refreshModelStatus() {
        val settings = settingsStore.load()
        val model = File(settings.localModelPath)
        val status = if (settings.localModelPath.isNotBlank() && model.isFile) {
            "Model lokal: " + model.name
        } else {
            "Model lokal belum diimpor • buka Pengaturan"
        }
        binding.tvChatStatus.text = status
        binding.tvDrawerModel.text = status
    }

    private fun loadSettingsIntoUi() {
        val settings = settingsStore.load()
        val model = File(settings.localModelPath)
        binding.tvModelStatus.text = if (settings.localModelPath.isNotBlank() && model.isFile) {
            "Terpasang: " + model.name + "\nUkuran: " +
                String.format(Locale.getDefault(), "%.2f GB", model.length() / 1_073_741_824.0) +
                "\nChat berjalan secara lokal di perangkat."
        } else {
            "Belum ada model. Impor model GGUF yang kompatibel, misalnya Qwen2.5-3B-Instruct Q4_K_M. File asli tidak akan dihapus."
        }
        binding.swUseLocalModel.isChecked = settings.useLocalModelCleaning
        binding.swProxyEnabled.isChecked = settings.proxyEnabled
        binding.etProxyHost.setText(settings.proxyHost)
        binding.etProxyPort.setText(settings.proxyPort.toString())
        refreshModelStatus()
    }

    private fun saveSettings() {
        if (isGenerating) {
            toast("Tunggu proses chat selesai sebelum menyimpan pengaturan.")
            return
        }
        val current = settingsStore.load()
        val useLocal = binding.swUseLocalModel.isChecked
        val proxyEnabled = binding.swProxyEnabled.isChecked
        val host = binding.etProxyHost.text?.toString()?.trim().orEmpty()
        val port = binding.etProxyPort.text?.toString()?.trim()?.toIntOrNull() ?: 0

        if (useLocal && (current.localModelPath.isBlank() || !File(current.localModelPath).isFile)) {
            toast("Impor model GGUF terlebih dahulu.")
            return
        }
        if (proxyEnabled && host.isBlank()) {
            binding.etProxyHost.error = "Isi host proxy"
            return
        }
        if (proxyEnabled && port !in 1..65535) {
            binding.etProxyPort.error = "Port harus 1–65535"
            return
        }

        settingsStore.save(
            current.copy(
                useLocalModelCleaning = useLocal,
                proxyEnabled = proxyEnabled,
                proxyHost = host,
                proxyPort = if (port in 1..65535) port else 8080
            )
        )
        toast("Pengaturan disimpan.")
        loadSettingsIntoUi()
    }

    private fun startNewChat() {
        chatHistory.clear()
        binding.chatMessages.removeAllViews()
        binding.chatMessages.addView(binding.chatWelcome)
        binding.chatWelcome.visibility = View.VISIBLE
        binding.btnSaveResult.visibility = View.GONE
        pendingSaveText = null
        etChatInputSafeClear()
        binding.drawerRoot.closeDrawer(GravityCompat.START)
        showChat()
    }

    private fun etChatInputSafeClear() {
        binding.etChatInput.setText("")
    }

    private fun sendMessage() {
        if (isGenerating) {
            toast("Model masih menyiapkan jawaban.")
            return
        }
        val userText = binding.etChatInput.text?.toString()?.trim().orEmpty()
        if (userText.isBlank()) return
        binding.etChatInput.setText("")
        binding.chatWelcome.visibility = View.GONE
        addChatBubble(userText, isUser = true)
        chatHistory.add(Turn("user", userText))
        binding.btnSaveResult.visibility = View.GONE
        pendingSaveText = null

        if (handleBuiltInCommand(userText)) return

        val settings = settingsStore.load()
        val modelFile = File(settings.localModelPath)
        if (settings.localModelPath.isBlank() || !modelFile.isFile) {
            addAssistantReply(
                "Model belum diimpor. Buka menu ☰ → Pengaturan → Impor model GGUF. " +
                    "Setelah model terpasang, chat dan perbaikan file akan dijalankan di HP."
            )
            return
        }

        val editFile = attachedFileText != null && isFileEditInstruction(userText)
        val prompt = buildPrompt(userText)
        val systemPrompt = if (editFile) FILE_EDIT_SYSTEM_PROMPT else CHAT_SYSTEM_PROMPT
        isGenerating = true
        binding.btnSendChat.isEnabled = false
        binding.btnAttachText.isEnabled = false
        binding.btnSendChat.text = "..."
        binding.tvChatStatus.text = "Model sedang berpikir di perangkat…"

        lifecycleScope.launch {
            try {
                val answer = withContext(Dispatchers.IO) {
                    assistantModel.generate(
                        prompt = prompt,
                        systemPrompt = systemPrompt,
                        modelPath = settings.localModelPath,
                        maxTokens = if (editFile) 1200 else 850
                    )
                }
                val safeAnswer = answer.ifBlank { "Model tidak menghasilkan jawaban. Coba instruksi yang lebih singkat." }
                addAssistantReply(safeAnswer)
                if (editFile) {
                    pendingSaveText = safeAnswer
                    pendingSaveName = makeEditedFileName(attachedFileName)
                    binding.btnSaveResult.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                addAssistantReply(
                    "Gagal menjalankan model lokal: " + (e.message ?: e.javaClass.simpleName) +
                        "\n\nPeriksa apakah file GGUF benar, ruang RAM cukup, dan model didukung."
                )
            } finally {
                isGenerating = false
                binding.btnSendChat.isEnabled = true
                binding.btnAttachText.isEnabled = true
                binding.btnSendChat.text = "Kirim"
                refreshModelStatus()
            }
        }
    }

    private fun buildPrompt(currentMessage: String): String {
        val previous = chatHistory.dropLast(1).takeLast(8)
        return buildString {
            if (previous.isNotEmpty()) {
                append("Riwayat percakapan:\n")
                previous.forEach { turn ->
                    append(if (turn.role == "user") "Pengguna: " else "Asisten: ")
                    append(turn.text)
                    append("\n")
                }
                append("\n")
            }
            if (attachedFileText != null) {
                append("File teks yang diberikan pengguna: ")
                append(attachedFileName ?: "dokumen.txt")
                append("\n--- AWAL ISI FILE ---\n")
                append(attachedFileText)
                if (attachedFileWasTruncated) append("\n[Catatan: file terlalu panjang; hanya bagian awal yang disertakan.]")
                append("\n--- AKHIR ISI FILE ---\n\n")
            }
            append("Pesan terbaru pengguna:\n")
            append(currentMessage)
            append("\n\nJawaban asisten:")
        }
    }

    private fun handleBuiltInCommand(message: String): Boolean {
        val text = message.trim()
        val lower = text.lowercase(Locale.ROOT)

        if (lower == "/new" || lower == "/newchat" || lower == "/chat-baru") {
            startNewChat()
            return true
        }
        if (lower == "/settings" || lower == "/pengaturan" ||
            lower.contains("buka pengaturan") || lower.contains("buka settings") ||
            lower.contains("tampilkan pengaturan")
        ) {
            addAssistantReply("Membuka halaman Pengaturan.")
            showSettings()
            return true
        }
        if (lower == "/chat" || lower == "buka chat" || lower == "tampilkan chat") {
            addAssistantReply("Kembali ke halaman Chat.")
            showChat()
            return true
        }
        if (lower == "/file" || lower.contains("pilih file teks") || lower.contains("buka file teks")) {
            addAssistantReply("Pilih file teks dari penyimpanan perangkat.")
            openTextPicker()
            return true
        }

        val wantsScraper = lower.startsWith("/scrape") || lower.startsWith("/crawl") ||
            lower.contains("buka scraper") || lower.contains("mulai scraping") ||
            lower.contains("ambil artikel dari")
        if (wantsScraper) {
            val url = findUrl(text)
            addAssistantReply(
                if (url == null) {
                    "Membuka Scraper. Masukkan URL situs pada kolom sumber, lalu tekan Mulai."
                } else {
                    "Membuka Scraper dengan URL sumber: " + normalizeUrl(url) +
                        "\nPeriksa target post dan tekan Mulai untuk menjalankan crawling."
                }
            )
            val intent = Intent(this, MainActivity::class.java)
            if (url != null) intent.putExtra("seed_url", normalizeUrl(url))
            binding.drawerRoot.closeDrawer(GravityCompat.START)
            startActivity(intent)
            return true
        }
        return false
    }

    private fun findUrl(text: String): String? {
        val tokens = text.split(' ', '\n', '\t')
        for (token in tokens) {
            val candidate = token.trim().trimEnd(',', '.', ')', ']', '>')
            val suffix = candidate.substringAfterLast('.', "")
            val looksLikeDomain = candidate.contains('.') && suffix.matches(Regex("[A-Za-z]{2,}"))
            if (candidate.startsWith("https://", true) ||
                candidate.startsWith("http://", true) ||
                candidate.startsWith("www.", true) ||
                looksLikeDomain
            ) {
                return candidate
            }
        }
        return null
    }

    private fun normalizeUrl(value: String): String =
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) value
        else "https://" + value

    private fun isFileEditInstruction(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return listOf(
            "perbaiki", "benerin", "benahi", "rapikan", "rapihin", "revisi",
            "edit", "koreksi", "sunting", "ubah", "format", "ringkas", "terjemahkan",
            "terjemahin", "tulis ulang", "periksa typo", "periksa ejaan"
        ).any { lower.contains(it) }
    }

    private fun addAssistantReply(text: String) {
        addChatBubble(text, isUser = false)
        chatHistory.add(Turn("assistant", text))
        binding.chatScroll.post { binding.chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addChatBubble(text: String, isUser: Boolean) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (isUser) Gravity.END else Gravity.START
            setPadding(0, dp(4), 0, dp(4))
        }
        val bubble = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.parseColor("#192338"))
            setPadding(dp(14), dp(11), dp(14), dp(11))
            maxWidth = (resources.displayMetrics.widthPixels * 0.84f).toInt()
            setTextIsSelectable(true)
            setLineSpacing(dp(2).toFloat(), 1.0f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(if (isUser) 20 else 14).toFloat()
                setColor(Color.parseColor(if (isUser) "#E8EEFF" else "#FFFFFF"))
                if (!isUser) setStroke(dp(1), Color.parseColor("#E0E5F0"))
            }
        }
        row.addView(
            bubble,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        binding.chatMessages.addView(row)
        binding.chatScroll.post { binding.chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun openTextPicker() {
        if (isGenerating) {
            toast("Tunggu jawaban model selesai sebelum membuka file.")
            return
        }
        textPicker.launch(arrayOf("text/plain", "text/*", "application/octet-stream"))
    }

    private fun loadTextFile(uri: Uri) {
        lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val name = contentResolver.query(
                        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (index >= 0) cursor.getString(index) else null
                        } else null
                    } ?: "dokumen.txt"

                    val text = StringBuilder()
                    var truncated = false
                    contentResolver.openInputStream(uri)?.use { stream ->
                        InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
                            val buffer = CharArray(2048)
                            while (text.length <= MAX_FILE_CHARS) {
                                val remaining = MAX_FILE_CHARS + 1 - text.length
                                val count = reader.read(buffer, 0, minOf(buffer.size, remaining))
                                if (count < 0) break
                                text.append(buffer, 0, count)
                            }
                            if (text.length > MAX_FILE_CHARS) truncated = true
                        }
                    } ?: throw IOException("File tidak dapat dibuka.")

                    Triple(name, text.toString().take(MAX_FILE_CHARS), truncated)
                }
                attachedFileName = loaded.first
                attachedFileText = loaded.second
                attachedFileWasTruncated = loaded.third
                binding.attachmentBar.visibility = View.VISIBLE
                binding.tvAttachedFile.text = if (loaded.third) {
                    loaded.first + " • cuplikan awal " + MAX_FILE_CHARS + " karakter"
                } else {
                    loaded.first + " • " + loaded.second.length + " karakter"
                }
                binding.btnSaveResult.visibility = View.GONE
                pendingSaveText = null
                showChat()
                toast("File teks siap. Tulis instruksi untuk mengolah isinya.")
            } catch (e: Exception) {
                toast("Gagal membuka file: " + (e.message ?: "format tidak terbaca"))
            }
        }
    }

    private fun clearAttachment() {
        attachedFileName = null
        attachedFileText = null
        attachedFileWasTruncated = false
        binding.attachmentBar.visibility = View.GONE
        binding.tvAttachedFile.text = ""
        binding.btnSaveResult.visibility = View.GONE
        pendingSaveText = null
    }

    private fun makeEditedFileName(originalName: String?): String {
        val stem = (originalName ?: "dokumen").substringBeforeLast('.', originalName ?: "dokumen")
            .replace(Regex("[^A-Za-z0-9_-]+"), "_")
            .take(64)
            .ifBlank { "dokumen" }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
        return stem + "_diedit_" + timestamp + ".txt"
    }

    private fun saveResultTo(uri: Uri) {
        val text = pendingSaveText ?: return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(text.toByteArray(StandardCharsets.UTF_8))
                    } ?: throw IOException("Lokasi penyimpanan tidak dapat dibuka.")
                }
                toast("Hasil teks berhasil disimpan.")
            } catch (e: Exception) {
                toast("Gagal menyimpan hasil: " + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun importModel(uri: Uri) {
        if (isGenerating) {
            toast("Tunggu proses chat selesai sebelum mengganti model.")
            return
        }
        binding.btnImportModel.isEnabled = false
        binding.tvModelStatus.text = "Mengimpor model. Proses dapat memerlukan waktu dan ruang kosong besar."
        lifecycleScope.launch {
            try {
                val destinationPath = withContext(Dispatchers.IO) {
                    assistantModel.release()
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
                        throw IOException("Pilih file yang berakhiran .gguf.")
                    }

                    val modelDir = getExternalFilesDir("models") ?: File(filesDir, "models")
                    if (!modelDir.exists() && !modelDir.mkdirs()) {
                        throw IOException("Folder model tidak dapat dibuat.")
                    }
                    val requiredBytes = (metadata.second ?: 0L) + 128L * 1024L * 1024L
                    if (StatFs(modelDir.absolutePath).availableBytes < requiredBytes) {
                        throw IOException("Ruang kosong tidak cukup untuk mengimpor model.")
                    }

                    val temporary = File(modelDir, "assistant-model.gguf.part")
                    if (temporary.exists()) temporary.delete()
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IOException("File model tidak dapat dibuka.")
                    input.use { source ->
                        temporary.outputStream().buffered(1024 * 1024).use { output ->
                            source.copyTo(output, 1024 * 1024)
                        }
                    }
                    if (temporary.length() < 100L * 1024L * 1024L) {
                        temporary.delete()
                        throw IOException("File terlalu kecil untuk model GGUF yang lengkap.")
                    }

                    val destination = File(modelDir, "assistant-model.gguf")
                    if (destination.exists() && !destination.delete()) {
                        temporary.delete()
                        throw IOException("Model sebelumnya tidak dapat diganti.")
                    }
                    if (!temporary.renameTo(destination)) {
                        temporary.copyTo(destination, overwrite = true)
                        temporary.delete()
                    }
                    val current = settingsStore.load()
                    settingsStore.save(
                        current.copy(
                            localModelPath = destination.absolutePath,
                            useLocalModelCleaning = true
                        )
                    )
                    destination.absolutePath
                }
                toast("Model berhasil diimpor.")
                loadSettingsIntoUi()
                showChat()
            } catch (e: Exception) {
                loadSettingsIntoUi()
                toast("Gagal mengimpor model: " + (e.message ?: e.javaClass.simpleName))
            } finally {
                binding.btnImportModel.isEnabled = true
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val MAX_FILE_CHARS = 8_000
        const val CHAT_SYSTEM_PROMPT =
            "Kamu adalah asisten AI lokal yang membantu dalam bahasa pengguna, terutama bahasa Indonesia. " +
            "Jawab jelas, relevan, dan jujur. Kamu berjalan di perangkat dan tidak memiliki akses internet atau " +
            "kemampuan mengendalikan fungsi Android di luar perintah yang secara eksplisit didukung aplikasi. " +
            "Jangan mengaku telah melakukan tindakan yang tidak benar-benar dijalankan. Bila pengguna memberi file teks, " +
            "gunakan isi file tersebut sebagai konteks dan ikuti instruksinya."
        const val FILE_EDIT_SYSTEM_PROMPT =
            "Kamu adalah asisten penyunting teks. Ikuti instruksi pengguna untuk memperbaiki, merapikan, meringkas, " +
            "memformat, menerjemahkan, atau merevisi file teks yang disertakan. Pertahankan maksud dan fakta sumber; " +
            "jangan menambah klaim yang tidak ada. Untuk permintaan menyunting file, keluarkan hanya isi file hasil " +
            "akhir, tanpa pembukaan, penjelasan, atau pagar Markdown, agar bisa langsung disimpan sebagai TXT. " +
            "Jika instruksi hanya meminta analisis, jawab sesuai permintaan."
    }
}
