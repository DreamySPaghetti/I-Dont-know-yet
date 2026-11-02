@file:OptIn(ExperimentalMaterial3Api::class)

package com.inkwell.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

@Composable
private fun rememberSaver(mime: String, data: () -> ByteArray): ManagedActivityResultLauncher<String, Uri?> {
    val ctx = LocalContext.current
    return rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(data()) } }
                .onSuccess { Toast.makeText(ctx, "Exported", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(ctx, "Export failed", Toast.LENGTH_SHORT).show() }
        }
    }
}

private fun printPdf(ctx: Context, web: WebView, name: String) {
    val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
    pm.print(name, web.createPrintDocumentAdapter(name), PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
}

@Composable
fun PreviewScreen(store: Store, settings: Settings, ids: List<String>, title: String, back: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val md = ids.mapNotNull { store.sheet(it)?.text?.trimEnd() }.joinToString("\n\n")
    val style = settings.exportStyle
    val html = remember(md, style) { toHtml(md, title, style) }
    val name = title.replace(Regex("[\\\\/:*?\"<>|]"), "").ifBlank { "Untitled" }
    var web by remember { mutableStateOf<WebView?>(null) }
    var menu by remember { mutableStateOf(false) }
    val htmlOut = rememberSaver("text/html") { html.toByteArray() }
    val mdOut = rememberSaver("text/markdown") { md.toByteArray() }
    val txtOut = rememberSaver("text/plain") { plain(md).toByteArray() }

    Scaffold(
        containerColor = p.bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = p.bg),
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Preview") },
                actions = {
                    TextButton(onClick = { web?.let { printPdf(ctx, it, name) } }) { Text("PDF") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.Share, "Export") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Save as PDF / Print") }, onClick = { menu = false; web?.let { printPdf(ctx, it, name) } })
                            DropdownMenuItem(text = { Text("Share text") }, onClick = {
                                menu = false
                                val i = Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, plain(md))
                                ctx.startActivity(Intent.createChooser(i, null))
                            })
                            DropdownMenuItem(text = { Text("Export HTML (.html)") }, onClick = { menu = false; htmlOut.launch("$name.html") })
                            DropdownMenuItem(text = { Text("Export Markdown (.md)") }, onClick = { menu = false; mdOut.launch("$name.md") })
                            DropdownMenuItem(text = { Text("Export plain text (.txt)") }, onClick = { menu = false; txtOut.launch("$name.txt") })
                            DropdownMenuItem(text = { Text("Copy Markdown") }, onClick = {
                                menu = false
                                clipboard.setText(AnnotatedString(md))
                                Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                            })
                        }
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Choice(exportStyles, style, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { settings.exportStyle = it }
            AndroidView(
                factory = { WebView(it).also { w -> web = w } },
                update = { w ->
                    if (w.tag != html) {
                        w.tag = html
                        w.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun SettingsScreen(settings: Settings, back: () -> Unit) {
    val p = LocalPalette.current
    Scaffold(
        containerColor = p.bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = p.bg),
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Settings") }
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            FieldLabel("Theme")
            Choice(listOf("system" to "System", "light" to "Light", "sepia" to "Sepia", "dark" to "Dark"), settings.theme) { settings.theme = it }
            FieldLabel("Editor font")
            Choice(listOf("serif" to "Serif", "sans" to "Sans", "mono" to "Mono"), settings.font) { settings.font = it }
            FieldLabel("Text size · ${settings.size}")
            Slider(settings.size.toFloat(), { settings.size = it.roundToInt() }, valueRange = 14f..28f, steps = 13)
            FieldLabel("Line spacing · ${"%.1f".format(settings.spacing)}")
            Slider(settings.spacing, { settings.spacing = (it * 10).roundToInt() / 10f }, valueRange = 1.2f..2.2f, steps = 9)
            FieldLabel("Line width")
            Choice(
                listOf("560" to "Narrow", "680" to "Medium", "860" to "Wide", "2000" to "Full"),
                settings.width.toString()
            ) { settings.width = it.toInt() }
            Text(
                "The quick brown fox jumps over the lazy dog. Every first draft is just you telling yourself the story.",
                Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.surface).padding(16.dp),
                style = TextStyle(
                    fontFamily = editorFont(settings.font), fontSize = settings.size.sp,
                    lineHeight = (settings.size * settings.spacing).sp, color = p.text
                )
            )
            FieldLabel("Writing")
            SwitchRow("Typewriter mode", "Keeps the line you're writing in the middle of the screen", settings.typewriter) { settings.typewriter = it }
            SwitchRow("Highlight current paragraph", "Dims everything except the paragraph you're on", settings.focusParagraph) { settings.focusParagraph = it }
            FieldLabel("Markdown cheat sheet")
            listOf(
                "# Heading" to "Headings (up to ######)",
                "**bold**  *italic*" to "Bold and italic",
                "~~strike~~  ==mark==" to "Strikethrough and highlight",
                "`code`  [text](url)" to "Code and links",
                "> quote" to "Block quote",
                "- item   1. item" to "Lists",
                "---" to "Divider",
                "%% note" to "Comment line (not exported)",
                "++note++" to "Inline comment (not exported)"
            ).forEach { (k, v) ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text(k, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 14.sp, color = p.text)
                    Text(v, fontSize = 13.sp, color = p.muted)
                }
            }
            FieldLabel("About")
            Text("Inkwell ${BuildConfig.VERSION_NAME}", color = p.text)
            TextButton(onClick = { UpdateTrigger.check() }) { Text("Check for updates") }
            Spacer(Modifier.height(48.dp))
        }
    }
}
