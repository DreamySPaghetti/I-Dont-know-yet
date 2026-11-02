package com.pixelporters.game

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlin.math.min
import kotlin.math.sin
import androidx.compose.ui.graphics.lerp as mix

val BG = Color(0xFF1C2030)
val PANEL = Color(0xFF2A3045)
val MUTED = Color(0xFF8A93AD)
val ACCENT = Color(0xFFFFC94D)
private val GROUND = Color(0xFF323A52)
private val GROUND_EDGE = Color(0xFF242A3D)
private val ROAD = Color(0xFF5B5146)
private val ROAD_TOP = Color(0xFF6E6356)
private val BUG = Color(0xFF2E3550)
private val BUG_HI = Color(0xFF4A5579)
private val NEST = Color(0xFF7A5A43)

class Scene(g: Game, size: Size) {
    private val top = 1.1f
    val k = min(size.width / (g.ww + 0.3f), size.height / (g.worldH + top + 0.2f))
    val ox = (size.width - g.ww * k) / 2
    val oy = top * k + (size.height - (g.worldH + top + 0.2f) * k) / 2
    fun p(o: Offset) = Offset(ox + o.x * k, oy + o.y * k)
    fun world(px: Offset) = Offset((px.x - ox) / k, (px.y - oy) / k)
}

private fun light(c: Color, a: Float) = mix(c, Color.White, a)
private fun dark(c: Color, a: Float) = mix(c, Color.Black, a)

private fun DrawScope.label(tm: TextMeasurer, s: String, center: Offset, color: Color, size: TextUnit) {
    val r = tm.measure(s, TextStyle(color = color, fontSize = size, fontWeight = FontWeight.Bold))
    drawText(r, topLeft = center - Offset(r.size.width / 2f, r.size.height / 2f))
}

private fun ink(c: Color) = if (c.luminance() > 0.55f) Color(0xFF1C2030) else Color.White

private fun poly(vararg pts: Offset) = Path().apply {
    moveTo(pts[0].x, pts[0].y)
    for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
    close()
}

fun DrawScope.cube3d(c: Color, tl: Offset, s: Float, d: Float, alpha: Float = 1f) {
    val x = tl.x
    val y = tl.y
    drawPath(poly(Offset(x, y), Offset(x + d, y - d), Offset(x + s + d, y - d), Offset(x + s, y)), light(c, 0.3f), alpha)
    drawPath(poly(Offset(x + s, y), Offset(x + s + d, y - d), Offset(x + s + d, y + s - d), Offset(x + s, y + s)), dark(c, 0.28f), alpha)
    drawRect(c, Offset(x, y), Size(s, s), alpha)
    drawRect(Color.White.copy(alpha = 0.18f * alpha), Offset(x, y), Size(s, s * 0.12f))
    drawRect(Color.Black.copy(alpha = 0.12f * alpha), Offset(x, y + s * 0.88f), Size(s, s * 0.12f))
}

private fun DrawScope.slab(center: Offset, wdt: Float, hgt: Float, d: Float, top: Color, front: Color) {
    val x = center.x - wdt / 2
    val y = center.y - hgt / 2
    drawPath(poly(Offset(x, y), Offset(x + d, y - d), Offset(x + wdt + d, y - d), Offset(x + wdt, y)), top)
    drawPath(poly(Offset(x + wdt, y), Offset(x + wdt + d, y - d), Offset(x + wdt + d, y + hgt - d), Offset(x + wdt, y + hgt)), dark(front, 0.25f))
    drawRect(front, Offset(x, y), Size(wdt, hgt))
}

private fun DrawScope.bug(px: Offset, k: Float, dir: Offset, walked: Float, carrying: Int?, now: Long) {
    val r = 0.3f * k
    val moving = walked
    val bob = sin(moving * 9f) * 0.03f * k
    val body = px + Offset(0f, -r * 0.35f + bob)
    drawOval(Color.Black.copy(alpha = 0.28f), px + Offset(-r * 1.15f, r * 0.35f), Size(r * 2.3f, r * 0.75f))
    for (side in listOf(-1f, 1f)) {
        for (j in 0..2) {
            val phase = sin(moving * 14f + j * 2.1f + if (side > 0) 1.6f else 0f)
            val base = body + Offset(side * r * 0.55f, (j - 1) * r * 0.45f)
            val foot = base + Offset(side * r * 0.65f, (j - 1) * r * 0.3f + phase * r * 0.18f)
            drawLine(BUG, base, foot, strokeWidth = r * 0.16f, cap = StrokeCap.Round)
        }
    }
    drawOval(BUG, body - Offset(r, r * 0.8f), Size(r * 2f, r * 1.6f))
    drawOval(BUG_HI, body - Offset(r * 0.7f, r * 0.65f), Size(r * 0.9f, r * 0.5f))
    val face = body + Offset(dir.x * r * 0.75f, r * 0.15f + dir.y * r * 0.25f)
    val blink = (now / 2600 + (px.x / 7).toLong()) % 7 == 0L && (now % 2600) < 140
    for (e in listOf(-1f, 1f)) {
        val eye = face + Offset(e * r * 0.32f, -r * 0.05f)
        if (blink) {
            drawLine(Color.White, eye - Offset(r * 0.14f, 0f), eye + Offset(r * 0.14f, 0f), strokeWidth = r * 0.08f)
        } else {
            drawCircle(Color.White, r * 0.2f, eye)
            drawCircle(BG, r * 0.1f, eye + dir * (r * 0.07f))
        }
    }
    drawLine(BUG, body + Offset(-r * 0.25f, -r * 0.75f), body + Offset(-r * 0.45f, -r * 1.15f), strokeWidth = r * 0.1f, cap = StrokeCap.Round)
    drawLine(BUG, body + Offset(r * 0.25f, -r * 0.75f), body + Offset(r * 0.45f, -r * 1.15f), strokeWidth = r * 0.1f, cap = StrokeCap.Round)
    if (carrying != null) {
        val s = 0.5f * k
        cube3d(COLORS[carrying], body + Offset(-s / 2 - s * 0.1f, -r * 0.6f - s), s, s * 0.25f)
    }
}

fun DrawScope.drawGame(g: Game, sc: Scene, tm: TextMeasurer, dp: Float) {
    val k = sc.k
    val barW = size.width - 32 * dp
    drawRoundRect(PANEL, Offset(16 * dp, 4 * dp), Size(barW, 6 * dp), CornerRadius(3 * dp))
    drawRoundRect(ACCENT, Offset(16 * dp, 4 * dp), Size(barW * g.collected / g.total, 6 * dp), CornerRadius(3 * dp))

    val gTl = sc.p(Offset(0f, -0.7f))
    val gBr = sc.p(Offset(g.ww, g.worldH))
    drawRoundRect(GROUND_EDGE, gTl + Offset(0f, 0.25f * k), Size(gBr.x - gTl.x, gBr.y - gTl.y), CornerRadius(0.5f * k))
    drawRoundRect(GROUND, gTl, Size(gBr.x - gTl.x, gBr.y - gTl.y), CornerRadius(0.5f * k))

    for (i in g.color.indices) {
        if (g.color[i] < 0) continue
        val c = g.cellPos(i)
        drawRect(Color.Black.copy(alpha = 0.18f), sc.p(c - Offset(0.46f, 0.46f)), Size(0.92f * k, 0.92f * k))
    }

    val roadL = sc.p(Offset(0.15f, g.roadY - 0.3f))
    val roadR = sc.p(Offset(g.ww - 0.15f, g.roadY + 0.3f))
    drawRoundRect(dark(ROAD, 0.3f), roadL + Offset(0f, 0.08f * k), Size(roadR.x - roadL.x, roadR.y - roadL.y), CornerRadius(0.3f * k))
    drawRoundRect(ROAD, roadL, Size(roadR.x - roadL.x, roadR.y - roadL.y), CornerRadius(0.3f * k))
    drawRoundRect(ROAD_TOP, roadL, Size(roadR.x - roadL.x, 0.12f * k), CornerRadius(0.3f * k))
    var dx = 0.9f
    while (dx < g.ww - 0.6f) {
        drawLine(light(ROAD, 0.25f), sc.p(Offset(dx, g.roadY)), sc.p(Offset(dx + 0.3f, g.roadY)), strokeWidth = 0.05f * k)
        dx += 0.7f
    }
    for (x in 0 until g.w) {
        val i = g.nextInColumn(x)
        if (i >= 0) {
            val a = sc.p(Offset(g.colX(x), g.roadY - 0.3f))
            drawCircle(light(ROAD, 0.35f), 0.07f * k, a)
        }
    }

    val n = sc.p(g.nest)
    drawOval(dark(NEST, 0.35f), n + Offset(-0.5f * k, -0.1f * k), Size(1.0f * k, 0.45f * k))
    drawArc(NEST, 180f, 180f, true, n + Offset(-0.45f * k, -0.45f * k), Size(0.9f * k, 0.9f * k))
    drawArc(light(NEST, 0.2f), 200f, 60f, true, n + Offset(-0.4f * k, -0.4f * k), Size(0.8f * k, 0.8f * k))
    drawArc(BG, 180f, 180f, true, n + Offset(-0.18f * k, -0.2f * k), Size(0.36f * k, 0.4f * k))

    label(tm, "DROP-OFF", sc.p(Offset(g.ww / 2, g.dockY - 0.78f)), MUTED, 9.sp)
    for (d in 0 until DOCKS) {
        val c = sc.p(g.dockPos(d))
        val hole = g.docks[d]
        val pw = 1.5f * k
        val ph = 0.85f * k
        if (hole == null) {
            drawRoundRect(GROUND_EDGE, c - Offset(pw / 2, ph / 2), Size(pw, ph), CornerRadius(0.2f * k))
            label(tm, "✓", c, MUTED.copy(alpha = 0.5f), 16.sp)
            continue
        }
        val col = COLORS[hole.color]
        val pop = ((g.now - hole.since) / 250f).coerceIn(0f, 1f)
        val sc2 = 0.7f + 0.3f * pop
        val w2 = pw * sc2
        val h2 = ph * sc2
        slab(c, w2, h2, 0.18f * k, light(col, 0.35f), col)
        drawRoundRect(dark(col, 0.65f), c - Offset(w2 * 0.38f, h2 * 0.32f), Size(w2 * 0.76f, h2 * 0.55f), CornerRadius(0.12f * k))
        val ms = 0.2f * k
        val shown = min(hole.delivered, 6)
        for (m in 0 until shown) {
            val mx = c.x - w2 * 0.33f + (m % 3) * ms * 1.15f
            val my = c.y - h2 * 0.18f + (m / 3) * ms * 0.9f
            cube3d(col, Offset(mx, my), ms, ms * 0.25f)
        }
        val done = hole.doneAt > 0
        label(tm, if (done) "✓" else "${hole.delivered}/${hole.cap}", c + Offset(w2 * 0.26f, h2 * 0.05f), ink(col), if (done) 18.sp else 13.sp)
    }
    if (g.queue.isNotEmpty()) {
        val y = g.dockY + 0.75f
        label(tm, "NEXT", sc.p(Offset(0.55f, y)), MUTED, 9.sp)
        g.queue.take(7).forEachIndexed { j, hq ->
            val s = 0.32f * k
            cube3d(COLORS[hq.color], sc.p(Offset(1.15f + j * 0.48f, y - 0.16f)), s, s * 0.25f, 0.85f)
        }
    }

    val full = g.now - g.refusedAt < 700
    label(tm, if (full) "STORAGE FULL!" else "STORAGE", sc.p(Offset(g.ww / 2, g.slotY - 0.72f)), if (full) Color(0xFFFF6B6B) else MUTED, 9.sp)
    for (s in 0 until g.slotCount) {
        val c = sc.p(g.slotPos(s))
        slab(c + Offset(0f, 0.18f * k), 1.0f * k, 0.32f * k, 0.18f * k, light(PANEL, 0.12f), PANEL)
        val st = g.slots[s] ?: continue
        val cs = 0.62f * k
        if (st.arrived) {
            val pop = ((g.now - st.since) / 200f).coerceIn(0f, 1f)
            cube3d(COLORS[st.color], c + Offset(-cs / 2, -cs + 0.05f * k - (1 - pop) * 0.3f * k), cs, cs * 0.25f)
        } else {
            drawRect(COLORS[st.color].copy(alpha = 0.5f), c + Offset(-cs / 2, -cs + 0.05f * k), Size(cs, cs), style = Stroke(1.5f * dp))
        }
    }

    val bob = (sin(g.now / 260.0) * 0.035 * k).toFloat()
    val cs = 0.9f * k
    val d = 0.22f * k
    for (y in g.h - 1 downTo 0) {
        for (x in 0 until g.w) {
            val i = y * g.w + x
            if (!g.present[i]) continue
            val front = g.nextInColumn(x) == i
            val tl = sc.p(g.cellPos(i) - Offset(0.45f, 0.45f)) + Offset(0f, if (front) bob else 0f)
            cube3d(COLORS[g.color[i]], tl, cs, d)
            if (front) drawRect(Color.White.copy(alpha = 0.8f), tl, Size(cs, cs), style = Stroke(2f * dp))
        }
    }

    g.carriers.sortedBy { it.pos.y }.forEach { c ->
        bug(sc.p(c.pos), k, c.dir, c.walked, if (c.carrying) c.color else null, g.now)
    }
}
