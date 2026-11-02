@file:OptIn(ExperimentalMaterial3Api::class)

package com.inkwell.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)
        setContent { AppTheme { App(store) } }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme) { Surface(Modifier.fillMaxSize(), content = content) }
}

sealed interface Screen {
    data object Groups : Screen
    data class Sheets(val group: String?) : Screen
    data class Editor(val sheet: String, val from: String?) : Screen
}

@Composable
fun App(store: Store) {
    var screen by remember { mutableStateOf<Screen>(Screen.Groups) }
    when (val s = screen) {
        Screen.Groups -> GroupsScreen(store) { screen = Screen.Sheets(it) }
        is Screen.Sheets -> {
            BackHandler { screen = Screen.Groups }
            SheetsScreen(store, s.group, { screen = Screen.Groups }) { screen = Screen.Editor(it, s.group) }
        }
        is Screen.Editor -> {
            val back: () -> Unit = { store.save(); screen = Screen.Sheets(s.from) }
            BackHandler(onBack = back)
            EditorScreen(store, s.sheet, back)
        }
    }
}

@Composable
fun Confirm(text: String, onDismiss: () -> Unit, onYes: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton({ onYes(); onDismiss() }) { Text("Delete") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun GroupsScreen(store: Store, open: (String?) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Group?>(null) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Inkwell") }) },
        floatingActionButton = { FloatingActionButton({ adding = true }) { Icon(Icons.Filled.Add, "New group") } }
    ) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            item {
                Text(
                    "${store.sheets.sumOf { words(it.text) }} words written · ${store.sheets.size} sheets",
                    Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("All Sheets") },
                    supportingContent = { Text("${store.sheets.size} sheets") },
                    modifier = Modifier.clickable { open(null) }
                )
            }
            items(store.groups, key = { it.id }) { g ->
                val n = store.sheets.count { it.group == g.id }
                ListItem(
                    headlineContent = { Text(g.name) },
                    supportingContent = { Text("$n sheets") },
                    trailingContent = { IconButton({ deleting = g }) { Icon(Icons.Filled.Delete, "Delete group") } },
                    modifier = Modifier.clickable { open(g.id) }
                )
            }
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("New group") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = {
                TextButton({ if (name.isNotBlank()) { store.addGroup(name.trim()); adding = false } }) { Text("Add") }
            },
            dismissButton = { TextButton({ adding = false }) { Text("Cancel") } }
        )
    }
    deleting?.let { g -> Confirm("Delete \"${g.name}\" and all its sheets?", { deleting = null }) { store.deleteGroup(g.id) } }
}

@Composable
fun SheetsScreen(store: Store, group: String?, onBack: () -> Unit, open: (String) -> Unit) {
    var deleting by remember { mutableStateOf<Sheet?>(null) }
    val list = store.sheets.filter { group == null || it.group == group }.sortedByDescending { it.updated }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(store.groups.firstOrNull { it.id == group }?.name ?: "All Sheets") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        },
        floatingActionButton = {
            FloatingActionButton({ open(store.addSheet(group ?: store.groups.first().id).id) }) {
                Icon(Icons.Filled.Add, "New sheet")
            }
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            if (list.isEmpty()) item { Text("No sheets yet. Tap + to start writing.", Modifier.padding(16.dp)) }
            items(list, key = { it.id }) { s ->
                val w = words(s.text)
                ListItem(
                    headlineContent = { Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(if (s.goal > 0) "$w / ${s.goal} words" else "$w words") },
                    trailingContent = { IconButton({ deleting = s }) { Icon(Icons.Filled.Delete, "Delete sheet") } },
                    modifier = Modifier.clickable { open(s.id) }
                )
            }
        }
    }
    deleting?.let { s -> Confirm("Delete \"${s.title}\"?", { deleting = null }) { store.deleteSheet(s.id) } }
}

@Composable
fun EditorScreen(store: Store, id: String, onBack: () -> Unit) {
    val sheet = store.sheet(id) ?: return
    val ctx = LocalContext.current
    var value by remember(id) { mutableStateOf(TextFieldValue(sheet.text)) }
    var focus by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var goalDialog by remember { mutableStateOf(false) }

    LaunchedEffect(value.text) {
        store.sheet(id)?.let {
            if (it.text != value.text) store.update(it.copy(text = value.text, updated = System.currentTimeMillis()))
        }
        delay(500)
        store.save()
    }

    val md = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { u ->
        u?.let { ctx.contentResolver.openOutputStream(it)?.use { o -> o.write(value.text.toByteArray()) } }
    }
    val txt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { u ->
        u?.let { ctx.contentResolver.openOutputStream(it)?.use { o -> o.write(plain(value.text).toByteArray()) } }
    }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { u ->
        u?.let { ctx.contentResolver.openOutputStream(it)?.use { o -> writePdf(o, plain(value.text)) } }
    }

    fun wrap(a: String) {
        val s = value.selection
        val t = value.text
        value = TextFieldValue(
            t.substring(0, s.min) + a + t.substring(s.min, s.max) + a + t.substring(s.max),
            TextRange(s.min + a.length, s.max + a.length)
        )
    }

    fun prefix(p: String) {
        val s = value.selection
        val t = value.text
        val start = if (s.min == 0) 0 else t.lastIndexOf('\n', s.min - 1) + 1
        value = TextFieldValue(t.substring(0, start) + p + t.substring(start), TextRange(s.min + p.length, s.max + p.length))
    }

    val w = words(value.text)
    val read = maxOf(1, (w + 199) / 200)

    Scaffold(
        topBar = {
            if (focus) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton({ focus = false }) { Text("Exit focus") }
                }
            } else {
                TopAppBar(
                    title = { Text(sheet.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    actions = {
                        TextButton({ focus = true }) { Text("Focus") }
                        IconButton({ goalDialog = true }) { Icon(Icons.Filled.Star, "Word goal") }
                        Box {
                            IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "Export") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("Share") }, onClick = {
                                    menu = false
                                    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, value.text)
                                    ctx.startActivity(Intent.createChooser(i, null))
                                })
                                DropdownMenuItem(text = { Text("Export Markdown (.md)") }, onClick = { menu = false; md.launch("${sheet.title}.md") })
                                DropdownMenuItem(text = { Text("Export Text (.txt)") }, onClick = { menu = false; txt.launch("${sheet.title}.txt") })
                                DropdownMenuItem(text = { Text("Export PDF") }, onClick = { menu = false; pdf.launch("${sheet.title}.pdf") })
                            }
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (!focus) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton({ prefix("# ") }) { Text("H1") }
                        TextButton({ prefix("## ") }) { Text("H2") }
                        TextButton({ wrap("**") }) { Text("B") }
                        TextButton({ wrap("*") }) { Text("I") }
                        TextButton({ prefix("- ") }) { Text("•") }
                        TextButton({ prefix("> ") }) { Text("❝") }
                    }
                    if (sheet.goal > 0) {
                        LinearProgressIndicator(
                            progress = { (w.toFloat() / sheet.goal).coerceAtMost(1f) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        )
                    }
                    val goalText = when {
                        sheet.goal <= 0 -> ""
                        w >= sheet.goal -> " · Goal reached ✓"
                        else -> " · ${sheet.goal - w} to goal"
                    }
                    Text(
                        "$w words · ${value.text.length} chars · $read min read$goalText",
                        Modifier.padding(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 10.dp),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    ) { pad ->
        val cs = MaterialTheme.colorScheme
        val transform = remember(cs) { MdTransform(cs.onSurface.copy(alpha = 0.4f), cs.primary) }
        BasicTextField(
            value,
            { value = it },
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 20.dp, vertical = 12.dp),
            textStyle = TextStyle(fontSize = 18.sp, lineHeight = 28.sp, fontFamily = FontFamily.Serif, color = cs.onSurface),
            cursorBrush = SolidColor(cs.primary),
            visualTransformation = transform,
            decorationBox = { inner ->
                Box {
                    if (value.text.isEmpty()) {
                        Text("Start writing…", style = TextStyle(fontSize = 18.sp, fontFamily = FontFamily.Serif, color = cs.onSurface.copy(alpha = 0.4f)))
                    }
                    inner()
                }
            }
        )
    }

    if (goalDialog) {
        var g by remember { mutableStateOf(if (sheet.goal > 0) sheet.goal.toString() else "") }
        AlertDialog(
            onDismissRequest = { goalDialog = false },
            title = { Text("Word goal") },
            text = {
                OutlinedTextField(
                    g, { v -> g = v.filter { c -> c.isDigit() }.take(6) },
                    singleLine = true,
                    label = { Text("Words (0 = no goal)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = {
                TextButton({
                    store.sheet(id)?.let { store.update(it.copy(goal = g.toIntOrNull() ?: 0)) }
                    store.save()
                    goalDialog = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton({ goalDialog = false }) { Text("Cancel") } }
        )
    }
}
