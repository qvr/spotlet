package fi.qvr.spotlet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Brings the receiver up on boot (when enabled) and after an app update, so the device is a
 * Connect target without anyone opening the app.
 *
 * Android 15+ refuses to start a mediaPlayback foreground service from BOOT_COMPLETED; that
 * start is caught and logged. The old devices this app targets are unaffected.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        val wanted = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> prefs.enabled && prefs.startOnBoot
            Intent.ACTION_MY_PACKAGE_REPLACED -> prefs.enabled
            else -> false
        }
        if (!wanted) return
        runCatching { ReceiverService.start(context) }
            .onFailure { Log.w("BootReceiver", "Could not start receiver from ${intent.action}", it) }
    }
}
