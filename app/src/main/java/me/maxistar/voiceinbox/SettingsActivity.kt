package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.*

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class SettingsActivity : AppCompatActivity() {
    private lateinit var settingsStore: ScheduledTranscriptionSettingsStore
    private lateinit var scheduledSwitch: SwitchCompat
    private lateinit var scheduledTime: TextView
    private lateinit var scheduledTimeDetail: TextView
    private var settings = ScheduledTranscriptionSettings()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)
        setSupportActionBar(findViewById(R.id.settingsToolbar))
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.settings_title)
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settingsRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        settingsStore = ScheduledTranscriptionSettingsStore(
            getSharedPreferences(ScheduledTranscriptionSettingsStore.PREFERENCES_NAME, MODE_PRIVATE),
        )
        settings = settingsStore.load()
        scheduledTime = findViewById(R.id.scheduledTime)
        scheduledTimeDetail = findViewById(R.id.scheduledTimeDetail)
        scheduledSwitch = findViewById(R.id.scheduledSwitch)
        scheduledSwitch.isChecked = settings.enabled
        scheduledSwitch.setOnCheckedChangeListener { _, enabled ->
            settings = settingsStore.setEnabled(enabled)
            ScheduledTranscriptionScheduler.sync(this, settings)
            renderSchedule()
        }
        findViewById<View>(R.id.scheduledTimeRow).setOnClickListener {
            showScheduledTimePicker()
        }
        renderSchedule()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    private fun showScheduledTimePicker() {
        TimePickerDialog(
            this,
            { _, hour, minute ->
                settings = settingsStore.setTime(hour, minute)
                ScheduledTranscriptionScheduler.sync(this, settings)
                renderSchedule()
            },
            settings.hour,
            settings.minute,
            true,
        ).show()
    }

    private fun renderSchedule() {
        val text = getString(
            R.string.settings_scheduled_transcription_time,
            settings.hour,
            settings.minute,
        )
        scheduledTime.text = text
        scheduledTimeDetail.text = text
    }
}
