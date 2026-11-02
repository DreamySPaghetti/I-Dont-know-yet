package com.pixelporters.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

class GameView(context: Context) : View(context) {
    var engine: Engine? = null
        private set
    private var lv: Level? = null
    var paused = false
    var fast = false
    var debug = false
        set(v) { field = v; if (v) computeDebug() }
    var pickMode = false
    var onEvent: (Ev) -> Unit = {}
    var onTapResult: (String, Cube?) -> Unit = { _, _ -> }
    var onPickSlot: (Int) -> Unit = {}

    private val dp = resources.displayMetrics.density
    private var CW = 0f
    private var CH = 0f
    private var lastNs = 0L
    private var pulseT = 0f

    private var pal = IntArray(0)
    private var palLight = IntArray(0)
    private var palDark = IntArray(0)

    // layout
    private var tile = 0f; private var bx = 0f; private var by = 0f
    private var slotS = 0f; private var slotGap = 0f; private var slotY = 0f; private var slotX0 = 0f
    private var trayTop = 0f; private var trayBot = 0f; private var colW = 0f; private var colX0 = 0f
    private var cubeW = 0f; private var cubeH = 0f; private var cubeGap = 0f
    private var laidSlots = -1; private var laidCols = -1
    private val baseRect = RectF()
    private var baseBmp: Bitmap? = null
    private var boardBmp: Bitmap? = null
    private var boardCanvas: Canvas? = null
    private var boardDirty = true
    private var antFrames = arrayOf<Bitmap>()
    private var antS = 12f
    private var debugText = ""

    private class Pop(val i: Int, var t: Float)
    private class Dust(var x: Float, var y: Float, val vx: Float, val vy: Float, val r: Float, var life: Float)
    private class Conf(var x: Float, var y: Float, var vx: Float, var vy: Float, var r: Float, val vr: Float, val s: Float, val c: Int, var life: Float)
    private val pops = ArrayList<Pop>()
    private val dust = ArrayList<Dust>()
    private val confetti = ArrayList<Conf>()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val textStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); style = Paint.Style.STROKE; color = Color.argb(90, 0, 0, 0) }
    private val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(200, 255, 255, 255); maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL) }
    private val rect = RectF()
    private val path = Path()

    fun start(level: Level) {
        lv = level
        engine = Engine(level)
        pal = level.palette.map { Color.parseColor(it) }.toIntArray()
        palLight = level.palette.map { Color.parseColor(shade(it, .35)) }.toIntArray()
        palDark = level.palette.map { Color.parseColor(shade(it, -.3)) }.toIntArray()
        pops.clear(); dust.clear(); confetti.clear()
        laidSlots = -1
        if (CW > 0) { layoutAll(); buildSprites(); buildBase() }
        boardDirty = true
        if (debug) computeDebug()
        invalidate()
    }

    fun refreshLayout() { if (engine != null && CW > 0) layoutAll() }

    fun celebrate() {
        val cols = intArrayOf(0xFFFFC94D.toInt(), 0xFFEF5D5D.toInt(), 0xFF5FCF86.toInt(), 0xFF7FB6FF.toInt(), 0xFFEC6FA8.toInt(), Color.WHITE)
        for (k in 0 until 140) confetti.add(Conf(CW / 2 + ((Math.random() - .5) * 60 * dp).toFloat(), CH * .3f, ((Math.random() - .5) * 520 * dp / 2).toFloat(), ((-200 - Math.random() * 420) * dp / 2).toFloat(),
            (Math.random() * 6).toFloat(), ((Math.random() - .5) * 12).toFloat(), ((6 + Math.random() * 6) * dp).toFloat(), cols[k % cols.size], 2.2f))
    }

    fun computeDebug() {
        val e = engine ?: return
        val s = solveEngine(e, 30000)
        debugText = "Level solution (columns): " + (lv?.solution?.joinToString(" ") { (it + 1).toString() } ?: "none") +
            "\nFrom here: " + (s?.let { r -> r.path.joinToString(" ") { (it + 1).toString() } + "  (${r.nodes} nodes)" } ?: "no solution found")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        CW = w.toFloat(); CH = h.toFloat()
        if (engine != null && w > 0 && h > 0) { layoutAll(); buildSprites(); buildBase(); boardDirty = true }
    }

    private fun layoutAll() {
        val e = engine ?: return; val l = lv ?: return
        val pad = CW * .04f
        val bTop = CH * .02f; val bH = CH * .5f; val bW = CW - 2 * pad
        val off = l.nests[0].y - l.H
        tile = min(bW / l.W, bH / (l.H + off + .6f))
        bx = (CW - tile * l.W) / 2; by = bTop + (bH - tile * (l.H + off + .6f)) / 2
        val sc = e.slotCount
        slotGap = CW * .022f
        slotS = minOf((CW - 2 * pad - (sc - 1) * slotGap) / sc, CH * .088f, CW * .16f)
        slotY = CH * .565f; slotX0 = (CW - (sc * slotS + (sc - 1) * slotGap)) / 2
        trayTop = CH * .685f; trayBot = CH * .985f
        val nc = max(1, e.cols.size)
        colW = (CW - 2 * pad) / nc; colX0 = pad
        cubeW = min(colW * .74f, CW * .2f); cubeH = min((trayBot - trayTop) / 4.5f, cubeW * .62f); cubeGap = cubeH * .16f
        laidSlots = sc; laidCols = nc
    }

    private fun gx(x: Float) = bx + x * tile
    private fun gy(y: Float) = by + y * tile

    private fun rr(c: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float, p: Paint) {
        rect.set(x, y, x + w, y + h); val rad = min(r, min(w / 2, h / 2)); c.drawRoundRect(rect, rad, rad, p)
    }

    private fun buildSprites() {
        antS = (tile * .95f).coerceIn(10f * dp / 1.6f, 26f * dp / 1.2f)
        val S = ceil(antS * 1.7f).toInt().coerceAtLeast(4)
        antFrames = Array(4) { f ->
            val b = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888); val c = Canvas(b)
            c.translate(antS * .85f, antS * .85f); drawAnt(c, antS, f); b
        }
    }

    private fun drawAnt(g: Canvas, s: Float, f: Int) {
        val ph = f / 4f * 2 * PI.toFloat()
        val leg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF3A2219.toInt(); strokeWidth = max(1f, s * .07f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        for (j in 0 until 3) {
            val bxx = (-.1f + j * .12f) * s
            for (side in intArrayOf(-1, 1)) {
                val sw = sin(ph + j * 2.1f + if (side > 0) PI.toFloat() else 0f) * .12f * s
                val p = Path(); p.moveTo(bxx, 0f); p.lineTo(bxx + sw * .5f, side * .22f * s); p.lineTo(bxx + sw + (j - 1) * .12f * s, side * .38f * s)
                g.drawPath(p, leg)
            }
        }
        val pf = Paint(Paint.ANTI_ALIAS_FLAG)
        pf.color = 0xFF5A3322.toInt(); g.drawOval(-.54f * s, -.19f * s, -.06f * s, .19f * s, pf)
        pf.color = 0xFF4A2A1D.toInt(); g.drawOval(-.13f * s, -.11f * s, .13f * s, .11f * s, pf)
        pf.color = 0xFF5A3322.toInt(); g.drawCircle(.24f * s, 0f, .16f * s, pf)
        pf.color = Color.argb(64, 255, 255, 255); g.drawOval(-.44f * s, -.12f * s, -.24f * s, -.02f * s, pf)
        leg.strokeWidth = max(.8f, s * .05f)
        val ant = Path(); ant.moveTo(.32f * s, -.08f * s); ant.quadTo(.45f * s, -.22f * s, .54f * s, -.17f * s); ant.moveTo(.32f * s, .08f * s); ant.quadTo(.45f * s, .22f * s, .54f * s, .17f * s)
        g.drawPath(ant, leg)
        pf.color = Color.WHITE; g.drawCircle(.29f * s, -.068f * s, .058f * s, pf); g.drawCircle(.29f * s, .068f * s, .058f * s, pf)
        pf.color = 0xFF151515.toInt(); g.drawCircle(.31f * s, -.068f * s, .03f * s, pf); g.drawCircle(.31f * s, .068f * s, .03f * s, pf)
    }

    private fun buildBase() {
        val l = lv ?: return
        val m = max(10 * dp, tile * .5f); val off = l.nests[0].y - l.H
        val x0 = bx - m; val y0 = by - m; val w = l.W * tile + 2 * m; val h = (l.H + off + .7f) * tile + m
        baseRect.set(x0, y0, x0 + w, y0 + h)
        val b = Bitmap.createBitmap(ceil(w).toInt().coerceAtLeast(1), ceil(h).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888); val g = Canvas(b)
        fill.color = 0xFFE8D9BB.toInt(); rr(g, 0f, 0f, w, h, 22 * dp, fill)
        val r = Rng(l.L * 31 + 5)
        val n = (w * h / (140 * dp * dp)).toInt()
        for (k in 0 until n) {
            fill.color = if (r.next() < .5) Color.argb(33, 150, 115, 70) else Color.argb(56, 255, 255, 255)
            g.drawCircle((r.next() * w).toFloat(), (r.next() * h).toFloat(), ((.6 + r.next() * 1.6) * dp).toFloat(), fill)
        }
        stroke.color = Color.argb(46, 120, 90, 50); stroke.strokeWidth = 2 * dp; rr(g, dp, dp, w - 2 * dp, h - 2 * dp, 21 * dp, stroke)
        baseBmp = b
    }

    private fun drawTile(g: Canvas, x: Float, y: Float, t: Float, i: Int, scale: Float = 1f) {
        val l = lv ?: return
        val c = l.grid[i]; val inset = max(.5f, t * .05f)
        var s = t - 2 * inset; var ox = x + inset; var oy = y + inset
        if (scale != 1f) { val ns = s * scale; ox += (s - ns) / 2; oy += (s - ns) / 2; s = ns }
        fill.color = pal[c]; rr(g, ox, oy, s, s, s * .2f, fill)
        if (t > 9 * dp) {
            fill.color = Color.argb(77, 255, 255, 255); rr(g, ox + s * .12f, oy + s * .1f, s * .76f, s * .17f, s * .085f, fill)
            fill.color = Color.argb(38, 0, 0, 0); rr(g, ox, oy + s * .8f, s, s * .2f, s * .1f, fill)
        }
    }

    private fun renderBoard() {
        val l = lv ?: return; val e = engine ?: return
        val w = ceil(l.W * tile).toInt().coerceAtLeast(1); val h = ceil(l.H * tile).toInt().coerceAtLeast(1)
        var b = boardBmp
        if (b == null || b.width != w || b.height != h) { b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); boardBmp = b; boardCanvas = Canvas(b) }
        b.eraseColor(Color.TRANSPARENT)
        val g = boardCanvas!!
        for (i in 0 until e.board.N) if (e.shown[i]) drawTile(g, (i % l.W) * tile, (i / l.W) * tile, tile, i)
        boardDirty = false
    }

    private fun eraseTile(i: Int) {
        val l = lv ?: return; val g = boardCanvas ?: return
        val x = (i % l.W) * tile; val y = (i / l.W) * tile
        g.drawRect(floor(x), floor(y), ceil(x + tile), ceil(y + tile), clear)
    }

    private fun slotX(si: Int) = slotX0 + si * (slotS + slotGap)
    private fun trayX(ci: Int) = colX0 + (ci + .5f) * colW - cubeW / 2
    private fun trayY(j: Int) = trayBot - (j + 1) * (cubeH + cubeGap)

    private fun padlock(g: Canvas, x: Float, y: Float, s: Float) {
        stroke.color = 0xFF7A5A12.toInt(); stroke.strokeWidth = max(1.2f * dp, s * .1f)
        rect.set(x - s * .28f, y - s * .15f - s * .28f, x + s * .28f, y - s * .15f + s * .28f); g.drawArc(rect, 180f, 180f, false, stroke)
        fill.color = 0xFFFFD25E.toInt(); rr(g, x - s * .4f, y - s * .15f, s * .8f, s * .6f, s * .12f, fill); rr(g, x - s * .4f, y - s * .15f, s * .8f, s * .6f, s * .12f, stroke)
        fill.color = 0xFF7A5A12.toInt(); g.drawCircle(x, y + s * .12f, s * .08f, fill)
    }

    private fun drawCube(g: Canvas, x: Float, y: Float, w: Float, h: Float, c: Cube, locked: Boolean = false, ring: Boolean = false) {
        val showReal = c.revealed && c.flip >= .5f
        val col = if (showReal) pal[c.color] else 0xFF9AA3B5.toInt()
        val dk = if (showReal) palDark[c.color] else 0xFF6F7789.toInt()
        val lt = if (showReal) palLight[c.color] else 0xFFC3C9D6.toInt()
        val sx = if (c.flip < 1f) max(.05f, abs(cos(c.flip * PI.toFloat()))) else 1f
        val sc = 1 + c.bounce * .12f * sin((1 - c.bounce) * PI.toFloat())
        val cx = x + w / 2; val cy = y + h / 2
        g.save(); g.scale(sx * sc, sc, cx, cy)
        val top = h * .2f
        fill.color = Color.argb(56, 0, 0, 0); rr(g, x + 2 * dp, y + top + 3 * dp, w, h - top, h * .2f, fill)
        fill.color = dk; rr(g, x, y + top, w, h - top, h * .2f, fill)
        fill.color = col; rr(g, x, y, w, h - top * .6f, h * .2f, fill)
        fill.color = lt; rr(g, x + w * .08f, y + h * .06f, w * .84f, h * .14f, h * .07f, fill)
        val fs = max(10 * dp, h * .44f)
        text.textSize = fs; textStroke.textSize = fs; textStroke.strokeWidth = max(2 * dp, fs * .16f)
        val txt = if (showReal) c.rem.toString() else "?"
        val ty = y + (h - top * .6f) / 2 - (text.descent() + text.ascent()) / 2
        g.drawText(txt, cx, ty, textStroke); text.color = Color.WHITE; g.drawText(txt, cx, ty, text)
        g.restore()
        if (locked) { fill.color = Color.argb(90, 30, 30, 40); rr(g, x, y, w, h, h * .2f, fill); padlock(g, x + w - h * .22f, y + h * .22f, h * .42f) }
        if (ring) {
            val a = .6f + .4f * (.5f + .5f * sin(pulseT * 2 * PI.toFloat() / 1.2f))
            glow.strokeWidth = 6 * dp; glow.alpha = (a * 200).toInt(); rr(g, x - 4 * dp, y - 4 * dp, w + 8 * dp, h + 8 * dp, h * .28f, glow)
            stroke.color = Color.argb((a * 255).toInt(), 255, 255, 255); stroke.strokeWidth = 3 * dp; rr(g, x - 4 * dp, y - 4 * dp, w + 8 * dp, h + 8 * dp, h * .28f, stroke)
        }
    }

    private fun drawAntAt(g: Canvas, x: Float, y: Float, ang: Float, walk: Float, carry: Int) {
        if (antFrames.isEmpty()) return
        val f = antFrames[((floor(walk * 2).toInt() % 4) + 4) % 4]
        g.save(); g.translate(x, y); g.rotate(ang * 180f / PI.toFloat())
        rect.set(-antS * .85f, -antS * .85f, antS * .85f, antS * .85f); g.drawBitmap(f, null, rect, null)
        if (carry >= 0) {
            val cs = min(tile * .6f, antS * .78f)
            fill.color = pal[carry]; rr(g, -antS * .3f - cs / 2, -cs / 2, cs, cs, cs * .22f, fill)
            fill.color = Color.argb(90, 255, 255, 255); g.drawRect(-antS * .3f - cs * .35f, -cs * .4f, -antS * .3f + cs * .35f, -cs * .25f, fill)
        }
        g.restore()
    }

    override fun onDraw(g: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0f else min(.05f, (now - lastNs) / 1e9f)
        lastNs = now
        val e = engine
        val l = lv
        if (e != null && l != null && CW > 0) {
            if (!paused) { e.update(dt * if (fast) 2f else 1f); drain(e) }
            if (laidSlots != e.slotCount || laidCols != max(1, e.cols.size)) layoutAll()
            updateFx(dt); pulseT += dt
            drawScene(g, e, l)
        }
        postInvalidateOnAnimation()
    }

    private fun drain(e: Engine) {
        if (e.events.isEmpty()) return
        val evs = ArrayList(e.events); e.events.clear()
        val l = lv ?: return
        for (ev in evs) {
            if (ev is Ev.Eat) {
                pops.add(Pop(ev.i, 0f))
                if (!boardDirty) eraseTile(ev.i)
                val x = gx(ev.i % l.W + .5f); val y = gy(ev.i / l.W + .5f)
                repeat(3) { dust.add(Dust(x, y, ((Math.random() - .5) * 60 * dp / 2).toFloat(), (((Math.random() - .5) * 60 - 20) * dp / 2).toFloat(), ((1 + Math.random() * 2) * dp / 1.5).toFloat(), .35f)) }
            }
            onEvent(ev)
        }
    }

    private fun updateFx(dt: Float) {
        pops.removeAll { it.t += dt; it.t > .15f }
        dust.removeAll { it.life -= dt; it.x += it.vx * dt; it.y += it.vy * dt; it.life <= 0 }
        confetti.removeAll { it.life -= dt; it.vy += 700 * dp / 2 * dt; it.x += it.vx * dt; it.y += it.vy * dt; it.vx *= .99f; it.r += it.vr * dt; it.life <= 0 || it.y > CH + 20 }
    }

    private fun drawScene(g: Canvas, e: Engine, l: Level) {
        val t = tile; val W = l.W
        baseBmp?.let { g.drawBitmap(it, null, baseRect, null) }
        if (boardDirty) renderBoard()
        boardBmp?.let { rect.set(bx, by, bx + W * t, by + l.H * t); g.drawBitmap(it, null, rect, null) }
        for (p in pops) { val k = 1 - p.t / .15f; if (k > 0) drawTile(g, gx((p.i % W).toFloat()), gy((p.i / W).toFloat()), t, p.i, k) }
        for (d in dust) { fill.color = Color.argb((max(0f, d.life / .35f) * .7f * 255).toInt(), 160, 125, 80); g.drawCircle(d.x, d.y, d.r, fill) }
        for (n in l.nests) {
            val x = gx(n.x); val y = gy(n.y); val w = max(30 * dp, t * 1.7f); val h = w * .48f
            fill.color = 0xFFB89668.toInt(); g.drawOval(x - w * .62f, y + 2 * dp - h * .62f, x + w * .62f, y + 2 * dp + h * .62f, fill)
            fill.color = 0xFF3B2A20.toInt(); g.drawOval(x - w / 2, y - h / 2, x + w / 2, y + h / 2, fill)
            fill.color = 0xFF20160F.toInt(); g.drawOval(x - w * .36f, y + h * .08f - h * .32f, x + w * .36f, y + h * .08f + h * .32f, fill)
        }
        if (debug) {
            stroke.color = 0xFFFF2FD0.toInt(); stroke.strokeWidth = 2 * dp
            for (i in 0 until e.board.N) if (e.board.reachable(i)) g.drawRect(gx((i % W).toFloat()) + 1.5f * dp, gy((i / W).toFloat()) + 1.5f * dp, gx((i % W).toFloat()) + t - 1.5f * dp, gy((i / W).toFloat()) + t - 1.5f * dp, stroke)
        }
        for (a in e.ants) if (a.state == AS.OUT || a.state == AS.PICK || a.state == AS.BACK) drawAntAt(g, gx(a.x), gy(a.y), a.ang, a.walk, a.carry)

        val empty = (0 until e.slotCount).filter { e.slots[it] == null }
        for (si in 0 until e.slotCount) {
            val x = slotX(si); val s = slotS
            fill.color = Color.argb(46, 0, 0, 0); rr(g, x, slotY + 3 * dp, s, s, s * .22f, fill)
            fill.color = if (pickMode) 0xFFFFF6D6.toInt() else 0xFFF4F1EA.toInt(); rr(g, x, slotY, s, s, s * .22f, fill)
            if (empty.size == 1 && empty[0] == si && e.status == GS.PLAY) {
                val a = .55f + .45f * (.5f + .5f * sin(pulseT * 2 * PI.toFloat() / 0.9f))
                stroke.color = Color.argb((a * 255).toInt(), 0xFF, 0xB0, 0x2E); stroke.strokeWidth = 3.5f * dp
                rr(g, x - 2 * dp, slotY - 2 * dp, s + 4 * dp, s + 4 * dp, s * .25f, stroke)
            }
            val c = e.slots[si]
            if (c != null && c.status == St.SLOT) {
                val w = s * .8f; val h = s * .62f
                drawCube(g, x + (s - w) / 2, slotY + (s - h) / 2, w, h, c)
                var k = 0
                for (a in c.ants) if (a.state == AS.IDLE) {
                    val ang = k * 2.4f + 1
                    drawAntAt(g, x + s / 2 + cos(ang) * s * .36f, slotY + s * .18f + (k % 2) * s * .64f, ang + PI.toFloat() / 2, 0f, -1)
                    k++; if (k > 7) break
                }
            }
        }

        val ropes = ArrayList<Cube>()
        e.cols.forEachIndexed { ci, col ->
            val cx = colX0 + (ci + .5f) * colW
            fill.color = Color.argb(12, 255, 255, 255); rr(g, cx - colW * .46f, trayTop - 6 * dp, colW * .92f, trayBot - trayTop + 6 * dp, 16 * dp, fill)
            val vis = min(col.size, 4)
            for (j in vis - 1 downTo 0) {
                val c = col[j]
                val sh = if (c.shake > 0) sin(c.shake * 60) * 5 * dp * (c.shake / .35f) else 0f
                val ring = j == 0 && c.revealed && e.tappable(c) && e.board.peek(c.color) >= 0
                drawCube(g, trayX(ci) + sh, trayY(j), cubeW, cubeH, c, e.isLocked(c), ring)
                if (c.ropeFrom != null) ropes.add(c)
            }
            text.color = 0xFFA9B2CC.toInt()
            if (col.size > 4) { text.textSize = 13 * dp; g.drawText("+${col.size - 4}", cx, trayY(4) + cubeH * .6f, text) }
            if (col.isEmpty()) { text.textSize = 12 * dp; text.color = Color.argb(128, 169, 178, 204); g.drawText("empty", cx, trayBot - cubeH / 2, text) }
        }
        fun posOf(c: Cube): FloatArray? {
            e.cols.forEachIndexed { ci, col -> val j = col.indexOf(c); if (j in 0..3) return floatArrayOf(trayX(ci) + cubeW / 2, trayY(j) + cubeH / 2) }
            return null
        }
        for (b in ropes) {
            val a = e.byId[b.ropeFrom!!] ?: continue
            if (a.status != St.TRAY) continue
            val p = posOf(a) ?: continue; val q = posOf(b) ?: continue
            stroke.color = 0xFFA0723F.toInt(); stroke.strokeWidth = 4 * dp; stroke.pathEffect = DashPathEffect(floatArrayOf(7 * dp, 4 * dp), 0f)
            path.reset(); path.moveTo(p[0], p[1]); path.quadTo((p[0] + q[0]) / 2, max(p[1], q[1]) + 24 * dp, q[0], q[1]); g.drawPath(path, stroke)
            stroke.pathEffect = null
        }

        for (si in 0 until MAX_SLOTS) {
            val c = e.slots[si] ?: continue
            if (c.status != St.FLYING) continue
            val fc = min(c.fromCol, e.cols.size - 1).coerceAtLeast(0)
            val fx = trayX(fc); val fy = trayY(0)
            val k = 1 - (1 - c.anim).pow(3)
            val w = cubeW + (slotS * .8f - cubeW) * k; val h = cubeH + (slotS * .62f - cubeH) * k
            val x = fx + (slotX(si) + (slotS - slotS * .8f) / 2 - fx) * k
            val y = fy + (slotY + (slotS - slotS * .62f) / 2 - fy) * k - sin(k * PI.toFloat()) * 30 * dp
            drawCube(g, x, y, w, h, c)
        }
        for (p in confetti) { g.save(); g.translate(p.x, p.y); g.rotate(p.r * 57.3f); fill.color = p.c; g.drawRect(-p.s / 2, -p.s / 4, p.s / 2, p.s / 4, fill); g.restore() }
        if (debug) {
            val lines = ArrayList<String>()
            for (ln in debugText.split("\n")) { var s = ln; while (s.length > 46) { lines.add(s.substring(0, 46)); s = "  " + s.substring(46) }; lines.add(s) }
            val lh = 14 * dp
            fill.color = Color.argb(184, 0, 0, 0); g.drawRect(6 * dp, 6 * dp, CW - 6 * dp, 6 * dp + lines.size * lh + 10 * dp, fill)
            text.textAlign = Paint.Align.LEFT; text.textSize = 11 * dp; text.color = 0xFF7DFFB0.toInt(); text.typeface = Typeface.MONOSPACE
            lines.forEachIndexed { i, s -> g.drawText(s, 12 * dp, 6 * dp + 14 * dp + i * lh, text) }
            text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked != MotionEvent.ACTION_DOWN) return true
        val e = engine ?: return true
        if (paused) return true
        val x = ev.x; val y = ev.y
        if (pickMode) {
            for (si in 0 until e.slotCount) {
                val sx = slotX(si)
                if (x >= sx - 4 * dp && x <= sx + slotS + 4 * dp && y >= slotY - 4 * dp && y <= slotY + slotS + 4 * dp) { onPickSlot(si); return true }
            }
            return true
        }
        if (y >= trayTop - 12 * dp && y <= trayBot + 4 * dp) {
            val ci = floor((x - colX0) / colW).toInt()
            if (ci >= 0 && ci < e.cols.size) {
                val front = e.cols[ci].firstOrNull()
                onTapResult(e.tap(ci), front)
            }
        }
        return true
    }
}
