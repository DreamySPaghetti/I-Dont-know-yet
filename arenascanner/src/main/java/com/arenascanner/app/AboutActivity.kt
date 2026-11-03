package com.arenascanner.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Version, privacy and the legal notices a fan-made Magic app needs. */
class AboutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        val scroll = ScrollView(this).apply { addView(col) }
        scroll.setOnApplyWindowInsetsListener { v, i ->
            val b = i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()); v.setPadding(b.left, b.top, b.right, b.bottom); i
        }
        fun h(t: String) = TextView(this).apply { text = t; textSize = 17f; setTypeface(typeface, Typeface.BOLD); setPadding(0, pad, 0, pad / 4) }
        fun p(t: String) = TextView(this).apply { text = t; textSize = 14f; setTextColor(context.secondaryText()); setLineSpacing(0f, 1.15f) }
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        col.addView(TextView(this).apply { text = "Arena Scanner"; textSize = 24f; setTypeface(typeface, Typeface.BOLD) })
        col.addView(p("Version $version"))
        col.addView(h("Privacy"))
        col.addView(p("Everything runs on your phone. Screenshots are read on the device and never uploaded. " +
            "The move coach uses Gemma running locally. The only network requests download the card list from Scryfall " +
            "and, if you choose, the Gemma model from Hugging Face. Match logs and lessons leave the phone only when you export them."))
        col.addView(h("Permissions"))
        col.addView(p("• Screen capture: to read your collection and the game board.\n" +
            "• Appear on top: for the floating button and the advice panel.\n" +
            "• Notifications: Android requires one while the screen is captured.\n" +
            "• Accessibility (optional): only to swipe through your collection when auto-scroll is on."))
        col.addView(h("Legal"))
        col.addView(p("Arena Scanner is unofficial Fan Content permitted under the Fan Content Policy. " +
            "Not approved/endorsed by Wizards. Portions of the materials used are property of Wizards of the Coast. " +
            "©Wizards of the Coast LLC.\n\n" +
            "Card data from Scryfall (scryfall.com); Arena Scanner is not produced by or endorsed by Scryfall.\n\n" +
            "Gemma is provided by Google under its own terms. Text recognition by Google ML Kit."))
        setContentView(scroll)
    }
}
