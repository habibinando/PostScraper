package com.postscraper.app

import android.content.Context
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

internal data class AppSettings(
    val firecrawlApiKey: String = "",
    val useFirecrawl: Boolean = false,
    val groqApiKey: String = "",
    val groqModel: String = "openai/gpt-oss-20b",
    val useGroqCleaning: Boolean = false,
    val proxyEnabled: Boolean = false,
    val proxyHost: String = "",
    val proxyPort: Int = 8080
)

internal class AppSettingsStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("postscraper_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        firecrawlApiKey = preferences.getString("firecrawl_api_key", "").orEmpty(),
        useFirecrawl = preferences.getBoolean("use_firecrawl", false),
        groqApiKey = preferences.getString("groq_api_key", "").orEmpty(),
        groqModel = preferences.getString("groq_model", "openai/gpt-oss-20b").orEmpty(),
        useGroqCleaning = preferences.getBoolean("use_groq_cleaning", false),
        proxyEnabled = preferences.getBoolean("proxy_enabled", false),
        proxyHost = preferences.getString("proxy_host", "").orEmpty(),
        proxyPort = preferences.getInt("proxy_port", 8080)
    )

    fun save(settings: AppSettings) {
        preferences.edit()
            .putString("firecrawl_api_key", settings.firecrawlApiKey)
            .putBoolean("use_firecrawl", settings.useFirecrawl)
            .putString("groq_api_key", settings.groqApiKey)
            .putString("groq_model", settings.groqModel)
            .putBoolean("use_groq_cleaning", settings.useGroqCleaning)
            .putBoolean("proxy_enabled", settings.proxyEnabled)
            .putString("proxy_host", settings.proxyHost)
            .putInt("proxy_port", settings.proxyPort)
            .apply()
    }
}

/** Reuses OkHttp connections and rebuilds the client only when proxy settings change. */
internal class NetworkClientFactory(private val settingsStore: AppSettingsStore) {
    @Volatile private var cachedFingerprint: String = ""
    @Volatile private var cachedClient: OkHttpClient? = null

    fun client(): OkHttpClient {
        val settings = settingsStore.load()
        val fingerprint = listOf(
            settings.proxyEnabled.toString(),
            settings.proxyHost,
            settings.proxyPort.toString()
        ).joinToString("|")

        synchronized(this) {
            if (cachedClient == null || cachedFingerprint != fingerprint) {
                val builder = OkHttpClient.Builder()
                    .connectTimeout(18, TimeUnit.SECONDS)
                    .readTimeout(45, TimeUnit.SECONDS)
                    .callTimeout(60, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .retryOnConnectionFailure(true)

                if (settings.proxyEnabled && settings.proxyHost.isNotBlank() &&
                    settings.proxyPort in 1..65535
                ) {
                    builder.proxy(
                        Proxy(
                            Proxy.Type.HTTP,
                            InetSocketAddress(settings.proxyHost, settings.proxyPort)
                        )
                    )
                }

                cachedClient = builder.build()
                cachedFingerprint = fingerprint
            }
            return cachedClient!!
        }
    }
}
