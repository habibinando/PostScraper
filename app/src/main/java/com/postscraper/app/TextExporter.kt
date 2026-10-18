package com.postscraper.app

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.OutputStreamWriter

/** Text assembly and Android Downloads export, isolated from the UI and crawler loop. */
internal class TextExporter(private val activity: Activity) {

    fun buildFinalText(posts: List<PostData>): String {
        val out = StringBuilder()
        for (post in posts) {
            val title = post.title.trim()
            val lines = post.lines.map { it.trim() }.filter { it.isNotBlank() }
            if (title.isBlank() || lines.isEmpty()) continue
            out.append(title).append('\n')
            for (line in lines) out.append(line).append('\n')
            out.append('\n')
        }
        return out.toString()
    }

    fun saveToDownload(fileName: String, content: String) {
        if (content.isBlank()) throw Exception("Konten kosong; tidak ada file yang dibuat.")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = activity.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("Gagal membuat file di Download")
            var saved = false
            try {
                activity.contentResolver.openOutputStream(uri)?.use { output ->
                    OutputStreamWriter(output, Charsets.UTF_8).use { it.write(content) }
                } ?: throw Exception("Gagal membuka file output")
                saved = true
            } finally {
                if (!saved) {
                    try { activity.contentResolver.delete(uri, null, null) } catch (_: Exception) { }
                }
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                    activity, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    activity, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 101
                )
                throw Exception("Izin penyimpanan belum diberikan. Berikan izin lalu jalankan ulang.")
            }
            val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!directory.exists() && !directory.mkdirs()) {
                throw Exception("Folder Download tidak bisa dibuat")
            }
            File(directory, fileName).writeText(content, Charsets.UTF_8)
        }
    }
}
