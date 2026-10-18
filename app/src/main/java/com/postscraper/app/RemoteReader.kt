package com.postscraper.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Optional cloud fallback for sites that block ordinary HTML extraction.
 * The primary crawler remains local. Only URLs explicitly processed as a fallback
 * are sent to Jina Reader; the UI exposes a switch to disable this behavior.
 */
internal class RemoteReader(private val networkClients: NetworkClientFactory) {
    @Volatile
    private var lastRequestAt = 0L

    @Synchronized
    fun fetchMarkdown(targetUrl: String): String? {
        // Anonymous Reader requests have a limited rate. Keep this app below 20 RPM.
        val wait = 3_100L - (System.currentTimeMillis() - lastRequestAt)
        if (wait > 0) Thread.sleep(wait)

        val removeSelectors = JSONArray()
            .put("nav")
            .put("footer")
            .put("aside")
            .put(".sidebar")
            .put(".related")
            .put(".comments")
            .put(".advert")
            .put(".ads")

        val payload = JSONObject()
            .put("url", targetUrl)
            .put("respondWith", "markdown")
            .put("timeout", 20_000)
            .put("removeSelector", removeSelectors)
            .toString()

        val request = Request.Builder()
            .url("https://r.jina.ai/")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "text/plain")
            .header("DNT", "true")
            .header("X-Respond-With", "markdown")
            .build()

        lastRequestAt = System.currentTimeMillis()
        return try {
            networkClients.client().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                if (body.contentLength() > 3L * 1024L * 1024L) return null
                body.string().takeIf { it.length >= 80 && !it.startsWith("{\"code\":4") }
            }
        } catch (_: Exception) {
            null
        }
    }
}
