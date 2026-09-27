package fi.qvr.spotlet

import android.content.Context
import android.util.Log

/**
 * JNI surface of the Rust core (rust/src/lib.rs). Every `external fun` here maps to a
 * `Java_fi_qvr_spotlet_NativeBridge_*` export — renaming this class or its package means
 * regenerating the native library.
 *
 * Callbacks flow the other way, into the static `onNative*` methods on [ReceiverService].
 */
object NativeBridge {
    /** False when the .so is missing (e.g. a local build without the native core). */
    val loaded: Boolean = try {
        System.loadLibrary("c++_shared")
        System.loadLibrary("spotlet_core")
        true
    } catch (e: UnsatisfiedLinkError) {
        Log.e("NativeBridge", "Failed to load native core: ${e.message}")
        false
    }

    external fun initAndroidContext(context: Context, cacheDir: String)
    external fun initLogger()

    /** Starts (or, if name/bitrate differ, restarts) the Connect receiver. Blocks briefly on a restart. */
    external fun startDevice(deviceName: String, deviceId: String, bitrateKbps: Int, startupVolumePercent: Int)
    external fun stopDevice()

    /** Re-advertises under a new name without dropping the runtime. */
    external fun renameDevice(deviceName: String)

    /** Volume (0..=100) the NEXT Connect session starts at; a live session is untouched. */
    external fun setStartupVolume(percent: Int)

    // Transport controls. Safe no-ops natively when no controller is connected.
    external fun play()
    external fun pause()
    external fun nextTrack()
    external fun previousTrack()
    external fun seekTo(positionMs: Long)

    /**
     * Fades the audible volume to [factor] (1.0 = full, 0.0 = silence) over [fadeMs] without
     * moving the Connect volume slider. Used for audio-focus ducking.
     */
    external fun setAttenuation(factor: Float, fadeMs: Int)

    /** Linked-volume mode: Connect volume drives the Android media volume; soft mixer stays at full. */
    external fun setVolumeLinked(linked: Boolean)

    /** Sets the live session's Connect volume (raw 0..=65535); the controller's slider follows. */
    external fun setConnectVolume(volume: Int)
}
