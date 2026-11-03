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
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        col.addView(titleText("Arena Scanner", 28f))
        col.addView(mutedText("Version $version").apply { setPadding(0, dp(2), 0, dp(12)) })
        col.addView(card(titleText("Privacy", 17f), bodyText("Everything runs on your phone. Screenshots are read on the device and never uploaded. " +
            "The only network requests download the card list from Scryfall and check GitHub for app updates. " +
            "Your collection leaves the phone only when you export it.")))
        col.addView(card(titleText("Permissions", 17f), bodyText("• Screen capture: to read your collection and the game board.\n" +
            "• Appear on top: for the floating scan button.\n" +
            "• Notifications: Android requires one while the screen is captured.\n" +
            "• Accessibility (optional): only to swipe through your collection when auto-scroll is on.")))
        col.addView(card(titleText("Legal", 17f), mutedText("Arena Scanner is unofficial Fan Content permitted under the Fan Content Policy. " +
            "Not approved/endorsed by Wizards. Portions of the materials used are property of Wizards of the Coast. " +
            "©Wizards of the Coast LLC.\n\n" +
            "Card data from Scryfall (scryfall.com); Arena Scanner is not produced by or endorsed by Scryfall.\n\n" +
            "Text recognition by Google ML Kit.")))
        val scroll = page(col)
        setContentView(scroll)
    }
}
