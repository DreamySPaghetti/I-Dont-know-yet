package com.arenascanner.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One thing the coach learned from you: the situation, what it said, and the play you confirmed or typed. */
data class Lesson(
    val time: Long,
    val phase: String,
    val cards: List<String>,
    val board: String,
    val answer: String,
    val correct: String,
    val good: Boolean,
)

/** Picks the past lessons most like the current board. Pure, so it's unit-tested. */
object LessonMatch {
    fun score(l: Lesson, phase: String, cards: Set<String>): Double {
        val lc = l.cards.toSet()
        val union = (lc + cards).size
        val overlap = if (union == 0) 0.0 else (lc intersect cards).size.toDouble() / union
        return overlap * 2 + if (l.phase == phase) 1.0 else 0.0
    }

    fun best(all: List<Lesson>, phase: String, cards: Set<String>, n: Int = 3): List<Lesson> =
        all.map { it to score(it, phase, cards) }
            .filter { it.second >= 1.3 } // same step and some shared cards, or many shared cards
            .sortedWith(compareByDescending<Pair<Lesson, Double>> { it.second }.thenByDescending { it.first.time })
            .take(n).map { it.first }
}

/** Lessons saved on the phone (lessons.jsonl) plus the screenshot of each, for exporting as training data. */
object Lessons {
    private fun file(ctx: Context) = File(ctx.filesDir, "lessons.jsonl")
    fun imageDir(ctx: Context) = File(ctx.filesDir, "lesson_images").apply { mkdirs() }

    @Synchronized fun all(ctx: Context): List<Lesson> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return f.readLines().filter { it.isNotBlank() }.mapNotNull { runCatching { fromJson(JSONObject(it)) }.getOrNull() }
    }

    @Synchronized fun add(ctx: Context, l: Lesson, image: ByteArray?, prompt: String) {
        image?.let { File(imageDir(ctx), "${l.time}.jpg").writeBytes(it) }
        file(ctx).appendText(toJson(l).put("prompt", prompt).put("image", "lesson_images/${l.time}.jpg").toString() + "\n")
    }

    @Synchronized fun clear(ctx: Context) {
        file(ctx).delete()
        imageDir(ctx).listFiles()?.forEach { it.delete() }
    }

    /** Writes lessons.jsonl and the screenshots into a zip (for fine-tuning in Colab later). */
    @Synchronized fun exportZip(ctx: Context, out: java.io.OutputStream) {
        java.util.zip.ZipOutputStream(out).use { z ->
            val f = file(ctx)
            if (f.exists()) { z.putNextEntry(java.util.zip.ZipEntry("lessons.jsonl")); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
            imageDir(ctx).listFiles()?.forEach { img ->
                z.putNextEntry(java.util.zip.ZipEntry("lesson_images/${img.name}")); img.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
    }

    private fun toJson(l: Lesson) = JSONObject().put("time", l.time).put("phase", l.phase).put("cards", JSONArray(l.cards))
        .put("board", l.board).put("answer", l.answer).put("correct", l.correct).put("good", l.good)

    private fun fromJson(o: JSONObject): Lesson {
        val c = o.getJSONArray("cards")
        return Lesson(o.getLong("time"), o.getString("phase"), (0 until c.length()).map(c::getString),
            o.optString("board"), o.optString("answer"), o.getString("correct"), o.optBoolean("good"))
    }
}
