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

// ---------------------------------------------------------------- design kit
// Small, dependency-free set of modern building blocks: rounded cards, pill buttons with ripple,
// switches and chips, with colours for both light and dark mode.

const val PRIMARY = "#2F6BDB"
const val COACH = "#6A3FB5"
const val GOOD = "#1F8A57"

fun Context.tone(light: String, dark: String) = Color.parseColor(if (isDark()) dark else light)
fun Context.bgColor() = tone("#F3F5F9", "#0E1116")
fun Context.surfaceColor() = tone("#FFFFFF", "#181C23")
fun Context.surface2() = tone("#EAEEF5", "#252B35")
fun Context.onSurface() = tone("#141821", "#E8ECF2")
fun Context.muted() = tone("#5B6678", "#9AA5B6")
fun Context.outline() = tone("#DDE2EB", "#2C333F")
fun Context.dp(v: Number) = (v.toFloat() * resources.displayMetrics.density).toInt()

fun Context.rounded(fill: Int, radiusDp: Int, stroke: Int? = null) = android.graphics.drawable.GradientDrawable().apply {
    cornerRadius = dp(radiusDp).toFloat(); setColor(fill); stroke?.let { setStroke(dp(1), it) }
}

/** Rounded pill button with a touch ripple and no default shadow. */
fun Context.pill(label: String, fill: Int, textColor: Int, onClick: () -> Unit) = android.widget.Button(this).apply {
    text = label; isAllCaps = false; textSize = 15f; setTextColor(textColor)
    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    background = android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), rounded(fill, 24), null)
    stateListAnimator = null
    minHeight = dp(48); minimumHeight = dp(48)
    setPadding(dp(18), dp(10), dp(18), dp(10))
    setOnClickListener { onClick() }
}

fun Context.primaryButton(label: String, color: String = PRIMARY, onClick: () -> Unit) = pill(label, Color.parseColor(color), Color.WHITE, onClick)
fun Context.tonalButton(label: String, onClick: () -> Unit) = pill(label, surface2(), onSurface(), onClick)

fun Context.titleText(t: String, size: Float = 20f) = android.widget.TextView(this).apply {
    text = t; textSize = size; setTextColor(onSurface())
    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
}
fun Context.bodyText(t: String = "", size: Float = 14f) = android.widget.TextView(this).apply {
    text = t; textSize = size; setTextColor(onSurface()); setLineSpacing(0f, 1.15f)
}
fun Context.mutedText(t: String = "", size: Float = 13f) = android.widget.TextView(this).apply {
    text = t; textSize = size; setTextColor(muted()); setLineSpacing(0f, 1.15f)
}

/** Card container: rounded surface with a hairline border; children stack with 10 dp gaps. */
fun Context.card(vararg children: android.view.View) = android.widget.LinearLayout(this).apply {
    orientation = android.widget.LinearLayout.VERTICAL
    background = rounded(surfaceColor(), 20, outline())
    setPadding(dp(16), dp(16), dp(16), dp(16))
    layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
    for ((i, c) in children.withIndex()) addView(c, android.widget.LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = dp(10) })
}

/** Equal-width row of views with a gap. */
fun Context.rowOf(vararg views: android.view.View) = android.widget.LinearLayout(this).apply {
    for ((i, v) in views.withIndex()) addView(v, android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(8) })
}

fun Context.switchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = android.widget.Switch(this).apply {
    text = label; isChecked = checked; textSize = 14f; setTextColor(onSurface())
    setPadding(0, dp(4), 0, dp(4))
    setOnCheckedChangeListener { _, on -> onChange(on) }
}

/** Single-choice chips; [onSelect] gets the index. */
fun Context.chips(labels: List<String>, selected: Int, onSelect: (Int) -> Unit): android.widget.HorizontalScrollView {
    val row = android.widget.LinearLayout(this)
    val views = mutableListOf<android.widget.TextView>()
    fun paint(sel: Int) = views.forEachIndexed { i, v ->
        v.background = rounded(if (i == sel) Color.parseColor(PRIMARY) else surface2(), 16)
        v.setTextColor(if (i == sel) Color.WHITE else onSurface())
    }
    labels.forEachIndexed { i, l ->
        val v = android.widget.TextView(this).apply {
            text = l; textSize = 13f; setPadding(dp(14), dp(7), dp(14), dp(7))
            setOnClickListener { paint(i); onSelect(i) }
        }
        views += v
        row.addView(v, android.widget.LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(6) })
    }
    paint(selected)
    return android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
}

/** Rounded text field. */
fun Context.field(hint: String) = android.widget.EditText(this).apply {
    this.hint = hint; textSize = 15f; setTextColor(onSurface()); setHintTextColor(muted())
    background = rounded(surface2(), 14); setPadding(dp(14), dp(12), dp(14), dp(12))
}

/** Scrolling page with the theme background, padded clear of system bars. */
fun android.app.Activity.page(content: android.widget.LinearLayout): android.widget.ScrollView {
    window.decorView.setBackgroundColor(bgColor())
    content.setPadding(dp(16), dp(16), dp(16), dp(24))
    return android.widget.ScrollView(this).apply {
        isFillViewport = true
        addView(content)
        setOnApplyWindowInsetsListener { v, i ->
            val b = i.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom); i
        }
    }
}
