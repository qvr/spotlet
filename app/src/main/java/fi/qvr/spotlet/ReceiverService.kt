package fi.qvr.spotlet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The whole receiver: a foreground service that owns the native Connect runtime, mirrors its
 * playback into a [MediaSessionCompat] (so the system media controls, lock screen, and anything
 * else that reads media sessions see it), and arbitrates Android audio focus.
 *
 * Threading: native callbacks arrive on Rust/Tokio threads and are posted to the main thread;
 * all state below is main-thread-only. Native lifecycle calls (start/stop, which block while an
 * old receiver shuts down) are serialised on the process-wide [nativeExecutor], so a stop from a
 * dying service instance can never land after the next instance's start.
 */
class ReceiverService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val artExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var prefs: Prefs
    private lateinit var session: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** (name, bitrate) the native receiver was last started with; null when not started. */
    private var started: Pair<String, Int>? = null
    private var connectedUser: String? = null
    private var track: Track? = null
    private var artUrl: String? = null
    private var art: Bitmap? = null

    private var focusRequest: AudioFocusRequestCompat? = null
    private var pausedForFocus = false
    private var ducked = false

    private data class Track(
        val state: String,
        val title: String?,
        val artist: String?,
        val album: String?,
        val positionMs: Long,
        val durationMs: Long,
        val coverUrl: String?,
    ) {
        val isPlaying get() = state == "PLAYING"
    }

    // ---- lifecycle -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        session = MediaSessionCompat(this, "Spotlet").apply {
            setCallback(sessionCallback, main)
            setPlaybackState(playbackState(PlaybackStateCompat.STATE_NONE, 0))
            setSessionActivity(openAppIntent())
        }

        // startForeground must happen promptly (ANR otherwise), before any native work.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )

        active = this
        acquireLocks()
        if (NativeBridge.loaded) {
            NativeBridge.initAndroidContext(applicationContext, cacheDir.absolutePath)
            NativeBridge.initLogger()
        }
        notifyStatus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.enabled = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PLAY -> ifNative { NativeBridge.play() }
            ACTION_PAUSE -> ifNative { NativeBridge.pause() }
            ACTION_NEXT -> ifNative { NativeBridge.nextTrack() }
            ACTION_PREVIOUS -> ifNative { NativeBridge.previousTrack() }
            else -> applySettings()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        active = null
        abandonFocus()
        session.isActive = false
        session.release()
        if (NativeBridge.loaded) nativeExecutor.execute { NativeBridge.stopDevice() }
        artExecutor.shutdownNow()
        multicastLock?.takeIf { it.isHeld }?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        started = null
        notifyStatus()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** (Re)starts or renames the native receiver to match the current settings. */
    private fun applySettings() {
        if (!NativeBridge.loaded) {
            Log.e(TAG, "Native core not bundled in this build; receiver cannot start")
            updateNotification()
            return
        }
        val name = prefs.deviceName
        val bitrate = prefs.bitrateKbps
        val volume = prefs.startupVolumePercent
        val deviceId = prefs.deviceId
        val previous = started
        started = name to bitrate
        updateNotification()
        notifyStatus()

        nativeExecutor.execute {
            NativeBridge.setStartupVolume(volume)
            when {
                previous == name to bitrate -> Unit
                // Name-only change: re-advertise in place, keeping any session alive.
                previous != null && previous.second == bitrate -> NativeBridge.renameDevice(name)
                // First start, or a bitrate change (needs a fresh player): (re)start.
                else -> runCatching { NativeBridge.startDevice(name, deviceId, bitrate, volume) }
                    .onFailure { e ->
                        Log.e(TAG, "Native receiver failed to start", e)
                        main.post { started = null; notifyStatus() }
                    }
            }
        }
    }

    private fun ifNative(block: () -> Unit) {
        if (NativeBridge.loaded) block()
    }

    private fun acquireLocks() {
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("spotlet:mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
        val power = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "spotlet:receiver").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    // ---- native events (main thread) -------------------------------------------------------

    private fun onConnected(username: String?) {
        connectedUser = username?.takeIf { it.isNotBlank() }
        session.isActive = true
        updateNotification()
        notifyStatus()
    }

    private fun onDisconnected() {
        connectedUser = null
        track = null
        artUrl = null
        art = null
        pausedForFocus = false
        abandonFocus()
        session.setMetadata(null)
        session.setPlaybackState(playbackState(PlaybackStateCompat.STATE_NONE, 0))
        session.isActive = false
        updateNotification()
        notifyStatus()
    }

    private fun onPlayback(next: Track) {
        val previous = track
        track = next

        if (next.isPlaying) {
            // Playing again (possibly resumed from the controller while another app held focus):
            // take focus back so that app ducks/pauses, and forget any focus-driven pause.
            pausedForFocus = false
            requestFocus()
        }

        val metadataChanged = previous == null || previous.title != next.title ||
            previous.artist != next.artist || previous.album != next.album ||
            previous.durationMs != next.durationMs || previous.coverUrl != next.coverUrl
        if (next.coverUrl != artUrl) {
            artUrl = next.coverUrl
            art = null
            next.coverUrl?.takeIf { prefs.albumArt }?.let(::loadArt)
        }
        if (metadataChanged) session.setMetadata(buildMetadata(next))

        val state = when (next.state) {
            "PLAYING" -> PlaybackStateCompat.STATE_PLAYING
            "PAUSED" -> PlaybackStateCompat.STATE_PAUSED
            "LOADING" -> PlaybackStateCompat.STATE_BUFFERING
            else -> PlaybackStateCompat.STATE_STOPPED
        }
        session.setPlaybackState(playbackState(state, next.positionMs))
        session.isActive = true
        if (metadataChanged || previous?.isPlaying != next.isPlaying) updateNotification()
        notifyStatus()
    }

    private fun buildMetadata(t: Track): MediaMetadataCompat =
        MediaMetadataCompat.Builder().apply {
            putString(MediaMetadataCompat.METADATA_KEY_TITLE, t.title)
            putString(MediaMetadataCompat.METADATA_KEY_ARTIST, t.artist)
            putString(MediaMetadataCompat.METADATA_KEY_ALBUM, t.album)
            putLong(MediaMetadataCompat.METADATA_KEY_DURATION, t.durationMs)
            t.coverUrl?.let {
                putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, it)
                putString(MediaMetadataCompat.METADATA_KEY_ART_URI, it)
            }
            art?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
        }.build()

    private fun playbackState(state: Int, positionMs: Long): PlaybackStateCompat =
        PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(
                state, positionMs,
                if (state == PlaybackStateCompat.STATE_PLAYING) 1f else 0f,
                SystemClock.elapsedRealtime(),
            )
            .build()

    // ---- cover art -------------------------------------------------------------------------

    /** Fetches and downsamples cover art off the main thread; drops the result if the track moved on. */
    private fun loadArt(url: String) {
        artExecutor.execute {
            val bitmap = runCatching { fetchBitmap(url) }
                .onFailure { Log.w(TAG, "Cover art fetch failed: ${it.message}") }
                .getOrNull() ?: return@execute
            main.post {
                if (active !== this || artUrl != url) return@post
                art = bitmap
                track?.let { session.setMetadata(buildMetadata(it)) }
                updateNotification()
            }
        }
    }

    private fun fetchBitmap(url: String): Bitmap? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 10_000
        val bytes = try {
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ART_MAX_PX) sample *= 2
        // RGB_565 halves the footprint; cover art has no alpha and the loss is invisible at this size.
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    // ---- audio focus -----------------------------------------------------------------------

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        val playing = track?.isPlaying == true
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (ducked) ifNative { NativeBridge.setAttenuation(1f, UNDUCK_FADE_MS) }
                ducked = false
                if (pausedForFocus) ifNative { NativeBridge.play() }
                pausedForFocus = false
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                ifNative { NativeBridge.setAttenuation(DUCK_FACTOR, DUCK_FADE_MS) }
                ducked = true
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (playing) {
                ifNative { NativeBridge.pause() }
                pausedForFocus = true
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Another app took over for good. Pause, and don't auto-resume: the user resumes
                // from their Spotify controller, which re-requests focus.
                pausedForFocus = false
                if (playing) ifNative { NativeBridge.pause() }
                abandonFocus()
            }
        }
    }

    private fun requestFocus() {
        if (!prefs.handleAudioFocus || focusRequest != null) return
        val request = AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributesCompat.Builder()
                    .setUsage(AudioAttributesCompat.USAGE_MEDIA)
                    .setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC)
                    .build()
            )
            // We duck ourselves (natively) rather than rely on the system's automatic ducking,
            // which doesn't reliably apply to AAudio streams. This flag makes Android hand us the
            // CAN_DUCK callback instead of ducking behind our back.
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(focusListener, main)
            .build()
        val result = AudioManagerCompat.requestAudioFocus(audioManager, request)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusRequest = request
        } else {
            Log.i(TAG, "Audio focus not granted ($result)")
        }
    }

    private fun abandonFocus() {
        focusRequest?.let { AudioManagerCompat.abandonAudioFocusRequest(audioManager, it) }
        focusRequest = null
        if (ducked) ifNative { NativeBridge.setAttenuation(1f, 0) }
        ducked = false
    }

    // ---- media session callbacks (system controls, Bluetooth/headset buttons) --------------

    private val sessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() = ifNative { NativeBridge.play() }
        override fun onPause() = ifNative { NativeBridge.pause() }
        override fun onStop() = ifNative { NativeBridge.pause() }
        override fun onSkipToNext() = ifNative { NativeBridge.nextTrack() }
        override fun onSkipToPrevious() = ifNative { NativeBridge.previousTrack() }
        override fun onSeekTo(pos: Long) = ifNative { NativeBridge.seekTo(pos) }
    }

    // ---- notification ----------------------------------------------------------------------

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_spotlet)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)

        val t = track
        if (t == null) {
            builder.setContentTitle(prefs.deviceName)
                .setContentText(statusLine())
                .addAction(R.drawable.ic_stop, getString(R.string.action_stop), serviceIntent(ACTION_STOP))
            return builder.build()
        }

        val playing = t.isPlaying
        return builder
            .setContentTitle(t.title)
            .setContentText(t.artist)
            .setSubText(prefs.deviceName)
            .setLargeIcon(art)
            .addAction(R.drawable.ic_previous, getString(R.string.action_previous), serviceIntent(ACTION_PREVIOUS))
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                getString(if (playing) R.string.action_pause else R.string.action_play),
                serviceIntent(if (playing) ACTION_PAUSE else ACTION_PLAY),
            )
            .addAction(R.drawable.ic_next, getString(R.string.action_next), serviceIntent(ACTION_NEXT))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun updateNotification() {
        // POST_NOTIFICATIONS may be denied on API 33+; the service keeps running regardless.
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun serviceIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, ReceiverService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )

    private fun statusLine(): String = when {
        !NativeBridge.loaded -> getString(R.string.status_no_native)
        started == null -> getString(R.string.status_starting)
        connectedUser != null -> getString(R.string.status_connected, connectedUser)
        else -> getString(R.string.status_ready)
    }

    companion object {
        private const val TAG = "ReceiverService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "receiver"
        private const val ART_MAX_PX = 320
        /** ~ -14 dB: clearly quieter under a voice prompt, but still audibly playing. */
        private const val DUCK_FACTOR = 0.2f
        private const val DUCK_FADE_MS = 300
        private const val UNDUCK_FADE_MS = 600

        private const val ACTION_STOP = "fi.qvr.spotlet.STOP"
        private const val ACTION_PLAY = "fi.qvr.spotlet.PLAY"
        private const val ACTION_PAUSE = "fi.qvr.spotlet.PAUSE"
        private const val ACTION_NEXT = "fi.qvr.spotlet.NEXT"
        private const val ACTION_PREVIOUS = "fi.qvr.spotlet.PREVIOUS"

        private val mainHandler = Handler(Looper.getMainLooper())

        /** Single thread for native start/stop/rename, shared across service instances. */
        private val nativeExecutor: ExecutorService = Executors.newSingleThreadExecutor()

        /** The running instance, main-thread only. */
        private var active: ReceiverService? = null

        /** Observer for the settings screen (main thread). */
        var statusListener: (() -> Unit)? = null

        val isRunning: Boolean get() = active != null

        /** One-line human status for the settings screen. */
        fun status(context: Context): String =
            active?.let { svc -> svc.track?.let { "${svc.statusLine()} — ${it.title} · ${it.artist}" } ?: svc.statusLine() }
                ?: context.getString(R.string.status_stopped)

        /** Starts the receiver, or makes a running one pick up changed settings. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ReceiverService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ReceiverService::class.java))
        }

        private fun notifyStatus() {
            statusListener?.invoke()
        }

        private fun onMain(block: ReceiverService.() -> Unit) {
            mainHandler.post { active?.block() }
        }

        // ---- JNI callbacks: signatures must match rust/src/lib.rs send_native_* ----

        @JvmStatic
        fun onNativeReceiverConnected(username: String?) = onMain { onConnected(username) }

        @JvmStatic
        fun onNativeReceiverDisconnected() = onMain { onDisconnected() }

        @JvmStatic
        fun onNativePlaybackEvent(
            state: String,
            title: String?,
            artist: String?,
            album: String?,
            positionMs: Long,
            durationMs: Long,
            coverUrl: String?,
        ) = onMain { onPlayback(Track(state, title, artist, album, positionMs, durationMs, coverUrl)) }
    }
}
