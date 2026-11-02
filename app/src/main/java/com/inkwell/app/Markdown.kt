package com.inkwell.app

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em

data class Run(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val mark: Boolean = false,
    val code: Boolean = false,
    val link: String? = null
)

private val linkRx = Regex("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)")
private val italicRx = Regex("(?<![*\\w])\\*(?![*\\s])(.+?)(?<![*\\s])\\*(?![*\\w])")
private val headingRx = Regex("^(#{1,6}) ")
private val listRx = Regex("^\\s*([-*+]|\\d+\\.) ")
private val hrRx = Regex("^(-{3,}|\\*{3,})\\s*$")

fun parseInline(s: String): List<Run> {
    val out = mutableListOf<Run>()
    val sb = StringBuilder()
    var b = false
    var i = false
    var st = false
    var mk = false
    fun flush() {
        if (sb.isNotEmpty()) {
            out += Run(sb.toString(), b, i, st, mk)
            sb.clear()
        }
    }
    var k = 0
    while (k < s.length) {
        if (s[k] == '`') {
            val e = s.indexOf('`', k + 1)
            if (e > k) {
                flush(); out += Run(s.substring(k + 1, e), code = true); k = e + 1; continue
            }
        }
        if (s.startsWith("++", k)) {
            val e = s.indexOf("++", k + 2)
            if (e > 0) {
                k = e + 2; continue
            }
        }
        if (s.startsWith("**", k) && (b || s.indexOf("**", k + 2) > 0)) {
            flush(); b = !b; k += 2; continue
        }
        if (s.startsWith("~~", k) && (st || s.indexOf("~~", k + 2) > 0)) {
            flush(); st = !st; k += 2; continue
        }
        if (s.startsWith("==", k) && (mk || s.indexOf("==", k + 2) > 0)) {
            flush(); mk = !mk; k += 2; continue
        }
        if (s[k] == '*' && (i || (s.indexOf('*', k + 1) > 0 && k + 1 < s.length && s[k + 1] != ' '))) {
            flush(); i = !i; k++; continue
        }
        if (s[k] == '[') {
            val m = linkRx.find(s, k)
            if (m != null && m.range.first == k) {
                flush(); out += Run(m.groupValues[1], b, i, st, mk, link = m.groupValues[2]); k = m.range.last + 1; continue
            }
        }
        sb.append(s[k])
        k++
    }
    flush()
    return out
}

fun plainLine(line: String): String {
    val t = line.trim()
    if (t.startsWith("%%") || t.startsWith("```") || hrRx.matches(t)) return ""
    val stripped = line.replace(Regex("^\\s*(#{1,6} |> ?|[-*+] |\\d+\\. )"), "")
    return parseInline(stripped).joinToString("") { it.text }
}

fun plain(md: String): String = md.lines().mapNotNull { l ->
    val t = l.trimStart()
    when {
        t.startsWith("%%") || t.startsWith("```") -> null
        hrRx.matches(t) -> "* * *"
        Regex("^[-*+] ").containsMatchIn(t) -> "• " + plainLine(l)
        Regex("^\\d+\\. ").containsMatchIn(t) -> t.substringBefore(' ') + " " + plainLine(l)
        else -> plainLine(l)
    }
}.joinToString("\n")

class MdTransform(private val p: Palette, private val focus: Pair<Int, Int>?) : VisualTransformation {
    override fun filter(text: AnnotatedString) = TransformedText(style(text.text), OffsetMapping.Identity)
    override fun equals(other: Any?) = other is MdTransform && other.p == p && other.focus == focus
    override fun hashCode() = p.hashCode() * 31 + focus.hashCode()

    private fun style(t: String) = buildAnnotatedString {
        append(t)
        val faint = SpanStyle(color = p.muted.copy(alpha = 0.6f))
        val mono = SpanStyle(fontFamily = FontFamily.Monospace, background = p.surface)
        var start = 0
        var inCode = false
        for (line in t.split('\n')) {
            val end = start + line.length
            val trimmed = line.trimStart()
            val h = headingRx.find(line)
            val fence = trimmed.startsWith("```")
            when {
                fence -> {
                    addStyle(mono.merge(faint), start, end); inCode = !inCode
                }
                inCode -> addStyle(mono, start, end)
                trimmed.startsWith("%%") -> addStyle(SpanStyle(color = p.muted, fontStyle = FontStyle.Italic, background = p.surface), start, end)
                h != null -> {
                    val size = when (h.groupValues[1].length) { 1 -> 1.6f; 2 -> 1.35f; 3 -> 1.18f; else -> 1.06f }.em
                    addStyle(SpanStyle(fontSize = size, fontWeight = FontWeight.Bold), start, end)
                    addStyle(faint, start, start + h.value.length)
                }
                hrRx.matches(line) -> addStyle(faint, start, end)
                trimmed.startsWith(">") -> {
                    addStyle(SpanStyle(fontStyle = FontStyle.Italic, color = p.muted), start, end)
                    addStyle(SpanStyle(color = p.accent, fontWeight = FontWeight.Bold), start, start + line.indexOf('>') + 1)
                }
                else -> listRx.find(line)?.let {
                    addStyle(SpanStyle(color = p.accent, fontWeight = FontWeight.Bold), start, start + it.value.length)
                }
            }
            if (!inCode && !fence && !trimmed.startsWith("%%")) inline(line, start)
            start = end + 1
        }
        focus?.let { (a, b) ->
            val dim = SpanStyle(color = p.text.copy(alpha = 0.28f))
            if (a > 0) addStyle(dim, 0, a)
            if (b < t.length) addStyle(dim, b, t.length)
        }
    }

    private fun AnnotatedString.Builder.inline(line: String, o: Int) {
        val faint = SpanStyle(color = p.muted.copy(alpha = 0.6f))
        fun pair(rx: Regex, n: Int, style: SpanStyle) = rx.findAll(line).forEach {
            val a = o + it.range.first
            val b = o + it.range.last + 1
            addStyle(style, a, b)
            addStyle(faint, a, a + n)
            addStyle(faint, b - n, b)
        }
        pair(Regex("\\*\\*(.+?)\\*\\*"), 2, SpanStyle(fontWeight = FontWeight.Bold))
        pair(italicRx, 1, SpanStyle(fontStyle = FontStyle.Italic))
        pair(Regex("~~(.+?)~~"), 2, SpanStyle(textDecoration = TextDecoration.LineThrough, color = p.muted))
        pair(Regex("==(.+?)=="), 2, SpanStyle(background = p.accent.copy(alpha = 0.28f)))
        pair(Regex("\\+\\+(.+?)\\+\\+"), 2, SpanStyle(color = p.muted, fontStyle = FontStyle.Italic, background = p.surface))
        pair(Regex("`([^`]+)`"), 1, SpanStyle(fontFamily = FontFamily.Monospace, background = p.surface))
        linkRx.findAll(line).forEach {
            val a = o + it.range.first
            val textEnd = a + 1 + it.groupValues[1].length
            addStyle(SpanStyle(color = p.accent, textDecoration = TextDecoration.Underline), a + 1, textEnd)
            addStyle(faint, a, a + 1)
            addStyle(faint, textEnd, o + it.range.last + 1)
        }
    }
}

fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

fun inlineHtml(s: String) = parseInline(s).joinToString("") { r ->
    var h = esc(r.text)
    if (r.code) h = "<code>$h</code>"
    if (r.bold) h = "<strong>$h</strong>"
    if (r.italic) h = "<em>$h</em>"
    if (r.strike) h = "<del>$h</del>"
    if (r.mark) h = "<mark>$h</mark>"
    r.link?.let { h = "<a href=\"${esc(it)}\">$h</a>" }
    h
}

val exportStyles = listOf("classic" to "Classic", "modern" to "Modern", "manuscript" to "Manuscript")

private fun css(style: String): String {
    val base = "body{max-width:680px;margin:0 auto;padding:40px 28px;color:#222;background:#fff;-webkit-text-size-adjust:100%}" +
        "h1,h2,h3,h4,h5,h6{line-height:1.25;margin:1.6em 0 .5em}" +
        "blockquote{margin:1em 0;padding-left:1em;border-left:3px solid #ccc;color:#555;font-style:italic}" +
        "pre{background:#f4f4f2;padding:12px;border-radius:6px;overflow:auto;white-space:pre-wrap}" +
        "code{font-family:monospace;background:#f4f4f2;padding:0 3px;border-radius:3px}pre code{background:none;padding:0}" +
        "hr{border:none;text-align:center;margin:2em 0}hr:after{content:'* * *';color:#999;letter-spacing:.5em}" +
        "mark{background:#ffe58a}a{color:#a86d00}"
    return base + when (style) {
        "modern" -> "body{font-family:Roboto,'Helvetica Neue',Arial,sans-serif;font-size:16px;line-height:1.6}h1{font-size:2em;font-weight:800}p{margin:0 0 1em}"
        "manuscript" -> "body{font-family:'Courier New',Courier,monospace;font-size:12pt;line-height:2}" +
            "h1,h2,h3,h4,h5,h6{font-size:12pt;text-transform:uppercase;text-align:center;font-weight:normal}p{text-indent:2em;margin:0}"
        else -> "body{font-family:Georgia,'Times New Roman',serif;font-size:17px;line-height:1.65}" +
            "h1{font-size:2.1em;font-weight:normal;text-align:center;margin-top:.4em}p{margin:0 0 1em}"
    }
}

fun toHtml(md: String, title: String, style: String): String {
    val body = StringBuilder()
    var inCode = false
    var list: String? = null
    val para = mutableListOf<String>()
    fun flushPara() {
        if (para.isNotEmpty()) {
            body.append("<p>").append(para.joinToString("<br>") { inlineHtml(it) }).append("</p>\n")
            para.clear()
        }
    }
    fun closeList() {
        list?.let { body.append("</$it>\n") }
        list = null
    }
    for (line in md.lines()) {
        val t = line.trimStart()
        if (t.startsWith("```")) {
            flushPara(); closeList()
            body.append(if (inCode) "</code></pre>\n" else "<pre><code>")
            inCode = !inCode
            continue
        }
        if (inCode) {
            body.append(esc(line)).append("\n"); continue
        }
        val h = headingRx.find(line)
        val ul = Regex("^\\s*[-*+] (.*)").find(line)
        val ol = Regex("^\\s*\\d+\\. (.*)").find(line)
        when {
            t.startsWith("%%") -> {}
            line.isBlank() -> { flushPara(); closeList() }
            h != null -> {
                flushPara(); closeList()
                val n = h.groupValues[1].length
                body.append("<h$n>${inlineHtml(line.substring(h.value.length))}</h$n>\n")
            }
            hrRx.matches(t) -> { flushPara(); closeList(); body.append("<hr>\n") }
            t.startsWith(">") -> { flushPara(); closeList(); body.append("<blockquote>${inlineHtml(t.removePrefix(">").trimStart())}</blockquote>\n") }
            ul != null || ol != null -> {
                flushPara()
                val tag = if (ul != null) "ul" else "ol"
                if (list != tag) {
                    closeList(); body.append("<$tag>"); list = tag
                }
                body.append("<li>${inlineHtml((ul ?: ol)!!.groupValues[1])}</li>")
            }
            else -> { closeList(); para += line }
        }
    }
    flushPara()
    closeList()
    if (inCode) body.append("</code></pre>")
    return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
        "<title>${esc(title)}</title><style>${css(style)}</style></head><body>$body</body></html>"
}
