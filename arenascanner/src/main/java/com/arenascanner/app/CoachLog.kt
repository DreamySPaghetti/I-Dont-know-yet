package com.arenascanner.app

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Every coach answer with its screenshot, so a whole match can be exported as one zip. */
object CoachLog {
    private fun dir(ctx: Context) = File(ctx.filesDir, "coach_log").apply { mkdirs() }
    private fun file(ctx: Context) = File(dir(ctx), "log.jsonl")

    @Synchronized fun add(ctx: Context, time: Long, screenshot: ByteArray?, entry: JSONObject) {
        screenshot?.let { File(dir(ctx), "$time.jpg").writeBytes(it) }
        file(ctx).appendText(entry.put("time", time).put("type", "answer").put("image", "$time.jpg").toString() + "\n")
    }

    /** Your 👍 or ✏️ for the answer given at [time]. */
    @Synchronized fun feedback(ctx: Context, time: Long, good: Boolean, correct: String) {
        file(ctx).appendText(JSONObject().put("time", time).put("type", "feedback").put("good", good).put("correct", correct).toString() + "\n")
    }

    @Synchronized fun count(ctx: Context): Int =
        if (!file(ctx).exists()) 0 else file(ctx).readLines().count { "\"type\":\"answer\"" in it }

    @Synchronized fun clear(ctx: Context) { dir(ctx).listFiles()?.forEach { it.delete() } }

    @Synchronized fun exportZip(ctx: Context, out: java.io.OutputStream) {
        java.util.zip.ZipOutputStream(out).use { z ->
            dir(ctx).listFiles()?.sortedBy { it.name }?.forEach { f ->
                z.putNextEntry(java.util.zip.ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
    }
}
