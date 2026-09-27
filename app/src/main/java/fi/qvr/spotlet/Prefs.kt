package fi.qvr.spotlet

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import java.util.UUID

/** All persisted settings, in one SharedPreferences file. */
class Prefs(context: Context) {
    private val appContext = context.applicationContext
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("spotlet", Context.MODE_PRIVATE)

    /** Whether the receiver should be running. The service is (re)started from this. */
    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_ENABLED, v).apply()

    var deviceName: String
        get() = sp.getString(KEY_DEVICE_NAME, null)?.takeIf { it.isNotBlank() } ?: defaultDeviceName(appContext)
        set(v) = sp.edit().putString(KEY_DEVICE_NAME, v.trim()).apply()

    var bitrateKbps: Int
        get() = sp.getInt(KEY_BITRATE, DEFAULT_BITRATE).takeIf { it in BITRATES } ?: DEFAULT_BITRATE
        set(v) = sp.edit().putInt(KEY_BITRATE, v).apply()

    var startupVolumePercent: Int
        get() = sp.getInt(KEY_STARTUP_VOLUME, 50).coerceIn(0, 100)
        set(v) = sp.edit().putInt(KEY_STARTUP_VOLUME, v.coerceIn(0, 100)).apply()

    var startOnBoot: Boolean
        get() = sp.getBoolean(KEY_START_ON_BOOT, true)
        set(v) = sp.edit().putBoolean(KEY_START_ON_BOOT, v).apply()

    /** Duck/pause for other apps (voice assistants, announcements) via Android audio focus. */
    var handleAudioFocus: Boolean
        get() = sp.getBoolean(KEY_AUDIO_FOCUS, true)
        set(v) = sp.edit().putBoolean(KEY_AUDIO_FOCUS, v).apply()

    /** Fetch cover art for the media session. Off saves a little memory/bandwidth on tiny devices. */
    var albumArt: Boolean
        get() = sp.getBoolean(KEY_ALBUM_ART, true)
        set(v) = sp.edit().putBoolean(KEY_ALBUM_ART, v).apply()

    /**
     * Stable per-install id seed, so Spotify sees one device across renames and restarts.
     * The native side hashes it into the 40-hex Connect device id.
     */
    val deviceId: String
        get() = sp.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            sp.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_BITRATE = "bitrate_kbps"
        private const val KEY_STARTUP_VOLUME = "startup_volume"
        private const val KEY_START_ON_BOOT = "start_on_boot"
        private const val KEY_AUDIO_FOCUS = "audio_focus"
        private const val KEY_ALBUM_ART = "album_art"

        val BITRATES = listOf(96, 160, 320)
        const val DEFAULT_BITRATE = 320

        /**
         * "Spotlet (<device name>)", using the name set in Android's About screen, else the model.
         * Not persisted, so it follows the device name until the user sets their own.
         */
        fun defaultDeviceName(context: Context): String {
            val device = runCatching {
                Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                ?: Build.MODEL?.trim()?.takeIf { it.isNotEmpty() }
            return if (device != null) "Spotlet ($device)" else "Spotlet"
        }
    }
}
