package com.arenascanner.app

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** The app is installed from GitHub releases, not a store, so it checks for a newer build itself (once a day). */
object Updates {
    const val REPO = "DreamySPaghetti/I-Dont-know-yet"
    const val APK_URL = "https://github.com/$REPO/releases/latest/download/ArenaScanner.apk"

    /** Release tags are "v<build number>", the same number as the app's versionCode. */
    fun buildOf(tag: String): Int? = Regex("^v(\\d+)$").find(tag.trim())?.groupValues?.get(1)?.toInt()

    /** Newer build number if one exists, else null. Blocking; call off the main thread. */
    fun check(ctx: Context, force: Boolean = false): Int? {
        val p = Prefs.get(ctx)
        val now = System.currentTimeMillis()
        if (!force && now - p.getLong("update_checked", 0) < 86_400_000L) return p.getInt("update_build", 0).takeIf { it > current(ctx) }
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 10000; c.readTimeout = 10000
        return try {
            if (c.responseCode != 200) null else {
                val b = buildOf(JSONObject(c.inputStream.bufferedReader().readText()).getString("tag_name")) ?: 0
                p.edit().putLong("update_checked", now).putInt("update_build", b).apply()
                b.takeIf { it > current(ctx) }
            }
        } finally { c.disconnect() }
    }

    fun current(ctx: Context): Int = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()
}
