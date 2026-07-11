package me.maxistar.voiceinbox.core

class AudioChunker(
    private val chunkSamples: Int = CHUNK_SAMPLES,
    private val overlapSamples: Int = OVERLAP_SAMPLES,
    private val consumer: (FloatArray) -> Unit,
) {
    private val pending = ArrayList<Float>(chunkSamples)
    private var emittedChunk = false

    fun add(samples: FloatArray) {
        samples.forEach { sample ->
            pending.add(sample)
            if (pending.size == chunkSamples) {
                consumer(pending.toFloatArray())
                emittedChunk = true
                val overlap = pending.takeLast(overlapSamples)
                pending.clear()
                pending.addAll(overlap)
            }
        }
    }

    fun finish() {
        if (pending.isNotEmpty() && (!emittedChunk || pending.size > overlapSamples)) {
            consumer(pending.toFloatArray())
        }
        pending.clear()
    }

    companion object {
        const val TARGET_SAMPLE_RATE = 16_000
        const val CHUNK_DURATION_SECONDS = 30
        const val OVERLAP_DURATION_SECONDS = 1
        const val CHUNK_SAMPLES = TARGET_SAMPLE_RATE * CHUNK_DURATION_SECONDS
        const val OVERLAP_SAMPLES = TARGET_SAMPLE_RATE * OVERLAP_DURATION_SECONDS
    }
}
