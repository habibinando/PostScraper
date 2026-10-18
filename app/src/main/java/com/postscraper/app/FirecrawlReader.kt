package com.postscraper.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Optional Firecrawl /v2/scrape adapter. Called only when enabled and local extraction needs a fallback. */
internal class FirecrawlReader(private val networkClients: NetworkClientFactory) {
    fun fetchMarkdown(targetUrl: String, apiKey: String): String? {
        if (apiKey.isBlank()) return null
        val payload = JSONObject()
            .put("url", targetUrl)
            .put("formats", JSONArray().put("markdown"))
            .put("onlyMainContent", true)
            .toString()

        val request = Request.Builder()
            .url("https://api.firecrawl.dev/v2/scrape")
            .header("Authorization", "Bearer " + apiKey.trim())
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        networkClients.client().newCall(request).execute().use { response ->
            val body = response.body ?: throw IOException("respons Firecrawl kosong")
            if (!response.isSuccessful) {
                val detail = try { body.string().take(240) } catch (_: Exception) { "" }
                throw IOException("HTTP " + response.code + if (detail.isBlank()) "" else ": " + detail)
            }
            if (body.contentLength() > 4L * 1024L * 1024L) {
                throw IOException("respons Firecrawl melewati batas ukuran")
            }
            val json = JSONObject(body.string())
            val data = json.optJSONObject("data") ?: json
            val markdown = data.optString("markdown", "")
            return markdown.takeIf { it.length >= 80 && it.length <= 3 * 1024 * 1024 }
        }
    }
}
