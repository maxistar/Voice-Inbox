package me.maxistar.voiceinbox

import android.content.SharedPreferences
import me.maxistar.voiceinbox.core.StartupProcessingPolicy

interface StartupProcessingPolicyStorage {
    fun loadRaw(): String?
    fun saveRaw(value: String)
}

class StartupProcessingPolicyStore(
    private val storage: StartupProcessingPolicyStorage,
) {
    constructor(preferences: SharedPreferences) : this(
        SharedPreferencesStartupProcessingPolicyStorage(preferences),
    )

    fun load(): StartupProcessingPolicy =
        when (storage.loadRaw()) {
            VALUE_AUTOMATIC -> StartupProcessingPolicy.AUTOMATIC
            VALUE_LEAVE_QUEUED -> StartupProcessingPolicy.LEAVE_QUEUED
            VALUE_ASK -> StartupProcessingPolicy.ASK
            else -> StartupProcessingPolicy.ASK
        }

    fun save(policy: StartupProcessingPolicy) {
        storage.saveRaw(
            when (policy) {
                StartupProcessingPolicy.ASK -> VALUE_ASK
                StartupProcessingPolicy.AUTOMATIC -> VALUE_AUTOMATIC
                StartupProcessingPolicy.LEAVE_QUEUED -> VALUE_LEAVE_QUEUED
            },
        )
    }

    companion object {
        const val PREFERENCES_NAME = "startup_processing"
        private const val VALUE_ASK = "ask"
        private const val VALUE_AUTOMATIC = "automatic"
        private const val VALUE_LEAVE_QUEUED = "leave_queued"
    }
}

private class SharedPreferencesStartupProcessingPolicyStorage(
    private val preferences: SharedPreferences,
) : StartupProcessingPolicyStorage {
    override fun loadRaw(): String? = preferences.getString(KEY_POLICY, null)

    override fun saveRaw(value: String) {
        preferences.edit().putString(KEY_POLICY, value).apply()
    }

    companion object {
        private const val KEY_POLICY = "startup_processing_policy"
    }
}
