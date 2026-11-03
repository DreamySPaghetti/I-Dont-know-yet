package com.arenascanner.app

import android.content.Context

/** App settings (file name kept from earlier versions so saved settings and decks survive updates). */
object Prefs {
    fun get(ctx: Context) = ctx.getSharedPreferences("coach", Context.MODE_PRIVATE)

    /** Auto-scroll speed: swipe length (fraction of screen width), drag time and hold before lifting. */
    data class Speed(val label: String, val step: Float, val moveMs: Long, val holdMs: Long)
    val SPEEDS = listOf(
        Speed("Careful", 0.26f, 800, 300),
        Speed("Normal", 0.36f, 600, 220),
        Speed("Fast", 0.46f, 450, 180),
    )
    fun speed(ctx: Context) = SPEEDS[get(ctx).getInt("scroll_speed", 1).coerceIn(0, SPEEDS.size - 1)]
}
