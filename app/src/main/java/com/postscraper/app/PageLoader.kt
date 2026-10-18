package com.postscraper.app

import android.annotation.SuppressLint
import android.app.Activity
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Network and JavaScript rendering boundary. MainActivity controls lifecycle and
 * can cancel both an HTTP request and a pending WebView render through cancelCurrent().
 */
internal class PageLoader(
    private val activity: Activity,
    private val shouldStop: () -> Boolean
) {
    private val desktopUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val maxHtmlBytes = 5 * 1024 * 1024

    @Volatile
    private var activeCall: Call? = null

    @Volatile
    private var cancelWebViewRender: (() -> Unit)? = null

    fun cancelCurrent() {
        activeCall?.cancel()
        cancelWebViewRender?.invoke()
    }

    fun fetchHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", desktopUa)
            .header("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        val call = client.newCall(request)
        activeCall = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                val body = response.body ?: throw Exception("body kosong")
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
                        if (shouldStop()) throw java.io.IOException("Crawl dihentikan")
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
    suspend fun renderWithWebView(url: String): String? {
        return try {
            withTimeoutOrNull(30_000L) {
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine<String?> { continuation ->
                        val webView = WebView(activity)
                        val finished = AtomicBoolean(false)

                        fun finish(result: String?) {
                            if (!finished.compareAndSet(false, true)) return
                            cancelWebViewRender = null
                            try {
                                webView.stopLoading()
                                webView.destroy()
                            } catch (_: Exception) { }
                            if (continuation.isActive) continuation.resume(result)
                        }

                        cancelWebViewRender = { finish(null) }

                        webView.settings.javaScriptEnabled = true
                        webView.settings.domStorageEnabled = true
                        webView.settings.blockNetworkImage = true
                        webView.settings.mediaPlaybackRequiresUserGesture = true
                        webView.settings.userAgentString = desktopUa

                        webView.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                                val target = view ?: return
                                target.postDelayed({
                                    if (finished.get() || shouldStop()) {
                                        finish(null)
                                        return@postDelayed
                                    }
                                    target.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                                        finish(decodeJsString(raw))
                                    }
                                }, 3_500L)
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                if (request?.isForMainFrame == true) finish(null)
                            }
                        }

                        continuation.invokeOnCancellation {
                            cancelWebViewRender = null
                            if (finished.compareAndSet(false, true)) {
                                webView.post {
                                    try {
                                        webView.stopLoading()
                                        webView.destroy()
                                    } catch (_: Exception) { }
                                }
                            }
                        }

                        webView.loadUrl(url)
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeJsString(raw: String?): String? {
        if (raw == null || raw == "null" || raw.length < 2) return null
        return try {
            JSONTokener(raw).nextValue() as? String
        } catch (_: Exception) {
            null
        }
    }
}
