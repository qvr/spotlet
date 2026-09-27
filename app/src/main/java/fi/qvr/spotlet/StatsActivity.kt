package fi.qvr.spotlet

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/** Listening statistics and recent-play history, read from [PlayHistory]. */
class StatsActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var content: LinearLayout
    private val statusListener: () -> Unit = { load() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(content)
        })
        title = getString(R.string.stats_title)
    }

    override fun onResume() {
        super.onResume()
        ReceiverService.statusListeners += statusListener
        load()
    }

    override fun onPause() {
        ReceiverService.statusListeners -= statusListener
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun load() {
        io.execute {
            val stats = runCatching { PlayHistory.get(this).stats() }.getOrNull()
            main.post { if (!isDestroyed) render(stats, ReceiverService.live()) }
        }
    }

    private fun render(stats: PlayHistory.Stats?, live: ReceiverService.Companion.Live?) {
        content.removeAllViews()

        section(getString(R.string.stats_receiver))
        if (live == null) {
            row(getString(R.string.stats_status), getString(R.string.status_stopped))
        } else {
            row(getString(R.string.stats_device), "${live.deviceName} · ${live.bitrateKbps} kbps")
            ReceiverService.runningSince?.let {
                row(getString(R.string.stats_running_for), duration(System.currentTimeMillis() - it))
            }
            row(getString(R.string.stats_listener), live.user?.let(::userLabel) ?: getString(R.string.stats_nobody))
            if (live.title != null) {
                row(
                    getString(if (live.playing) R.string.stats_now_playing else R.string.stats_paused),
                    listOfNotNull(live.title, live.artist).joinToString(" · "),
                )
            }
        }

        if (stats == null || stats.plays == 0) {
            section(getString(R.string.stats_history))
            text(getString(R.string.stats_empty), secondary = true)
            return
        }

        section(getString(R.string.stats_overview))
        row(getString(R.string.stats_plays), stats.plays.toString())
        row(getString(R.string.stats_listening_time), duration(stats.listenedMs))
        row(getString(R.string.stats_unique), getString(R.string.stats_unique_value, stats.uniqueTracks, stats.uniqueArtists))
        row(getString(R.string.stats_recent_counts), getString(R.string.stats_recent_counts_value, stats.playsToday, stats.plays7d, stats.plays30d))
        stats.busiestHour?.let { row(getString(R.string.stats_busiest_hour), "%02d:00–%02d:00".format(it, (it + 1) % 24)) }
        stats.since?.let { row(getString(R.string.stats_since), DateUtils.formatDateTime(this, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)) }

        section(getString(R.string.stats_listeners))
        stats.listeners.forEach {
            row(userLabel(it.user), getString(R.string.stats_listener_value, it.plays, duration(it.listenedMs), `when`(it.lastPlayedAt)))
        }

        section(getString(R.string.stats_top_artists))
        stats.topArtists.forEachIndexed { i, r -> row("${i + 1}. ${r.label}", plays(r.plays)) }

        section(getString(R.string.stats_top_tracks))
        stats.topTracks.forEachIndexed { i, r -> row("${i + 1}. ${r.label}", plays(r.plays), sub = r.detail) }

        section(getString(R.string.stats_recent))
        stats.recent.forEach { e ->
            row(e.title, `when`(e.startedAt), sub = listOf(e.artist, userLabel(e.user)).filter { it.isNotBlank() }.joinToString(" · "))
        }

        content.addView(Button(this).apply {
            text = getString(R.string.stats_clear)
            setOnClickListener { confirmClear() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(24)
        })
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage(R.string.stats_clear_confirm)
            .setPositiveButton(R.string.stats_clear) { _, _ ->
                io.execute {
                    PlayHistory.get(this).clear()
                    main.post { load() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- formatting ------------------------------------------------------------------------

    private fun userLabel(user: String): String = user.ifBlank { getString(R.string.stats_unknown_user) }

    private fun plays(n: Int) = resources.getQuantityString(R.plurals.stats_play_count, n, n)

    private fun duration(ms: Long): String {
        val minutes = ms / 60_000
        val hours = minutes / 60
        return when {
            hours >= 24 -> getString(R.string.duration_dh, hours / 24, hours % 24)
            hours > 0 -> getString(R.string.duration_hm, hours, minutes % 60)
            else -> getString(R.string.duration_m, minutes)
        }
    }

    private fun `when`(ts: Long): String {
        val flags = if (DateUtils.isToday(ts)) DateUtils.FORMAT_SHOW_TIME
        else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
        return DateUtils.formatDateTime(this, ts, flags)
    }

    // ---- view helpers ----------------------------------------------------------------------

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun section(text: String) {
        content.addView(TextView(this).apply {
            this.text = text
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
            typeface = Typeface.DEFAULT_BOLD
            // The first section sits right under the action bar title; no extra gap needed.
            setPadding(0, if (content.childCount == 0) 0 else dp(20), 0, dp(4))
        })
    }

    private fun text(text: String, secondary: Boolean = false) {
        content.addView(TextView(this).apply {
            this.text = text
            if (secondary) alpha = 0.7f
        })
    }

    /** A label/value line, with an optional dimmer second line under the label. */
    private fun row(label: String, value: String, sub: String? = null) {
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = label; maxLines = 2 })
            if (!sub.isNullOrBlank()) addView(TextView(context).apply {
                text = sub
                alpha = 0.7f
                setTextAppearance(android.R.style.TextAppearance_Material_Caption)
                maxLines = 1
            })
        }
        val right = TextView(this).apply {
            text = value
            gravity = Gravity.END
            alpha = 0.85f
            setPadding(dp(12), 0, 0, 0)
        }
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
            addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(right, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        })
    }
}
