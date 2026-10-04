package me.maxistar.voiceinbox

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAudioMetadataFormatterTest {
    @Test
    fun formatCombinesLocalizedTimestampSizeAndDuration() {
        val locale = Locale.US
        val timeZone = TimeZone.getTimeZone("UTC")
        val timestamp = 1_704_207_840_000L
        val expectedTimestamp = DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT,
            locale,
        ).apply { this.timeZone = timeZone }.format(Date(timestamp))

        val metadata = requireNotNull(
            AndroidAudioMetadataFormatter.format(
                timestampMillis = timestamp,
                sizeBytes = 2L * 1024 * 1024,
                durationUs = 65_000_000,
                locale = locale,
                timeZone = timeZone,
            ),
        )

        assertEquals("$expectedTimestamp • 2 MiB • 1:05", metadata)
        assertFalse(metadata.contains("Created"))
        assertFalse(metadata.contains("Recorded"))
    }

    @Test
    fun formatOmitsUnknownOrZeroComponentsAndSupportsLongDuration() {
        assertNull(AndroidAudioMetadataFormatter.format(null, null, null))
        assertNull(AndroidAudioMetadataFormatter.format(0, 0, 0))
        assertEquals(
            "1:01:01",
            AndroidAudioMetadataFormatter.format(null, null, 3_661_000_000),
        )
        assertEquals("512 B", AndroidAudioMetadataFormatter.format(null, 512, null))
    }

    @Test
    fun mapperUsesSameMetadataPolicyAcrossFiltersAndKeepsDuration() {
        val entry = AndroidMainScreenStateHostTest.entryForMetadata(
            id = 77,
            state = me.maxistar.voiceinbox.core.AudioFileState.PROCESSED,
            modified = 1_704_207_840_000,
            durationUs = 65_000_000,
        )
        val processed = AndroidTaskListSnapshotMapper.state(
            AndroidMainScreenStateHostTest.readyInputForMetadata(
                filter = me.maxistar.voiceinbox.core.TaskListFilter.PROCESSED,
                entries = listOf(entry),
            ),
        ).taskList.tasks.single().detail
        val all = AndroidTaskListSnapshotMapper.state(
            AndroidMainScreenStateHostTest.readyInputForMetadata(
                filter = me.maxistar.voiceinbox.core.TaskListFilter.ALL,
                entries = listOf(entry),
            ),
        ).taskList.tasks.single().detail

        assertEquals(processed, all)
        assertTrue(requireNotNull(processed).fallback.endsWith(" • 1 KiB • 1:05"))
    }
}
