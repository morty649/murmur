package com.morty649.murmur

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

sealed interface ModelState {
    data object NotDownloaded : ModelState
    data class Downloading(val doneBytes: Long, val totalBytes: Long) : ModelState {
        val fraction: Float get() = if (totalBytes > 0) doneBytes.toFloat() / totalBytes else 0f
    }
    /** All files arrived; checksums not yet verified. */
    data object Downloaded : ModelState
    data object Verifying : ModelState
    data object Ready : ModelState
    data class Failed(val reason: String) : ModelState
}

/**
 * Downloads models with Android's DownloadManager (survives the app being closed,
 * resumes on flaky networks), then verifies sizes + SHA-256 before marking them ready.
 */
class ModelManager(context: Context) {
    private val ctx = context.applicationContext
    private val dm = ctx.getSystemService(DownloadManager::class.java)

    fun dir(model: AsrModel) = File(ctx.getExternalFilesDir(null), "models/${model.id}")

    fun isReady(model: AsrModel): Boolean {
        val d = dir(model)
        return File(d, READY_MARKER).exists() && model.files.all { File(d, it.name).length() == it.size }
    }

    fun poll(model: AsrModel): ModelState {
        if (isReady(model)) return ModelState.Ready
        if (model.id in verifying) return ModelState.Verifying
        failures[model.id]?.let { return ModelState.Failed(it) }

        val ids = Prefs.downloadIds(ctx, model.id)
        if (ids.isEmpty()) return ModelState.NotDownloaded

        var done = 0L
        var successful = 0
        var rows = 0
        var failed = false
        dm.query(DownloadManager.Query().setFilterById(*ids))?.use { c ->
            val statusCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val bytesCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            while (c.moveToNext()) {
                rows++
                when (c.getInt(statusCol)) {
                    DownloadManager.STATUS_SUCCESSFUL -> successful++
                    DownloadManager.STATUS_FAILED -> failed = true
                }
                done += c.getLong(bytesCol).coerceAtLeast(0)
            }
        }
        // A missing row means the download was cancelled from the notification.
        if (failed || rows < ids.size) {
            cancel(model)
            failures[model.id] = "Download failed. Check your connection and retry."
            return ModelState.Failed(failures[model.id]!!)
        }
        if (successful == ids.size) return ModelState.Downloaded
        return ModelState.Downloading(done, model.totalBytes)
    }

    fun download(model: AsrModel) {
        failures.remove(model.id)
        val d = dir(model)
        d.deleteRecursively()
        d.mkdirs()
        val ids = model.files.map { f ->
            val req = DownloadManager.Request(Uri.parse(model.url(f)))
                .setTitle("Murmur · ${model.title}")
                .setDescription(f.name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(ctx, null, "models/${model.id}/${f.name}")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
            dm.enqueue(req)
        }.toLongArray()
        Prefs.setDownloadIds(ctx, model.id, ids)
    }

    fun cancel(model: AsrModel) {
        val ids = Prefs.downloadIds(ctx, model.id)
        if (ids.isNotEmpty()) dm.remove(*ids)
        Prefs.setDownloadIds(ctx, model.id, LongArray(0))
        dir(model).deleteRecursively()
    }

    fun delete(model: AsrModel) {
        cancel(model)
        failures.remove(model.id)
    }

    fun clearFailure(model: AsrModel) {
        failures.remove(model.id)
    }

    suspend fun verify(model: AsrModel) {
        if (!verifying.add(model.id)) return
        try {
            val ok = withContext(Dispatchers.IO) {
                val d = dir(model)
                model.files.all { f ->
                    val file = File(d, f.name)
                    file.length() == f.size && (f.sha256 == null || sha256(file) == f.sha256)
                }
            }
            // Forget the DownloadManager rows without deleting the files.
            Prefs.setDownloadIds(ctx, model.id, LongArray(0))
            if (ok) {
                File(dir(model), READY_MARKER).writeText("ok")
            } else {
                dir(model).deleteRecursively()
                failures[model.id] = "Files were corrupted in transit. Please retry."
            }
        } finally {
            verifying.remove(model.id)
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 20).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val READY_MARKER = ".ready"
        private val verifying: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val failures = ConcurrentHashMap<String, String>()
    }
}
