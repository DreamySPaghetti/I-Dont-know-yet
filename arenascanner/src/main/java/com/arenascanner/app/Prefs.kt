package com.arenascanner.app

import android.content.Context

/** App settings (file name kept from earlier versions so saved settings and decks survive updates). */
object Prefs {
    fun get(ctx: Context) = ctx.getSharedPreferences("coach", Context.MODE_PRIVATE)

    /** Auto-scroll speed: swipe length (fraction of screen width), drag time and hold before lifting. */
    /**
     * Auto-scroll drags the collection at a steady pace, scanning while it moves.
     * [perSec] is how much of the screen width scrolls past per second.
     */
    data class Speed(val label: String, val perSec: Float) {
        /** Duration of one long drag across [span] of the screen. */
        fun dragMs(span: Float) = (span / perSec * 1000).toLong()
    }
    val SPEEDS = listOf(Speed("Careful", 0.16f), Speed("Normal", 0.24f), Speed("Fast", 0.34f), Speed("Turbo", 0.46f))
    fun speed(ctx: Context) = SPEEDS[get(ctx).getInt("scroll_speed", 1).coerceIn(0, SPEEDS.size - 1)]
}
