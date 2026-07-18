package me.maxistar.voiceinbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistedSelectionAccessPolicyTest {
    @Test
    fun successfulValidationIsValidWithoutPersistedPermission() {
        assertEquals(
            PersistedSelectionAccessState.VALID,
            PersistedSelectionAccessPolicy.classify(
                validationSucceeded = true,
                hasRequiredPersistedPermission = false,
            ),
        )
    }

    @Test
    fun failedValidationWithPersistedPermissionIsTransient() {
        val state = PersistedSelectionAccessPolicy.classify(
            validationSucceeded = false,
            hasRequiredPersistedPermission = true,
        )

        assertEquals(PersistedSelectionAccessState.TEMPORARILY_UNAVAILABLE, state)
        assertFalse(PersistedSelectionAccessPolicy.shouldClearSelection(state))
    }

    @Test
    fun failedValidationWithoutPersistedPermissionIsRevoked() {
        val state = PersistedSelectionAccessPolicy.classify(
            validationSucceeded = false,
            hasRequiredPersistedPermission = false,
        )

        assertEquals(PersistedSelectionAccessState.PERMISSION_REVOKED, state)
        assertTrue(PersistedSelectionAccessPolicy.shouldClearSelection(state))
    }

    @Test
    fun validAndTransientSelectionsAreNeverClearedByPolicy() {
        assertFalse(
            PersistedSelectionAccessPolicy.shouldClearSelection(
                PersistedSelectionAccessState.VALID,
            ),
        )
        assertFalse(
            PersistedSelectionAccessPolicy.shouldClearSelection(
                PersistedSelectionAccessState.TEMPORARILY_UNAVAILABLE,
            ),
        )
    }
}
