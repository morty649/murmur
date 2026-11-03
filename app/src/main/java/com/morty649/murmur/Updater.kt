package com.morty649.murmur

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** One build on GitHub, as described by the `version.json` that CI publishes next to the APK. */
data class Release(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val size: Long,
)

/**
 * Self-update from the public GitHub release tagged `latest`:
 * check `version.json` → download the APK → check its SHA-256 → hand it to Android's PackageInstaller.
 * Android also checks that the new APK has the same signing key, so only our builds can install.
 */
object Updater {
    private const val MANIFEST_URL = "https://github.com/morty649/murmur/releases/download/latest/version.json"

    fun currentVersionCode(ctx: Context): Long =
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))

    fun currentVersionName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** The latest release if it's newer than this install, else null. Throws on network errors. */
    suspend fun check(ctx: Context): Release? = withContext(Dispatchers.IO) {
        val json = JSONObject(get(MANIFEST_URL).use { it.readBytes().toString(Charsets.UTF_8) })
        val r = Release(
            versionCode = json.getLong("versionCode"),
            versionName = json.getString("versionName"),
            apkUrl = json.getString("apk"),
            sha256 = json.getString("sha256").lowercase(),
            size = json.getLong("size"),
        )
        if (r.versionCode > currentVersionCode(ctx)) r else null
    }

    /** Downloads the APK into the app cache and verifies it. [onProgress] gets 0..1. */
    suspend fun download(ctx: Context, r: Release, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "Murmur-${r.versionName}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        get(r.apkUrl).use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    done += n
                    onProgress((done.toFloat() / r.size).coerceIn(0f, 1f))
                }
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (file.length() != r.size || hex != r.sha256) {
            file.delete()
            error("The download didn't match the published checksum. Try again.")
        }
        file
    }

    /** Can Murmur install APKs? (Android's "Install unknown apps" switch for this app.) */
    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    /** Starts the install. Android shows its own confirm screen unless it already trusts Murmur as the updater. */
    suspend fun install(ctx: Context, apk: File) = withContext(Dispatchers.IO) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            // Android 12+: once Murmur installed itself, later updates can skip the confirm screen.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("murmur.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(ctx, id, Intent(ctx, UpdateReceiver::class.java), flags)
            session.commit(callback.intentSender)
        }
    }

    private fun get(url: String) = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Murmur-Android")
        if (responseCode !in 200..299) error("HTTP $responseCode")
        inputStream
    }
}

/** Receives the PackageInstaller result: shows Android's confirm screen, or reports a failure. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)?.let {
                    ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // Android restarts nothing; the user reopens Murmur.
            else -> Toast.makeText(
                ctx,
                "Update failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
