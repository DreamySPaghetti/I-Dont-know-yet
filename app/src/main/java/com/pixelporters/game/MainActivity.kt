package com.pixelporters.game

import android.content.Context
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

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

sealed class Panel {
    object Home : Panel()
    object Loading : Panel()
    object Settings : Panel()
    object Gallery : Panel()
    class Tutorial(val b: BoosterDef) : Panel()
    object Jam : Panel()
    class Win(val coins: Int, val three: Boolean, val gift: BoosterDef?, val chest: Boolean) : Panel()
    object Vacuum : Panel()
}

class MainActivity : ComponentActivity() {
    private lateinit var save: SaveData
    private lateinit var view: GameView
    private lateinit var sound: Sound
    private val gen = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var lv: Level? = null
    private var panel by mutableStateOf<Panel?>(Panel.Home)
    private var levelNo by mutableIntStateOf(1)
    private var tag by mutableStateOf("Normal")
    private var coins by mutableIntStateOf(0)
    private var progress by mutableFloatStateOf(0f)
    private var uiTick by mutableIntStateOf(0)
    private var toast by mutableStateOf<String?>(null)
    private var toastId by mutableIntStateOf(0)
    private var pickMode by mutableStateOf<BoosterDef?>(null)
    private var debug by mutableStateOf(false)

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

    private fun startLevel(n: Int) {
        panel = Panel.Loading
        view.paused = true
        gen.execute {
            val level = genLevel(n)
            main.post {
                lv = level; levelNo = n; tag = level.tag
                save.level = n; persist()
                pickMode = null; view.pickMode = false
                view.start(level); view.debug = debug
                panel = null; view.paused = false
                level.hint?.let { showToast(it) }
                checkTutorials()
                prefetch(n + 1)
            }
        }
    }

    private fun checkTutorials() {
        val L = lv?.L ?: return
        for (b in BOOSTERS) if (L >= b.unlock && b.k !in save.seen) {
            save.seen.add(b.k); save.boosters[b.k] = (save.boosters[b.k] ?: 0) + 2; persist()
            panel = Panel.Tutorial(b); view.paused = true
            return
        }
    }

    private fun closePanel() { panel = null; view.paused = false }

    // ---------------- game events ----------------
    private fun onGameEvent(ev: Ev) {
        when (ev) {
            is Ev.Eat -> if (!ev.quiet) sound.pop()
            is Ev.Tap -> { sound.click(); vib(12) }
            is Ev.Land -> sound.land()
            is Ev.Deliver -> sound.tick()
            is Ev.Done -> { sound.done(); view.refreshLayout() }
            is Ev.Won -> onWin()
            is Ev.Jam -> { sound.jam(); vib(200); save.streak = 0; persist(); main.postDelayed({ panel = Panel.Jam; view.paused = true }, 350) }
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
        val L = l.L
        val three = !e.used
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
        val d = buildString { for (v in l.grid) append(if (v < 0) '.' else v.toString(36)[0]) }
        save.gallery.removeAll { it.l == L }
        save.gallery.add(0, GalleryItem(L, l.name, l.W, l.H, l.palette, d))
        while (save.gallery.size > 200) save.gallery.removeAt(save.gallery.size - 1)
        save.level = L + 1
        persist()
        sound.win(); vib(60); view.celebrate()
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

    private fun useBooster(b: BoosterDef) {
        val L = lv?.L ?: return
        val e = view.engine ?: return
        if (L < b.unlock) { showToast("${b.name} unlocks at level ${b.unlock}."); return }
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

    // ---------------- UI ----------------
    @Composable
    private fun Root() {
        BackHandler(panel != null && panel !is Panel.Loading && panel !is Panel.Home) {
            when (panel) {
                is Panel.Gallery -> panel = if (lv == null) Panel.Home else Panel.Settings
                is Panel.Jam, is Panel.Win -> {}
                else -> closePanel()
            }
        }
        Box(Modifier.fillMaxSize().background(Color(0xFF151A28)).systemBarsPadding()) {
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
            ToastView()
            panel?.let { p -> Overlay(p) }
        }
    }

    @Composable
    private fun Hud() {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Level $levelNo", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tag.uppercase(),
                        Modifier.clip(CircleShape).background(if (tag == "Hard") Color(0xFFE08A2E) else if (tag == "Super Hard") RED else Color(0xFF3F8F5D)).padding(horizontal = 9.dp, vertical = 2.dp),
                        color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(PANEL)) {
                        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(ACCENT))
                    }
                    Spacer(Modifier.width(12.dp))
                }
            }
            Row(Modifier.clip(CircleShape).background(PANEL).padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Coin(18); Spacer(Modifier.width(6.dp)); Text("$coins", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(PANEL).clickable { if (lv != null) { panel = Panel.Settings; view.paused = true } }, contentAlignment = Alignment.Center) {
                Text("⚙", color = Color.White, fontSize = 20.sp)
            }
        }
    }

    @Composable
    private fun Coin(size: Int) {
        Box(Modifier.size(size.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Color(0xFFFFE9A3), Color(0xFFF5B81F), Color(0xFFC98A0B)))))
    }

    @Composable
    private fun BoosterBar() {
        uiTick
        val L = lv?.L ?: levelNo
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

    @Composable
    private fun Overlay(p: Panel) {
        Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(enabled = true) {}, contentAlignment = Alignment.Center) {
            Column(
                Modifier.padding(18.dp).widthIn(max = 400.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(CARD).verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (p) {
                    Panel.Home -> HomeCard()
                    Panel.Loading -> { Title("Preparing level…"); Body("Laying out a fresh trail for your ants.") }
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
                }
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
    private fun Dots(n: Int, size: Int) {
        Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in 0 until 5) Box(Modifier.size(size.dp).clip(CircleShape).background(if (i < n) GREEN else Color(0xFFD9CFBD)))
        }
    }

    @Composable
    private fun HomeCard() {
        uiTick
        Text("🐜", fontSize = 56.sp)
        Title("Ant Trail")
        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Coin(18); Spacer(Modifier.width(6.dp)); Text("$coins", color = CARD_INK, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        }
        Text("Treetop Mission", Modifier.padding(top = 8.dp), color = CARD_INK, fontWeight = FontWeight.ExtraBold)
        Dots(save.streak, 16)
        Body("Win 5 levels in a row without jamming to open a 100-coin chest.")
        Btn("Play Level ${save.level}") { startLevel(save.level) }
        Btn("Gallery (${save.gallery.size})", alt = true) { panel = Panel.Gallery }
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
        Btn("Gallery (${save.gallery.size})", alt = true) { panel = Panel.Gallery }
        Btn("Restart level", alt = true) { lv?.let { startLevel(it.L) } }
        Btn("Home", alt = true) { panel = Panel.Home; view.paused = true }
        Btn("Close") { closePanel() }
    }

    @Composable
    private fun Picture(w: Int, h: Int, grid: IntArray, pal: List<String>, modifier: Modifier) {
        val cols = pal.map { Color(android.graphics.Color.parseColor(it)) }
        Canvas(modifier.aspectRatio(w.toFloat() / h).clip(RoundedCornerShape(10.dp)).background(Color(0xFFEFE3C8))) {
            val px = size.width / w
            for (i in grid.indices) if (grid[i] >= 0) drawRect(cols[grid[i]], Offset((i % w) * px, (i / w) * px), Size(px + .5f, px + .5f))
        }
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
        Btn("Close") { panel = if (lv == null) Panel.Home else Panel.Settings }
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
        Btn("Retry", alt = true) { lv?.let { startLevel(it.L) } }
    }

    @Composable
    private fun WinCard(p: Panel.Win) {
        val l = lv ?: return
        Title("Picture revealed!")
        Text(if (p.three) "★★★" else "★★☆", color = Color(0xFFF5B81F), fontSize = 38.sp, letterSpacing = 6.sp)
        Picture(l.W, l.H, l.grid, l.palette, Modifier.padding(vertical = 8.dp).widthIn(max = 220.dp).fillMaxWidth())
        Text("${l.name} · Level ${l.L}", color = CARD_MUTED, fontWeight = FontWeight.Bold)
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Coin(18); Spacer(Modifier.width(6.dp)); Text("+${p.coins}", color = CARD_INK, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            p.gift?.let { Spacer(Modifier.width(14.dp)); Text("${it.name} +1", color = CARD_INK, fontWeight = FontWeight.ExtraBold) }
        }
        if (p.chest) Body("🎁 Treetop Mission complete! The chest held 100 coins.") else Dots(save.streak, 12)
        if (!p.three) Body("Win without boosters for 3 stars.")
        Btn("Next level") { panel = Panel.Home }
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
