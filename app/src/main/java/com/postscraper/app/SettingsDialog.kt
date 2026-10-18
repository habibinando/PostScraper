package com.postscraper.app

import android.text.InputType
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Compact settings editor opened from the top hamburger button. */
internal class SettingsDialog(
    private val activity: AppCompatActivity,
    private val store: AppSettingsStore,
    private val groqCleaner: GroqCleaner
) {
    fun show(onSaved: () -> Unit) {
        val current = store.load()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
        }
        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            addView(content)
        }

        fun addCaption(text: String) {
            content.addView(TextView(activity).apply {
                this.text = text
                textSize = 12f
                setPadding(4, 10, 4, 4)
            }, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        fun addField(label: String, initial: String, password: Boolean = false, number: Boolean = false): TextInputEditText {
            val wrapper = TextInputLayout(activity).apply {
                hint = label
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                val radius = (12 * activity.resources.displayMetrics.density).toInt().toFloat()
                setBoxCornerRadii(radius, radius, radius, radius)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (6 * activity.resources.displayMetrics.density).toInt() }
            }
            val edit = TextInputEditText(activity).apply {
                setText(initial)
                isSingleLine = true
                inputType = when {
                    password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    number -> InputType.TYPE_CLASS_NUMBER
                    else -> InputType.TYPE_CLASS_TEXT
                }
                textSize = 14f
            }
            wrapper.addView(edit)
            content.addView(wrapper)
            return edit
        }

        addCaption("FIRECRAWL — fallback cloud saat HTML lokal tidak cukup")
        val firecrawlKey = addField("Firecrawl API key", current.firecrawlApiKey, password = true)
        val firecrawlEnabled = MaterialSwitch(activity).apply {
            text = "Aktifkan Firecrawl sebagai fallback"
            isChecked = current.useFirecrawl
            textSize = 14f
            setPadding(2, 8, 2, 4)
        }
        content.addView(firecrawlEnabled)

        addCaption("GROQ — AI untuk membersihkan hasil teks, bukan untuk mengambil halaman")
        val groqKey = addField("Groq API key", current.groqApiKey, password = true)
        val groqModel = addField("Model ID", current.groqModel)
        val groqEnabled = MaterialSwitch(activity).apply {
            text = "Gunakan Groq untuk merapikan teks"
            isChecked = current.useGroqCleaning
            textSize = 14f
            setPadding(2, 8, 2, 4)
        }
        content.addView(groqEnabled)
        addCaption("Gunakan tombol “Muat model Groq” di bawah untuk mengambil daftar model dari API. Model yang tampil belum tentu mempunyai kuota gratis pada akunmu.")

        addCaption("PROXY HTTP — hanya gunakan proxy yang kamu percaya")
        val proxyEnabled = MaterialSwitch(activity).apply {
            text = "Gunakan proxy manual"
            isChecked = current.proxyEnabled
            textSize = 14f
            setPadding(2, 8, 2, 4)
        }
        content.addView(proxyEnabled)
        val proxyHost = addField("Host proxy", current.proxyHost)
        val proxyPort = addField("Port proxy", current.proxyPort.toString(), number = true)
        addCaption("Proxy publik gratis tidak dicari atau diganti otomatis karena sering mati dan dapat memantau lalu lintas. Proxy ini dipakai untuk permintaan HTTP/API OkHttp; WebView bisa tetap memakai rute jaringan Android.")

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Pengaturan")
            .setView(scroll)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan", null)
            .setNeutralButton("Muat model Groq", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val keyFirecrawl = firecrawlKey.text?.toString()?.trim().orEmpty()
                val keyGroq = groqKey.text?.toString()?.trim().orEmpty()
                val model = groqModel.text?.toString()?.trim().orEmpty()
                val host = proxyHost.text?.toString()?.trim().orEmpty()
                val port = proxyPort.text?.toString()?.trim()?.toIntOrNull() ?: 0

                if (firecrawlEnabled.isChecked && keyFirecrawl.isBlank()) {
                    firecrawlKey.error = "Isi API key atau nonaktifkan Firecrawl"
                    return@setOnClickListener
                }
                if (groqEnabled.isChecked && keyGroq.isBlank()) {
                    groqKey.error = "Isi API key atau nonaktifkan Groq"
                    return@setOnClickListener
                }
                if (proxyEnabled.isChecked && host.isBlank()) {
                    proxyHost.error = "Isi host proxy"
                    return@setOnClickListener
                }
                if (proxyEnabled.isChecked && port !in 1..65535) {
                    proxyPort.error = "Port harus 1–65535"
                    return@setOnClickListener
                }

                store.save(
                    AppSettings(
                        firecrawlApiKey = keyFirecrawl,
                        useFirecrawl = firecrawlEnabled.isChecked,
                        groqApiKey = keyGroq,
                        groqModel = model.ifBlank { "openai/gpt-oss-20b" },
                        useGroqCleaning = groqEnabled.isChecked,
                        proxyEnabled = proxyEnabled.isChecked,
                        proxyHost = host,
                        proxyPort = if (port in 1..65535) port else 8080
                    )
                )
                dialog.dismiss()
                Toast.makeText(activity, "Pengaturan disimpan", Toast.LENGTH_SHORT).show()
                onSaved()
            }

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val key = groqKey.text?.toString()?.trim().orEmpty()
                if (key.isBlank()) {
                    groqKey.error = "Isi Groq API key terlebih dahulu"
                    return@setOnClickListener
                }
                Toast.makeText(activity, "Mengambil daftar model Groq…", Toast.LENGTH_SHORT).show()
                Thread {
                    val outcome = runCatching { groqCleaner.fetchModels(key) }
                    Handler(Looper.getMainLooper()).post {
                        if (activity.isFinishing || activity.isDestroyed) return@post
                        val models = outcome.getOrNull()
                        if (models.isNullOrEmpty()) {
                            Toast.makeText(
                                activity,
                                outcome.exceptionOrNull()?.message ?: "Daftar model tidak tersedia",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            MaterialAlertDialogBuilder(activity)
                                .setTitle("Pilih model Groq")
                                .setItems(models.toTypedArray()) { _, index ->
                                    groqModel.setText(models[index])
                                }
                                .setNegativeButton("Batal", null)
                                .show()
                        }
                    }
                }.start()
            }
        }
        dialog.show()
    }
}
