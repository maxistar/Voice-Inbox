package me.maxistar.voiceinbox.core


import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.Test

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

        assertFailsWith<IllegalStateException> {
            AppendPublication.publish("existing", "entry") {
                attempts += 1
                throw IllegalStateException("provider failed")
            }
        }

        assertEquals(1, attempts)
    }
}
