package com.mojahid.watchcompanion

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ModelDownloadStatus(
    val label: String,
    val progress: Int = 0,
    val ready: Boolean = false,
    val downloading: Boolean = false
)

/**
 * Free, genuinely on-device Qwen3 model. No cloud provider API key.
 * Model file is intentionally NOT bundled into the APK; it is downloaded only
 * after a user tap. We verify the publisher's SHA-256 before passing it to LiteRT.
 */
class OfflineModelStore(private val context: Context) {
    companion object {
        const val NAME = "momo-qwen3-nothink.litertlm"
        const val URL = "https://huggingface.co/litert-community/Qwen3-0.6B-int4/resolve/main/qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm"
        const val SHA256 = "2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139"
        private const val MIN_BYTES = 320L * 1024 * 1024
    }
    private val prefs = context.getSharedPreferences("momo_offline_model", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val folder get() = context.getExternalFilesDir("models")
        ?: File(context.filesDir, "models").also { it.mkdirs() }
    val modelFile: File get() = File(folder, NAME)

    fun verified(): Boolean = prefs.getBoolean("verified", false) &&
        modelFile.exists() && modelFile.length() >= MIN_BYTES

    fun requestDownload(): String {
        if (verified()) return "Offline Momo is installed."
        if (isDownloading()) return "Model download is already running."
        val file = modelFile
        file.parentFile?.mkdirs()
        if (file.exists()) file.delete()
        prefs.edit().putBoolean("verified", false).apply()
        val request = DownloadManager.Request(Uri.parse(URL))
            .setTitle("Momo offline AI brain")
            .setDescription("One-time 347 MB download. Qwen3-0.6B model, no API key.")
            .setMimeType("application/octet-stream")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationUri(Uri.fromFile(file))
            .addRequestHeader("User-Agent", "MomoCompanion/6.0")
        return try {
            val id = manager.enqueue(request)
            prefs.edit().putLong("download_id", id).apply()
            "Started downloading Momo's offline brain (~347 MB). Wi-Fi is recommended."
        } catch (e: Exception) {
            "Couldn't start download: " + (e.message ?: "check internet and storage")
        }
    }

    private fun isDownloading(): Boolean {
        val id = prefs.getLong("download_id", -1)
        if (id < 0) return false
        val query = DownloadManager.Query().setFilterById(id)
        return runCatching {
            manager.query(query)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use false
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                status == DownloadManager.STATUS_PENDING ||
                    status == DownloadManager.STATUS_RUNNING ||
                    status == DownloadManager.STATUS_PAUSED
            } ?: false
        }.getOrDefault(false)
    }

    suspend fun status(): ModelDownloadStatus = withContext(Dispatchers.IO) {
        if (verified()) {
            return@withContext ModelDownloadStatus("Offline Momo is ready. No API key needed.", 100, true)
        }
        val id = prefs.getLong("download_id", -1L)
        if (id == -1L) {
            return@withContext ModelDownloadStatus("Free offline AI not installed. Download once: ~347 MB.")
        }
        runCatching {
            manager.query(DownloadManager.Query().setFilterById(id))?.use { cur ->
                if (!cur.moveToFirst()) return@use ModelDownloadStatus(
                    "Download not found. Tap Download to retry.")
                val status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val bytes = cur.getLong(cur.getColumnIndexOrThrow(
                    DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = cur.getLong(cur.getColumnIndexOrThrow(
                    DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val pct = if (total > 0L) ((bytes * 100L) / total).toInt().coerceIn(0, 99) else 0
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        if (modelFile.length() < MIN_BYTES) {
                            modelFile.delete()
                            prefs.edit().remove("download_id").apply()
                            ModelDownloadStatus("Incomplete model file. Tap Download to retry.")
                        } else {
                            val hash = sha256(modelFile)
                            if (hash == SHA256) {
                                prefs.edit().putBoolean("verified", true).remove("download_id").apply()
                                ModelDownloadStatus("Offline AI installed and verified. Ready!", 100, true)
                            } else {
                                modelFile.delete()
                                prefs.edit().remove("download_id").apply()
                                ModelDownloadStatus("Model integrity check failed. Tap Download again.")
                            }
                        }
                    }
                    DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> {
                        ModelDownloadStatus(
                            "Downloading offline AI: " + pct + "% (" +
                                (bytes / (1024 * 1024)) + " MB received)", pct, false, true
                        )
                    }
                    DownloadManager.STATUS_PAUSED -> {
                        ModelDownloadStatus("Download paused. Check Wi-Fi / data.", pct, false, true)
                    }
                    else -> {
                        prefs.edit().remove("download_id").apply()
                        ModelDownloadStatus("Download failed. Tap Download to retry.")
                    }
                }
            } ?: ModelDownloadStatus("Can't read download status. Retry from the app.")
        }.getOrElse {
            ModelDownloadStatus("Model download check failed. Please try again.")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // Only delete the app-owned downloaded model, never anything belonging to the watch.
    fun removeModel(): Boolean {
        val id = prefs.getLong("download_id", -1)
        if (id > 0) runCatching { manager.remove(id) }
        prefs.edit().clear().apply()
        return !modelFile.exists() || modelFile.delete()
    }
}
