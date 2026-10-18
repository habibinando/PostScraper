package com.postscraper.app

import android.content.Context
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

internal data class AppSettings(
    val localModelPath: String = "",
    val useLocalModelCleaning: Boolean = false,
    val proxyEnabled: Boolean = false,
    val proxyHost: String = "",
    val proxyPort: Int = 8080
)

internal class AppSettingsStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("postscraper_settings", Context.MODE_PRIVATE)

    init {
        preferences.edit()
            .remove("groq_api_key")
            .remove("groq_model")
            .remove("use_groq_cleaning")
            .remove("firecrawl_api_key")
            .remove("use_firecrawl")
            .apply()
    }

    fun load(): AppSettings = AppSettings(
        localModelPath = preferences.getString("local_model_path", "").orEmpty(),
        useLocalModelCleaning = preferences.getBoolean("use_local_model_cleaning", false),
        proxyEnabled = preferences.getBoolean("proxy_enabled", false),
        proxyHost = preferences.getString("proxy_host", "").orEmpty(),
        proxyPort = preferences.getInt("proxy_port", 8080)
    )

    fun save(settings: AppSettings) {
        preferences.edit()
            .putString("local_model_path", settings.localModelPath)
            .putBoolean("use_local_model_cleaning", settings.useLocalModelCleaning)
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
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(12, TimeUnit.SECONDS)
                    .callTimeout(18, TimeUnit.SECONDS)
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
