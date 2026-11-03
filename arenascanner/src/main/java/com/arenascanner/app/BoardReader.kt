package com.arenascanner.app

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.text.Text

/** Turns a screenshot's recognised text into the coach's board and question. Shared by the overlay and the test screen. */
object BoardReader {
    fun seen(text: Text, bmp: Bitmap): List<CoachPrompt.Seen> {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        val rgb = { x: Int, y: Int -> px[y * w + x] and 0xFFFFFF }
        return text.textBlocks.flatMap { it.lines }.mapNotNull { l ->
            val r = l.boundingBox ?: return@mapNotNull null
            val cy = r.exactCenterY() / h
            // Only hand cards (bottom of my side) get the "playable" outline check.
            val glow = if (cy > 0.62f) CoachPrompt.glowFraction(rgb, w, h, r.left, r.top, r.right, r.bottom) else -1f
            CoachPrompt.Seen(l.text, r.exactCenterX() / w, cy, r.height().toFloat() / h, glow, l.angle)
        }
    }

    fun board(text: Text, bmp: Bitmap, matcher: Matcher) = CoachPrompt.analyze(seen(text, bmp)) { matcher.match(it) }

    fun prompt(ctx: Context, board: CoachPrompt.Board): String {
        val names = board.cards.map { it.first.name }.toSet()
        return CoachPrompt.build(board, lessons = LessonMatch.best(Lessons.all(ctx), board.phase.name, names))
    }

    fun jpeg(bmp: Bitmap, maxW: Int): ByteArray {
        val scaled = if (bmp.width <= maxW) bmp
            else Bitmap.createScaledBitmap(bmp, maxW, (bmp.height.toLong() * maxW / bmp.width).toInt(), true)
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }
}
