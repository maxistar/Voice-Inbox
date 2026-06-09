package me.maxistar.watchface.notesrecognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppendPublicationTest {
    @Test
    fun publishesOneCombinedAppend() {
        val writes = mutableListOf<String>()

        AppendPublication.publish("existing", "entry", writes::add)

        assertEquals(listOf("\n\nentry"), writes)
    }

    @Test
    fun appendFailureIsNotRetried() {
        var attempts = 0

        assertThrows(IllegalStateException::class.java) {
            AppendPublication.publish("existing", "entry") {
                attempts += 1
                throw IllegalStateException("provider failed")
            }
        }

        assertEquals(1, attempts)
    }
}
