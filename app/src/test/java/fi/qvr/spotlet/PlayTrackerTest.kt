package fi.qvr.spotlet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class PlayTrackerTest {
    private var now = 0L
    private val counted = mutableListOf<PlayTracker.Play>()
    private val finished = mutableListOf<PlayTracker.Play>()
    private lateinit var tracker: PlayTracker

    @Before
    fun setUp() {
        tracker = PlayTracker({ now }, { 1_000_000L + now }, object : PlayTracker.Listener {
            override fun onCounted(play: PlayTracker.Play) { counted += play }
            override fun onFinished(play: PlayTracker.Play) { finished += play }
        })
    }

    private fun event(state: String, title: String = "Song", durationMs: Long = 200_000, user: String? = "qvr") =
        tracker.onEvent(state, title, "Artist", "Album", durationMs, user)

    @Test
    fun countsAfterThirtySecondsOfPlayback() {
        event("LOADING"); event("PLAYING")
        now = 29_999; tracker.poll()
        assertEquals(0, counted.size)
        assertEquals(1L, tracker.msUntilCounted())
        now = 30_000; tracker.poll()
        assertEquals(1, counted.size)
        assertEquals("qvr", counted[0].user)
        assertNull(tracker.msUntilCounted())
    }

    @Test
    fun shortTrackCountsAtHalfItsLength() {
        event("LOADING", durationMs = 20_000); event("PLAYING", durationMs = 20_000)
        now = 10_000; tracker.poll()
        assertEquals(1, counted.size)
    }

    @Test
    fun pausedTimeDoesNotCount() {
        event("LOADING"); event("PLAYING")
        now = 20_000; event("PAUSED")
        now = 100_000; tracker.poll()
        assertEquals(0, counted.size)
        assertNull(tracker.msUntilCounted())
        event("PLAYING")
        assertEquals(10_000L, tracker.msUntilCounted())
        now = 110_000; tracker.poll()
        assertEquals(1, counted.size)
    }

    @Test
    fun skippedTrackIsNeverRecorded() {
        event("LOADING", title = "A"); event("PLAYING", title = "A")
        now = 5_000; event("LOADING", title = "B"); event("PLAYING", title = "B")
        now = 40_000; tracker.poll()
        assertEquals(listOf("B"), counted.map { it.title })
        assertEquals(0, finished.size)
    }

    @Test
    fun finishingRecordsTotalListenedTime() {
        event("LOADING"); event("PLAYING")
        now = 90_000; event("LOADING", title = "Next")
        assertEquals(1, finished.size)
        assertEquals(90_000L, finished[0].listenedMs)
    }

    @Test
    fun reloadingSameTrackStartsANewPlay() {
        event("LOADING"); event("PLAYING")
        now = 60_000; event("LOADING"); event("PLAYING")
        now = 100_000; tracker.poll()
        assertEquals(2, counted.size)
    }

    @Test
    fun sessionEndClosesCurrentPlay() {
        event("LOADING"); event("PLAYING")
        now = 45_000; tracker.onSessionEnded()
        assertEquals(1, counted.size)
        assertEquals(1, finished.size)
        assertNull(tracker.nowPlaying)
    }

    @Test
    fun stoppedEndsThePlay() {
        event("LOADING"); event("PLAYING")
        now = 50_000; event("STOPPED")
        assertEquals(1, finished.size)
        assertNull(tracker.nowPlaying)
    }
}
