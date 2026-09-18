package me.maxistar.voiceinbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import me.maxistar.voiceinbox.core.ModelSetupSnapshot
import me.maxistar.voiceinbox.core.ModelSetupSnapshotState
import me.maxistar.voiceinbox.core.TaskActionKind
import me.maxistar.voiceinbox.core.TaskListFilter

enum class AndroidVoiceKeyboardStatus {
    DISABLED,
    ENABLED,
    SELECTED,
}

internal object AndroidVoiceKeyboardStatusResolver {
    fun resolve(
        serviceComponent: String,
        enabledComponents: Collection<String>,
        selectedComponent: String?,
    ): AndroidVoiceKeyboardStatus {
        val target = normalize(serviceComponent)
        val enabled = enabledComponents.any { normalize(it) == target }
        if (!enabled) return AndroidVoiceKeyboardStatus.DISABLED
        return if (normalize(selectedComponent) == target) {
            AndroidVoiceKeyboardStatus.SELECTED
        } else {
            AndroidVoiceKeyboardStatus.ENABLED
        }
    }

    private fun normalize(value: String?): String? {
        val (packageName, className) = value?.trim()?.split('/', limit = 2)
            ?.takeIf { it.size == 2 }
            ?: return null
        val expandedClass = if (className.startsWith('.')) "$packageName$className" else className
        return "$packageName/$expandedClass"
    }
}

class AndroidVoiceKeyboardStatusProvider(private val context: Context) {
    private val serviceComponent = ComponentName(context, VoiceKeyboardInputMethodService::class.java)

    fun current(): AndroidVoiceKeyboardStatus {
        val manager = context.getSystemService(InputMethodManager::class.java)
        val enabled = manager?.enabledInputMethodList.orEmpty().map { it.id }
        val selected = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        )
        return AndroidVoiceKeyboardStatusResolver.resolve(
            serviceComponent.flattenToString(),
            enabled,
            selected,
        )
    }
}

enum class AndroidVoiceKeyboardSystemAction {
    ENABLE,
    CHOOSE,
}

internal object AndroidVoiceKeyboardActionPresenter {
    fun systemAction(status: AndroidVoiceKeyboardStatus): AndroidVoiceKeyboardSystemAction =
        if (status == AndroidVoiceKeyboardStatus.DISABLED) {
            AndroidVoiceKeyboardSystemAction.ENABLE
        } else {
            AndroidVoiceKeyboardSystemAction.CHOOSE
        }

    fun taskAction(status: AndroidVoiceKeyboardStatus): TaskActionKind =
        if (status == AndroidVoiceKeyboardStatus.DISABLED) {
            TaskActionKind.ENABLE_VOICE_KEYBOARD
        } else {
            TaskActionKind.CHOOSE_VOICE_KEYBOARD
        }
}

class AndroidVoiceKeyboardSystemGateway(private val context: Context) {
    fun perform(action: AndroidVoiceKeyboardSystemAction): Boolean = runCatching {
        when (action) {
            AndroidVoiceKeyboardSystemAction.ENABLE -> context.startActivity(
                Intent(Settings.ACTION_INPUT_METHOD_SETTINGS),
            )
            AndroidVoiceKeyboardSystemAction.CHOOSE ->
                context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
                    ?: error("Input method picker is unavailable")
        }
    }.isSuccess
}

enum class AndroidVoiceKeyboardDiscoveryLifecycle {
    ELIGIBLE,
    SUPPRESSED,
    DISMISSED,
    COMPLETED,
}

interface AndroidVoiceKeyboardDiscoveryStorage {
    fun loadRaw(): String?
    fun saveRaw(value: String)
}

class AndroidVoiceKeyboardDiscoveryStore(
    private val storage: AndroidVoiceKeyboardDiscoveryStorage,
) {
    constructor(preferences: SharedPreferences) : this(
        object : AndroidVoiceKeyboardDiscoveryStorage {
            override fun loadRaw(): String? = preferences.getString(KEY_LIFECYCLE, null)
            override fun saveRaw(value: String) {
                preferences.edit().putString(KEY_LIFECYCLE, value).apply()
            }
        },
    )

    fun loadOrInitialize(
        onboardingLifecycle: AndroidOnboardingHintLifecycle,
    ): AndroidVoiceKeyboardDiscoveryLifecycle {
        val stored = storage.loadRaw()?.let(::decode)
        if (stored != null) return stored
        return if (onboardingLifecycle == AndroidOnboardingHintLifecycle.ACTIVE) {
            AndroidVoiceKeyboardDiscoveryLifecycle.SUPPRESSED
        } else {
            AndroidVoiceKeyboardDiscoveryLifecycle.ELIGIBLE
        }.also(::save)
    }

    fun save(lifecycle: AndroidVoiceKeyboardDiscoveryLifecycle) {
        storage.saveRaw(lifecycle.name.lowercase())
    }

    private fun decode(value: String): AndroidVoiceKeyboardDiscoveryLifecycle? =
        AndroidVoiceKeyboardDiscoveryLifecycle.entries.firstOrNull {
            it.name.equals(value, ignoreCase = true)
        }

    companion object {
        const val PREFERENCES_NAME = "android_voice_keyboard_discovery"
        private const val KEY_LIFECYCLE = "lifecycle"
    }
}

data class AndroidVoiceKeyboardDiscoveryPresentation(
    val visible: Boolean = false,
    val titleRes: Int = R.string.voice_keyboard_discovery_title,
    val explanationRes: Int = R.string.voice_keyboard_discovery_explanation,
    val setupLabelRes: Int = R.string.voice_keyboard_discovery_setup,
    val setupAction: TaskActionKind = TaskActionKind.ENABLE_VOICE_KEYBOARD,
    val documentationLabelRes: Int = R.string.voice_keyboard_discovery_documentation,
) {
    companion object {
        val HIDDEN = AndroidVoiceKeyboardDiscoveryPresentation()
    }
}

object AndroidVoiceKeyboardDiscoveryPresenter {
    fun present(
        lifecycle: AndroidVoiceKeyboardDiscoveryLifecycle,
        filter: TaskListFilter,
        model: ModelSetupSnapshot,
        modelKnown: Boolean,
        keyboardStatus: AndroidVoiceKeyboardStatus,
        keyboardKnown: Boolean,
    ): AndroidVoiceKeyboardDiscoveryPresentation {
        if (
            lifecycle != AndroidVoiceKeyboardDiscoveryLifecycle.ELIGIBLE ||
            filter != TaskListFilter.NEW ||
            !modelKnown ||
            model.state != ModelSetupSnapshotState.READY ||
            !keyboardKnown ||
            keyboardStatus != AndroidVoiceKeyboardStatus.DISABLED
        ) {
            return AndroidVoiceKeyboardDiscoveryPresentation.HIDDEN
        }
        return AndroidVoiceKeyboardDiscoveryPresentation(
            visible = true,
            setupAction = AndroidVoiceKeyboardActionPresenter.taskAction(keyboardStatus),
        )
    }

    fun shouldComplete(
        lifecycle: AndroidVoiceKeyboardDiscoveryLifecycle,
        keyboardStatus: AndroidVoiceKeyboardStatus,
        keyboardKnown: Boolean,
    ): Boolean = lifecycle == AndroidVoiceKeyboardDiscoveryLifecycle.ELIGIBLE &&
        keyboardKnown &&
        keyboardStatus != AndroidVoiceKeyboardStatus.DISABLED
}
