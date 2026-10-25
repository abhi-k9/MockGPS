package dev.burixon.mockgps

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** System shortcut launch only; this non-exported activity never starts simulation. */
class AutomationShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val accepted = AutomationShortcuts.dispatch(this, intent)
        AutomationShortcuts.publish(this)
        // Unattended commands must not launch the visible UI to recreate a missing session.
        // Only the explicit status action opens the app in this mode.
        val openStatus = accepted && runCatching {
            @Suppress("DEPRECATION")
            intent.extras?.get("command") == "status"
        }.getOrDefault(false)
        if (!AutomationSettings.unattendedEnabled(this) || openStatus) {
            startActivity(Intent(this, MainActivity::class.java))
        }
        if (!accepted) android.widget.Toast.makeText(this,
            "Shortcut expired or disabled. Reopen MockGPS and retry.", android.widget.Toast.LENGTH_LONG).show()
        finish()
    }
}
