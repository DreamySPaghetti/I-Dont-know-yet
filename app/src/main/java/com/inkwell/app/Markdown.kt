package com.inkwell.app

import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.sp
import java.io.OutputStream

private val italicRx = Regex("(?<![*\\w])\\*(?![*\\s])(.+?)(?<![*\\s])\\*(?![*\\w])")

class MdTransform(private val dim: Color, private val accent: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString) = TransformedText(style(text.text), OffsetMapping.Identity)

    private fun style(t: String) = buildAnnotatedString {
        append(t)
        val faint = SpanStyle(color = dim)
        var start = 0
        for (line in t.split('\n')) {
            val end = start + line.length
            val h = Regex("^(#{1,6}) ").find(line)
            if (h != null) {
                val size = when (h.groupValues[1].length) { 1 -> 28; 2 -> 24; 3 -> 21; else -> 19 }.sp
                addStyle(SpanStyle(fontSize = size, fontWeight = FontWeight.Bold), start, end)
                addStyle(faint, start, start + h.value.length)
            } else if (line.startsWith("> ")) {
                addStyle(SpanStyle(fontStyle = FontStyle.Italic, color = dim), start, end)
            } else {
                Regex("^\\s*([-*+]|\\d+\\.) ").find(line)?.let {
                    addStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold), start, start + it.value.length)
                }
            }
            start = end + 1
        }
        Regex("\\*\\*(.+?)\\*\\*").findAll(t).forEach {
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), it.range.first, it.range.last + 1)
            addStyle(faint, it.range.first, it.range.first + 2)
            addStyle(faint, it.range.last - 1, it.range.last + 1)
        }
        italicRx.findAll(t).forEach {
            addStyle(SpanStyle(fontStyle = FontStyle.Italic), it.range.first, it.range.last + 1)
            addStyle(faint, it.range.first, it.range.first + 1)
            addStyle(faint, it.range.last, it.range.last + 1)
        }
    }
}

fun plain(t: String) = t.replace(Regex("(?m)^#{1,6} |^> "), "").replace("**", "").replace(italicRx, "$1")

fun writePdf(out: OutputStream, text: String) {
    val doc = PdfDocument()
    val paint = TextPaint().apply { textSize = 12f; isAntiAlias = true }
    val w = 495
    val h = 742
    val src = text.ifEmpty { " " }
    val layout = StaticLayout.Builder.obtain(src, 0, src.length, paint, w).build()
    var line = 0
    var page = 1
    while (line < layout.lineCount) {
        val p = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, page++).create())
        val top = layout.getLineTop(line)
        var last = line + 1
        while (last < layout.lineCount && layout.getLineBottom(last) - top <= h) last++
        val c = p.canvas
        c.translate(50f, 50f - top)
        c.clipRect(0, top, w, layout.getLineBottom(last - 1))
        layout.draw(c)
        doc.finishPage(p)
        line = last
    }
    doc.writeTo(out)
    doc.close()
}
