@file:OptIn(ExperimentalFoundationApi::class)

package com.inkwell.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun FolderGlyph(color: Color, modifier: Modifier = Modifier) = Canvas(modifier.size(20.dp)) {
    val w = size.width
    val h = size.height
    val sw = w * 0.085f
    drawRoundRect(color = color, topLeft = Offset(sw / 2, h * 0.28f), size = Size(w - sw, h * 0.6f), cornerRadius = CornerRadius(w * 0.12f), style = Stroke(sw))
    drawRoundRect(color = color, topLeft = Offset(sw / 2, h * 0.16f), size = Size(w * 0.42f, h * 0.18f), cornerRadius = CornerRadius(w * 0.08f))
}

@Composable
fun Ring(progress: Float, color: Color, track: Color, modifier: Modifier = Modifier) = Canvas(modifier) {
    val sw = size.minDimension * 0.16f
    val tl = Offset(sw / 2, sw / 2)
    val sz = Size(size.width - sw, size.height - sw)
    drawArc(color = track, startAngle = 0f, sweepAngle = 360f, useCenter = false, topLeft = tl, size = sz, style = Stroke(sw))
    drawArc(
        color = color, startAngle = -90f, sweepAngle = 360f * progress.coerceIn(0f, 1f), useCenter = false,
        topLeft = tl, size = sz, style = Stroke(sw, cap = StrokeCap.Round)
    )
}

@Composable
fun SectionHeader(text: String) {
    val p = LocalPalette.current
    Text(
        text.uppercase(), Modifier.padding(start = 24.dp, top = 26.dp, bottom = 6.dp),
        color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp
    )
}

@Composable
fun FieldLabel(text: String) {
    val p = LocalPalette.current
    Text(
        text.uppercase(), Modifier.padding(top = 20.dp, bottom = 8.dp),
        color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp
    )
}

@Composable
fun NavRow(
    label: String,
    count: Int?,
    onClick: () -> Unit,
    indent: Int = 0,
    onLongClick: (() -> Unit)? = null,
    lead: @Composable () -> Unit = {},
    icon: @Composable () -> Unit
) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = 50.dp).padding(start = (8 + indent * 18).dp, end = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { lead() }
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(14.dp))
        Text(label, Modifier.weight(1f), fontSize = 16.sp, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (count != null) Text("$count", fontSize = 14.sp, color = p.muted)
    }
}

@Composable
fun ActionItem(label: String, color: Color = LocalPalette.current.text, onClick: () -> Unit) {
    Text(label, Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), fontSize = 16.sp, color = color)
}

@Composable
fun Choice(options: List<Pair<String, String>>, selected: String, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (k, l) -> FilterChip(selected = k == selected, onClick = { onSelect(k) }, label = { Text(l) }) }
    }
}

@Composable
fun Toggle(label: String, on: Boolean, set: (Boolean) -> Unit) = DropdownMenuItem(
    text = { Text(label) },
    onClick = { set(!on) },
    trailingIcon = { if (on) Icon(Icons.Filled.Check, null) }
)

@Composable
fun SwitchRow(title: String, sub: String, on: Boolean, set: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().clickable { set(!on) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = p.text, fontSize = 16.sp)
            Text(sub, color = p.muted, fontSize = 13.sp)
        }
        Switch(on, set)
    }
}

@Composable
fun TextInputDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { fr.requestFocus() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(v, { v = it }, Modifier.focusRequester(fr), singleLine = true) },
        confirmButton = {
            TextButton(onClick = { if (v.isNotBlank()) { onDone(v.trim()); onDismiss() } }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ConfirmDialog(text: String, confirm: String, onDismiss: () -> Unit, onYes: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onYes(); onDismiss() }) { Text(confirm, color = Color(0xFFD9534F)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
