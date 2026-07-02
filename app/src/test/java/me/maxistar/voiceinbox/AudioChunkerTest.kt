package me.maxistar.voiceinbox

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioChunkerTest {
    @Test
    fun shortInputProducesOneChunk() {
        val chunks = mutableListOf<FloatArray>()
        val chunker = AudioChunker(chunkSamples = 5, overlapSamples = 1, consumer = chunks::add)

        chunker.add(floatArrayOf(1f, 2f, 3f))
        chunker.finish()

        assertEquals(1, chunks.size)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), chunks.single(), 0f)
    }

    @Test
    fun exactChunkDoesNotEmitOverlapAgain() {
        val chunks = mutableListOf<FloatArray>()
        val chunker = AudioChunker(chunkSamples = 5, overlapSamples = 1, consumer = chunks::add)

        chunker.add(floatArrayOf(1f, 2f, 3f, 4f, 5f))
        chunker.finish()

        assertEquals(1, chunks.size)
    }

    @Test
    fun longInputRetainsConfiguredOverlap() {
        val chunks = mutableListOf<FloatArray>()
        val chunker = AudioChunker(chunkSamples = 5, overlapSamples = 2, consumer = chunks::add)

        chunker.add(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f))
        chunker.finish()

        assertEquals(2, chunks.size)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f), chunks[0], 0f)
        assertArrayEquals(floatArrayOf(4f, 5f, 6f, 7f), chunks[1], 0f)
    }

    @Test
    fun longStreamingInputProducesOnlyBoundedChunks() {
        var chunkCount = 0
        var maximumChunkSize = 0
        val chunker = AudioChunker { chunk ->
            chunkCount += 1
            maximumChunkSize = maxOf(maximumChunkSize, chunk.size)
        }
        val oneSecond = FloatArray(AudioChunker.TARGET_SAMPLE_RATE)

        repeat(5 * 60) {
            chunker.add(oneSecond)
        }
        chunker.finish()

        assertTrue(chunkCount > 1)
        assertTrue(maximumChunkSize <= AudioChunker.CHUNK_SAMPLES)
    }
}
