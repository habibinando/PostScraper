package com.postscraper.app

import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** On-device GGUF extraction fallback. It is loaded only when ordinary extractors fail. */
internal class LocalModelCleaner(
    private val shouldStop: () -> Boolean = { false }
) {
    private val mutex = Mutex()
    private var loadedPath: String? = null
    private var loadedModel: LlamaModel? = null

    suspend fun extractArticle(title: String, html: String, modelPath: String, minChars: Int): String? =
        mutex.withLock {
            if (shouldStop()) return@withLock null
            val file = File(modelPath)
            if (!file.isFile || file.length() < 100L * 1024L * 1024L) {
                throw IllegalStateException("File model GGUF tidak ada atau tidak lengkap")
            }

            val document = Jsoup.parse(html)
            document.select(
                "script,style,noscript,svg,nav,footer,header,aside,form,button,iframe,dialog," +
                    ".advert,.advertisement,.ads,.ad,.sidebar,.related,.comments,.comment," +
                    ".social-share,.share-buttons,.breadcrumbs,.menu,.navigation"
            ).remove()

            val preferred = document.select(
                "article,main,[itemprop=articleBody],.article-content,.article-body," +
                    ".entry-content,.post-content,.post-body,.content-article,#article-body"
            ).maxByOrNull { it.text().length }

            val bodyText = (preferred ?: document.body())
                ?.text()
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                .orEmpty()

            if (bodyText.length < max(120, minChars / 2)) return@withLock null
            val inputText = bodyText.take(5_200)
            val model = getOrLoadModel(file.absolutePath)
            if (shouldStop()) return@withLock null

            val response = Llama.complete(
                model = model,
                prompt = "Judul halaman: $title\n\nTeks halaman yang sudah dibersihkan dari menu dan skrip:\n$inputText",
                systemPrompt = EXTRACT_PROMPT,
                maxTokens = 650
            ).text.trim()

            if (shouldStop()) return@withLock null
            if (response.length < minChars || response.contains("NO_ARTICLE", ignoreCase = true)) {
                return@withLock null
            }
            response
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

    private companion object {
        const val EXTRACT_PROMPT =
            "Kamu adalah ekstraktor artikel dari teks halaman web. Ambil hanya isi utama artikel yang benar-benar ada dalam sumber. Hilangkan navigasi, iklan, rekomendasi, komentar, tombol, dan teks antarmuka. Pertahankan fakta, angka, nama, kutipan, urutan informasi, dan paragraf sebanyak mungkin. Jangan meringkas, jangan menambah fakta, jangan mengarang. Jika halaman bukan artikel atau teks tidak memuat isi artikel, jawab persis NO_ARTICLE. Keluarkan hanya teks artikel."
    }
}
