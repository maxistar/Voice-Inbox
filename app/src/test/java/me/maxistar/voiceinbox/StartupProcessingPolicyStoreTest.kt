package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.ScheduledTranscriptionSettings
import me.maxistar.voiceinbox.core.ScheduledTranscriptionSettingsStorage
import me.maxistar.voiceinbox.core.StartupProcessingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupProcessingPolicyStoreTest {
    @Test
    fun missingAndUnknownValuesDefaultToAsk() {
        val storage = FakeStartupStorage()
        val store = StartupProcessingPolicyStore(storage)

        assertEquals(StartupProcessingPolicy.ASK, store.load())
        storage.raw = "future-value"
        assertEquals(StartupProcessingPolicy.ASK, store.load())
    }

    @Test
    fun policiesRoundTripThroughStableValues() {
        val storage = FakeStartupStorage()
        val store = StartupProcessingPolicyStore(storage)

        StartupProcessingPolicy.entries.forEach { policy ->
            store.save(policy)
            assertEquals(policy, store.load())
        }
    }

    @Test
    fun startupPolicyDoesNotChangeScheduledSettings() {
        val scheduledStorage = FakeScheduledStorage(
            ScheduledTranscriptionSettings(enabled = true, hour = 4, minute = 30),
        )
        val scheduledStore = ScheduledTranscriptionSettingsStore(scheduledStorage)
        val startupStore = StartupProcessingPolicyStore(FakeStartupStorage())

        startupStore.save(StartupProcessingPolicy.AUTOMATIC)

        assertTrue(scheduledStore.load().enabled)
        assertEquals(4, scheduledStore.load().hour)
        assertEquals(30, scheduledStore.load().minute)
    }

    private class FakeStartupStorage(var raw: String? = null) : StartupProcessingPolicyStorage {
        override fun loadRaw(): String? = raw

        override fun saveRaw(value: String) {
            raw = value
        }
    }

    private class FakeScheduledStorage(
        private var settings: ScheduledTranscriptionSettings,
    ) : ScheduledTranscriptionSettingsStorage {
        override fun load(): ScheduledTranscriptionSettings = settings

        override fun save(settings: ScheduledTranscriptionSettings) {
            this.settings = settings
        }
    }
}
