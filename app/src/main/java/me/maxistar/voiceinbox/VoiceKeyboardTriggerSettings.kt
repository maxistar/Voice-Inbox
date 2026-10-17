package me.maxistar.voiceinbox

import android.content.SharedPreferences
import android.view.KeyEvent

enum class VoiceKeyboardHardwareTrigger {
    VOLUME_DOWN,
    PHYSICAL_KEY,
    TOUCH_ONLY,
}

data class VoiceKeyboardTriggerSettings(
    val trigger: VoiceKeyboardHardwareTrigger = VoiceKeyboardHardwareTrigger.VOLUME_DOWN,
    val physicalKeyCode: Int? = null,
)

internal interface VoiceKeyboardTriggerStorage {
    fun loadTrigger(): String?
    fun loadPhysicalKeyCode(): Int?
    fun save(trigger: String, physicalKeyCode: Int?)
}

object VoiceKeyboardFunctionKeys {
    fun isSupported(keyCode: Int): Boolean = keyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12

    fun label(keyCode: Int): String? =
        if (isSupported(keyCode)) KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_") else null
}

class VoiceKeyboardTriggerSettingsStore internal constructor(
    private val storage: VoiceKeyboardTriggerStorage,
) {
    constructor(preferences: SharedPreferences) : this(
        SharedPreferencesVoiceKeyboardTriggerStorage(preferences),
    )

    fun load(): VoiceKeyboardTriggerSettings {
        val trigger = storage.loadTrigger()
            ?.let { raw -> VoiceKeyboardHardwareTrigger.entries.firstOrNull { it.name == raw } }
            ?: return DEFAULT_SETTINGS
        return when (trigger) {
            VoiceKeyboardHardwareTrigger.VOLUME_DOWN -> VoiceKeyboardTriggerSettings(
                trigger,
                storage.loadPhysicalKeyCode()?.takeIf(VoiceKeyboardFunctionKeys::isSupported),
            )
            VoiceKeyboardHardwareTrigger.TOUCH_ONLY -> VoiceKeyboardTriggerSettings(
                trigger,
                storage.loadPhysicalKeyCode()?.takeIf(VoiceKeyboardFunctionKeys::isSupported),
            )
            VoiceKeyboardHardwareTrigger.PHYSICAL_KEY -> {
                val keyCode = storage.loadPhysicalKeyCode()
                if (keyCode != null && VoiceKeyboardFunctionKeys.isSupported(keyCode)) {
                    VoiceKeyboardTriggerSettings(trigger, keyCode)
                } else {
                    DEFAULT_SETTINGS
                }
            }
        }
    }

    fun save(settings: VoiceKeyboardTriggerSettings): Boolean {
        if (settings.trigger == VoiceKeyboardHardwareTrigger.PHYSICAL_KEY) {
            val keyCode = settings.physicalKeyCode ?: return false
            if (!VoiceKeyboardFunctionKeys.isSupported(keyCode)) return false
        }
        storage.save(
            settings.trigger.name,
            settings.physicalKeyCode
                ?.takeIf(VoiceKeyboardFunctionKeys::isSupported)
                ?: storage.loadPhysicalKeyCode()?.takeIf(VoiceKeyboardFunctionKeys::isSupported),
        )
        return true
    }

    companion object {
        const val PREFERENCES_NAME = "voice_keyboard_trigger"
        val DEFAULT_SETTINGS = VoiceKeyboardTriggerSettings()
    }
}

private class SharedPreferencesVoiceKeyboardTriggerStorage(
    private val preferences: SharedPreferences,
) : VoiceKeyboardTriggerStorage {
    override fun loadTrigger(): String? = preferences.getString(KEY_TRIGGER, null)

    override fun loadPhysicalKeyCode(): Int? =
        if (preferences.contains(KEY_PHYSICAL_KEY_CODE)) {
            preferences.getInt(KEY_PHYSICAL_KEY_CODE, KeyEvent.KEYCODE_UNKNOWN)
        } else {
            null
        }

    override fun save(trigger: String, physicalKeyCode: Int?) {
        preferences.edit().apply {
            putString(KEY_TRIGGER, trigger)
            if (physicalKeyCode == null) remove(KEY_PHYSICAL_KEY_CODE)
            else putInt(KEY_PHYSICAL_KEY_CODE, physicalKeyCode)
        }.apply()
    }

    companion object {
        private const val KEY_TRIGGER = "hardware_trigger"
        private const val KEY_PHYSICAL_KEY_CODE = "physical_key_code"
    }
}
