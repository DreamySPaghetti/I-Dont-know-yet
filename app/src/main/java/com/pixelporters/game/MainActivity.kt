package com.pixelporters.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val BG = Color(0xFF20263A)
private val PANEL = Color(0xFF2C3450)
private val MUTED = Color(0xFFA9B2CC)
private val ACCENT = Color(0xFFFFC94D)
private val RED = Color(0xFFEF5D5D)
private val GREEN = Color(0xFF5FCF86)
private val CARD = Color(0xFFFFFAF0)
private val CARD_INK = Color(0xFF3A3226)
private val CARD_MUTED = Color(0xFF6D6252)

class BoosterDef(val k: String, val name: String, val icon: String, val unlock: Int, val price: Int, val desc: String)

val BOOSTERS = listOf(
    BoosterDef("slot", "Extra Slot", "➕", 3, 40, "Adds a waiting slot for the rest of this level (up to 7)."),
    BoosterDef("shuffle", "Shuffle", "🔀", 4, 40, "Reorders the cubes in the tray. The board never changes, and Ant Trail checks the new order can still be won."),
    BoosterDef("pickup", "Pick Up", "✋", 6, 50, "Tap a cube in a slot to send it back to the front of its column. It keeps its number."),
    BoosterDef("vacuum", "Vacuum", "🌀", 9, 60, "Pick a colour: every tile and every cube of that colour disappears.")
)

enum class Screen { MENU, PUZZLES, CREATE, GAME }

sealed class Panel {
    object Loading : Panel()
    object Settings : Panel()
    object Gallery : Panel()
    class Tutorial(val b: BoosterDef) : Panel()
    object Jam : Panel()
    class Win(val coins: Int, val three: Boolean, val gift: BoosterDef?, val chest: Boolean) : Panel()
    object Vacuum : Panel()
    class Confirm(val text: String, val yes: String, val action: () -> Unit) : Panel()
}

private val DIFF_NAMES = listOf("Relaxed", "Normal", "Hard")

class MainActivity : ComponentActivity() {
    private lateinit var save: SaveData
    private lateinit var view: GameView
    private lateinit var sound: Sound
    private val gen = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var lv: Level? = null
    private var screen by mutableStateOf(Screen.MENU)
    private var panel by mutableStateOf<Panel?>(null)
    private var levelNo by mutableIntStateOf(1)
    private var tag by mutableStateOf("Normal")
    private var coins by mutableIntStateOf(0)
    private var progress by mutableFloatStateOf(0f)
    private var uiTick by mutableIntStateOf(0)
    private var toast by mutableStateOf<String?>(null)
    private var toastId by mutableIntStateOf(0)
    private var pickMode by mutableStateOf<BoosterDef?>(null)
    private var debug by mutableStateOf(false)
    private var loadingText by mutableStateOf("Laying out a fresh trail for your ants.")

    // ---- create-from-photo state ----
    private var source: Bitmap? = null
    private var draftArt by mutableStateOf<Art?>(null)
    private var draftSize by mutableIntStateOf(40)
    private var draftColors by mutableIntStateOf(6)
    private var draftDiff by mutableIntStateOf(1)
    private var draftName by mutableStateOf("My picture")
    private var draftSeed = 1
    private var draftBusy by mutableStateOf(false)
    private var draftJob = 0
    private val customCache = HashMap<String, Level>()

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) loadPhoto(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val t = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(SystemBarStyle.dark(t), SystemBarStyle.dark(t))
        save = SaveData.load(this)
        sound = Sound(this).also { it.soundOn = save.sound; it.musicOn = save.music }
        coins = save.coins; levelNo = save.level
        view = GameView(this).apply {
            fast = save.fast
            paused = true
            onEvent = ::onGameEvent
            onTapResult = ::onTap
            onPickSlot = ::onPickSlot
        }
        prefetch(save.level)
        setContent { Root() }
        main.post(object : Runnable {
            override fun run() {
                val e = view.engine; val l = lv
                if (e != null && l != null) { val total = l.grid.count { it >= 0 }; progress = if (total == 0) 0f else 1f - e.shownLeft.toFloat() / total }
                main.postDelayed(this, 120)
            }
        })
    }

    override fun onPause() { super.onPause(); sound.stopMusic(); save.persist(this) }
    override fun onResume() { super.onResume(); if (save.music) sound.startMusic() }

    private fun persist() { save.coins = coins; save.persist(this); uiTick++ }

    private fun vib(ms: Long) {
        if (!save.vib) return
        try {
            @Suppress("DEPRECATION")
            (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    private fun showToast(msg: String) { toast = msg; toastId++ }

    // ---------------- levels ----------------
    /** Levels are generated on a background thread and cached; the next one is prepared while you play. */
    private fun prefetch(n: Int) { gen.execute { genLevel(n) } }

    private fun play(level: Level) {
        lv = level; levelNo = level.L; tag = level.tag
        pickMode = null; view.pickMode = false
        view.start(level); view.debug = debug
        screen = Screen.GAME; panel = null; view.paused = false
        level.hint?.let { showToast(it) }
        if (level.custom == null) { checkTutorials(); prefetch(level.L + 1) }
    }

    private fun startLevel(n: Int) {
        loadingText = "Laying out a fresh trail for your ants."
        panel = Panel.Loading; view.paused = true
        gen.execute {
            val level = genLevel(n)
            main.post { save.level = n; persist(); play(level) }
        }
    }

    private fun startCustom(p: CustomPuzzle) {
        customCache[p.id]?.let { play(it); return }
        loadingText = "Building a solvable puzzle from your picture. Big pictures can take a few seconds."
        panel = Panel.Loading; view.paused = true
        gen.execute {
            val level = customLevel(p.id, p.art(), p.difficulty, p.seed)
            main.post {
                if (level == null) { panel = null; showToast("Couldn't build a puzzle from that picture. Try fewer colours or a smaller size.") }
                else { customCache[p.id] = level; play(level) }
            }
        }
    }

    private fun retry() { val l = lv ?: return; if (l.custom != null) play(l) else startLevel(l.L) }

    private fun goMenu() { view.paused = true; panel = null; pickMode = null; view.pickMode = false; screen = Screen.MENU }

    private fun checkTutorials() {
        val L = lv?.L ?: return
        for (b in BOOSTERS) if (L >= b.unlock && b.k !in save.seen) {
            save.seen.add(b.k); save.boosters[b.k] = (save.boosters[b.k] ?: 0) + 2; persist()
            panel = Panel.Tutorial(b); view.paused = true
            return
        }
    }

    private fun closePanel() { panel = null; view.paused = screen != Screen.GAME }

    // ---------------- photos ----------------
    private fun loadPhoto(uri: Uri) {
        loadingText = "Opening your picture…"
        panel = Panel.Loading
        gen.execute {
            val bmp = try {
                if (Build.VERSION.SDK_INT >= 28) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { d, info, _ ->
                        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val m = maxOf(info.size.width, info.size.height)
                        if (m > 640) d.setTargetSize(info.size.width * 640 / m, info.size.height * 640 / m)
                    }
                } else {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                    var s = 1; while (maxOf(o.outWidth, o.outHeight) / s > 1280) s *= 2
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
                }
            } catch (e: Exception) { null }
            main.post {
                panel = null
                if (bmp == null) { showToast("Couldn't open that picture."); return@post }
                source = bmp; draftSeed = (System.currentTimeMillis() % 100000).toInt(); draftName = "My picture ${save.puzzles.size + 1}"
                screen = Screen.CREATE; rebuildDraft()
            }
        }
    }

    /** Downscales the photo to the chosen grid (longest side = size, aspect kept up to 2:1) and quantises it. */
    private fun rebuildDraft() {
        val src = source ?: return
        val job = ++draftJob; draftBusy = true
        val size = draftSize; val colors = draftColors; val diff = draftDiff; val seed = draftSeed; val name = draftName
        gen.execute {
            var sw = src.width; var sh = src.height; var sx = 0; var sy = 0
            if (sw > sh * 2) { sx = (sw - sh * 2) / 2; sw = sh * 2 } else if (sh > sw * 2) { sy = (sh - sw * 2) / 2; sh = sw * 2 }
            val w = if (sw >= sh) size else maxOf(8, (size * sw.toFloat() / sh).roundToInt())
            val h = if (sh >= sw) size else maxOf(8, (size * sh.toFloat() / sw).roundToInt())
            val crop = Bitmap.createBitmap(src, sx, sy, sw, sh)
            val mid = Bitmap.createScaledBitmap(crop, w * 4, h * 4, true)
            val small = Bitmap.createScaledBitmap(mid, w, h, true)
            val px = IntArray(w * h); small.getPixels(px, 0, w, 0, 0, w, h)
            val art = pixelArt(px, w, h, colors, customDifficulty(diff).third, seed, name, diff)
            main.post { if (job == draftJob) { draftArt = art; draftBusy = false } }
        }
    }

    private fun saveDraftAndPlay() {
        val art = draftArt ?: return
        val p = CustomPuzzle(UUID.randomUUID().toString().take(8), draftName.ifBlank { "My picture" }, art.W, art.H, art.pal.toList(), CustomPuzzle.encode(art.grid), draftDiff, draftSeed)
        save.puzzles.add(0, p); persist()
        source = null
        startCustom(p)
    }

    // ---------------- game events ----------------
    private fun onGameEvent(ev: Ev) {
        when (ev) {
            is Ev.Eat -> if (!ev.quiet) sound.pop()
            is Ev.Tap -> { sound.click(); vib(12) }
            is Ev.Land -> sound.land()
            is Ev.Deliver -> sound.tick()
            is Ev.Done -> { sound.done(); view.refreshLayout() }
            is Ev.Won -> onWin()
            is Ev.Jam -> {
                sound.jam(); vib(200)
                if (lv?.custom == null) { save.streak = 0; persist() }
                main.postDelayed({ panel = Panel.Jam; view.paused = true }, 350)
            }
        }
    }

    private fun onTap(res: String, c: Cube?) {
        when (res) {
            "full" -> { sound.shake(); vib(40); showToast("All slots are full.") }
            "locked" -> {
                sound.shake(); vib(40)
                val e = view.engine
                showToast(if (c != null && c.ropeFrom != null && e != null && !e.isLocked(c)) "Take the roped cube first." else "Locked until the cube in front of it finishes.")
            }
        }
    }

    private fun onPickSlot(si: Int) {
        val b = pickMode ?: return
        val e = view.engine ?: return
        if (e.pickUp(si)) { pay(b); pickMode = null; view.pickMode = false; view.refreshLayout(); sound.land(); vib(15) }
    }

    private fun onWin() {
        val e = view.engine ?: return
        val l = lv ?: return
        val three = !e.used
        sound.win(); vib(60); view.celebrate()
        if (l.custom != null) {
            val reward = if (three) 8 else 5
            coins += reward
            save.puzzles.firstOrNull { it.id == l.custom }?.let { it.stars = maxOf(it.stars, if (three) 3 else 2) }
            persist()
            main.postDelayed({ panel = Panel.Win(reward, three, null, false); view.paused = true }, 1500)
            return
        }
        val L = l.L
        var reward = 12 + minOf(20, L / 10)
        var gift: BoosterDef? = null
        if (three) {
            reward += 8
            val ok = BOOSTERS.filter { L >= it.unlock }
            if (ok.isNotEmpty() && Math.random() < .55) gift = ok[(Math.random() * ok.size).toInt()]
        }
        save.streak++
        val chest = save.streak >= 5
        if (chest) { reward += 100; save.streak = 0 }
        coins += reward
        gift?.let { save.boosters[it.k] = (save.boosters[it.k] ?: 0) + 1 }
        save.stars[L] = maxOf(save.stars[L] ?: 0, if (three) 3 else 2)
        save.gallery.removeAll { it.l == L }
        save.gallery.add(0, GalleryItem(L, l.name, l.W, l.H, l.palette, CustomPuzzle.encode(l.grid)))
        while (save.gallery.size > 200) save.gallery.removeAt(save.gallery.size - 1)
        save.level = L + 1
        persist()
        val w = Panel.Win(reward, three, gift, chest)
        main.postDelayed({ panel = w; view.paused = true }, 1500)
    }

    // ---------------- boosters ----------------
    private fun canPay(b: BoosterDef) = (save.boosters[b.k] ?: 0) > 0 || coins >= b.price
    private fun pay(b: BoosterDef) {
        val n = save.boosters[b.k] ?: 0
        if (n > 0) save.boosters[b.k] = n - 1 else coins -= b.price
        persist()
    }

    private fun boosterLevel() = lv?.let { if (it.custom != null) 999 else it.L } ?: levelNo

    private fun useBooster(b: BoosterDef) {
        val e = view.engine ?: return
        if (boosterLevel() < b.unlock) { showToast("${b.name} unlocks at level ${b.unlock}."); return }
        if (e.status != GS.PLAY || panel != null) return
        if (pickMode != null) { val same = pickMode == b; pickMode = null; view.pickMode = false; if (same) return }
        if (!canPay(b)) { showToast("Not enough coins."); sound.shake(); return }
        when (b.k) {
            "slot" -> if (e.extraSlot()) { pay(b); view.refreshLayout(); sound.land(); vib(15) } else showToast("No room for more slots.")
            "shuffle" -> when (e.shuffle()) {
                "none" -> showToast("Nothing to shuffle.")
                "stuck" -> { pay(b); view.refreshLayout(); showToast("This shuffle couldn't find a way out.") }
                else -> { pay(b); view.refreshLayout(); sound.click(); vib(15) }
            }
            "pickup" -> if ((0 until e.slotCount).none { e.slots[it]?.status == St.SLOT }) showToast("No cube in a slot to pick up.") else { pickMode = b; view.pickMode = true }
            "vacuum" -> { panel = Panel.Vacuum; view.paused = true }
        }
    }

    // ================= UI =================
    @Composable
    private fun Root() {
        BackHandler(panel == null && screen != Screen.MENU) {
            when (screen) {
                Screen.CREATE -> { source = null; screen = Screen.PUZZLES }
                else -> goMenu()
            }
        }
        BackHandler(panel != null && panel !is Panel.Loading) {
            when (panel) {
                is Panel.Gallery -> panel = if (screen == Screen.GAME) Panel.Settings else null
                is Panel.Jam, is Panel.Win -> {}
                else -> closePanel()
            }
        }
        Box(Modifier.fillMaxSize().background(Color(0xFF151A28)).systemBarsPadding()) {
            when (screen) {
                Screen.MENU -> MenuScreen()
                Screen.PUZZLES -> PuzzlesScreen()
                Screen.CREATE -> CreateScreen()
                Screen.GAME -> GameScreen()
            }
            ToastView()
            panel?.let { p -> Overlay(p) }
        }
    }

    // ---------------- main menu ----------------
    @Composable
    private fun MenuScreen() {
        uiTick
        val march = rememberInfiniteTransition(label = "march")
        val t by march.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart), label = "t")
        Column(Modifier.fillMaxSize().background(BG).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                CoinPill()
                Spacer(Modifier.width(10.dp))
                IconBtn("⚙") { panel = Panel.Settings }
            }
            Spacer(Modifier.height(18.dp))
            // a little ant trail marching across a pixel hill
            Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                val tile = size.width / 24f
                val hill = listOf(0xFF8BC97AL, 0xFF6FB35FL, 0xFFF6C443L, 0xFFE8524AL, 0xFF4A90E2L)
                for (x in 0 until 24) {
                    val hgt = (3 + 2.2 * sin(x / 3.8) + (x % 5) * .3).toInt().coerceIn(1, 6)
                    for (y in 0 until hgt) {
                        val c = Color(hill[(x * 7 + y * 3) % hill.size])
                        drawRoundRect(c, Offset(x * tile + 1, size.height - (y + 1) * tile + 1), Size(tile - 2, tile - 2), CornerRadius(tile * .2f))
                    }
                }
                for (k in 0 until 7) {
                    val px = ((t + k / 7f) % 1f) * (size.width + 60) - 30
                    val ground = size.height - 7.2f * tile + sin((px / size.width) * 2 * PI).toFloat() * 4
                    drawAntIcon(px, ground, tile * .9f, t * 40 + k)
                }
            }
            Text("Ant Trail", Modifier.padding(top = 10.dp), color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Black)
            Text("Send the colony, reveal the picture.", color = MUTED, fontSize = 15.sp)
            Spacer(Modifier.height(26.dp))
            BigBtn("▶  Play", "Level ${save.level} · ${difficulty(save.level)}") { startLevel(save.level) }
            Spacer(Modifier.height(12.dp))
            BigBtn("🖼  My Pictures", "Turn your photos into puzzles", alt = true) { screen = Screen.PUZZLES }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallBtn("Gallery (${save.gallery.size})", Modifier.weight(1f)) { panel = Panel.Gallery }
                SmallBtn("Settings", Modifier.weight(1f)) { panel = Panel.Settings }
            }
            Spacer(Modifier.height(20.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(PANEL).padding(16.dp)) {
                Text("🌳 Treetop Mission", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                Text("Win 5 levels in a row without jamming to open a 100-coin chest.", color = MUTED, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
                Dots(save.streak, 18, Color(0xFF4A5372))
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAntIcon(x: Float, y: Float, s: Float, phase: Float) {
        val body = Color(0xFF5A3322)
        for (j in 0..2) for (side in listOf(-1f, 1f)) {
            val bx = x + (j - 1) * s * .22f
            val sw = sin(phase * 3 + j * 2.1f + side) * s * .12f
            drawLine(Color(0xFF3A2219), Offset(bx, y), Offset(bx + sw, y + s * .42f), strokeWidth = s * .07f)
        }
        drawOval(body, Offset(x - s * .62f, y - s * .2f), Size(s * .5f, s * .38f))
        drawCircle(Color(0xFF4A2A1D), s * .13f, Offset(x, y))
        drawCircle(body, s * .17f, Offset(x + s * .3f, y - s * .04f))
        drawCircle(Color.White, s * .06f, Offset(x + s * .36f, y - s * .08f))
        drawCircle(Color(0xFF151515), s * .03f, Offset(x + s * .38f, y - s * .08f))
        drawLine(Color(0xFF3A2219), Offset(x + s * .36f, y - s * .18f), Offset(x + s * .5f, y - s * .36f), strokeWidth = s * .05f)
        drawRoundRect(Color(0xFFF6C443), Offset(x - s * .55f, y - s * .62f), Size(s * .36f, s * .36f), CornerRadius(s * .08f))
    }

    // ---------------- my pictures ----------------
    @Composable
    private fun PuzzlesScreen() {
        uiTick
        Column(Modifier.fillMaxSize().background(BG)) {
            TopBar("My Pictures") { goMenu() }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(ACCENT).clickable {
                        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }.padding(18.dp)
                ) {
                    Text("＋ New from a photo", color = Color(0xFF3A2A00), fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Text("Pick any picture. It becomes a pixel map your ants have to clear, checked by the solver so it can always be won.", color = Color(0xFF5A4500), fontSize = 13.sp)
                }
                Spacer(Modifier.height(14.dp))
                if (save.puzzles.isEmpty()) Text("No pictures yet.", Modifier.fillMaxWidth().padding(24.dp), color = MUTED, textAlign = TextAlign.Center)
                for (p in save.puzzles) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(PANEL).clickable { startCustom(p) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Picture(p.w, p.h, p.grid(), p.p, Modifier.width(64.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(p.name, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                            Text("${p.w}×${p.h} · ${p.p.size} colours · ${DIFF_NAMES[p.difficulty]}", color = MUTED, fontSize = 12.sp)
                            Text(if (p.stars > 0) "★".repeat(p.stars) + "☆".repeat(3 - p.stars) else "Not solved yet", color = if (p.stars > 0) Color(0xFFF5B81F) else MUTED, fontSize = 13.sp)
                        }
                        Text("🗑", Modifier.clip(CircleShape).clickable {
                            panel = Panel.Confirm("Delete \"${p.name}\"?", "Delete") { save.puzzles.remove(p); customCache.remove(p.id); persist() }
                        }.padding(8.dp), fontSize = 18.sp)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    @Composable
    private fun CreateScreen() {
        Column(Modifier.fillMaxSize().background(BG)) {
            TopBar("New puzzle") { source = null; screen = Screen.PUZZLES }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
                Box(Modifier.fillMaxWidth().heightIn(min = 200.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFFE8D9BB)).padding(14.dp), contentAlignment = Alignment.Center) {
                    val a = draftArt
                    if (a != null) Picture(a.W, a.H, a.grid, a.pal, Modifier.widthIn(max = 320.dp).fillMaxWidth())
                    else Text(if (draftBusy) "Converting…" else "That picture didn't work. Try more colours.", color = CARD_MUTED)
                }
                draftArt?.let { a ->
                    Text("${a.W}×${a.H} tiles · ${a.pal.size} colours · ${a.grid.count { it >= 0 }} tiles to clear", Modifier.padding(top = 8.dp), color = MUTED, fontSize = 13.sp)
                }
                Label("Name")
                BasicTextField(
                    draftName, { draftName = it.take(28) }, singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold), cursorBrush = SolidColor(ACCENT),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PANEL).padding(14.dp)
                )
                Label("Size")
                Seg(listOf(24 to "Small", 32 to "Medium", 40 to "Large", 48 to "XL"), draftSize) { draftSize = it; rebuildDraft() }
                Label("Colours")
                Seg(listOf(4 to "4", 5 to "5", 6 to "6", 8 to "8"), draftColors) { draftColors = it; rebuildDraft() }
                Label("Difficulty")
                Seg(listOf(0 to "Relaxed", 1 to "Normal", 2 to "Hard"), draftDiff) { draftDiff = it; rebuildDraft() }
                Text(
                    when (draftDiff) { 0 -> "Most of the picture is reachable from the start."; 1 -> "Adds a frame and buries details a layer deeper."; else -> "Two frames and deep layers. Expect to need every slot." },
                    Modifier.padding(top = 6.dp), color = MUTED, fontSize = 13.sp
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("🎲 Reshuffle colours", Modifier.clip(RoundedCornerShape(10.dp)).clickable { draftSeed++; rebuildDraft() }.padding(8.dp), color = ACCENT, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                BigBtn("Save & play", if (draftBusy) "Converting…" else "The solver will check it can be won", enabled = draftArt != null && !draftBusy) { saveDraftAndPlay() }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // ---------------- game ----------------
    @Composable
    private fun GameScreen() {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().background(BG)) {
                Hud()
                if (tag == "Super Hard") {
                    Text("SUPER HARD", Modifier.fillMaxWidth().background(RED).padding(vertical = 3.dp), color = Color.White, fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 3.sp, textAlign = TextAlign.Center)
                }
                AndroidView({ view }, Modifier.weight(1f).fillMaxWidth())
                BoosterBar()
            }
            pickMode?.let {
                Row(
                    Modifier.align(Alignment.BottomCenter).offset(y = (-104).dp).clip(CircleShape).background(ACCENT).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Tap a cube in a slot to pick it up", color = Color(0xFF2A2000), fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                    Spacer(Modifier.width(10.dp))
                    Text("Cancel", Modifier.clip(CircleShape).background(Color(0x26000000)).clickable { pickMode = null; view.pickMode = false }.padding(horizontal = 10.dp, vertical = 4.dp), fontWeight = FontWeight.ExtraBold, color = Color(0xFF2A2000))
                }
            }
        }
    }

    @Composable
    private fun Hud() {
        val custom = lv?.custom != null
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn("‹") { goMenu() }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(if (custom) lv!!.name else "Level $levelNo", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, maxLines = 1)
                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tag.uppercase(),
                        Modifier.clip(CircleShape).background(when (tag) { "Hard" -> Color(0xFFE08A2E); "Super Hard" -> RED; "Custom" -> Color(0xFF6C5BD6); else -> Color(0xFF3F8F5D) }).padding(horizontal = 9.dp, vertical = 2.dp),
                        color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(PANEL)) {
                        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(ACCENT))
                    }
                    Spacer(Modifier.width(12.dp))
                }
            }
            CoinPill()
            Spacer(Modifier.width(10.dp))
            IconBtn("⚙") { panel = Panel.Settings; view.paused = true }
        }
    }

    // ---------------- shared bits ----------------
    @Composable
    private fun TopBar(title: String, back: () -> Unit) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn("‹", back)
            Text(title, Modifier.padding(start = 12.dp).weight(1f), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
            CoinPill()
        }
    }

    @Composable
    private fun IconBtn(icon: String, onClick: () -> Unit) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(PANEL).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Text(icon, color = Color.White, fontSize = if (icon == "‹") 26.sp else 20.sp, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    private fun CoinPill() {
        Row(Modifier.clip(CircleShape).background(PANEL).padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Coin(18); Spacer(Modifier.width(6.dp)); Text("$coins", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
        }
    }

    @Composable
    private fun BigBtn(title: String, sub: String, alt: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(if (!enabled) PANEL else if (alt) Color(0xFF3A4466) else ACCENT)
                .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, color = if (alt || !enabled) Color.White else Color(0xFF3A2A00), fontWeight = FontWeight.Black, fontSize = 22.sp)
            Text(sub, color = if (alt || !enabled) MUTED else Color(0xFF6A5200), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }

    @Composable
    private fun SmallBtn(label: String, modifier: Modifier, onClick: () -> Unit) {
        Text(label, modifier.clip(RoundedCornerShape(16.dp)).background(PANEL).clickable(onClick = onClick).padding(14.dp), color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }

    @Composable
    private fun Label(t: String) { Text(t.uppercase(), Modifier.padding(top = 16.dp, bottom = 6.dp), color = MUTED, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, letterSpacing = 1.sp) }

    @Composable
    private fun Seg(options: List<Pair<Int, String>>, sel: Int, onSel: (Int) -> Unit) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PANEL).padding(4.dp)) {
            for ((v, l) in options) {
                Text(
                    l, Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (v == sel) ACCENT else Color.Transparent).clickable { if (v != sel) onSel(v) }.padding(vertical = 10.dp),
                    color = if (v == sel) Color(0xFF3A2A00) else Color.White, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center
                )
            }
        }
    }

    @Composable
    private fun Coin(size: Int) {
        Box(Modifier.size(size.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Color(0xFFFFE9A3), Color(0xFFF5B81F), Color(0xFFC98A0B)))))
    }

    @Composable
    private fun Dots(n: Int, size: Int, off: Color = Color(0xFFD9CFBD)) {
        Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in 0 until 5) Box(Modifier.size(size.dp).clip(CircleShape).background(if (i < n) GREEN else off))
        }
    }

    @Composable
    private fun Picture(w: Int, h: Int, grid: IntArray, pal: List<String>, modifier: Modifier) {
        val cols = pal.map { Color(android.graphics.Color.parseColor(it)) }
        Canvas(modifier.aspectRatio(w.toFloat() / h).clip(RoundedCornerShape(8.dp)).background(Color(0xFFEFE3C8))) {
            val px = size.width / w
            for (i in grid.indices) if (grid[i] >= 0) drawRect(cols[grid[i]], Offset((i % w) * px, (i / w) * px), Size(px + .5f, px + .5f))
        }
    }

    @Composable
    private fun BoosterBar() {
        uiTick
        val L = boosterLevel()
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (b in BOOSTERS) {
                val locked = L < b.unlock
                val n = save.boosters[b.k] ?: 0
                Box(Modifier.weight(1f)) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(16.dp)).background(PANEL)
                            .then(if (pickMode == b) Modifier.border(3.dp, ACCENT, RoundedCornerShape(16.dp)) else Modifier)
                            .clickable { useBooster(b) }.padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(b.icon, fontSize = 22.sp, color = Color.White.copy(alpha = if (locked) .4f else 1f))
                        Text(b.name, color = MUTED.copy(alpha = if (locked) .5f else 1f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        when {
                            locked -> Text("Lv ${b.unlock}", color = MUTED.copy(alpha = .6f), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                            n > 0 -> Text("Use", color = GREEN, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                            else -> Row(verticalAlignment = Alignment.CenterVertically) { Coin(11); Spacer(Modifier.width(3.dp)); Text("${b.price}", color = ACCENT, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold) }
                        }
                    }
                    if (!locked && n > 0) {
                        Text("$n", Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-6).dp).clip(CircleShape).background(GREEN).padding(horizontal = 7.dp, vertical = 2.dp), color = Color(0xFF10331D), fontWeight = FontWeight.Black, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    @Composable
    private fun ToastView() {
        val msg = toast ?: return
        LaunchedEffect(toastId) { delay(3200); toast = null }
        Box(Modifier.fillMaxSize().padding(top = 70.dp), contentAlignment = Alignment.TopCenter) {
            Text(msg, Modifier.widthIn(max = 340.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xEB141826)).padding(horizontal = 14.dp, vertical = 9.dp), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
    }

    // ---------------- panels ----------------
    @Composable
    private fun Overlay(p: Panel) {
        Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(enabled = true) {}, contentAlignment = Alignment.Center) {
            Column(
                Modifier.padding(18.dp).widthIn(max = 400.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(CARD).verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (p) {
                    Panel.Loading -> { LoadingAnts(); Title("One moment…"); Body(loadingText) }
                    Panel.Settings -> SettingsCard()
                    Panel.Gallery -> GalleryCard()
                    is Panel.Tutorial -> {
                        Text(p.b.icon, fontSize = 56.sp); Title("New booster: ${p.b.name}"); Body(p.b.desc)
                        Body("You got 2 free! After that it costs ${p.b.price} coins.")
                        Btn("Got it") { closePanel(); checkTutorials() }
                    }
                    Panel.Jam -> JamCard()
                    is Panel.Win -> WinCard(p)
                    Panel.Vacuum -> VacuumCard()
                    is Panel.Confirm -> {
                        Title(p.text)
                        Btn(p.yes) { panel = null; p.action() }
                        Btn("Cancel", alt = true) { panel = null }
                    }
                }
            }
        }
    }

    @Composable
    private fun LoadingAnts() {
        val tr = rememberInfiniteTransition(label = "load")
        val a by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "a")
        Canvas(Modifier.size(90.dp)) {
            drawCircle(Color(0xFFE9E1D1), size.minDimension / 2 - 6, style = Stroke(6f))
            for (k in 0 until 3) {
                val ang = (a + k / 3f) * 2 * PI.toFloat()
                val r = size.minDimension / 2 - 6
                drawAntIcon(center.x + cos(ang) * r, center.y + sin(ang) * r, 22f, a * 40 + k)
            }
        }
    }

    @Composable private fun Title(t: String) { Text(t, color = CARD_INK, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center) }
    @Composable private fun Body(t: String) { Text(t, Modifier.padding(vertical = 6.dp), color = CARD_MUTED, fontSize = 15.sp, textAlign = TextAlign.Center) }

    @Composable
    private fun Btn(label: String, alt: Boolean = false, onClick: () -> Unit) {
        Text(
            label,
            Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (alt) Color(0xFFE9E1D1) else ACCENT).clickable(onClick = onClick).padding(13.dp),
            color = if (alt) Color(0xFF4A4033) else Color(0xFF3A2A00), fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, textAlign = TextAlign.Center
        )
    }

    @Composable
    private fun SettingRow(label: String, on: Boolean, set: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), color = CARD_INK, fontWeight = FontWeight.Bold)
            Switch(on, set, colors = SwitchDefaults.colors(checkedTrackColor = GREEN))
        }
    }

    @Composable
    private fun SettingsCard() {
        uiTick
        Title("Settings")
        SettingRow("Sound", save.sound) { save.sound = it; sound.soundOn = it; persist() }
        SettingRow("Music", save.music) { save.music = it; sound.musicOn = it; persist() }
        SettingRow("Vibration", save.vib) { save.vib = it; persist() }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Game speed", Modifier.weight(1f), color = CARD_INK, fontWeight = FontWeight.Bold)
            Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFFE9E1D1)).padding(3.dp)) {
                for (fast in listOf(false, true)) {
                    Text(
                        if (fast) "2×" else "1×",
                        Modifier.clip(RoundedCornerShape(9.dp)).background(if (save.fast == fast) Color.White else Color.Transparent)
                            .clickable { save.fast = fast; view.fast = fast; persist() }.padding(horizontal = 14.dp, vertical = 6.dp),
                        color = CARD_INK, fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
        SettingRow("Debug view (reachable tiles + solver)", debug) { debug = it; view.debug = it }
        if (screen == Screen.GAME) {
            Btn("Restart", alt = true) { retry() }
            Btn("Main menu", alt = true) { goMenu() }
        }
        Btn("Close") { closePanel() }
    }

    @Composable
    private fun GalleryCard() {
        Title("Gallery")
        Body("Every picture you have revealed.")
        if (save.gallery.isEmpty()) Body("Finish a level to fill your gallery.")
        for (row in save.gallery.chunked(3)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (g in row) {
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Color(0xFFEFE3C8)).padding(6.dp)) {
                        Picture(g.w, g.h, g.grid(), g.p, Modifier.fillMaxWidth())
                        Text("Lv ${g.l} · ${g.n}", color = CARD_MUTED, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Btn("Close") { panel = if (screen == Screen.GAME) Panel.Settings else null }
    }

    @Composable
    private fun JamCard() {
        val e = view.engine ?: return
        val b = BOOSTERS[0]
        val have = save.boosters["slot"] ?: 0
        Text("🐜", fontSize = 56.sp)
        Title("Colony Jammed")
        Body("Every slot is full and no ant can reach a tile of its colour.")
        if (e.slotCount < MAX_SLOTS) {
            Btn(if (have > 0) "+1 slot · use 1 Extra Slot" else "+1 slot · ${b.price} coins") {
                if (!canPay(b)) { showToast("Not enough coins."); sound.shake() } else { pay(b); e.extraSlot(); view.refreshLayout(); closePanel(); sound.land() }
            }
        }
        Btn("Retry", alt = true) { retry() }
        Btn("Main menu", alt = true) { goMenu() }
    }

    @Composable
    private fun WinCard(p: Panel.Win) {
        val l = lv ?: return
        Title("Picture revealed!")
        Text(if (p.three) "★★★" else "★★☆", color = Color(0xFFF5B81F), fontSize = 38.sp, letterSpacing = 6.sp)
        Picture(l.W, l.H, l.grid, l.palette, Modifier.padding(vertical = 8.dp).widthIn(max = 220.dp).fillMaxWidth())
        Text(if (l.custom != null) l.name else "${l.name} · Level ${l.L}", color = CARD_MUTED, fontWeight = FontWeight.Bold)
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Coin(18); Spacer(Modifier.width(6.dp)); Text("+${p.coins}", color = CARD_INK, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            p.gift?.let { Spacer(Modifier.width(14.dp)); Text("${it.name} +1", color = CARD_INK, fontWeight = FontWeight.ExtraBold) }
        }
        if (l.custom == null) { if (p.chest) Body("🎁 Treetop Mission complete! The chest held 100 coins.") else Dots(save.streak, 12) }
        if (!p.three) Body("Win without boosters for 3 stars.")
        if (l.custom != null) {
            Btn("My Pictures") { panel = null; view.paused = true; screen = Screen.PUZZLES }
        } else {
            Btn("Next level") { panel = null; startLevel(save.level) }
            Btn("Main menu", alt = true) { goMenu() }
        }
    }

    @Composable
    private fun VacuumCard() {
        val e = view.engine ?: return
        val l = lv ?: return
        Text("🌀", fontSize = 56.sp)
        Title("Vacuum")
        Body("Pick a colour to remove from the board and the tray.")
        for (row in e.colorsLeft().chunked(5)) {
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (c in row) {
                    Box(Modifier.size(50.dp).clip(RoundedCornerShape(14.dp)).background(Color(android.graphics.Color.parseColor(l.palette[c]))).clickable {
                        closePanel(); pay(BOOSTERS[3]); e.vacuum(c); view.refreshLayout(); sound.win(); vib(30)
                    })
                }
            }
        }
        Btn("Cancel", alt = true) { closePanel() }
    }
}
