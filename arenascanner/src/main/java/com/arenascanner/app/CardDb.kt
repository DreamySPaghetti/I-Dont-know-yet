package com.arenascanner.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The list of every card in Arena's Standard, with rarity, from Scryfall's free card database.
 * Downloaded once and kept on the phone; "Update card list" fetches it again after a new set.
 */
object CardDb {
    /** "standard" (default) or "all": every card on Arena (Historic, Alchemy, Brawl...). */
    fun pool(ctx: Context) = ctx.getSharedPreferences("coach", Context.MODE_PRIVATE).getString("card_pool", "standard")!!
    private fun query(ctx: Context) = if (pool(ctx) == "all") "game:arena" else "game:arena legal:standard"
    @Volatile private var cached: List<Card>? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "standard_cards.json")

    fun isReady(ctx: Context) = cached != null || file(ctx).exists()

    /** Days since the card list was downloaded (new sets need an update). */
    fun ageDays(ctx: Context): Int =
        if (!file(ctx).exists()) 0 else ((System.currentTimeMillis() - file(ctx).lastModified()) / 86_400_000L).toInt()

    fun load(ctx: Context): List<Card> {
        cached?.let { return it }
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        val list = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val faces = o.optJSONArray("faces")
            Card(o.getString("name"), o.getString("rarity"),
                if (faces == null) emptyList() else (0 until faces.length()).map(faces::getString),
                o.optString("rules"))
        }
        cached = list
        return list
    }

    /** Blocking download; call off the main thread. [progress] gets the running card count. */
    fun download(ctx: Context, progress: (Int) -> Unit): List<Card> {
        val out = ArrayList<Card>()
        val seenNames = HashSet<String>()
        var url: String? = "https://api.scryfall.com/cards/search?unique=cards&q=" +
            URLEncoder.encode(query(ctx), "UTF-8")
        while (url != null) {
            val page = getJson(url)
            val data = page.getJSONArray("data")
            for (i in 0 until data.length()) parse(data.getJSONObject(i)).let { if (seenNames.add(it.name)) out.add(it) }
            progress(out.size)
            url = if (page.optBoolean("has_more")) page.getString("next_page") else null
            // Scryfall limits search requests to about 2 per second.
            if (url != null) Thread.sleep(600)
        }
        val arr = JSONArray()
        for (c in out) arr.put(JSONObject().put("name", c.name).put("rarity", c.rarity).put("faces", JSONArray(c.faces)).put("rules", c.rules))
        file(ctx).writeText(arr.toString())
        cached = out
        return out
    }

    private fun parse(o: JSONObject): Card {
        val full = o.getString("name")
        val faces = o.optJSONArray("card_faces")?.let { f -> (0 until f.length()).map { f.getJSONObject(it).getString("name") } }
            ?: emptyList()
        // Arena lists double-faced cards by their front face.
        // Alchemy rebalanced cards are "A-Name" on Scryfall but show as "Name" in Arena.
        val name = (if (faces.isNotEmpty()) faces[0] else full).removePrefix("A-")
        return Card(name, rarityCode(o.optString("rarity")), faces, rulesOf(o))
    }

    /** True once the saved list includes rules text (lists saved before the coach existed don't). */
    fun hasRules(ctx: Context) = load(ctx).any { it.rules.isNotEmpty() }

    /** "{1}{W} — Creature — Cat — Lifelink — 2/2"; double-faced cards list each face, separated by " // ". */
    private fun rulesOf(o: JSONObject): String {
        val faces = o.optJSONArray("card_faces")
        if (faces != null && o.optString("oracle_text").isEmpty())
            return (0 until faces.length()).joinToString(" // ") { faceRules(faces.getJSONObject(it), withName = true) }
        return faceRules(o, withName = false)
    }

    private fun faceRules(f: JSONObject, withName: Boolean): String {
        val parts = mutableListOf<String>()
        if (withName) parts += f.optString("name")
        for (k in listOf("mana_cost", "type_line", "oracle_text")) f.optString(k).takeIf { it.isNotEmpty() }?.let { parts += it.replace('\n', ' ') }
        if (f.has("power")) parts += "${f.optString("power")}/${f.optString("toughness")}"
        if (f.has("loyalty")) parts += "Loyalty ${f.optString("loyalty")}"
        return parts.joinToString(" — ")
    }

    fun rarityCode(r: String) = when (r) {
        "common" -> "C"; "uncommon" -> "U"; "rare" -> "R"; "mythic" -> "M"; else -> "?"
    }

    /** Fetches a page, waiting and retrying when Scryfall says "too many requests" (429). */
    private fun getJson(url: String): JSONObject {
        var wait = 2000L
        repeat(5) {
            try { return getOnce(url) } catch (e: RateLimited) {
                Thread.sleep(maxOf(wait, e.retryAfterMs)); wait *= 2
            }
        }
        throw IllegalStateException("Scryfall is busy right now. Wait a minute and tap Download card list again.")
    }

    private class RateLimited(val retryAfterMs: Long) : Exception()

    private fun getOnce(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "ArenaScanner/1.0 (personal collection app)")
        c.setRequestProperty("Accept", "application/json")
        c.connectTimeout = 15000; c.readTimeout = 30000
        try {
            if (c.responseCode == 429) throw RateLimited((c.getHeaderField("Retry-After")?.toLongOrNull() ?: 0) * 1000)
            if (c.responseCode != 200) throw IllegalStateException("Scryfall answered ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().readText())
        } finally { c.disconnect() }
    }
}
