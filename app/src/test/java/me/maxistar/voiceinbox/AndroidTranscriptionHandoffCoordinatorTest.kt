package me.maxistar.voiceinbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidTranscriptionHandoffCoordinatorTest {
    @Test
    fun acceptedSingleEntryIsActiveAndOwnsPreparationImmediately() {
        val coordinator = AndroidTranscriptionHandoffCoordinator()

        coordinator.begin("work-1", requestedEntryId = 42)

        assertTrue(coordinator.active(observedActive = false))
        assertEquals(
            AndroidTranscriptionHandoffCoordinator.PREPARATION_PHASE,
            coordinator.phase(observedPhase = null, observedActive = false, activeEntryId = null),
        )
        assertEquals(
            42L,
            coordinator.preparationOwnerEntryId(
                observedActive = false,
                activeEntryId = null,
                requestedEntryId = null,
            ),
        )
    }

    @Test
    fun acceptedBatchLeavesPreparationOwnerForSharedFallback() {
        val coordinator = AndroidTranscriptionHandoffCoordinator()

        coordinator.begin("work-1", requestedEntryId = null)

        assertTrue(coordinator.active(observedActive = false))
        assertNull(
            coordinator.preparationOwnerEntryId(
                observedActive = false,
                activeEntryId = null,
                requestedEntryId = null,
            ),
        )
    }

    @Test
    fun observedRequestInputOwnsPreparationBeforeProgressExists() {
        val coordinator = AndroidTranscriptionHandoffCoordinator()

        val requestedEntryId = coordinator.requestedEntryId(
            setOf("unrelated", AndroidTranscriptionHandoffCoordinator.ENTRY_TAG_PREFIX + 17),
        )

        assertEquals(
            17L,
            coordinator.preparationOwnerEntryId(
                observedActive = true,
                activeEntryId = null,
                requestedEntryId = requestedEntryId,
            ),
        )
        assertEquals(
            AndroidTranscriptionHandoffCoordinator.PREPARATION_PHASE,
            coordinator.phase(observedPhase = null, observedActive = true, activeEntryId = null),
        )
    }

    @Test
    fun malformedOrMissingWorkTagsDoNotInventPreparationOwner() {
        val coordinator = AndroidTranscriptionHandoffCoordinator()

        assertNull(coordinator.requestedEntryId(emptySet()))
        assertNull(
            coordinator.requestedEntryId(
                setOf(AndroidTranscriptionHandoffCoordinator.ENTRY_TAG_PREFIX + "invalid"),
            ),
        )
    }

    @Test
    fun claimedEntryReplacesPreparationOwnership() {
        val coordinator = AndroidTranscriptionHandoffCoordinator()
        coordinator.begin("work-1", requestedEntryId = 17)

        assertNull(
            coordinator.preparationOwnerEntryId(
                observedActive = true,
                activeEntryId = 17,
                requestedEntryId = 17,
            ),
        )
        assertEquals(
            "Transcribing",
            coordinator.phase(observedPhase = null, observedActive = true, activeEntryId = 17),
        )
    }

    @Test
    fun authoritativeObservationClearsMatchingHandoff() {
        val coordinator = AndroidTranscriptionHandoffCoordinator()
        coordinator.begin("work-1", requestedEntryId = 17)

        coordinator.reconcile(
            listOf(AndroidTranscriptionWorkObservation(workId = "work-1", active = true)),
        )

        assertNull(coordinator.handoff)
        assertFalse(coordinator.active(observedActive = false))
    }

    @Test
    fun differentActiveWorkSupersedesStaleHandoff() {
        val coordinator = AndroidTranscriptionHandoffCoordinator()
        coordinator.begin("work-ignored", requestedEntryId = 17)

        coordinator.reconcile(
            listOf(AndroidTranscriptionWorkObservation(workId = "work-existing", active = true)),
        )

        assertNull(coordinator.handoff)
    }

    @Test
    fun emptyObservationDoesNotEraseJustAcceptedHandoff() {
        val coordinator = AndroidTranscriptionHandoffCoordinator()
        coordinator.begin("work-1", requestedEntryId = 17)

        coordinator.reconcile(emptyList())

        assertEquals("work-1", coordinator.handoff?.workId)
    }
}
