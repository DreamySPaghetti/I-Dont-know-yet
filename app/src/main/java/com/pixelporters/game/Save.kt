package com.pixelporters.game

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class GalleryItem(val l: Int, val n: String, val w: Int, val h: Int, val p: List<String>, val d: String) {
    fun grid() = IntArray(d.length) { if (d[it] == '.') -1 else d[it].toString().toInt(36) }
}

/** A player-made puzzle: the finished pixel map plus the settings needed to rebuild the same level. */
class CustomPuzzle(val id: String, var name: String, val w: Int, val h: Int, val p: List<String>, val d: String, val difficulty: Int, val seed: Int, var stars: Int = 0) {
    fun grid() = IntArray(d.length) { if (d[it] == '.') -1 else d[it].toString().toInt(36) }
    fun art() = Art(w, h, grid(), p.toMutableList(), name)
    companion object {
        fun encode(grid: IntArray) = buildString { for (v in grid) append(if (v < 0) '.' else v.toString(36)[0]) }
    }
}

class SaveData {
    var level = 1
    var coins = 50
    val boosters = mutableMapOf("slot" to 0, "shuffle" to 0, "pickup" to 0, "vacuum" to 0)
    val stars = mutableMapOf<Int, Int>()
    val gallery = mutableListOf<GalleryItem>()
    val seen = mutableSetOf<String>()
    var sound = true
    var music = true
    var vib = true
    var fast = false
    var streak = 0
    val puzzles = mutableListOf<CustomPuzzle>()

    fun toJson(): String {
        val o = JSONObject()
        o.put("level", level); o.put("coins", coins); o.put("streak", streak)
        o.put("boosters", JSONObject(boosters as Map<*, *>))
        o.put("stars", JSONObject(stars.mapKeys { it.key.toString() } as Map<*, *>))
        o.put("gallery", JSONArray().apply { gallery.forEach { g -> put(JSONObject().put("l", g.l).put("n", g.n).put("w", g.w).put("h", g.h).put("p", JSONArray(g.p)).put("d", g.d)) } })
        o.put("seen", JSONArray(seen.toList()))
        o.put("puzzles", JSONArray().apply { puzzles.forEach { c -> put(JSONObject().put("id", c.id).put("n", c.name).put("w", c.w).put("h", c.h).put("p", JSONArray(c.p)).put("d", c.d).put("diff", c.difficulty).put("seed", c.seed).put("stars", c.stars)) } })
        o.put("settings", JSONObject().put("sound", sound).put("music", music).put("vib", vib).put("fast", fast))
        return o.toString()
    }

    companion object {
        private const val KEY = "antTrail.v1"
        fun load(ctx: Context): SaveData {
            val s = SaveData()
            val raw = ctx.getSharedPreferences("game", Context.MODE_PRIVATE).getString(KEY, null) ?: return s
            try {
                val o = JSONObject(raw)
                s.level = o.optInt("level", 1).coerceAtLeast(1); s.coins = o.optInt("coins", 50); s.streak = o.optInt("streak", 0)
                o.optJSONObject("boosters")?.let { b -> for (k in s.boosters.keys.toList()) s.boosters[k] = b.optInt(k, 0) }
                o.optJSONObject("stars")?.let { st -> st.keys().forEach { k -> s.stars[k.toInt()] = st.optInt(k) } }
                o.optJSONArray("gallery")?.let { a ->
                    for (i in 0 until a.length()) {
                        val g = a.getJSONObject(i); val p = g.getJSONArray("p")
                        s.gallery.add(GalleryItem(g.getInt("l"), g.getString("n"), g.getInt("w"), g.getInt("h"), List(p.length()) { p.getString(it) }, g.getString("d")))
                    }
                }
                o.optJSONArray("seen")?.let { a -> for (i in 0 until a.length()) s.seen.add(a.getString(i)) }
                o.optJSONArray("puzzles")?.let { a ->
                    for (i in 0 until a.length()) {
                        val c = a.getJSONObject(i); val p = c.getJSONArray("p")
                        s.puzzles.add(CustomPuzzle(c.getString("id"), c.getString("n"), c.getInt("w"), c.getInt("h"), List(p.length()) { p.getString(it) }, c.getString("d"), c.getInt("diff"), c.getInt("seed"), c.optInt("stars")))
                    }
                }
                o.optJSONObject("settings")?.let { st ->
                    s.sound = st.optBoolean("sound", true); s.music = st.optBoolean("music", true); s.vib = st.optBoolean("vib", true); s.fast = st.optBoolean("fast", false)
                }
            } catch (_: Exception) {}
            return s
        }
    }

    fun persist(ctx: Context) {
        try { ctx.getSharedPreferences("game", Context.MODE_PRIVATE).edit().putString(KEY, toJson()).apply() } catch (_: Exception) {}
    }
}
