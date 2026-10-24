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
        // Opening the normal UI is never considered confirmation of a simulation request.
        startActivity(Intent(this, MainActivity::class.java))
        if (!accepted) android.widget.Toast.makeText(this,
            "Shortcut expired or disabled. Reopen MockGPS and retry.", android.widget.Toast.LENGTH_LONG).show()
        finish()
    }
}
