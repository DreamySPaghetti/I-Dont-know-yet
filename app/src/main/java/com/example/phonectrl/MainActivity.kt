package com.example.phonectrl

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var et: EditText
    private val prefs by lazy { getSharedPreferences("p", 0) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        et = EditText(this).apply {
            hint = "open chrome then tap Search then type pizza then enter"
            setText(prefs.getString("s", ""))
        }
        val mins = EditText(this).apply { hint = "minutes from now"; inputType = InputType.TYPE_CLASS_NUMBER }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 80, 40, 40)
            addView(btn("Enable service") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
            addView(et)
            addView(btn("Voice") {
                startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM), 1)
            })
            addView(btn("Run now") { go(et.text.toString()) })
            addView(mins)
            addView(btn("Schedule") {
                prefs.edit().putString("s", et.text.toString()).apply()
                val ms = (mins.text.toString().toLongOrNull() ?: 1) * 60000
                (getSystemService(ALARM_SERVICE) as AlarmManager).setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + ms,
                    PendingIntent.getBroadcast(this@MainActivity, 0, Intent(this@MainActivity, Fire::class.java), PendingIntent.FLAG_IMMUTABLE))
                Toast.makeText(this@MainActivity, "Scheduled", Toast.LENGTH_SHORT).show()
            })
        })
    }

    private fun go(s: String) {
        val svc = CtrlService.inst ?: return Toast.makeText(this, "Enable service first", Toast.LENGTH_SHORT).show()
        prefs.edit().putString("s", s).apply()
        moveTaskToBack(true)
        svc.run(s)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val t = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        et.setText(t)
        go(t)
    }
}
