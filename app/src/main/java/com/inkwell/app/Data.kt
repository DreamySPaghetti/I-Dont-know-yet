package com.inkwell.app

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Group(val id: String, val name: String)

data class Sheet(
    val id: String,
    val group: String,
    val text: String = "",
    val goal: Int = 0,
    val updated: Long = System.currentTimeMillis()
) {
    val title: String
        get() = text.lineSequence().firstOrNull { it.isNotBlank() }
            ?.trimStart('#', ' ', '>')?.takeIf { it.isNotBlank() }?.take(60) ?: "Untitled"
}

fun words(t: String) = Regex("[\\p{L}\\p{N}']+").findAll(t).count()

class Store(private val ctx: Context) {
    val groups = mutableStateListOf<Group>()
    val sheets = mutableStateListOf<Sheet>()
    private val file get() = File(ctx.filesDir, "library.json")

    init {
        runCatching { load() }
        if (groups.isEmpty()) addGroup("Inbox")
    }

    private fun load() {
        if (!file.exists()) return
        val o = JSONObject(file.readText())
        val g = o.getJSONArray("groups")
        for (i in 0 until g.length()) g.getJSONObject(i).let { groups += Group(it.getString("id"), it.getString("name")) }
        val s = o.getJSONArray("sheets")
        for (i in 0 until s.length()) s.getJSONObject(i).let {
            sheets += Sheet(it.getString("id"), it.getString("group"), it.getString("text"), it.getInt("goal"), it.getLong("updated"))
        }
    }

    fun save() {
        val o = JSONObject()
        o.put("groups", JSONArray().apply { groups.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
        o.put("sheets", JSONArray().apply {
            sheets.forEach {
                put(JSONObject().put("id", it.id).put("group", it.group).put("text", it.text).put("goal", it.goal).put("updated", it.updated))
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

    fun addGroup(name: String) {
        groups += Group(UUID.randomUUID().toString(), name)
        save()
    }

    fun addSheet(group: String): Sheet {
        val s = Sheet(UUID.randomUUID().toString(), group)
        sheets += s
        save()
        return s
    }

    fun deleteGroup(id: String) {
        groups.removeAll { it.id == id }
        sheets.removeAll { it.group == id }
        if (groups.isEmpty()) addGroup("Inbox") else save()
    }

    fun deleteSheet(id: String) {
        sheets.removeAll { it.id == id }
        save()
    }
}
