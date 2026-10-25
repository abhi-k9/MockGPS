package dev.burixon.mockgps

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

internal object SensitiveClipboard {
    fun copy(context: Context, label: String, value: String) {
        val clipboard = context.applicationContext.getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText(label, value)
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        clipboard.setPrimaryClip(clip)
        Handler(Looper.getMainLooper()).postDelayed({
            // Best effort: background clipboard access is restricted on some Android versions.
            runCatching {
                val current = clipboard.primaryClip
                if (current?.let { it.itemCount == 1 && it.description.label == label && it.getItemAt(0).text == value } == true)
                    clipboard.clearPrimaryClip()
            }
        }, 60_000)
    }
}
