package com.postscraper.app

import android.text.InputType
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
import java.io.File

/** Settings editor opened from the top hamburger button. */
internal class SettingsDialog(
    private val activity: AppCompatActivity,
    private val store: AppSettingsStore,
    private val onPickModel: () -> Unit
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

        addCaption("MODEL AI LOKAL — inferensi berjalan di HP, tanpa kuota API")
        val modelFile = File(current.localModelPath)
        val modelInfo = TextView(activity).apply {
            text = if (current.localModelPath.isNotBlank() && modelFile.isFile) {
                "Model terpasang: " + modelFile.name + "\nLokasi aplikasi: " + modelFile.parent
            } else {
                "Belum ada model GGUF. Pilih file Qwen2.5-3B-Instruct-Abliterated.Q4_K_M.gguf dari penyimpanan HP."
            }
            textSize = 13f
            setPadding(4, 6, 4, 8)
        }
        content.addView(modelInfo)

        val localModelEnabled = MaterialSwitch(activity).apply {
            text = "Gunakan Qwen sebagai fallback ekstraksi artikel"
            isChecked = current.useLocalModelCleaning
            textSize = 14f
            setPadding(2, 8, 2, 4)
        }
        content.addView(localModelEnabled)
        addCaption("Qwen hanya dijalankan jika HTML, AMP, dan WebView tidak mendapatkan isi artikel yang cukup. Impor menyalin model ke ruang aplikasi; file asli tidak dihapus. Model memakai CPU, RAM, dan baterai.")

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
            .setNeutralButton("Pilih file GGUF", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val host = proxyHost.text?.toString()?.trim().orEmpty()
                val port = proxyPort.text?.toString()?.trim()?.toIntOrNull() ?: 0
                val modelPath = store.load().localModelPath

                if (localModelEnabled.isChecked && (modelPath.isBlank() || !File(modelPath).isFile)) {
                    Toast.makeText(activity, "Pilih file GGUF terlebih dahulu", Toast.LENGTH_LONG).show()
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
                    store.load().copy(
                        useLocalModelCleaning = localModelEnabled.isChecked,
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
                dialog.dismiss()
                onPickModel()
            }
        }
        dialog.show()
    }
}
