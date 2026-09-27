package fi.qvr.spotlet

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView

/**
 * The only screen: receiver on/off and a handful of settings. Plain framework widgets on
 * purpose — no AppCompat/Material — to keep the app light on old hardware.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var enabled: CompoundButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        status = findViewById(R.id.status)
        enabled = findViewById(R.id.enabled)
        enabled.isChecked = prefs.enabled
        enabled.setOnCheckedChangeListener { _, on ->
            prefs.enabled = on
            if (on) ReceiverService.start(this) else ReceiverService.stop(this)
        }

        val name = findViewById<EditText>(R.id.device_name)
        name.setText(prefs.deviceName)
        val applyName = {
            val value = name.text.toString().trim()
            if (value.isNotEmpty() && value != prefs.deviceName) {
                prefs.deviceName = value
                restartIfRunning()
            }
            name.setText(prefs.deviceName)
        }
        findViewById<Button>(R.id.device_name_apply).setOnClickListener { applyName() }
        name.setOnEditorActionListener { _, actionId, event ->
            val done = actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)
            if (done) applyName()
            done
        }

        val bitrate = findViewById<RadioGroup>(R.id.bitrate)
        val bitrateIds = mapOf(96 to R.id.bitrate_96, 160 to R.id.bitrate_160, 320 to R.id.bitrate_320)
        findViewById<RadioButton>(bitrateIds.getValue(prefs.bitrateKbps)).isChecked = true
        bitrate.setOnCheckedChangeListener { _, id ->
            val kbps = bitrateIds.entries.first { it.value == id }.key
            if (kbps != prefs.bitrateKbps) {
                prefs.bitrateKbps = kbps
                restartIfRunning()
            }
        }

        val volumeLabel = findViewById<TextView>(R.id.startup_volume_label)
        val volume = findViewById<SeekBar>(R.id.startup_volume)
        volume.progress = prefs.startupVolumePercent
        volumeLabel.text = getString(R.string.startup_volume, prefs.startupVolumePercent)
        volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                volumeLabel.text = getString(R.string.startup_volume, progress)
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) {
                prefs.startupVolumePercent = bar.progress
                restartIfRunning()   // applied live natively; no session restart
            }
        })

        bindSwitch(R.id.start_on_boot, prefs.startOnBoot) { prefs.startOnBoot = it }
        bindSwitch(R.id.audio_focus, prefs.handleAudioFocus) { prefs.handleAudioFocus = it }
        bindSwitch(R.id.album_art, prefs.albumArt) { prefs.albumArt = it }

        findViewById<TextView>(R.id.about).text = getString(R.string.about, BuildConfig.VERSION_NAME)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        if (prefs.enabled) ReceiverService.start(this)
    }

    override fun onResume() {
        super.onResume()
        ReceiverService.statusListener = ::refreshStatus
        refreshStatus()
    }

    override fun onPause() {
        ReceiverService.statusListener = null
        super.onPause()
    }

    private fun refreshStatus() {
        status.text = ReceiverService.status(this)
        enabled.isChecked = prefs.enabled
    }

    private fun restartIfRunning() {
        if (prefs.enabled) ReceiverService.start(this)
    }

    private fun bindSwitch(id: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        findViewById<CompoundButton>(id).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
    }
}
