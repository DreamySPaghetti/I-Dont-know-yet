@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.inkwell.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val prefixRx = Regex("^(#{1,6} |> |[-*+] |\\d+\\. |%% )")

fun lineBounds(t: String, pos: Int): Pair<Int, Int> {
    val s = if (pos <= 0) 0 else t.lastIndexOf('\n', pos - 1) + 1
    val e = t.indexOf('\n', pos).let { if (it < 0) t.length else it }
    return s to e
}

@Composable
fun EditorScreen(store: Store, settings: Settings, id: String, back: () -> Unit, go: (Screen) -> Unit) {
    val sheet = store.sheet(id) ?: return
    val p = LocalPalette.current
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var value by remember(id) { mutableStateOf(TextFieldValue(sheet.text)) }
    val undo = remember(id) { ArrayDeque<TextFieldValue>() }
    val redo = remember(id) { ArrayDeque<TextFieldValue>() }
    var lastEdit by remember { mutableLongStateOf(0L) }
    var history by remember { mutableIntStateOf(0) }
    var chrome by remember { mutableStateOf(true) }
    var dashboard by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val startWords = remember(id) { counts(sheet.text).words }
    var lastWords by remember(id) { mutableIntStateOf(startWords) }
    val c = remember(value.text) { counts(value.text) }
    val fr = remember { FocusRequester() }

    LaunchedEffect(value.text) {
        store.sheet(id)?.let {
            if (it.text != value.text) store.update(it.copy(text = value.text, updated = now()))
        }
        delay(600)
        val w = counts(value.text).words
        if (w > lastWords) store.logWords(w - lastWords)
        lastWords = w
        store.save()
    }

    fun change(v: TextFieldValue) {
        if (v.text != value.text) {
            val t = now()
            if (t - lastEdit > 1200 || undo.isEmpty()) {
                undo.addLast(value)
                if (undo.size > 300) undo.removeFirst()
            }
            lastEdit = t
            redo.clear()
            history++
        }
        value = v
    }

    fun undoIt() {
        if (undo.isEmpty()) return
        redo.addLast(value)
        value = undo.removeLast()
        lastEdit = 0
        history++
    }

    fun redoIt() {
        if (redo.isEmpty()) return
        undo.addLast(value)
        value = redo.removeLast()
        lastEdit = 0
        history++
    }

    fun wrap(a: String) {
        val t = value.text
        val s = value.selection
        if (s.min >= a.length && t.startsWith(a, s.min - a.length) && t.startsWith(a, s.max)) {
            change(
                TextFieldValue(
                    t.substring(0, s.min - a.length) + t.substring(s.min, s.max) + t.substring(s.max + a.length),
                    TextRange(s.min - a.length, s.max - a.length)
                )
            )
        } else {
            change(TextFieldValue(t.substring(0, s.min) + a + t.substring(s.min, s.max) + a + t.substring(s.max), TextRange(s.min + a.length, s.max + a.length)))
        }
    }

    fun setPrefix(newPrefix: String) {
        val t = value.text
        val s = value.selection
        val ls = lineBounds(t, s.min).first
        val old = prefixRx.find(t.substring(ls))?.value ?: ""
        val np = if (old == newPrefix) "" else newPrefix
        val d = np.length - old.length
        change(
            TextFieldValue(
                t.substring(0, ls) + np + t.substring(ls + old.length),
                TextRange(maxOf(ls, s.min + d), maxOf(ls, s.max + d))
            )
        )
    }

    fun heading() {
        val t = value.text
        val ls = lineBounds(t, value.selection.min).first
        val n = Regex("^#{1,6} ").find(t.substring(ls))?.value?.length?.minus(1) ?: 0
        if (n >= 3) setPrefix("#".repeat(n) + " ") else setPrefix("#".repeat(n + 1) + " ")
    }

    fun insert(x: String) {
        val t = value.text
        val s = value.selection
        change(TextFieldValue(t.substring(0, s.min) + x + t.substring(s.max), TextRange(s.min + x.length)))
    }

    fun link() {
        val t = value.text
        val s = value.selection
        val sel = t.substring(s.min, s.max)
        val pos = if (sel.isEmpty()) s.min + 1 else s.min + sel.length + 3
        change(TextFieldValue(t.substring(0, s.min) + "[$sel]()" + t.substring(s.max), TextRange(pos)))
    }

    val focusRange = if (settings.focusParagraph && value.text.isNotEmpty()) {
        lineBounds(value.text, value.selection.end.coerceIn(0, value.text.length))
    } else null
    val transform = remember(p, focusRange) { MdTransform(p, focusRange) }

    val counter = when (settings.counter) {
        "chars" -> "${fmt(c.chars)} characters"
        "read" -> "${c.readMinutes} min read"
        "goal" -> if (sheet.goal.target > 0) "${fmt(sheet.goal.value(c))} / ${fmt(sheet.goal.target)} ${unitNames[sheet.goal.unit]}" else "No goal set"
        else -> "${fmt(c.words)} words"
    }
    val nextCounter = mapOf("words" to "chars", "chars" to "read", "read" to "goal", "goal" to "words")

    Scaffold(
        containerColor = p.bg,
        topBar = {
            if (chrome) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = p.bg),
                    navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    title = {
                        Row(
                            Modifier.clip(RoundedCornerShape(8.dp)).clickable { settings.counter = nextCounter[settings.counter] ?: "words" }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (sheet.goal.target > 0) {
                                Ring(sheet.goal.value(c).toFloat() / sheet.goal.target, ringColor(p, sheet.goal.state(c)), p.line, Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(counter, fontSize = 15.sp, color = p.muted)
                        }
                    },
                    actions = {
                        IconButton(onClick = { dashboard = true }) { Icon(Icons.Outlined.Info, "Dashboard") }
                        IconButton(onClick = { store.save(); go(Screen.Preview(listOf(id), sheet.title)) }) {
                            Icon(Icons.Outlined.Share, "Preview & export")
                        }
                        Box {
                            IconButton(onClick = { more = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(more, { more = false }) {
                                DropdownMenuItem(text = { Text("Focus mode") }, onClick = { more = false; chrome = false })
                                Toggle("Typewriter mode", settings.typewriter) { settings.typewriter = it }
                                Toggle("Highlight current paragraph", settings.focusParagraph) { settings.focusParagraph = it }
                                Toggle("Favorite", sheet.favorite) { f -> store.sheet(id)?.let { store.update(it.copy(favorite = f)); store.save() } }
                            }
                        }
                    }
                )
            } else {
                Box(Modifier.fillMaxWidth().statusBarsPadding(), contentAlignment = Alignment.CenterEnd) {
                    TextButton(onClick = { chrome = true }) { Text("Done", color = p.muted) }
                }
            }
        },
        bottomBar = {
            if (chrome) {
                MarkupBar(history >= 0 && undo.isNotEmpty(), history >= 0 && redo.isNotEmpty()) { a ->
                    when (a) {
                        "undo" -> undoIt()
                        "redo" -> redoIt()
                        "h" -> heading()
                        "b" -> wrap("**")
                        "i" -> wrap("*")
                        "s" -> wrap("~~")
                        "m" -> wrap("==")
                        "code" -> wrap("`")
                        "link" -> link()
                        "quote" -> setPrefix("> ")
                        "ul" -> setPrefix("- ")
                        "ol" -> setPrefix("1. ")
                        "hr" -> insert("\n---\n")
                        "cl" -> setPrefix("%% ")
                        "ic" -> wrap("++")
                        "kb" -> {
                            keyboard?.hide()
                            focusManager.clearFocus()
                        }
                    }
                }
            }
        }
    ) { pad ->
        var viewH by remember { mutableIntStateOf(0) }
        val scroll = rememberScrollState()
        var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
        val topPadPx = if (settings.typewriter) viewH * 0.4f else with(density) { 20.dp.toPx() }
        Box(
            Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding().onSizeChanged { viewH = it.height }
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                Spacer(Modifier.height(with(density) { topPadPx.toDp() }))
                BasicTextField(
                    value,
                    { change(it) },
                    Modifier.align(Alignment.CenterHorizontally).widthIn(max = settings.width.dp).fillMaxWidth()
                        .padding(horizontal = 24.dp).focusRequester(fr),
                    textStyle = TextStyle(
                        fontFamily = editorFont(settings.font), fontSize = settings.size.sp,
                        lineHeight = (settings.size * settings.spacing).sp, color = p.text
                    ),
                    cursorBrush = SolidColor(p.accent),
                    visualTransformation = transform,
                    onTextLayout = { layout = it },
                    decorationBox = { inner ->
                        Box {
                            if (value.text.isEmpty()) {
                                Text(
                                    "Start writing…",
                                    style = TextStyle(fontFamily = editorFont(settings.font), fontSize = settings.size.sp, color = p.muted.copy(alpha = 0.6f))
                                )
                            }
                            inner()
                        }
                    }
                )
                Spacer(Modifier.height(with(density) { (viewH * 0.5f).toDp() }))
            }
        }
        LaunchedEffect(value.selection, layout, settings.typewriter, viewH) {
            val l = layout ?: return@LaunchedEffect
            if (viewH == 0) return@LaunchedEffect
            val r = l.getCursorRect(value.selection.end.coerceIn(0, l.layoutInput.text.length))
            val top = topPadPx + r.top
            if (settings.typewriter) {
                scroll.animateScrollTo((top - viewH * 0.4f).toInt().coerceAtLeast(0))
            } else {
                val margin = viewH * 0.12f
                if (top + r.height > scroll.value + viewH - margin) {
                    scroll.animateScrollTo((top + r.height - viewH + margin).toInt())
                } else if (top < scroll.value) {
                    scroll.animateScrollTo((top - margin).toInt().coerceAtLeast(0))
                }
            }
        }
    }

    LaunchedEffect(id) {
        if (value.text.isEmpty()) {
            delay(250)
            runCatching { fr.requestFocus() }
        }
    }

    if (dashboard) {
        Dashboard(store, id, c, value.text, startWords, { dashboard = false }) { pos ->
            value = value.copy(selection = TextRange(pos))
            dashboard = false
            runCatching { fr.requestFocus() }
        }
    }
}

@Composable
private fun MarkupBar(canUndo: Boolean, canRedo: Boolean, on: (String) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.background(p.surface).windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
        HorizontalDivider(thickness = 0.5.dp, color = p.line)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Key("↶", canUndo) { on("undo") }
            Key("↷", canRedo) { on("redo") }
            Sep()
            Key("#") { on("h") }
            Key("B", weight = FontWeight.Bold) { on("b") }
            Key("I", italic = true) { on("i") }
            Key("S", strike = true) { on("s") }
            Key("==") { on("m") }
            Key("</>") { on("code") }
            Key("Link") { on("link") }
            Sep()
            Key("❝") { on("quote") }
            Key("•") { on("ul") }
            Key("1.") { on("ol") }
            Key("—") { on("hr") }
            Sep()
            Key("%%") { on("cl") }
            Key("++") { on("ic") }
            Sep()
            Key("⌄") { on("kb") }
        }
    }
}

@Composable
private fun Key(
    label: String,
    enabled: Boolean = true,
    weight: FontWeight = FontWeight.Medium,
    italic: Boolean = false,
    strike: Boolean = false,
    onClick: () -> Unit
) {
    val p = LocalPalette.current
    Box(
        Modifier.padding(2.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, fontSize = 17.sp, fontWeight = weight,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            textDecoration = if (strike) TextDecoration.LineThrough else null,
            color = if (enabled) p.text else p.muted.copy(alpha = 0.35f)
        )
    }
}

@Composable
private fun Sep() {
    val p = LocalPalette.current
    Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(20.dp).background(p.line))
}

@Composable
private fun Dashboard(
    store: Store,
    id: String,
    c: Counts,
    text: String,
    startWords: Int,
    onDismiss: () -> Unit,
    jump: (Int) -> Unit
) {
    val p = LocalPalette.current
    val sheet = store.sheet(id) ?: return
    var tab by remember { mutableIntStateOf(0) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.bg
    ) {
        TabRow(selectedTabIndex = tab, containerColor = p.bg, contentColor = p.accent) {
            listOf("Outline", "Stats", "Goal", "Notes").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        Column(
            Modifier.fillMaxWidth().heightIn(min = 320.dp, max = 560.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            when (tab) {
                0 -> OutlineTab(text, jump)
                1 -> StatsTab(sheet, c, startWords)
                2 -> GoalTab(store, sheet, c)
                else -> NotesTab(store, sheet)
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun OutlineTab(text: String, jump: (Int) -> Unit) {
    val p = LocalPalette.current
    val hs = Regex("(?m)^(#{1,6}) (.+)$").findAll(text).toList()
    if (hs.isEmpty()) {
        Text(
            "Start a line with # to make a heading. Headings show up here so you can jump between sections.",
            Modifier.padding(top = 12.dp), color = p.muted
        )
    }
    hs.forEach { m ->
        val lvl = m.groupValues[1].length
        Text(
            plainLine(m.value),
            Modifier.fillMaxWidth().clickable { jump(m.range.last + 1) }.padding(start = ((lvl - 1) * 16).dp, top = 10.dp, bottom = 10.dp),
            fontSize = if (lvl == 1) 17.sp else 15.sp,
            fontWeight = if (lvl <= 2) FontWeight.SemiBold else FontWeight.Normal,
            color = p.text
        )
    }
}

@Composable
private fun StatsTab(sheet: Sheet, c: Counts, startWords: Int) {
    val p = LocalPalette.current
    val session = c.words - startWords
    val rows = listOf(
        "Words" to fmt(c.words),
        "Characters" to fmt(c.chars),
        "Characters (no spaces)" to fmt(c.charsNoSpaces),
        "Sentences" to fmt(c.sentences),
        "Paragraphs" to fmt(c.paragraphs),
        "Pages (≈250 words)" to fmt(c.pages),
        "Reading time" to "${c.readMinutes} min",
        "This session" to "${if (session >= 0) "+" else ""}${fmt(session)} words",
        "Created" to dateStr(sheet.created),
        "Modified" to dateStr(sheet.updated)
    )
    rows.forEach { (k, v) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Text(k, Modifier.weight(1f), color = p.muted)
            Text(v, color = p.text, fontWeight = FontWeight.Medium)
        }
        HorizontalDivider(thickness = 0.5.dp, color = p.line)
    }
}

@Composable
private fun GoalTab(store: Store, sheet: Sheet, c: Counts) {
    val p = LocalPalette.current
    val g = sheet.goal
    fun set(ng: Goal) {
        store.sheet(sheet.id)?.let { store.update(it.copy(goal = ng)) }
        store.save()
    }
    var target by remember { mutableStateOf(if (g.target > 0) g.target.toString() else "") }
    var picking by remember { mutableStateOf(false) }
    if (g.target > 0) {
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Ring(g.value(c).toFloat() / g.target, ringColor(p, g.state(c)), p.line, Modifier.size(56.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text("${fmt(g.value(c))} of ${fmt(g.target)} ${unitNames[g.unit]}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = p.text)
                Text(goalStatus(g, c), fontSize = 14.sp, color = p.muted)
            }
        }
    }
    FieldLabel("Target")
    OutlinedTextField(
        target,
        { v ->
            target = v.filter { ch -> ch.isDigit() }.take(7)
            set(g.copy(target = target.toIntOrNull() ?: 0))
        },
        Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("e.g. 1000") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
    FieldLabel("Count")
    Choice(unitNames.toList(), g.unit) { set(g.copy(unit = it)) }
    FieldLabel("Type")
    Choice(listOf("least" to "At least", "most" to "At most", "about" to "About"), g.mode) { set(g.copy(mode = it)) }
    FieldLabel("Deadline")
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { picking = true }) {
            Text(
                if (g.deadline > 0) LocalDate.ofEpochDay(g.deadline).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
                else "Set a deadline"
            )
        }
        if (g.deadline > 0) TextButton(onClick = { set(g.copy(deadline = 0)) }) { Text("Clear") }
    }
    if (g.target > 0) {
        TextButton(onClick = { target = ""; set(Goal()) }, modifier = Modifier.padding(top = 8.dp)) {
            Text("Remove goal", color = Color(0xFFD9534F))
        }
    }
    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = if (g.deadline > 0) g.deadline * 86_400_000L else null)
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { set(g.copy(deadline = it / 86_400_000L)) }
                    picking = false
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } }
        ) { DatePicker(state) }
    }
}

@Composable
private fun NotesTab(store: Store, sheet: Sheet) {
    var kw by remember { mutableStateOf("") }
    var notes by remember(sheet.id) { mutableStateOf(sheet.notes) }
    fun upd(f: (Sheet) -> Sheet) {
        store.sheet(sheet.id)?.let { store.update(f(it)) }
        store.save()
    }
    fun addKeyword() {
        val k = kw.trim()
        if (k.isNotEmpty()) upd { it.copy(keywords = (it.keywords + k).distinct()) }
        kw = ""
    }
    FieldLabel("Keywords")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        sheet.keywords.forEach { k ->
            InputChip(
                selected = false,
                onClick = { upd { it.copy(keywords = it.keywords - k) } },
                label = { Text(k) },
                trailingIcon = { Icon(Icons.Filled.Close, "Remove", Modifier.size(16.dp)) }
            )
        }
    }
    OutlinedTextField(
        kw, { kw = it }, Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("Add a keyword") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { addKeyword() })
    )
    FieldLabel("Notes")
    OutlinedTextField(
        notes,
        { v -> notes = v; upd { it.copy(notes = v) } },
        Modifier.fillMaxWidth().heightIn(min = 160.dp),
        placeholder = { Text("Research, reminders, ideas. Kept with this sheet, never exported.") }
    )
}
