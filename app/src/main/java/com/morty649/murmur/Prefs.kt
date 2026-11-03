package com.morty649.murmur

import android.content.Context

enum class CleanupMode { OFF, LIGHT }

/** How the idle bubble looks. Listening and transcribing always use the same capsule. */
enum class BubbleStyle(val label: String) { GLASS("Glass dot"), EDGE("Edge tab"), ICON("App icon") }

object Prefs {
    const val KEY_STYLE = "bubble_style"
    const val KEY_TRANSPARENCY = "bubble_transparency"
    const val MAX_TRANSPARENCY = 85

    fun register(ctx: Context, l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        sp(ctx).registerOnSharedPreferenceChangeListener(l)

    fun unregister(ctx: Context, l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        sp(ctx).unregisterOnSharedPreferenceChangeListener(l)

    private fun sp(ctx: Context) = ctx.getSharedPreferences("murmur", Context.MODE_PRIVATE)

    fun selectedModel(ctx: Context): AsrModel =
        ModelCatalog.byId(sp(ctx).getString("model", ModelCatalog.DEFAULT_ID)!!)

    fun setSelectedModel(ctx: Context, id: String) = sp(ctx).edit().putString("model", id).apply()

    fun cleanup(ctx: Context): CleanupMode =
        runCatching { CleanupMode.valueOf(sp(ctx).getString("cleanup", "LIGHT")!!) }
            .getOrDefault(CleanupMode.LIGHT)

    fun setCleanup(ctx: Context, mode: CleanupMode) =
        sp(ctx).edit().putString("cleanup", mode.name).apply()

    fun bubbleStyle(ctx: Context): BubbleStyle =
        runCatching { BubbleStyle.valueOf(sp(ctx).getString(KEY_STYLE, "GLASS")!!) }.getOrDefault(BubbleStyle.GLASS)

    fun setBubbleStyle(ctx: Context, style: BubbleStyle) = sp(ctx).edit().putString(KEY_STYLE, style.name).apply()

    /** 0 = solid, 85 = barely there. Applies only while the bubble is idle. */
    fun transparency(ctx: Context): Int = sp(ctx).getInt(KEY_TRANSPARENCY, 40)

    fun setTransparency(ctx: Context, percent: Int) =
        sp(ctx).edit().putInt(KEY_TRANSPARENCY, percent.coerceIn(0, MAX_TRANSPARENCY)).apply()

    /** Which screen edge the bubble docks to, and how far down. Migrates the old free x position. */
    fun bubbleDock(ctx: Context): Pair<Boolean, Int> {
        val p = sp(ctx)
        val right = if (p.contains("bubble_right")) p.getBoolean("bubble_right", true) else p.getInt("bubble_x", 1) > 0
        return right to p.getInt("bubble_y", 400)
    }

    fun setBubbleDock(ctx: Context, right: Boolean, y: Int) =
        sp(ctx).edit().putBoolean("bubble_right", right).putInt("bubble_y", y).apply()

    /** "Remind me later" hides one version's prompt until this time. */
    fun updateSnoozed(ctx: Context, versionCode: Long): Boolean =
        sp(ctx).getLong("update_snooze_code", -1) == versionCode &&
            System.currentTimeMillis() < sp(ctx).getLong("update_snooze_until", 0)

    fun snoozeUpdate(ctx: Context, versionCode: Long, millis: Long) = sp(ctx).edit()
        .putLong("update_snooze_code", versionCode)
        .putLong("update_snooze_until", System.currentTimeMillis() + millis)
        .apply()

    fun downloadIds(ctx: Context, modelId: String): LongArray =
        sp(ctx).getString("dl_$modelId", "")!!.split(',').mapNotNull { it.toLongOrNull() }.toLongArray()

    fun setDownloadIds(ctx: Context, modelId: String, ids: LongArray) =
        sp(ctx).edit().putString("dl_$modelId", ids.joinToString(",")).apply()
}
