package com.arenascanner.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

/** Theme-aware secondary text colour (readable in light and dark mode). */
fun Context.secondaryText(): Int {
    val a = obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
    val c = a.getColor(0, Color.GRAY)
    a.recycle()
    return c
}

fun Context.isDark() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/** Rarity colours; common is near-black on light, light grey on dark. */
fun Context.rarityColor(r: String): Int = Color.parseColor(when (r) {
    "M" -> "#E2643A"; "R" -> "#C9A13A"; "U" -> "#8FA3B8"; "C" -> if (isDark()) "#C9CED6" else "#3B3F46"; else -> "#8A93A3"
})
