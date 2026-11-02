package com.pixelporters.game

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

private val BG = Color(0xFF1C2030)
private val PANEL = Color(0xFF2A3045)
private val MUTED = Color(0xFF8A93AD)
private val ACCENT = Color(0xFFFFC94D)
private val BOT = Color(0xFF3B4460)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val t = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(SystemBarStyle.dark(t), SystemBarStyle.dark(t))
        val prefs = getSharedPreferences("game", Context.MODE_PRIVATE)
        setContent { GameApp(prefs) }
    }
}

class Layout(val size: Size, g: Game, dp: Float) {
    private val gw = g.w
    val pad = 16 * dp
    private val boardH = size.height * 0.5f
    val cell = min((size.width - 2 * pad - 20 * dp) / g.w, (boardH - 40 * dp) / g.h)
    val boardLeft = (size.width - cell * g.w) / 2
    val boardTop = 24 * dp + (boardH - 40 * dp - cell * g.h) / 2
    private val slotGap = 8 * dp
    val slotSize = min(60 * dp, (size.width - 2 * pad - slotGap * (g.slotCount - 1)) / g.slotCount)
    private val slotsLeft = (size.width - slotSize * g.slotCount - slotGap * (g.slotCount - 1)) / 2
    val slotsTop = boardH + 8 * dp
    val lanesTop = slotsTop + slotSize + 28 * dp
    private val laneGap = 10 * dp
    val laneW = (size.width - 2 * pad - laneGap * (g.laneCount - 1)) / g.laneCount
    val tileH = min(56 * dp, (size.height - lanesTop - pad) / 4.7f)
    val tileGap = 6 * dp

    fun cellRect(i: Int) = Rect(Offset(boardLeft + (i % gw) * cell, boardTop + (i / gw) * cell), Size(cell, cell))
    fun slotRect(si: Int) = Rect(Offset(slotsLeft + si * (slotSize + slotGap), slotsTop), Size(slotSize, slotSize))
    fun laneLeft(l: Int) = pad + l * (laneW + laneGap)
    fun laneAt(p: Offset, lanes: Int): Int? {
        if (p.y < lanesTop - 16 * (slotGap / 8)) return null
        return (0 until lanes).firstOrNull { p.x >= laneLeft(it) && p.x <= laneLeft(it) + laneW }
    }
}

@Composable
fun GameApp(prefs: SharedPreferences) {
    var level by remember { mutableIntStateOf(prefs.getInt("level", 1)) }
    var attempt by remember { mutableIntStateOf(0) }
    val game = remember(level, attempt) { Game(level) }
    var frame by remember { mutableLongStateOf(0L) }
    var status by remember(game) { mutableIntStateOf(0) }
    val tm = rememberTextMeasurer()
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(game) {
        while (true) {
            withFrameMillis { t ->
                game.update(t)
                frame = t
                if (status != game.status) status = game.status
            }
        }
    }

    Box(Modifier.fillMaxSize().background(BG).systemBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Level $level", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(game.pic.name, color = MUTED, fontSize = 14.sp)
                }
                IconButton(onClick = { attempt++ }) { Icon(Icons.Filled.Refresh, "Restart level", tint = MUTED) }
            }
            Canvas(
                Modifier.fillMaxSize().pointerInput(game) {
                    detectTapGestures { pos ->
                        val l = Layout(size.toSize(), game, density).laneAt(pos, game.laneCount)
                        if (l != null && game.tap(l)) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
            ) {
                frame
                drawGame(game, Layout(size, game, density), tm, density)
            }
        }

        if (status == 1) {
            EndCard("Picture complete!", "You finished “${game.pic.name}”.") {
                Button(
                    onClick = {
                        level++
                        attempt = 0
                        prefs.edit().putInt("level", level).apply()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ACCENT, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Next level", fontWeight = FontWeight.Bold) }
            }
        }
        if (status == 2) {
            EndCard("Jammed!", "Every storage slot is full and nothing fits on the picture yet.") {
                if (!game.rescued) {
                    Button(
                        onClick = { game.rescue(); status = 0 },
                        colors = ButtonDefaults.buttonColors(containerColor = ACCENT, contentColor = Color.Black),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Add an extra slot (free)", fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedButton(onClick = { attempt++ }, modifier = Modifier.fillMaxWidth()) { Text("Retry level", color = Color.White) }
            }
        }
    }
}

@Composable
private fun EndCard(title: String, body: String, buttons: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().clickable(enabled = false) {}, contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(PANEL).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(body, color = MUTED, fontSize = 15.sp)
            Spacer(Modifier.height(10.dp))
            buttons()
        }
    }
}

private fun DrawScope.label(tm: TextMeasurer, s: String, center: Offset, color: Color, size: TextUnit) {
    val r = tm.measure(s, TextStyle(color = color, fontSize = size, fontWeight = FontWeight.Bold))
    drawText(r, topLeft = center - Offset(r.size.width / 2f, r.size.height / 2f))
}

private fun ink(c: Color) = if (c.luminance() > 0.55f) Color(0xFF1C2030) else Color.White

private fun DrawScope.cube(c: Color, topLeft: Offset, s: Float) {
    drawRoundRect(c, topLeft, Size(s, s), CornerRadius(s * 0.2f))
    drawRoundRect(Color.White.copy(alpha = 0.28f), topLeft + Offset(s * 0.15f, s * 0.12f), Size(s * 0.7f, s * 0.16f), CornerRadius(s * 0.08f))
    drawRoundRect(Color.Black.copy(alpha = 0.14f), topLeft + Offset(0f, s * 0.82f), Size(s, s * 0.18f), CornerRadius(s * 0.09f))
}

private fun DrawScope.bot(pos: Offset, s: Float, c: Color) {
    val r = s * 0.42f
    val body = pos + Offset(0f, r * 0.9f)
    drawCircle(BOT, r, body)
    drawCircle(Color.White, r * 0.22f, body + Offset(-r * 0.38f, r * 0.05f))
    drawCircle(Color.White, r * 0.22f, body + Offset(r * 0.38f, r * 0.05f))
    drawCircle(BG, r * 0.1f, body + Offset(-r * 0.34f, r * 0.08f))
    drawCircle(BG, r * 0.1f, body + Offset(r * 0.42f, r * 0.08f))
    val cs = s * 0.62f
    cube(c, pos - Offset(cs / 2, cs * 0.75f), cs)
}

private fun DrawScope.drawGame(g: Game, l: Layout, tm: TextMeasurer, dp: Float) {
    val barW = size.width - 2 * l.pad
    drawRoundRect(PANEL, Offset(l.pad, 4 * dp), Size(barW, 6 * dp), CornerRadius(3 * dp))
    drawRoundRect(ACCENT, Offset(l.pad, 4 * dp), Size(barW * g.done / g.total, 6 * dp), CornerRadius(3 * dp))

    drawRoundRect(
        PANEL, Offset(l.boardLeft - 10 * dp, l.boardTop - 10 * dp),
        Size(l.cell * g.w + 20 * dp, l.cell * g.h + 20 * dp), CornerRadius(16 * dp)
    )
    for (i in g.color.indices) {
        val ci = g.color[i]
        if (ci < 0) continue
        val c = COLORS[ci]
        val r = l.cellRect(i)
        val gap = l.cell * 0.07f
        val s = l.cell - 2 * gap
        val tl = r.topLeft + Offset(gap, gap)
        if (g.filled[i]) {
            cube(c, tl, s)
        } else {
            drawRoundRect(c.copy(alpha = 0.16f), tl, Size(s, s), CornerRadius(s * 0.2f))
            if (g.isOpen(i)) drawRoundRect(c.copy(alpha = 0.9f), tl, Size(s, s), CornerRadius(s * 0.2f), style = Stroke(2 * dp))
        }
    }

    label(tm, "STORAGE", Offset(size.width / 2, l.slotsTop - 2 * dp - 6 * dp), MUTED, 10.sp)
    for (si in 0 until g.slotCount) {
        val r = l.slotRect(si)
        drawRoundRect(PANEL, r.topLeft, r.size, CornerRadius(12 * dp))
        val s = g.slots[si]
        if (s == null) {
            drawRoundRect(MUTED.copy(alpha = 0.35f), r.topLeft, r.size, CornerRadius(12 * dp), style = Stroke(1.5f * dp))
        } else {
            val pop = ((g.now - s.since) / 180f).coerceIn(0f, 1f)
            val inset = 7 * dp + (1 - pop) * r.width * 0.2f
            val c = COLORS[s.color]
            cube(c, r.topLeft + Offset(inset, inset), r.width - 2 * inset)
            label(tm, "${s.n}", r.center, ink(c), 18.sp)
        }
    }

    label(tm, "TAP A COLUMN TO SEND ITS FRONT STACK", Offset(size.width / 2, l.lanesTop - 14 * dp), MUTED, 10.sp)
    for (ln in 0 until g.laneCount) {
        val x = l.laneLeft(ln)
        val q = g.lanes[ln]
        drawRoundRect(PANEL.copy(alpha = 0.55f), Offset(x, l.lanesTop - 4 * dp), Size(l.laneW, size.height - l.lanesTop - 4 * dp), CornerRadius(14 * dp))
        q.take(4).forEachIndexed { k, st ->
            val y = l.lanesTop + k * (l.tileH + l.tileGap)
            val c = COLORS[st.color]
            val a = if (k == 0) 1f else 0.5f
            val tl = Offset(x + 6 * dp, y)
            val sz = Size(l.laneW - 12 * dp, l.tileH)
            drawRoundRect(c.copy(alpha = a), tl, sz, CornerRadius(10 * dp))
            if (k == 0) drawRoundRect(Color.White.copy(alpha = 0.25f), tl + Offset(sz.width * 0.1f, sz.height * 0.12f), Size(sz.width * 0.8f, sz.height * 0.14f), CornerRadius(4 * dp))
            label(tm, "×${st.n}", tl + Offset(sz.width / 2, sz.height / 2), ink(c).copy(alpha = if (k == 0) 1f else 0.7f), if (k == 0) 18.sp else 14.sp)
        }
        val below = Offset(x + l.laneW / 2, l.lanesTop + 4 * (l.tileH + l.tileGap) + 8 * dp)
        if (q.size > 4) label(tm, "+${q.size - 4} more", below, MUTED, 12.sp)
        if (q.isEmpty()) label(tm, "empty", Offset(x + l.laneW / 2, l.lanesTop + l.tileH / 2), MUTED.copy(alpha = 0.6f), 13.sp)
    }

    for (f in g.flyers) {
        val t = ((g.now - f.start) / FLY_MS.toFloat()).coerceIn(0f, 1f)
        val e = t * t * (3 - 2 * t)
        val from = l.slotRect(f.slot).center
        val to = l.cellRect(f.cell).center
        val pos = lerp(from, to, e) - Offset(0f, sin(PI * e).toFloat() * 46 * dp)
        bot(pos, maxOf(l.cell, 22 * dp), COLORS[f.color])
    }
}
