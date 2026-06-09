package me.maxistar.watchface.notesrecognition

import android.media.AudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmNormalizerTest {
    @Test
    fun convertsSignedPcm16() {
        val input = ByteBuffer.allocate(6).order(ByteOrder.nativeOrder())
            .putShort(Short.MIN_VALUE)
            .putShort(0)
            .putShort(Short.MAX_VALUE)
            .flip() as ByteBuffer
        val normalizer = PcmNormalizer(16_000, 1, AudioFormat.ENCODING_PCM_16BIT)

        val output = normalizer.normalize(input)

        assertEquals(-1f, output[0], 0.0001f)
        assertEquals(0f, output[1], 0.0001f)
        assertEquals(Short.MAX_VALUE / 32768f, output[2], 0.0001f)
    }

    @Test
    fun downmixesStereo() {
        val input = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
            .putShort(Short.MAX_VALUE)
            .putShort(Short.MIN_VALUE)
            .putShort(16384)
            .putShort(16384)
            .flip() as ByteBuffer
        val normalizer = PcmNormalizer(16_000, 2, AudioFormat.ENCODING_PCM_16BIT)

        assertArrayEquals(floatArrayOf(-0.000015258789f, 0.5f), normalizer.normalize(input), 0.0001f)
    }

    @Test
    fun resamplesToSixteenKilohertz() {
        val input = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
            .putShort(0)
            .putShort(10_000)
            .putShort(20_000)
            .putShort(30_000)
            .flip() as ByteBuffer
        val normalizer = PcmNormalizer(8_000, 1, AudioFormat.ENCODING_PCM_16BIT)

        val output = normalizer.normalize(input)

        assertEquals(6, output.size)
    }
}
