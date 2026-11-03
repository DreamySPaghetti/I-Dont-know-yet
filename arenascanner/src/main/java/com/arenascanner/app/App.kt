package com.arenascanner.app

import android.app.Application
import java.io.File

/** Saves any crash to a file so the next launch can offer to send it. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val f = crashFile(this)
                val entry = "Version ${packageManager.getPackageInfo(packageName, 0).versionName} · " +
                    "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}\n" +
                    "Thread ${t.name}\n${e.stackTraceToString()}\n\n"
                f.writeText((if (f.exists()) f.readText() else "").takeLast(20_000) + entry)
            }
            previous?.uncaughtException(t, e)
        }
    }

    companion object {
        fun crashFile(ctx: android.content.Context) = File(ctx.filesDir, "crash.txt")
    }
}
