package fi.qvr.spotlet

/**
 * Turns the receiver's playback events into "plays" for the history.
 *
 * A track counts as played once it has been audibly playing for [countAfterMs] of its length
 * (30 s, or half the track if that is shorter) — so skipping through a playlist doesn't flood
 * the history. Paused time doesn't count. Every (re)load of a track starts a new play, so
 * repeat-one produces one play per loop.
 *
 * Pure logic with an injected monotonic [clock], so it is unit-testable off-device. The caller
 * polls [poll] at [msUntilCounted] to catch the threshold between events.
 */
class PlayTracker(
    private val clock: () -> Long,
    private val wallClock: () -> Long,
    private val listener: Listener,
) {
    interface Listener {
        /** The current track just crossed the threshold; persist it. */
        fun onCounted(play: Play)

        /** A counted play ended; [Play.listenedMs] is final. */
        fun onFinished(play: Play)
    }

    class Play(
        val startedAt: Long,
        val user: String?,
        val title: String,
        val artist: String?,
        val album: String?,
        val durationMs: Long,
    ) {
        @Volatile var listenedMs: Long = 0

        /** Database row id, assigned by the persistence layer after [Listener.onCounted]. */
        @Volatile var rowId: Long = -1
    }

    private class Current(val play: Play) {
        var listenedMs = 0L
        var playingSince: Long? = null
        var counted = false
    }

    private var current: Current? = null

    /** The play in progress (counted or not), for "now playing" displays. */
    val nowPlaying: Play? get() = current?.play

    fun onEvent(
        state: String,
        title: String?,
        artist: String?,
        album: String?,
        durationMs: Long,
        user: String?,
    ) {
        val cur = current
        val sameTrack = cur != null && cur.play.title == title && cur.play.artist == artist &&
            (cur.play.durationMs == durationMs || durationMs <= 0 || cur.play.durationMs <= 0)
        val active = state == "LOADING" || state == "PLAYING" || state == "PAUSED"

        if (state == "LOADING" || !sameTrack || !active) {
            finish()
            if (active && !title.isNullOrBlank()) {
                current = Current(Play(wallClock(), user, title, artist, album, durationMs))
            }
        }

        val c = current ?: return
        val now = clock()
        if (state == "PLAYING") {
            if (c.playingSince == null) c.playingSince = now
        } else {
            c.playingSince?.let { c.listenedMs += now - it }
            c.playingSince = null
        }
        poll()
    }

    /** The session ended (controller left, receiver stopped): close out the current play. */
    fun onSessionEnded() = finish()

    /** Counts the current play if it has crossed the threshold since the last event. */
    fun poll() {
        val c = current ?: return
        if (c.counted) return
        val listened = listened(c)
        if (listened >= countAfterMs(c.play.durationMs)) {
            c.counted = true
            c.play.listenedMs = listened
            listener.onCounted(c.play)
        }
    }

    /** Milliseconds of further playback before the current track counts; null if nothing to wait for. */
    fun msUntilCounted(): Long? {
        val c = current ?: return null
        if (c.counted || c.playingSince == null) return null
        return (countAfterMs(c.play.durationMs) - listened(c)).coerceAtLeast(0)
    }

    private fun finish() {
        // Count first if the threshold passed since the last poll, so a play is never lost
        // just because the scheduled poll hadn't fired yet when the track changed.
        poll()
        val c = current ?: return
        current = null
        if (!c.counted) return
        c.play.listenedMs = listened(c)
        listener.onFinished(c.play)
    }

    private fun listened(c: Current): Long =
        c.listenedMs + (c.playingSince?.let { clock() - it } ?: 0L)

    companion object {
        const val MIN_COUNT_MS = 30_000L

        fun countAfterMs(durationMs: Long): Long =
            if (durationMs > 0) minOf(MIN_COUNT_MS, durationMs / 2) else MIN_COUNT_MS
    }
}
