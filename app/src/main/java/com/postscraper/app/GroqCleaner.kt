package com.postscraper.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Optional Groq text cleanup and model discovery using the OpenAI-compatible API. */
internal class GroqCleaner(private val networkClients: NetworkClientFactory) {
    fun fetchModels(apiKey: String): List<String> {
        if (apiKey.isBlank()) throw IOException("Isi Groq API key terlebih dahulu")
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/models")
            .header("Authorization", "Bearer " + apiKey.trim())
            .get()
            .build()

        networkClients.client().newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Groq API HTTP " + response.code + ": " + body.take(200))
            }
            val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
            val result = ArrayList<String>()
            for (index in 0 until data.length()) {
                val id = data.optJSONObject(index)?.optString("id").orEmpty()
                if (id.isNotBlank()) result.add(id)
            }
            if (result.isEmpty()) throw IOException("Groq tidak mengembalikan daftar model")
            return result.distinct().sorted()
        }
    }

    fun clean(title: String, text: String, apiKey: String, model: String): String? {
        if (apiKey.isBlank() || text.isBlank()) return null
        val inputText = text.take(10_000)
        val systemPrompt = "Kamu membersihkan teks artikel untuk korpus. Hapus navigasi, label tombol, sisa iklan, duplikasi, dan teks antarmuka. Pertahankan semua fakta, urutan, angka, nama, kutipan, serta paragraf isi. Jangan meringkas, menambah informasi, atau mengarang. Keluarkan teks artikel saja tanpa komentar dan tanpa markdown tambahan."
        val userPrompt = "Judul artikel: " + title + "\n\nTeks hasil ekstraksi:\n" + inputText
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", userPrompt))
        val payload = JSONObject()
            .put("model", model.trim().ifBlank { "openai/gpt-oss-20b" })
            .put("messages", messages)
            .put("temperature", 0.1)
            .put("max_completion_tokens", 4096)
            .toString()

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer " + apiKey.trim())
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        networkClients.client().newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Groq API HTTP " + response.code + ": " + body.take(240))
            }
            val choices = JSONObject(body).optJSONArray("choices") ?: JSONArray()
            val message = choices.optJSONObject(0)?.optJSONObject("message")
            return message?.optString("content")?.trim()?.takeIf { it.length >= 80 }
        }
    }
}
