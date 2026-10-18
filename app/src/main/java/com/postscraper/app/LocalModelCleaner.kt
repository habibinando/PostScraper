package com.postscraper.app

import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Local-only GGUF inference. A single model is reused for all articles in a crawl. */
internal class LocalModelCleaner {
    private val mutex = Mutex()
    private var loadedPath: String? = null
    private var loadedModel: LlamaModel? = null

    suspend fun clean(title: String, text: String, modelPath: String): String? = mutex.withLock {
        val file = File(modelPath)
        if (!file.isFile || file.length() < 100L * 1024L * 1024L) {
            throw IllegalStateException("File model GGUF tidak ada atau tidak lengkap")
        }

        val model = getOrLoadModel(file.absolutePath)
        val chunks = splitIntoChunks(text, maxChars = 3000)
        if (chunks.isEmpty()) return@withLock null

        val cleaned = ArrayList<String>(chunks.size)
        for ((index, chunk) in chunks.withIndex()) {
            val prompt = "Judul artikel: " + title + "\nBagian " + (index + 1) + " dari " + chunks.size + ":\n\n" + chunk
            val response = Llama.complete(
                model = model,
                prompt = prompt,
                systemPrompt = SYSTEM_PROMPT,
                maxTokens = 1200
            ).text.trim()

            val minimumUsefulLength = min(160, max(60, chunk.length / 3))
            cleaned.add(if (response.length >= minimumUsefulLength) response else chunk)
        }
        cleaned.joinToString("\n\n").takeIf { it.isNotBlank() }
    }

    suspend fun release() = mutex.withLock {
        loadedModel?.let { if (it.isLoaded) Llama.releaseModel(it) }
        loadedModel = null
        loadedPath = null
    }

    private suspend fun getOrLoadModel(path: String): LlamaModel {
        val existing = loadedModel
        if (existing != null && existing.isLoaded && loadedPath == path) return existing

        existing?.let { if (it.isLoaded) Llama.releaseModel(it) }
        loadedModel = null
        loadedPath = null

        val cores = Runtime.getRuntime().availableProcessors()
        val loaded = Llama.loadModel(
            modelPath = path,
            config = LlamaConfig(
                contextSize = 4096,
                threads = min(4, max(2, cores - 1)),
                gpuLayers = 0,
                temperature = 0.1f,
                topP = 0.9f,
                topK = 30
            )
        )
        loadedModel = loaded
        loadedPath = path
        return loaded
    }

    private fun splitIntoChunks(text: String, maxChars: Int): List<String> {
        val paragraphs = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        val chunks = ArrayList<String>()
        var current = StringBuilder()

        fun flush() {
            if (current.isNotEmpty()) {
                chunks.add(current.toString())
                current = StringBuilder()
            }
        }

        for (paragraph in paragraphs) {
            if (paragraph.length > maxChars) {
                flush()
                var start = 0
                while (start < paragraph.length) {
                    val end = min(start + maxChars, paragraph.length)
                    chunks.add(paragraph.substring(start, end))
                    start = end
                }
                continue
            }

            val additionSize = if (current.isEmpty()) paragraph.length else paragraph.length + 2
            if (current.length + additionSize > maxChars) flush()
            if (current.isNotEmpty()) current.append("\n\n")
            current.append(paragraph)
        }
        flush()
        return chunks
    }

    private companion object {
        const val SYSTEM_PROMPT =
            "Kamu membersihkan hasil ekstraksi artikel. Hapus menu navigasi, label tombol, sisa iklan, teks antarmuka, baris berulang, dan elemen yang jelas bukan isi artikel. Pertahankan fakta, angka, nama, kutipan, urutan informasi, dan paragraf utama. Jangan meringkas, jangan menambah fakta, dan jangan mengarang. Keluarkan hanya teks artikel yang sudah dibersihkan."
    }
}
