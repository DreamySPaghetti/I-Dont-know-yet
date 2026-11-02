@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.inkwell.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

data class Filter(val kind: String, val id: String? = null)

fun Set<String>.toggle(x: String) = if (x in this) this - x else this + x

@Composable
fun LibraryScreen(store: Store, go: (Screen) -> Unit) {
    val p = LocalPalette.current
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var creatingIn by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<Group?>(null) }
    var renaming by remember { mutableStateOf<Group?>(null) }
    var deleting by remember { mutableStateOf<Group?>(null) }
    val live = store.sheets.filter { it.trashed == 0L }

    val rows = mutableListOf<Pair<Group, Int>>()
    store.groups.firstOrNull { it.id == INBOX }?.let { rows += it to 0 }
    fun walk(parent: String?, d: Int) {
        store.children(parent).forEach {
            rows += it to d
            if (expanded[it.id] != false) walk(it.id, d + 1)
        }
    }
    walk(null, 0)

    Scaffold(containerColor = p.bg) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pad) {
            item {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Library", Modifier.weight(1f), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = p.text)
                        IconButton(onClick = { go(Screen.Options) }) { Icon(Icons.Outlined.Settings, "Settings", tint = p.muted) }
                    }
                    Text(
                        "${fmt(store.today)} words written today · ${fmt(live.size)} sheets",
                        Modifier.padding(start = 24.dp, top = 2.dp), fontSize = 14.sp, color = p.muted
                    )
                    Row(
                        Modifier.padding(horizontal = 20.dp, vertical = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(p.surface).clickable { go(Screen.Sheets(Filter("search"))) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Search, null, Modifier.size(20.dp), tint = p.muted)
                        Spacer(Modifier.width(10.dp))
                        Text("Search all sheets", color = p.muted, fontSize = 16.sp)
                    }
                }
            }
            item { SectionHeader("Filters") }
            item {
                NavRow("All Sheets", live.size, { go(Screen.Sheets(Filter("all"))) }) {
                    Icon(Icons.AutoMirrored.Outlined.List, null, tint = p.accent)
                }
            }
            item {
                NavRow("Last 7 Days", live.count { it.updated > now() - WEEK }, { go(Screen.Sheets(Filter("recent"))) }) {
                    Icon(Icons.Outlined.DateRange, null, tint = p.accent)
                }
            }
            item {
                NavRow("Favorites", live.count { it.favorite }, { go(Screen.Sheets(Filter("fav"))) }) {
                    Icon(Icons.Outlined.Star, null, tint = p.accent)
                }
            }
            item { SectionHeader("Groups") }
            items(rows, key = { it.first.id }) { (g, d) ->
                val hasKids = store.children(g.id).isNotEmpty()
                val ids = store.descendants(g.id) + g.id
                NavRow(
                    g.name, live.count { it.group in ids }, { go(Screen.Sheets(Filter("group", g.id))) },
                    indent = d,
                    onLongClick = { actionsFor = g },
                    lead = {
                        if (hasKids) Icon(
                            if (expanded[g.id] != false) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            "Expand", Modifier.size(22.dp).clip(CircleShape).clickable { expanded[g.id] = expanded[g.id] == false },
                            tint = p.muted
                        )
                    }
                ) {
                    if (g.id == INBOX) Icon(Icons.Outlined.Email, null, tint = p.accent) else FolderGlyph(p.accent)
                }
            }
            item {
                NavRow("New Group", null, { creatingIn = null; creating = true }) { Icon(Icons.Filled.Add, null, tint = p.muted) }
            }
            item { Spacer(Modifier.height(12.dp)) }
            item {
                NavRow("Trash", store.sheets.count { it.trashed > 0 }, { go(Screen.Sheets(Filter("trash"))) }) {
                    Icon(Icons.Outlined.Delete, null, tint = p.muted)
                }
            }
            item {
                Text(
                    "Tip: long-press a group to add subgroups, rename or export it.",
                    Modifier.padding(24.dp), fontSize = 13.sp, color = p.muted
                )
            }
        }
    }

    actionsFor?.let { g ->
        AlertDialog(
            onDismissRequest = { actionsFor = null },
            title = { Text(g.name) },
            text = {
                Column {
                    ActionItem("New sheet here") { actionsFor = null; go(Screen.Editor(store.addSheet(g.id).id)) }
                    if (g.id != INBOX) ActionItem("New subgroup") { actionsFor = null; creatingIn = g.id; creating = true }
                    ActionItem("Preview & export group") {
                        actionsFor = null
                        val ids = store.descendants(g.id) + g.id
                        go(Screen.Preview(store.sheets.filter { it.trashed == 0L && it.group in ids }.sortedBy { it.created }.map { it.id }, g.name))
                    }
                    if (g.id != INBOX) ActionItem("Rename") { actionsFor = null; renaming = g }
                    if (g.id != INBOX) ActionItem("Delete group", Color(0xFFD9534F)) { actionsFor = null; deleting = g }
                }
            },
            confirmButton = { TextButton(onClick = { actionsFor = null }) { Text("Close") } }
        )
    }
    if (creating) {
        TextInputDialog(if (creatingIn == null) "New group" else "New subgroup", "", "Create", { creating = false }) {
            store.addGroup(it, creatingIn)
            creatingIn?.let { id -> expanded[id] = true }
        }
    }
    renaming?.let { g -> TextInputDialog("Rename group", g.name, "Rename", { renaming = null }) { store.renameGroup(g.id, it) } }
    deleting?.let { g ->
        ConfirmDialog("Delete \"${g.name}\" and its subgroups? Their sheets move to Trash.", "Delete", { deleting = null }) {
            store.deleteGroup(g.id)
        }
    }
}

@Composable
fun SheetsScreen(store: Store, settings: Settings, filter: Filter, back: () -> Unit, go: (Screen) -> Unit) {
    val p = LocalPalette.current
    var query by rememberSaveable { mutableStateOf("") }
    var sel by remember { mutableStateOf(setOf<String>()) }
    var menu by remember { mutableStateOf(false) }
    var selMenu by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val trash = filter.kind == "trash"
    val scope = filter.id?.let { store.descendants(it) + it } ?: emptySet()
    val list = store.sheets.filter { s ->
        if (trash) s.trashed > 0 else s.trashed == 0L && when (filter.kind) {
            "recent" -> s.updated > now() - WEEK
            "fav" -> s.favorite
            "group" -> s.group in scope
            "search" -> query.isNotBlank() && (s.text.contains(query, true) || s.notes.contains(query, true) ||
                s.keywords.any { it.contains(query, true) })
            else -> true
        }
    }.let { l ->
        when (settings.sort) {
            "created" -> l.sortedByDescending { it.created }
            "title" -> l.sortedBy { it.title.lowercase() }
            else -> l.sortedByDescending { it.updated }
        }
    }
    val title = when (filter.kind) {
        "all" -> "All Sheets"
        "recent" -> "Last 7 Days"
        "fav" -> "Favorites"
        "trash" -> "Trash"
        "search" -> "Search"
        else -> store.groups.firstOrNull { it.id == filter.id }?.name ?: "Group"
    }
    BackHandler(sel.isNotEmpty()) { sel = emptySet() }
    val fr = remember { FocusRequester() }
    if (filter.kind == "search") LaunchedEffect(Unit) {
        delay(150)
        runCatching { fr.requestFocus() }
    }
    val ordered = list.map { it.id }.filter { it in sel }

    Scaffold(
        containerColor = p.bg,
        topBar = {
            if (sel.isNotEmpty()) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = p.surface),
                    navigationIcon = { IconButton(onClick = { sel = emptySet() }) { Icon(Icons.Filled.Close, "Cancel") } },
                    title = { Text("${sel.size} selected") },
                    actions = {
                        if (trash) {
                            TextButton(onClick = { store.restore(sel); sel = emptySet() }) { Text("Restore") }
                            TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
                        } else {
                            IconButton(onClick = {
                                val fav = !list.filter { it.id in sel }.all { it.favorite }
                                store.edit(sel) { it.copy(favorite = fav) }
                            }) { Icon(Icons.Outlined.Star, "Favorite") }
                            IconButton(onClick = { moving = true }) { FolderGlyph(p.text) }
                            if (sel.size > 1) TextButton(onClick = { store.glue(ordered); sel = emptySet() }) { Text("Glue") }
                            IconButton(onClick = { store.trash(sel); sel = emptySet() }) { Icon(Icons.Outlined.Delete, "Move to Trash") }
                            Box {
                                IconButton(onClick = { selMenu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                                DropdownMenu(selMenu, { selMenu = false }) {
                                    if (sel.size == 1) DropdownMenuItem(text = { Text("Duplicate") }, onClick = {
                                        store.duplicate(sel.first()); selMenu = false; sel = emptySet()
                                    })
                                    DropdownMenuItem(text = { Text("Preview & export") }, onClick = {
                                        selMenu = false
                                        val t = if (ordered.size == 1) store.sheet(ordered[0])?.title ?: title else title
                                        go(Screen.Preview(ordered, t))
                                        sel = emptySet()
                                    })
                                }
                            }
                        }
                    }
                )
            } else {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = p.bg),
                    navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    title = {
                        if (filter.kind == "search") {
                            BasicTextField(
                                query, { query = it }, Modifier.fillMaxWidth().focusRequester(fr), singleLine = true,
                                textStyle = TextStyle(fontSize = 18.sp, color = p.text), cursorBrush = SolidColor(p.accent),
                                decorationBox = { inner ->
                                    Box {
                                        if (query.isEmpty()) Text("Search text, notes, keywords", fontSize = 18.sp, color = p.muted)
                                        inner()
                                    }
                                }
                            )
                        } else {
                            Text(title, fontWeight = FontWeight.SemiBold)
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Options") }
                            DropdownMenu(menu, { menu = false }) {
                                listOf("modified" to "Sort by date modified", "created" to "Sort by date created", "title" to "Sort by title").forEach { (k, l) ->
                                    DropdownMenuItem(
                                        text = { Text(l) },
                                        onClick = { settings.sort = k; menu = false },
                                        trailingIcon = { if (settings.sort == k) Icon(Icons.Filled.Check, null) }
                                    )
                                }
                                if (!trash && list.isNotEmpty()) DropdownMenuItem(text = { Text("Preview & export all") }, onClick = {
                                    menu = false; go(Screen.Preview(list.map { it.id }, title))
                                })
                                if (trash && list.isNotEmpty()) DropdownMenuItem(text = { Text("Empty Trash") }, onClick = {
                                    menu = false; confirmEmpty = true
                                })
                            }
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!trash && filter.kind != "search" && sel.isEmpty()) {
                FloatingActionButton(
                    onClick = {
                        val s = store.addSheet(if (filter.kind == "group") filter.id!! else INBOX)
                        if (filter.kind == "fav") store.update(s.copy(favorite = true))
                        go(Screen.Editor(s.id))
                    },
                    shape = CircleShape,
                    containerColor = p.accent,
                    contentColor = if (p.dark) Color.Black else Color.White
                ) { Icon(Icons.Filled.Edit, "New sheet") }
            }
        }
    ) { pad ->
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    when {
                        trash -> "Trash is empty"
                        filter.kind == "search" && query.isBlank() -> "Type to search every sheet"
                        filter.kind == "search" -> "No matches"
                        else -> "No sheets yet.\nTap the pencil to start writing."
                    },
                    color = p.muted, textAlign = TextAlign.Center, fontSize = 16.sp
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = pad) {
                items(list, key = { it.id }) { s ->
                    SheetRow(
                        s, s.id in sel,
                        onClick = { if (sel.isNotEmpty()) sel = sel.toggle(s.id) else go(Screen.Editor(s.id)) },
                        onLongClick = { sel = sel.toggle(s.id) }
                    )
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
    }

    if (moving) {
        AlertDialog(
            onDismissRequest = { moving = false },
            title = { Text("Move to") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    store.tree().forEach { (g, d) ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                store.edit(sel) { it.copy(group = g.id) }
                                sel = emptySet()
                                moving = false
                            }.padding(start = (d * 18).dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FolderGlyph(p.accent)
                            Spacer(Modifier.width(12.dp))
                            Text(g.name, fontSize = 16.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { moving = false }) { Text("Cancel") } }
        )
    }
    if (confirmEmpty) {
        ConfirmDialog("Permanently delete everything in Trash? This can't be undone.", "Empty Trash", { confirmEmpty = false }) {
            store.deleteForever(list.map { it.id })
        }
    }
    if (confirmDelete) {
        ConfirmDialog("Permanently delete ${sel.size} sheet(s)? This can't be undone.", "Delete", { confirmDelete = false }) {
            store.deleteForever(sel)
            sel = emptySet()
        }
    }
}

@Composable
fun SheetRow(s: Sheet, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val p = LocalPalette.current
    val c = remember(s.text) { counts(s.text) }
    Column(
        Modifier.fillMaxWidth().background(if (selected) p.accent.copy(alpha = 0.14f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(Modifier.padding(start = 24.dp, end = 20.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(s.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val ex = s.excerpt
                if (ex.isNotEmpty()) {
                    Text(
                        ex, Modifier.padding(top = 3.dp), fontSize = 14.sp, lineHeight = 19.sp, color = p.muted,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (s.favorite) {
                        Icon(Icons.Filled.Star, null, Modifier.size(13.dp), tint = p.accent)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        "${relTime(s.updated)} · ${fmt(c.words)} words",
                        fontSize = 12.sp, color = p.muted, maxLines = 1
                    )
                    s.keywords.take(3).forEach {
                        Text(
                            it, Modifier.padding(start = 6.dp).clip(RoundedCornerShape(4.dp)).background(p.surface)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                            fontSize = 11.sp, color = p.muted, maxLines = 1
                        )
                    }
                }
            }
            if (s.goal.target > 0) {
                Ring(
                    c.of(s.goal.unit).toFloat() / s.goal.target, ringColor(p, s.goal.state(c)), p.line,
                    Modifier.padding(start = 12.dp, top = 2.dp).size(22.dp)
                )
            }
        }
        HorizontalDivider(Modifier.padding(start = 24.dp), thickness = 0.5.dp, color = p.line)
    }
}

