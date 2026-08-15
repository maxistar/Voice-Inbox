package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.ModelSetupSnapshot
import me.maxistar.voiceinbox.core.ModelSetupSnapshotState
import me.maxistar.voiceinbox.core.TaskActionKind
import me.maxistar.voiceinbox.core.TaskListFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidVoiceKeyboardDiscoveryTest {
    @Test
    fun statusResolverNormalizesComponentsAndDistinguishesAllStates() {
        val service = "me.maxistar.voiceinbox/me.maxistar.voiceinbox.VoiceKeyboardInputMethodService"
        val shortService = "me.maxistar.voiceinbox/.VoiceKeyboardInputMethodService"

        assertEquals(
            AndroidVoiceKeyboardStatus.DISABLED,
            AndroidVoiceKeyboardStatusResolver.resolve(service, emptyList(), null),
        )
        assertEquals(
            AndroidVoiceKeyboardStatus.ENABLED,
            AndroidVoiceKeyboardStatusResolver.resolve(service, listOf(shortService), "other/.Keyboard"),
        )
        assertEquals(
            AndroidVoiceKeyboardStatus.SELECTED,
            AndroidVoiceKeyboardStatusResolver.resolve(service, listOf(shortService), shortService),
        )
    }

    @Test
    fun actionPresentationUsesEnableOnlyForDisabledStatus() {
        assertEquals(
            AndroidVoiceKeyboardSystemAction.ENABLE,
            AndroidVoiceKeyboardActionPresenter.systemAction(AndroidVoiceKeyboardStatus.DISABLED),
        )
        assertEquals(
            TaskActionKind.ENABLE_VOICE_KEYBOARD,
            AndroidVoiceKeyboardActionPresenter.taskAction(AndroidVoiceKeyboardStatus.DISABLED),
        )
        assertEquals(
            AndroidVoiceKeyboardSystemAction.CHOOSE,
            AndroidVoiceKeyboardActionPresenter.systemAction(AndroidVoiceKeyboardStatus.ENABLED),
        )
        assertEquals(
            AndroidVoiceKeyboardSystemAction.CHOOSE,
            AndroidVoiceKeyboardActionPresenter.systemAction(AndroidVoiceKeyboardStatus.SELECTED),
        )
    }

    @Test
    fun migrationMakesOnlyLegacyTerminalOnboardingEligible() {
        val legacyStorage = FakeStorage()
        val legacy = AndroidVoiceKeyboardDiscoveryStore(legacyStorage)
        assertEquals(
            AndroidVoiceKeyboardDiscoveryLifecycle.ELIGIBLE,
            legacy.loadOrInitialize(AndroidOnboardingHintLifecycle.COMPLETED),
        )
        assertEquals("eligible", legacyStorage.raw)

        val freshStorage = FakeStorage()
        val fresh = AndroidVoiceKeyboardDiscoveryStore(freshStorage)
        assertEquals(
            AndroidVoiceKeyboardDiscoveryLifecycle.SUPPRESSED,
            fresh.loadOrInitialize(AndroidOnboardingHintLifecycle.ACTIVE),
        )
        assertEquals(
            AndroidVoiceKeyboardDiscoveryLifecycle.SUPPRESSED,
            fresh.loadOrInitialize(AndroidOnboardingHintLifecycle.DISMISSED),
        )
    }

    @Test
    fun presenterRequiresEligibleNewReadyKnownAndDisabled() {
        val visible = present()
        assertTrue(visible.visible)
        assertEquals(TaskActionKind.ENABLE_VOICE_KEYBOARD, visible.setupAction)
        assertFalse(present(lifecycle = AndroidVoiceKeyboardDiscoveryLifecycle.DISMISSED).visible)
        assertFalse(present(filter = TaskListFilter.ALL).visible)
        assertFalse(present(modelKnown = false).visible)
        assertFalse(present(model = ModelSetupSnapshot(ModelSetupSnapshotState.REQUIRED)).visible)
        assertFalse(present(keyboardKnown = false).visible)
        assertFalse(present(keyboardStatus = AndroidVoiceKeyboardStatus.ENABLED).visible)
    }

    @Test
    fun eligibleDiscoveryCompletesOnceKeyboardIsEnabled() {
        assertFalse(
            AndroidVoiceKeyboardDiscoveryPresenter.shouldComplete(
                AndroidVoiceKeyboardDiscoveryLifecycle.ELIGIBLE,
                AndroidVoiceKeyboardStatus.DISABLED,
                true,
            ),
        )
        assertTrue(
            AndroidVoiceKeyboardDiscoveryPresenter.shouldComplete(
                AndroidVoiceKeyboardDiscoveryLifecycle.ELIGIBLE,
                AndroidVoiceKeyboardStatus.ENABLED,
                true,
            ),
        )
    }

    private fun present(
        lifecycle: AndroidVoiceKeyboardDiscoveryLifecycle = AndroidVoiceKeyboardDiscoveryLifecycle.ELIGIBLE,
        filter: TaskListFilter = TaskListFilter.NEW,
        model: ModelSetupSnapshot = ModelSetupSnapshot(ModelSetupSnapshotState.READY),
        modelKnown: Boolean = true,
        keyboardStatus: AndroidVoiceKeyboardStatus = AndroidVoiceKeyboardStatus.DISABLED,
        keyboardKnown: Boolean = true,
    ) = AndroidVoiceKeyboardDiscoveryPresenter.present(
        lifecycle,
        filter,
        model,
        modelKnown,
        keyboardStatus,
        keyboardKnown,
    )

    private class FakeStorage(var raw: String? = null) : AndroidVoiceKeyboardDiscoveryStorage {
        override fun loadRaw(): String? = raw
        override fun saveRaw(value: String) {
            raw = value
        }
    }
}
