package dev.burixon.mockgps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import dev.burixon.mockgps.core.AutomationCommand
import dev.burixon.mockgps.core.ShortcutAuthority
import dev.burixon.mockgps.core.ShortcutAuthorityStore
import java.util.UUID

/** Standard Android shortcuts usable by any compatible launcher or automation host. */
object AutomationShortcuts {
    private const val ACTION = "dev.abhik.mockgps.AUTOMATION_SHORTCUT"
    // New integrations require explicit opt-in consent; command IDs describe the public API.
    private const val ID_PREFIX = "automation_"
    private var needsRefresh = true
    private val commands = listOf(
        AutomationCommand.PAUSE,
        AutomationCommand.REQUEST_SIMULATION,
        AutomationCommand.UNKNOWN,
        AutomationCommand.STOP,
        AutomationCommand.STATUS,
    )
    private val ids = commands.map { ID_PREFIX + it.shortcutName }
    private fun prefs(context: Context) = context.getSharedPreferences("automation_shortcuts", Context.MODE_PRIVATE)
    private fun authority(context: Context) = ShortcutAuthority(object : ShortcutAuthorityStore {
        override fun read(): Map<String, String> = commands.mapNotNull { command ->
            prefs(context).getString("nonce_${command.shortcutName}", null)?.let { command.shortcutName to it }
        }.toMap()

        override fun write(commands: Map<String, String>) {
            val editor = prefs(context).edit()
            for (command in AutomationShortcuts.commands) {
                editor.remove("nonce_${command.shortcutName}")
            }
            commands.forEach { (command, nonce) -> editor.putString("nonce_$command", nonce) }
            check(editor.commit()) { "Shortcut authority storage unavailable" }
        }
    })

    /** Consent changes revoke any previously issued request before fresh publication. */
    internal fun invalidateAuthority(context: Context) {
        authority(context).publish(emptyMap())
        needsRefresh = true
    }

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("enabled", false)

    internal fun setEnabled(context: Context, value: Boolean) {
        // Consent changes and capability revocation share one durable transaction.
        val editor = prefs(context).edit().putBoolean("enabled", value)
        commands.forEach { editor.remove("nonce_${it.shortcutName}") }
        check(editor.commit())
        needsRefresh = true
        if (!publish(context) && value) {
            check(prefs(context).edit().putBoolean("enabled", false).commit())
            error("Shortcut publication unavailable")
        }
    }

    fun publish(context: Context): Boolean {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: run {
            runCatching { authority(context).publish(emptyMap()) }
            needsRefresh = true
            return false
        }
        return try {
            val legacyIds = (manager.dynamicShortcuts + manager.pinnedShortcuts)
                .map { it.id }.filter { it.startsWith("samsung_") }.distinct()
            if (legacyIds.isNotEmpty()) {
                manager.disableShortcuts(legacyIds, "Refresh app shortcuts in MockGPS")
                manager.removeDynamicShortcuts(legacyIds)
            }
            if (!enabled(context)) {
                authority(context).publish(emptyMap())
                manager.disableShortcuts(ids, "App shortcuts disabled")
                manager.removeDynamicShortcuts(ids)
                true
            } else {
                // Four entries are required so a host never publishes observations without Stop.
                if (manager.maxShortcutCountPerActivity < 4) {
                    authority(context).publish(emptyMap())
                    return false
                }
                val selected = commands.take(manager.maxShortcutCountPerActivity)
                val selectedIds = selected.map { ID_PREFIX + it.shortcutName }.toSet()
                val existing = manager.dynamicShortcuts
                if (!needsRefresh && existing.all { it.isEnabled } &&
                    existing.map { it.id }.toSet() == selectedIds) return true
                // A failed replacement must not leave any previously issued capability usable.
                authority(context).publish(emptyMap())
                needsRefresh = true
                val tokens = selected.associate { it.shortcutName to UUID.randomUUID().toString() }
                val shortcuts = selected.mapIndexed { rank, command ->
                    val label = when (command) {
                        AutomationCommand.PAUSE -> "Pause requests"
                        AutomationCommand.REQUEST_SIMULATION -> "Request simulation"
                        AutomationCommand.UNKNOWN -> "Context unknown"
                        AutomationCommand.STOP -> "Stop simulation"
                        AutomationCommand.STATUS -> "Open status"
                        AutomationCommand.SELECT_PRESET -> error("Not a shortcut")
                    }
                    ShortcutInfo.Builder(context, ID_PREFIX + command.shortcutName)
                        .setActivity(ComponentName(context, MainActivity::class.java))
                        .setShortLabel(label)
                        .setLongLabel(label)
                        .setRank(rank)
                        .setIcon(Icon.createWithResource(context, when (command) {
                            AutomationCommand.PAUSE -> android.R.drawable.presence_online
                            AutomationCommand.REQUEST_SIMULATION -> android.R.drawable.presence_offline
                            AutomationCommand.UNKNOWN -> android.R.drawable.ic_dialog_alert
                            AutomationCommand.STOP -> android.R.drawable.ic_menu_close_clear_cancel
                            AutomationCommand.STATUS -> android.R.drawable.ic_menu_info_details
                            AutomationCommand.SELECT_PRESET -> error("Not a shortcut")
                        }))
                        .setIntent(Intent(context, AutomationShortcutActivity::class.java).setAction(ACTION)
                            .putExtra("command", command.shortcutName)
                            .putExtra("nonce", tokens.getValue(command.shortcutName)))
                        .build()
                }
                manager.enableShortcuts(ids)
                if (!manager.setDynamicShortcuts(shortcuts)) false else {
                    authority(context).publish(tokens)
                    needsRefresh = false
                    true
                }
            }
        } catch (_: RuntimeException) {
            runCatching { authority(context).publish(emptyMap()) }
            needsRefresh = true
            false
        }
    }

    fun dispatch(context: Context, intent: Intent): Boolean = try {
        if (!enabled(context) || intent.action != ACTION) false else {
            val extras = intent.extras
            if (extras == null || extras.keySet() != setOf("command", "nonce")) false else {
                @Suppress("DEPRECATION") // Do not coerce untrusted extras into strings.
                val rawCommand = extras.get("command")
                @Suppress("DEPRECATION")
                val nonce = extras.get("nonce")
                val command = AutomationCommand.fromShortcut(rawCommand as? String)
                if (command == null || !authority(context).consume(command.shortcutName, nonce)) false else {
                    needsRefresh = true
                    ControlCenter.shortcutCommand(context, command.shortcutName)
                    true
                }
            }
        }
    } catch (_: RuntimeException) { false }
}
