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
private val GROUND = Color(0xFF3A4A3A)
private val GROUND_EDGE = Color(0xFF283428)
private val SOIL = Color(0xFF5E4B3C)
private val TRAIL = Color(0xFF7A6650)
private val REVEAL_BG = Color(0xFFEDE6D6)
private val ANT = Color(0xFF6B3A2A)
private val ANT_HI = Color(0xFF9A5B42)
private val NEST = Color(0xFF8A6A4E)
private val RED = Color(0xFFFF6B6B)

class Scene(g: Game, size: Size) {
    private val top = 1.2f
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

private fun poly(a: Offset, b: Offset, c: Offset, d: Offset) = Path().apply {
    moveTo(a.x, a.y)
    lineTo(b.x, b.y)
    lineTo(c.x, c.y)
    lineTo(d.x, d.y)
    close()
}

/** A 2.5D box: front face from (x, bottom - hgt) to (x + s, bottom), top and right faces offset by depth d. */
fun DrawScope.prism(c: Color, x: Float, bottom: Float, s: Float, hgt: Float, d: Float, layers: Int = 1, alpha: Float = 1f) {
    val top = bottom - hgt
    drawPath(poly(Offset(x, top), Offset(x + d, top - d), Offset(x + s + d, top - d), Offset(x + s, top)), light(c, 0.32f), alpha)
    drawPath(poly(Offset(x + s, top), Offset(x + s + d, top - d), Offset(x + s + d, bottom - d), Offset(x + s, bottom)), dark(c, 0.3f), alpha)
    drawRect(c, Offset(x, top), Size(s, hgt), alpha)
    drawRect(Color.White.copy(alpha = 0.16f * alpha), Offset(x, top), Size(s, hgt * 0.1f / layers.coerceAtLeast(1) + 1f))
    val line = dark(c, 0.35f).copy(alpha = 0.7f * alpha)
    for (j in 1 until layers) {
        val y = bottom - hgt * j / layers
        drawLine(line, Offset(x, y), Offset(x + s, y), strokeWidth = 1.5f)
        drawLine(line, Offset(x + s, y), Offset(x + s + d, y - d), strokeWidth = 1.5f)
    }
}

private fun DrawScope.slab(center: Offset, wdt: Float, hgt: Float, d: Float, front: Color) {
    prism(front, center.x - wdt / 2, center.y + hgt / 2, wdt, hgt, d)
}

private fun DrawScope.ant(px: Offset, k: Float, dir: Offset, walked: Float, color: Int?, load: Int, now: Long, seed: Float) {
    val r = 0.22f * k
    val bob = sin(walked * 10f) * 0.025f * k
    val c = px + Offset(0f, -r * 0.5f + bob)
    val fx = if (dir.x < -0.2f) -1f else if (dir.x > 0.2f) 1f else 0f
    val fy = if (dir.y < -0.2f) -1f else if (dir.y > 0.2f) 1f else 0f
    val f = Offset(fx, fy * 0.55f)
    drawOval(Color.Black.copy(alpha = 0.25f), px + Offset(-r * 1.6f, r * 0.15f), Size(r * 3.2f, r * 0.8f))
    for (side in listOf(-1f, 1f)) {
        for (j in 0..2) {
            val ph = sin(walked * 16f + j * 2.1f + if (side > 0) 1.6f else 0f)
            val base = c + Offset((j - 1) * r * 0.42f, side * r * 0.2f)
            val knee = base + Offset((j - 1) * r * 0.25f + ph * r * 0.15f, side * r * 0.55f - r * 0.2f)
            val foot = knee + Offset((j - 1) * r * 0.15f, r * 0.55f)
            drawLine(dark(ANT, 0.25f), base, knee, strokeWidth = r * 0.13f, cap = StrokeCap.Round)
            drawLine(dark(ANT, 0.25f), knee, foot, strokeWidth = r * 0.11f, cap = StrokeCap.Round)
        }
    }
    val back = c - Offset(if (fx == 0f) 0f else fx * r * 1.05f, if (fx == 0f) -r * 0.55f * (if (fy == 0f) 1f else fy) else 0f)
    drawOval(ANT, back - Offset(r * 0.85f, r * 0.68f), Size(r * 1.7f, r * 1.36f))
    drawOval(ANT_HI, back - Offset(r * 0.55f, r * 0.55f), Size(r * 0.7f, r * 0.38f))
    drawCircle(ANT, r * 0.5f, c)
    val head = c + Offset(f.x * r * 1.0f, if (fx == 0f) r * 0.75f * (if (fy == 0f) 1f else fy) - r * 0.25f else -r * 0.15f)
    val antBase = head + Offset(0f, -r * 0.55f)
    drawLine(dark(ANT, 0.3f), antBase, antBase + Offset(-r * 0.35f + f.x * r * 0.35f, -r * 0.7f), strokeWidth = r * 0.09f, cap = StrokeCap.Round)
    drawLine(dark(ANT, 0.3f), antBase, antBase + Offset(r * 0.35f + f.x * r * 0.35f, -r * 0.7f), strokeWidth = r * 0.09f, cap = StrokeCap.Round)
    drawCircle(ANT, r * 0.62f, head)
    drawCircle(ANT_HI, r * 0.2f, head + Offset(-r * 0.25f, -r * 0.3f))
    val blink = ((now / 3000L) + seed.toLong()) % 5 == 0L && now % 3000 < 150
    for (e in listOf(-1f, 1f)) {
        val eye = head + Offset(e * r * 0.27f + f.x * r * 0.18f, r * 0.02f)
        if (blink) {
            drawLine(Color.White, eye - Offset(r * 0.13f, 0f), eye + Offset(r * 0.13f, 0f), strokeWidth = r * 0.07f)
        } else {
            drawCircle(Color.White, r * 0.19f, eye)
            drawCircle(Color(0xFF1C1C1C), r * 0.1f, eye + Offset(f.x * r * 0.06f, r * 0.03f))
        }
    }
    drawCircle(Color(0xFFFF9AA2).copy(alpha = 0.55f), r * 0.1f, head + Offset(-r * 0.4f, r * 0.28f))
    drawCircle(Color(0xFFFF9AA2).copy(alpha = 0.55f), r * 0.1f, head + Offset(r * 0.4f, r * 0.28f))
    if (color != null) {
        val s = 0.42f * k
        val u = 0.17f * k
        prism(COLORS[color], back.x - s / 2 - s * 0.08f, back.y - r * 0.35f, s, u * load, s * 0.22f, load)
    }
}

fun DrawScope.drawGame(g: Game, sc: Scene, tm: TextMeasurer, dp: Float) {
    val k = sc.k
    val barW = size.width - 32 * dp
    drawRoundRect(PANEL, Offset(16 * dp, 4 * dp), Size(barW, 6 * dp), CornerRadius(3 * dp))
    drawRoundRect(ACCENT, Offset(16 * dp, 4 * dp), Size(barW * g.cleared / g.n, 6 * dp), CornerRadius(3 * dp))

    val gTl = sc.p(Offset(0.05f, -1.1f))
    val gBr = sc.p(Offset(g.ww - 0.05f, g.worldH))
    val gs = Size(gBr.x - gTl.x, gBr.y - gTl.y)
    drawRoundRect(GROUND_EDGE, gTl + Offset(0f, 0.3f * k), gs, CornerRadius(0.5f * k))
    drawRoundRect(GROUND, gTl, gs, CornerRadius(0.5f * k))

    val tTl = sc.p(Offset(g.bx0 - 1f, -1f))
    val tBr = sc.p(Offset(g.bx0 + g.w + 1f, g.h + 1f))
    drawRoundRect(TRAIL, tTl, Size(tBr.x - tTl.x, tBr.y - tTl.y), CornerRadius(0.45f * k))
    val bTl = sc.p(Offset(g.bx0, 0f))
    val bBr = sc.p(Offset(g.bx0 + g.w, g.h.toFloat()))
    drawRect(SOIL, bTl, Size(bBr.x - bTl.x, bBr.y - bTl.y))

    val road = sc.p(Offset(0.2f, g.roadY - 0.32f))
    val roadEnd = sc.p(Offset(g.ww - 0.2f, g.roadY + 0.32f))
    drawRoundRect(dark(TRAIL, 0.3f), road + Offset(0f, 0.08f * k), Size(roadEnd.x - road.x, roadEnd.y - road.y), CornerRadius(0.32f * k))
    drawRoundRect(TRAIL, road, Size(roadEnd.x - road.x, roadEnd.y - road.y), CornerRadius(0.32f * k))
    val joinTop = sc.p(Offset(g.bx0 - 0.85f, g.h + 0.4f))
    drawRect(TRAIL, joinTop, Size(0.7f * k, (g.roadY - g.h) * k))
    var dx = 1.2f
    while (dx < g.ww - 0.6f) {
        drawCircle(dark(TRAIL, 0.18f), 0.05f * k, sc.p(Offset(dx, g.roadY + 0.12f)))
        dx += 0.55f
    }

    for (i in 0 until g.n) {
        if (g.present[i]) continue
        val tl = sc.p(g.cellPos(i) - Offset(0.5f, 0.5f))
        val px = g.pixel[i]
        drawRect(if (px < 0) REVEAL_BG else COLORS[px], tl, Size(k, k))
    }
    drawRect(Color.Black.copy(alpha = 0.25f), bTl, Size(bBr.x - bTl.x, bBr.y - bTl.y), style = Stroke(1.5f * dp))

    val n = sc.p(g.nest)
    drawOval(dark(NEST, 0.35f), n + Offset(-0.5f * k, -0.1f * k), Size(1.0f * k, 0.45f * k))
    drawArc(NEST, 180f, 180f, true, n + Offset(-0.45f * k, -0.45f * k), Size(0.9f * k, 0.9f * k))
    drawArc(light(NEST, 0.2f), 200f, 60f, true, n + Offset(-0.4f * k, -0.4f * k), Size(0.8f * k, 0.8f * k))
    drawArc(Color(0xFF2A1E16), 180f, 180f, true, n + Offset(-0.18f * k, -0.2f * k), Size(0.36f * k, 0.4f * k))

    label(tm, "HOLES", sc.p(Offset(g.ww / 2, g.dockY - 0.8f)), MUTED, 9.sp)
    for (d in 0 until DOCKS) {
        val c = sc.p(g.dockPos(d))
        val hole = g.docks[d]
        val pw = 1.55f * k
        val ph = 0.6f * k
        if (hole == null) {
            drawOval(GROUND_EDGE, c - Offset(pw / 2, ph / 2), Size(pw, ph))
            label(tm, "✓", c, MUTED.copy(alpha = 0.5f), 15.sp)
            continue
        }
        val col = COLORS[hole.color]
        val pop = ((g.now - hole.since) / 260f).coerceIn(0f, 1f)
        val s2 = 0.6f + 0.4f * pop
        val w2 = pw * s2
        val h2 = ph * s2
        drawOval(dark(col, 0.2f), c - Offset(w2 / 2, h2 / 2 - 0.07f * k), Size(w2, h2))
        drawOval(light(col, 0.15f), c - Offset(w2 / 2, h2 / 2), Size(w2, h2))
        drawOval(Color(0xFF151515), c - Offset(w2 * 0.36f, h2 * 0.3f), Size(w2 * 0.72f, h2 * 0.6f))
        val done = hole.doneAt > 0
        val txt = if (done) "✓" else "${hole.delivered}/${hole.cap}"
        label(tm, txt, c + Offset(0f, h2 * 0.62f + 6 * dp), if (done) Color(0xFF7CD992) else Color.White, 12.sp)
    }
    if (g.queue.isNotEmpty()) {
        val y = g.dockY + 0.92f
        label(tm, "NEXT", sc.p(Offset(0.6f, y)), MUTED, 9.sp)
        g.queue.take(8).forEachIndexed { j, hq ->
            drawCircle(COLORS[hq.color].copy(alpha = 0.85f), 0.13f * k, sc.p(Offset(1.2f + j * 0.42f, y)))
        }
    }

    val full = g.now - g.refusedAt < 800
    label(tm, if (full) "SORTING SPACE FULL!" else "SORTING SPACE", sc.p(Offset(g.ww / 2, g.slotY - 0.85f)), if (full) RED else MUTED, 9.sp)
    for (s in 0 until g.slotCount) {
        val c = sc.p(g.slotPos(s))
        slab(c + Offset(0f, 0.2f * k), 1.05f * k, 0.22f * k, 0.18f * k, if (full) dark(RED, 0.4f) else light(PANEL, 0.1f))
        val st = g.slots[s] ?: continue
        val sw = 0.6f * k
        val u = 0.2f * k
        if (st.arrived) {
            val pop = ((g.now - st.since) / 200f).coerceIn(0f, 1f)
            prism(COLORS[st.color], c.x - sw / 2, c.y + 0.08f * k - (1 - pop) * 0.3f * k, sw, u * st.load, sw * 0.22f, st.load)
        } else {
            drawRect(COLORS[st.color].copy(alpha = 0.55f), Offset(c.x - sw / 2, c.y + 0.08f * k - u * st.load), Size(sw, u * st.load), style = Stroke(1.5f * dp))
        }
    }

    val bob = (sin(g.now / 280.0) * 0.03 * k).toFloat()
    val s = 0.8f * k
    val d = 0.2f * k
    val u = 0.24f * k
    val flash = g.now - g.blockedAt < 500
    for (y in 0 until g.h) {
        for (x in 0 until g.w) {
            val i = y * g.w + x
            if (!g.present[i]) continue
            val tl = sc.p(g.cellPos(i) - Offset(0.5f, 0.5f))
            val ok = g.reachable(i)
            val res = g.isReserved(i)
            val col = if (ok || res) COLORS[g.stackColor[i]] else dark(COLORS[g.stackColor[i]], 0.38f)
            val bottom = tl.y + k - 0.06f * k + if (ok) bob else 0f
            prism(col, tl.x + 0.06f * k, bottom, s, u * g.height[i], d, g.height[i], if (res) 0.55f else 1f)
            if (flash && g.blockedCell == i) {
                drawRect(RED, Offset(tl.x + 0.06f * k, bottom - u * g.height[i]), Size(s, u * g.height[i]), style = Stroke(2.5f * dp))
            }
        }
    }

    g.ants.sortedBy { it.pos.y }.forEachIndexed { j, a ->
        ant(sc.p(a.pos), k, a.dir, a.walked, if (a.carrying) a.color else null, a.load, g.now, j * 1.7f)
    }
}
