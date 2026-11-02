package com.inkwell.app

import android.content.Context
import android.content.SharedPreferences
import android.text.format.DateUtils
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date
import java.util.UUID
import kotlin.reflect.KProperty

const val INBOX = "inbox"
const val WEEK = 7 * 86_400_000L

fun now() = System.currentTimeMillis()
fun newId() = UUID.randomUUID().toString()
fun fmt(n: Int) = "%,d".format(n)
fun relTime(t: Long) = DateUtils.getRelativeTimeSpanString(t, now(), DateUtils.MINUTE_IN_MILLIS).toString()
fun dateStr(t: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(t))

data class Group(val id: String, val name: String, val parent: String? = null)

data class Goal(val target: Int = 0, val unit: String = "words", val mode: String = "least", val deadline: Long = 0)

data class Sheet(
    val id: String,
    val group: String,
    val text: String = "",
    val created: Long = now(),
    val updated: Long = now(),
    val favorite: Boolean = false,
    val trashed: Long = 0,
    val keywords: List<String> = emptyList(),
    val notes: String = "",
    val goal: Goal = Goal()
) {
    val title: String
        get() = text.lineSequence().map { plainLine(it).trim() }.firstOrNull { it.isNotEmpty() }?.take(80) ?: "Untitled"
    val excerpt: String
        get() = text.lineSequence().map { plainLine(it).trim() }.filter { it.isNotEmpty() }.drop(1).take(4).joinToString(" ").take(180)
}

data class Counts(val words: Int, val chars: Int, val charsNoSpaces: Int, val sentences: Int, val paragraphs: Int) {
    val pages get() = if (words == 0) 0 else (words + 249) / 250
    val readMinutes get() = if (words == 0) 0 else maxOf(1, (words + 199) / 200)
    fun of(unit: String) = when (unit) {
        "chars" -> chars
        "sentences" -> sentences
        "paragraphs" -> paragraphs
        "pages" -> pages
        else -> words
    }
}

private val wordRx = Regex("[\\p{L}\\p{N}']+")
private val inlineCommentRx = Regex("\\+\\+.*?\\+\\+")

fun counts(t: String): Counts {
    val body = t.lines().filterNot { it.trimStart().startsWith("%%") }.joinToString("\n").replace(inlineCommentRx, "")
    return Counts(
        wordRx.findAll(body).count(),
        body.length,
        body.count { !it.isWhitespace() },
        body.split(Regex("[.!?]+\\s+|\\n+")).count { s -> s.any { it.isLetterOrDigit() } },
        body.lines().count { l -> l.any { it.isLetterOrDigit() } }
    )
}

val unitNames = linkedMapOf("words" to "words", "chars" to "characters", "sentences" to "sentences", "paragraphs" to "paragraphs", "pages" to "pages")

fun Goal.value(c: Counts) = c.of(unit)

fun Goal.state(c: Counts): Int {
    val v = c.of(unit)
    return when (mode) {
        "most" -> if (v > target) 2 else if (v >= target * 0.9) 1 else 0
        "about" -> if (v > target * 1.1) 2 else if (v >= target * 0.9) 1 else 0
        else -> if (v >= target) 1 else 0
    }
}

fun goalStatus(g: Goal, c: Counts): String {
    val v = g.value(c)
    val u = unitNames[g.unit]
    val st = g.state(c)
    val base = when (st) {
        2 -> "${fmt(v - g.target)} $u over"
        1 -> if (g.mode == "most") "Within the limit" else "Goal reached"
        else -> if (g.mode == "most") "${fmt(g.target - v)} $u left" else "${fmt(g.target - v)} $u to go"
    }
    if (g.deadline <= 0 || st != 0 || g.mode == "most") return base
    val days = (g.deadline - LocalDate.now().toEpochDay()).toInt()
    return when {
        days < 0 -> "$base · deadline passed"
        days == 0 -> "$base · due today"
        else -> "$base · ${fmt((g.target - v + days) / (days + 1))} a day for ${days + 1} days"
    }
}

class Pref<T>(
    private val sp: SharedPreferences,
    private val key: String,
    def: T,
    read: SharedPreferences.(String, T) -> T,
    private val write: SharedPreferences.Editor.(String, T) -> Unit
) {
    private var state by mutableStateOf(sp.read(key, def))
    operator fun getValue(r: Any?, p: KProperty<*>): T = state
    operator fun setValue(r: Any?, p: KProperty<*>, v: T) {
        state = v
        val e = sp.edit()
        e.write(key, v)
        e.apply()
    }
}

class Settings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun s(k: String, d: String) = Pref(sp, k, d, { x, y -> getString(x, y) ?: y }, { x, y -> putString(x, y) })
    private fun i(k: String, d: Int) = Pref(sp, k, d, { x, y -> getInt(x, y) }, { x, y -> putInt(x, y) })
    private fun f(k: String, d: Float) = Pref(sp, k, d, { x, y -> getFloat(x, y) }, { x, y -> putFloat(x, y) })
    private fun b(k: String, d: Boolean) = Pref(sp, k, d, { x, y -> getBoolean(x, y) }, { x, y -> putBoolean(x, y) })

    var theme by s("theme", "system")
    var font by s("font", "serif")
    var size by i("size", 19)
    var spacing by f("spacing", 1.6f)
    var width by i("width", 680)
    var typewriter by b("typewriter", false)
    var focusParagraph by b("focusParagraph", false)
    var sort by s("sort", "modified")
    var counter by s("counter", "words")
    var exportStyle by s("exportStyle", "classic")
}

class Store(private val ctx: Context) {
    val groups = mutableStateListOf<Group>()
    val sheets = mutableStateListOf<Sheet>()
    private val stats = ctx.getSharedPreferences("stats", Context.MODE_PRIVATE)
    var today by mutableIntStateOf(stats.getInt("d" + LocalDate.now(), 0))
        private set
    private val file get() = File(ctx.filesDir, "library.json")

    init {
        runCatching { load() }
        if (groups.none { it.id == INBOX }) {
            val old = groups.indexOfFirst { it.name == "Inbox" && it.parent == null }
            if (old >= 0) {
                val oid = groups[old].id
                groups[old] = Group(INBOX, "Inbox")
                for (i in sheets.indices) if (sheets[i].group == oid) sheets[i] = sheets[i].copy(group = INBOX)
            } else {
                groups.add(0, Group(INBOX, "Inbox"))
            }
            save()
        }
    }

    private fun load() {
        if (!file.exists()) return
        val o = JSONObject(file.readText())
        val g = o.getJSONArray("groups")
        for (i in 0 until g.length()) {
            val x = g.getJSONObject(i)
            groups += Group(x.getString("id"), x.getString("name"), x.optString("parent").ifEmpty { null })
        }
        val s = o.getJSONArray("sheets")
        for (i in 0 until s.length()) {
            val x = s.getJSONObject(i)
            val upd = x.optLong("updated", now())
            val gl = x.opt("goal")
            val goal = if (gl is JSONObject) {
                Goal(gl.optInt("target"), gl.optString("unit", "words"), gl.optString("mode", "least"), gl.optLong("deadline"))
            } else {
                Goal((gl as? Int) ?: 0)
            }
            val kw = x.optJSONArray("keywords")
            sheets += Sheet(
                x.getString("id"), x.getString("group"), x.optString("text"), x.optLong("created", upd), upd,
                x.optBoolean("favorite"), x.optLong("trashed"), List(kw?.length() ?: 0) { kw!!.getString(it) },
                x.optString("notes"), goal
            )
        }
    }

    fun save() {
        val o = JSONObject()
        o.put("groups", JSONArray().apply {
            groups.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("parent", it.parent ?: "")) }
        })
        o.put("sheets", JSONArray().apply {
            sheets.forEach { s ->
                put(
                    JSONObject().put("id", s.id).put("group", s.group).put("text", s.text)
                        .put("created", s.created).put("updated", s.updated).put("favorite", s.favorite)
                        .put("trashed", s.trashed).put("keywords", JSONArray(s.keywords)).put("notes", s.notes)
                        .put(
                            "goal", JSONObject().put("target", s.goal.target).put("unit", s.goal.unit)
                                .put("mode", s.goal.mode).put("deadline", s.goal.deadline)
                        )
                )
            }
        })
        val tmp = File(ctx.filesDir, "library.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
    }

    fun sheet(id: String) = sheets.firstOrNull { it.id == id }

    fun update(s: Sheet) {
        val i = sheets.indexOfFirst { it.id == s.id }
        if (i >= 0) sheets[i] = s
    }

    fun edit(ids: Collection<String>, f: (Sheet) -> Sheet) {
        for (i in sheets.indices) if (sheets[i].id in ids) sheets[i] = f(sheets[i])
        save()
    }

    fun addSheet(group: String): Sheet {
        val s = Sheet(newId(), group)
        sheets += s
        save()
        return s
    }

    fun addGroup(name: String, parent: String?) {
        groups += Group(newId(), name, parent)
        save()
    }

    fun renameGroup(id: String, name: String) {
        val i = groups.indexOfFirst { it.id == id }
        if (i >= 0) groups[i] = groups[i].copy(name = name)
        save()
    }

    fun children(parent: String?) = groups.filter { it.parent == parent && it.id != INBOX }.sortedBy { it.name.lowercase() }

    fun descendants(id: String): Set<String> = children(id).flatMap { descendants(it.id) + it.id }.toSet()

    fun tree(): List<Pair<Group, Int>> {
        val out = mutableListOf<Pair<Group, Int>>()
        groups.firstOrNull { it.id == INBOX }?.let { out += it to 0 }
        fun walk(p: String?, d: Int) {
            children(p).forEach { out += it to d; walk(it.id, d + 1) }
        }
        walk(null, 0)
        return out
    }

    fun deleteGroup(id: String) {
        val ids = descendants(id) + id
        edit(sheets.filter { it.group in ids }.map { it.id }) { it.copy(group = INBOX, trashed = now()) }
        groups.removeAll { it.id in ids }
        save()
    }

    fun trash(ids: Collection<String>) = edit(ids) { it.copy(trashed = now()) }

    fun restore(ids: Collection<String>) = edit(ids) { s ->
        s.copy(trashed = 0, group = if (groups.any { it.id == s.group }) s.group else INBOX)
    }

    fun deleteForever(ids: Collection<String>) {
        val set = ids.toSet()
        sheets.removeAll { it.id in set }
        save()
    }

    fun glue(ids: List<String>) {
        val parts = ids.mapNotNull { sheet(it) }
        if (parts.size < 2) return
        update(
            parts[0].copy(
                text = parts.joinToString("\n\n") { it.text.trimEnd() },
                updated = now(),
                keywords = parts.flatMap { it.keywords }.distinct()
            )
        )
        trash(parts.drop(1).map { it.id })
    }

    fun duplicate(id: String) {
        sheet(id)?.let { sheets += it.copy(id = newId(), created = now(), updated = now()) }
        save()
    }

    fun logWords(n: Int) {
        val k = "d" + LocalDate.now()
        today = stats.getInt(k, 0) + n
        stats.edit().putInt(k, today).apply()
    }
}
