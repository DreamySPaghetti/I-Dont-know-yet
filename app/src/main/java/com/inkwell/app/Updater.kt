package com.inkwell.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL

private const val REPO = "DreamySPaghetti/I-Dont-know-yet"

object UpdateTrigger {
    var n by androidx.compose.runtime.mutableIntStateOf(0)
    var manual = false
    fun check() {
        manual = true
        n++
    }
}

private fun latestVersion(): Int? = runCatching {
    val json = URL("https://api.github.com/repos/$REPO/releases/latest").readText()
    JSONObject(json).getString("tag_name").removePrefix("v").toInt()
}.getOrNull()

private fun download(ctx: Context): File {
    val f = File(ctx.cacheDir, "update.apk")
    URL("https://github.com/$REPO/releases/latest/download/Inkwell.apk").openStream().use { i ->
        f.outputStream().use { i.copyTo(it) }
    }
    return f
}

private fun install(ctx: Context, f: File) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    ctx.startActivity(
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

@Composable
fun UpdateCheck(store: Store) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var newer by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(UpdateTrigger.n) {
        val v = withContext(Dispatchers.IO) { latestVersion() }
        if (v != null && v > BuildConfig.VERSION_CODE) newer = v
        else if (UpdateTrigger.manual) {
            android.widget.Toast.makeText(ctx, if (v == null) "Couldn't check for updates" else "You're on the latest version", android.widget.Toast.LENGTH_SHORT).show()
        }
        UpdateTrigger.manual = false
    }
    val v = newer ?: return
    AlertDialog(
        onDismissRequest = { if (!busy) newer = null },
        title = { Text("Update available") },
        text = {
            Text(
                when {
                    busy -> "Downloading…"
                    failed -> "Download failed. Check your connection and try again."
                    else -> "Version 1.$v is ready (you have ${BuildConfig.VERSION_NAME}). Install now?"
                }
            )
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (!ctx.packageManager.canRequestPackageInstalls()) {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
                    return@TextButton
                }
                store.save()
                busy = true
                failed = false
                scope.launch {
                    val f = runCatching { withContext(Dispatchers.IO) { download(ctx) } }.getOrNull()
                    busy = false
                    if (f == null) failed = true else install(ctx, f)
                }
            }) { Text("Install") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { newer = null }) { Text("Later") } }
    )
}
