package com.example.phonectrl

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Date

class MainActivity : Activity() {
    private lateinit var et: EditText
    private lateinit var setup: LinearLayout
    private lateinit var status: TextView
    private lateinit var svcTitle: TextView
    private lateinit var sched: TextView
    private lateinit var sv: ScrollView
    private val prefs by lazy { getSharedPreferences("p", 0) }

    private val night get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val bg get() = if (night) 0xFF101114.toInt() else 0xFFF3F4F7.toInt()
    private val cardC get() = if (night) 0xFF1C1E23.toInt() else Color.WHITE
    private val fg get() = if (night) 0xFFECEEF2.toInt() else 0xFF15171C.toInt()
    private val sub get() = if (night) 0xFF9AA0AB.toInt() else 0xFF5E6470.toInt()
    private val field get() = if (night) 0xFF26292F.toInt() else 0xFFEEF0F4.toInt()
    private val accent = 0xFF4F7CFF.toInt()
    private val green = 0xFF2E9E5B.toInt()
    private val red = 0xFFD9483B.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rr(c: Int, r: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }
    private fun lp(top: Int = 12) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    private fun label(s: String, size: Float = 15f, color: Int = fg, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); setLineSpacing(0f, 1.2f)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun btn(s: String, c: Int = accent, f: () -> Unit) = label(s, 15f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER; background = rr(c, 12)
        setPadding(dp(16), dp(13), dp(16), dp(13)); setOnClickListener { f() }
    }

    private fun input(hint: String) = EditText(this).apply {
        this.hint = hint; setHintTextColor(sub); setTextColor(fg); textSize = 16f
        background = rr(field, 12); setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun card(title: String, vararg kids: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = rr(cardC, 20)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        addView(label(title, 18f, bold = true))
        kids.forEach { addView(it, lp()) }
    }

    private fun row(vararg kids: View) = LinearLayout(this).apply {
        kids.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(10) }) }
    }

    private fun step(title: TextView, desc: String, action: String, f: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = rr(field, 14)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        addView(title)
        addView(label(desc, 14f, sub), lp(4))
        addView(btn(action) { f() }, lp(10))
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        actionBar?.hide()
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        status = label("", 13f, Color.WHITE, true).apply { setPadding(dp(12), dp(6), dp(12), dp(6)) }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label("PhoneCtrl", 28f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(status)
        }

        svcTitle = label("", 16f, bold = true)
        setup = card("Setup guide",
            label("Takes about a minute. Do these in order.", 14f, sub),
            step(label("1   Allow restricted settings", 16f, bold = true),
                "Only needed if the PhoneCtrl switch is greyed out. In App info tap ⋮ (top right) → Allow restricted settings.",
                "Open app info") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            },
            step(svcTitle,
                "Accessibility → Installed/Downloaded apps → PhoneCtrl → turn on. Then come back here.",
                "Open accessibility") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            step(label("3   Test it", 16f, bold = true),
                "Goes to your home screen, then pulls down notifications.",
                "Run test") { go("home then wait 1 then notifications") },
            label("Done, hide guide", 15f, accent, true).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(2))
                setOnClickListener { prefs.edit().putBoolean("setupDone", true).apply(); setup.visibility = View.GONE }
            }
        )

        et = input("open chrome then tap Search then type pizza then enter").apply {
            minLines = 3; gravity = Gravity.TOP; setText(prefs.getString("s", ""))
        }
        val chips = LinearLayout(this)
        listOf("open ", "tap ", "type ", "enter", "back", "home", "scroll down", "wait 2").forEach { c ->
            chips.addView(label(c.trim(), 14f).apply {
                background = rr(field, 18); setPadding(dp(14), dp(8), dp(14), dp(8)); setOnClickListener { insert(c) }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        val cmd = card("Command", et,
            HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) },
            row(btn("🎤  Speak", 0xFF6A5AE0.toInt()) { voice() }, btn("▶  Run", green) { go(et.text.toString()) }))

        val mins = input("Minutes").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        sched = label("", 14f, sub)
        val schedCard = card("Schedule",
            label("Runs the command above later. Phone must be unlocked when it fires.", 14f, sub),
            row(mins, btn("Schedule") {
                val t = System.currentTimeMillis() + (mins.text.toString().toLongOrNull() ?: 1) * 60000
                prefs.edit().putString("s", et.text.toString()).putLong("next", t).apply()
                (getSystemService(ALARM_SERVICE) as AlarmManager).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t,
                    PendingIntent.getBroadcast(this, 0, Intent(this, Fire::class.java), PendingIntent.FLAG_IMMUTABLE))
                refresh()
            }),
            sched)

        val help = card("Commands",
            label("open <app name>\ntap <visible text>  ·  tap x y\nswipe x1 y1 x2 y2 [ms]\nscroll up · scroll down\ntype <text>  ·  enter\nback · home · recents · notifications\nwait <seconds>\n\nChain with \"then\", \";\" or new lines.", 14f, sub).apply {
                typeface = Typeface.MONOSPACE
            },
            label("Show setup guide", 15f, accent, true).apply {
                setOnClickListener { setup.visibility = View.VISIBLE; sv.smoothScrollTo(0, 0) }
            })

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(32))
            addView(header); addView(setup, lp(20)); addView(cmd, lp(16)); addView(schedCard, lp(16)); addView(help, lp(16))
        }
        sv = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true; addView(col) }
        sv.setOnApplyWindowInsetsListener { v, i ->
            val s = i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(s.left, s.top, s.right, s.bottom); i
        }
        setContentView(sv)
        if (!night) window.insetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun enabled() = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?.contains("$packageName/") == true

    private fun refresh() {
        val on = enabled()
        status.text = if (on) "● Active" else "● Off"
        status.background = rr(if (on) green else red, 14)
        svcTitle.text = if (on) "2   Service is on  ✓" else "2   Turn on the PhoneCtrl service"
        svcTitle.setTextColor(if (on) green else fg)
        setup.visibility = if (on && prefs.getBoolean("setupDone", false)) View.GONE else View.VISIBLE
        val t = prefs.getLong("next", 0)
        sched.text = if (t > System.currentTimeMillis()) "Next run: " + DateFormat.getTimeFormat(this).format(Date(t)) else "Nothing scheduled"
    }

    private fun insert(c: String) {
        val s = et.text.toString().trimEnd()
        et.setText(if (s.isEmpty()) c else "$s then $c")
        et.setSelection(et.text.length); et.requestFocus()
    }

    private fun voice() = startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM), 1)

    private fun go(s: String) {
        val svc = CtrlService.inst ?: run {
            Toast.makeText(this, "Turn on the service first (setup step 2)", Toast.LENGTH_LONG).show()
            setup.visibility = View.VISIBLE; sv.smoothScrollTo(0, 0); return
        }
        if (s.isBlank()) return
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
