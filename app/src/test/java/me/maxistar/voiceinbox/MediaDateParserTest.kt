package me.maxistar.voiceinbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MediaDateParserTest {
    @Test
    fun parsesEpochSecondsAndMilliseconds() {
        assertEquals(1_000_000L, MediaDateParser.parse("1000"))
        assertEquals(10_000_000_000L, MediaDateParser.parse("10000000000"))
    }

    @Test
    fun parsesCommonEmbeddedDate() {
        assertNotNull(MediaDateParser.parse("20260609T123456.000Z"))
    }

    @Test
    fun rejectsUnknownDate() {
        assertNull(MediaDateParser.parse("unknown"))
    }
}
