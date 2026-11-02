package com.morty649.murmur

import android.content.Context

enum class CleanupMode { OFF, LIGHT }

object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("murmur", Context.MODE_PRIVATE)

    fun selectedModel(ctx: Context): AsrModel =
        ModelCatalog.byId(sp(ctx).getString("model", ModelCatalog.DEFAULT_ID)!!)

    fun setSelectedModel(ctx: Context, id: String) = sp(ctx).edit().putString("model", id).apply()

    fun cleanup(ctx: Context): CleanupMode =
        runCatching { CleanupMode.valueOf(sp(ctx).getString("cleanup", "LIGHT")!!) }
            .getOrDefault(CleanupMode.LIGHT)

    fun setCleanup(ctx: Context, mode: CleanupMode) =
        sp(ctx).edit().putString("cleanup", mode.name).apply()

    fun bubblePos(ctx: Context): Pair<Int, Int> =
        sp(ctx).getInt("bubble_x", 0) to sp(ctx).getInt("bubble_y", 400)

    fun setBubblePos(ctx: Context, x: Int, y: Int) =
        sp(ctx).edit().putInt("bubble_x", x).putInt("bubble_y", y).apply()

    fun downloadIds(ctx: Context, modelId: String): LongArray =
        sp(ctx).getString("dl_$modelId", "")!!.split(',').mapNotNull { it.toLongOrNull() }.toLongArray()

    fun setDownloadIds(ctx: Context, modelId: String, ids: LongArray) =
        sp(ctx).edit().putString("dl_$modelId", ids.joinToString(",")).apply()
}
