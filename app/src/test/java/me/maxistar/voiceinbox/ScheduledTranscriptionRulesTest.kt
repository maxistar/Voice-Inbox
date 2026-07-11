package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ScheduledTranscriptionRulesTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun defaultsAreDisabledAtOneAm() {
        val settings = ScheduledTranscriptionSettings()

        assertFalse(settings.enabled)
        assertEquals(1, settings.hour)
        assertEquals(0, settings.minute)
    }

    @Test
    fun settingsStorePersistsEnabledStateIndependently() {
        val storage = FakeScheduledStorage()
        val store = ScheduledTranscriptionSettingsStore(storage)

        val enabled = store.setEnabled(true)

        assertTrue(enabled.enabled)
        assertEquals(ScheduledTranscriptionSettings(enabled = true), store.load())

        val disabled = store.setEnabled(false)

        assertFalse(disabled.enabled)
        assertEquals(ScheduledTranscriptionSettings(enabled = false), store.load())
    }

    @Test
    fun nextRunUsesTodayWhenTimeIsStillAhead() {
        val now = millis(2026, Calendar.JULY, 7, 0, 30)

        val next = ScheduledTranscriptionRules.nextRunAtMillis(now, 1, 0, utc)

        assertEquals(millis(2026, Calendar.JULY, 7, 1, 0), next)
    }

    @Test
    fun nextRunUsesTomorrowWhenTimeAlreadyPassed() {
        val now = millis(2026, Calendar.JULY, 7, 1, 1)

        val next = ScheduledTranscriptionRules.nextRunAtMillis(now, 1, 0, utc)

        assertEquals(millis(2026, Calendar.JULY, 8, 1, 0), next)
    }

    @Test
    fun pendingFilesStartOnlyWhenTranscriptionIsIdle() {
        assertTrue(ScheduledTranscriptionRules.shouldStartTranscription(1, transcriptionActive = false))
        assertFalse(ScheduledTranscriptionRules.shouldStartTranscription(0, transcriptionActive = false))
        assertFalse(ScheduledTranscriptionRules.shouldStartTranscription(1, transcriptionActive = true))
    }

    private fun millis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis

    private class FakeScheduledStorage : ScheduledTranscriptionSettingsStorage {
        private var settings = ScheduledTranscriptionSettings()

        override fun load(): ScheduledTranscriptionSettings = settings

        override fun save(settings: ScheduledTranscriptionSettings) {
            this.settings = settings
        }
    }
}
