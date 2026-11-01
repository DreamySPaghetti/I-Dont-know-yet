package com.example.phonectrl

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("p", 0) }
    private var draft = ""
    private var showSetup = false
    private val W = ViewGroup.LayoutParams.WRAP_CONTENT
    private val M = ViewGroup.LayoutParams.MATCH_PARENT

    private val dark get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val cBg get() = if (dark) 0xFF0F1115.toInt() else 0xFFF4F5F8.toInt()
    private val cCard get() = if (dark) 0xFF1A1D24.toInt() else Color.WHITE
    private val cText get() = if (dark) 0xFFECEDEF.toInt() else 0xFF14161A.toInt()
    private val cSub get() = if (dark) 0xFF9AA0AA.toInt() else 0xFF5F6670.toInt()
    private val cLine get() = if (dark) 0xFF2A2E37.toInt() else 0xFFE3E5EA.toInt()
    private val accent = 0xFF5B5BF0.toInt()
    private val ok = 0xFF16A34A.toInt()
    private val bad = 0xFFE11D48.toInt()

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
    private fun round(c: Int, r: Int = 16, s: Int? = null) = GradientDrawable().apply {
        setColor(c); cornerRadius = r.dp.toFloat(); s?.let { setStroke(1.dp, it) }
    }
    private fun lp(w: Float = 1f, left: Int = 0) = LinearLayout.LayoutParams(0, W, w).apply { leftMargin = left }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun label(s: String, size: Float, c: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(c); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun btn(s: String, primary: Boolean, f: () -> Unit) = TextView(this).apply {
        text = s; textSize = 15f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (primary) Color.WHITE else cText)
        background = RippleDrawable(ColorStateList.valueOf(0x33888888), round(if (primary) accent else cCard, 14, if (primary) null else cLine), null)
        setPadding(16.dp, 13.dp, 16.dp, 13.dp); setOnClickListener { f() }
    }
    private fun input(h: String) = EditText(this).apply {
        hint = h; setTextColor(cText); setHintTextColor(cSub); textSize = 16f
        background = round(cBg, 14, cLine); setPadding(14.dp, 12.dp, 14.dp, 12.dp)
    }
    private fun card(title: String, vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = round(cCard, 20, cLine); setPadding(16.dp, 16.dp, 16.dp, 16.dp)
        layoutParams = LinearLayout.LayoutParams(M, W).apply { bottomMargin = 14.dp }
        addView(label(title, 17f, cText, true).apply { setPadding(0, 0, 0, 10.dp) })
        views.forEach { addView(it) }
    }

    private fun svcOn() = (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains("$packageName/")
    private fun batteryOk() = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        draft = prefs.getString("s", "")!!
        showSetup = !prefs.getBoolean("seen", false)
    }
    override fun onResume() { super.onResume(); build() }
    override fun onPause() { super.onPause(); prefs.edit().putString("s", draft).apply() }

    private fun build() {
        val ready = svcOn()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20.dp, 16.dp, 20.dp, 24.dp) }
        col.addView(row().apply {
            addView(label("PhoneCtrl", 26f, cText, true), lp())
            addView(label(if (ready) "● Ready" else "● Setup needed", 12f, if (ready) ok else bad, true).apply {
                background = round(((if (ready) ok else bad) and 0x00FFFFFF) or 0x22000000, 50)
                setPadding(10.dp, 5.dp, 10.dp, 5.dp)
            })
        })
        col.addView(label("Control your phone with typed or spoken commands.", 14f, cSub).apply { setPadding(0, 4.dp, 0, 16.dp) })
        if (!ready || showSetup) col.addView(setupCard(ready))
        col.addView(commandCard())
        col.addView(scheduleCard())
        col.addView(helpCard())
        if (ready) col.addView(label(if (showSetup) "Hide setup guide" else "Show setup guide", 14f, accent, true).apply {
            gravity = Gravity.CENTER; setPadding(0, 8.dp, 0, 8.dp)
            setOnClickListener { showSetup = !showSetup; build() }
        })
        setContentView(ScrollView(this).apply {
            setBackgroundColor(cBg)
            addView(col)
            setOnApplyWindowInsetsListener { v, ins ->
                val i = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(i.left, i.top, i.right, i.bottom); ins
            }
        })
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (dark) 0 else light, light)
    }

    private fun setupCard(ready: Boolean): View {
        if (ready) prefs.edit().putBoolean("seen", true).apply()
        return card("Startup helper",
            step(1, "Allow restricted settings", "Only needed if the PhoneCtrl switch in step 2 is greyed out. In App info tap ⋮ (top right) → Allow restricted settings.", if (ready) true else null, "Open app info") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            },
            step(2, "Turn on the PhoneCtrl service", "Accessibility → Installed apps (or Downloaded apps) → PhoneCtrl → On. This is what lets the app tap and type for you.", ready, "Open accessibility") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            step(3, "Let schedules run in the background", "Stops Android from delaying or killing scheduled commands.", batteryOk(), "Allow") {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            },
            step(4, "Try a test command", "Loads a safe example into the box below. Then tap Run.", null, "Load example") {
                draft = "home then wait 1 then open settings"; showSetup = false; build()
            })
    }

    private fun step(n: Int, t: String, d: String, done: Boolean?, action: String, f: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, 6.dp, 0, 10.dp)
        addView(label(if (done == true) "✓" else "$n", 14f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (done == true) ok else accent) }
        }, LinearLayout.LayoutParams(28.dp, 28.dp).apply { rightMargin = 12.dp })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(t, 15f, cText, true))
            addView(label(d, 13f, cSub).apply { setPadding(0, 2.dp, 0, 8.dp) })
            if (done != true) addView(btn(action, false, f), LinearLayout.LayoutParams(W, W))
        }, lp())
    }

    private fun commandCard(): View {
        val et = input("e.g. open chrome then tap Search then type pizza then enter").apply {
            setText(draft); minLines = 3; gravity = Gravity.TOP
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { draft = s.toString() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        val chips = row()
        listOf("open ", "tap ", "type ", "enter", "wait 1", "scroll down", "back", "home").forEach { c ->
            chips.addView(label(c.trim(), 13f, cText).apply {
                background = round(cBg, 50, cLine); setPadding(12.dp, 7.dp, 12.dp, 7.dp)
                setOnClickListener {
                    val cur = et.text.toString().trimEnd()
                    et.setText(if (cur.isEmpty()) c else "$cur then $c"); et.setSelection(et.text.length); et.requestFocus()
                }
            }, LinearLayout.LayoutParams(W, W).apply { rightMargin = 8.dp })
        }
        return card("Command", et,
            HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setPadding(0, 10.dp, 0, 12.dp); addView(chips) },
            row().apply {
                addView(btn("🎤  Speak", false) { voice() }, lp())
                addView(btn("▶  Run", true) { go(draft) }, lp(1f, 10.dp))
            })
    }

    private fun scheduleCard(): View {
        val mins = input("Minutes").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        return card("Schedule",
            label("Runs the command above later. Phone must be unlocked when it fires.", 13f, cSub).apply { setPadding(0, 0, 0, 10.dp) },
            row().apply {
                addView(mins, lp())
                addView(btn("Schedule", true) { schedule(mins.text.toString().toLongOrNull() ?: 1) }, lp(1f, 10.dp))
            })
    }

    private fun helpCard() = card("Commands",
        label(listOf(
            "open <app>" to "launch an app by name",
            "tap <text>" to "tap a button/label on screen",
            "tap x y" to "tap a screen position",
            "type <text>" to "fill the selected text box",
            "enter" to "submit / search",
            "swipe x1 y1 x2 y2" to "drag a finger",
            "scroll up | down" to "scroll the page",
            "wait <sec>" to "pause",
            "back · home · recents · notifications" to "system buttons"
        ).joinToString("\n") { "${it.first}  —  ${it.second}" } + "\n\nJoin steps with \"then\", \";\" or new lines.", 13f, cSub).apply { setLineSpacing(4.dp.toFloat(), 1f) })

    private fun go(s: String) {
        val svc = CtrlService.inst
        if (svc == null || !svcOn()) {
            Toast.makeText(this, "Turn on the PhoneCtrl service first", Toast.LENGTH_SHORT).show()
            showSetup = true; build(); return
        }
        if (s.isBlank()) return
        prefs.edit().putString("s", s).apply()
        moveTaskToBack(true)
        svc.run(s)
    }

    private fun voice() {
        try {
            startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a command"), 1)
        } catch (e: Exception) {
            Toast.makeText(this, "No voice input app found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun schedule(m: Long) {
        prefs.edit().putString("s", draft).apply()
        (getSystemService(ALARM_SERVICE) as AlarmManager).setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + m * 60000,
            PendingIntent.getBroadcast(this, 0, Intent(this, Fire::class.java), PendingIntent.FLAG_IMMUTABLE))
        Toast.makeText(this, "Runs in $m min", Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val t = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        draft = t
        go(t)
    }
}
