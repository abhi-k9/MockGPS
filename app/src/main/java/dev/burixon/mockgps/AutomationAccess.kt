package dev.burixon.mockgps

import android.content.Context
import java.security.SecureRandom

/** Private credential-protected preferences; excluded from cloud backup and device transfer. */
object AutomationAccess {
    private fun prefs(context: Context) = context.getSharedPreferences("automation_access", Context.MODE_PRIVATE)
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean("enabled", false)
    fun token(context: Context): String? = if (isEnabled(context)) prefs(context).getString("token", null) else null
    internal fun setEnabled(context: Context, enabled: Boolean) {
        val p = prefs(context)
        if (!enabled) { check(p.edit().putBoolean("enabled", false).remove("token").commit()); return }
        val token = p.getString("token", null) ?: ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(p.edit().putString("token", token).putBoolean("enabled", true).commit())
    }
    fun revoke(context: Context): Boolean = ControlCenter.setIntegrationEnabled(context, false)
}
