package com.pixelporters.game

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val t = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(SystemBarStyle.dark(t), SystemBarStyle.dark(t))
        val prefs = getSharedPreferences("game", Context.MODE_PRIVATE)
        setContent { GameApp(prefs) }
    }
}

@Composable
fun GameApp(prefs: SharedPreferences) {
    var level by remember { mutableIntStateOf(prefs.getInt("level", 1)) }
    var attempt by remember { mutableIntStateOf(0) }
    var fast by remember { mutableStateOf(prefs.getBoolean("fast", false)) }
    val game = remember(level, attempt) { Game(level) }
    game.speed = if (fast) 2f else 1f
    var frame by remember { mutableLongStateOf(0L) }
    var status by remember(game) { mutableIntStateOf(0) }
    val tm = rememberTextMeasurer()
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(game) {
        while (true) {
            withFrameMillis { t ->
                game.update(t)
                frame = t
                if (status != game.status) status = game.status
            }
        }
    }

    Box(Modifier.fillMaxSize().background(BG).systemBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Level $level", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(game.pic.name, color = MUTED, fontSize = 14.sp)
                }
                Text(
                    if (fast) "2×" else "1×",
                    Modifier.clip(RoundedCornerShape(10.dp)).background(if (fast) ACCENT else PANEL)
                        .clickable { fast = !fast; prefs.edit().putBoolean("fast", fast).apply() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    color = if (fast) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp
                )
                IconButton(onClick = { attempt++ }) { Icon(Icons.Filled.Refresh, "Restart level", tint = MUTED) }
            }
            Canvas(
                Modifier.fillMaxSize().pointerInput(game) {
                    detectTapGestures { pos ->
                        val wp = Scene(game, size.toSize()).world(pos)
                        val x = kotlin.math.floor(wp.x - game.bx0).toInt()
                        val y = kotlin.math.floor(wp.y + 0.15f).toInt()
                        if (x in 0 until game.w && y in 0 until game.h) {
                            val r = game.tapCell(y * game.w + x)
                            if (r != 0) haptic.performHapticFeedback(if (r == 1) HapticFeedbackType.TextHandleMove else HapticFeedbackType.LongPress)
                        }
                    }
                }
            ) {
                frame
                drawGame(game, Scene(game, size), tm, density)
            }
        }

        if (status == 1) {
            EndCard("Picture complete!", "You finished “${game.pic.name}”.") {
                Button(
                    onClick = {
                        level++
                        attempt = 0
                        prefs.edit().putInt("level", level).apply()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ACCENT, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Next level", fontWeight = FontWeight.Bold) }
            }
        }
        if (status == 2) {
            EndCard("Jammed!", "The sorting space is full and no hole can take what's left. Plan the order and try again.") {
                if (!game.rescued) {
                    Button(
                        onClick = { game.rescue(); status = 0 },
                        colors = ButtonDefaults.buttonColors(containerColor = ACCENT, contentColor = Color.Black),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Add an extra slot (free)", fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedButton(onClick = { attempt++ }, modifier = Modifier.fillMaxWidth()) { Text("Retry level", color = Color.White) }
            }
        }
    }
}

@Composable
private fun EndCard(title: String, body: String, buttons: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().clickable(enabled = false) {}, contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(PANEL).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(body, color = MUTED, fontSize = 15.sp)
            Spacer(Modifier.height(10.dp))
            buttons()
        }
    }
}

