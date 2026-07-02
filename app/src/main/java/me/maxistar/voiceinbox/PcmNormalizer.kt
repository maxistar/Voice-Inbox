package me.maxistar.voiceinbox

import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmNormalizer(
    private var sourceSampleRate: Int,
    private var channelCount: Int,
    private var pcmEncoding: Int,
) {
    private var sourcePosition = 0.0
    private var previous: Float? = null

    fun updateFormat(sampleRate: Int, channels: Int, encoding: Int) {
        sourceSampleRate = sampleRate
        channelCount = channels
        pcmEncoding = encoding
    }

    fun normalize(buffer: ByteBuffer): FloatArray {
        val mono = decodeAndDownmix(buffer)
        if (mono.isEmpty()) return mono
        if (sourceSampleRate == AudioChunker.TARGET_SAMPLE_RATE) return mono

        val source = if (previous == null) mono else floatArrayOf(previous!!) + mono
        val ratio = sourceSampleRate.toDouble() / AudioChunker.TARGET_SAMPLE_RATE
        val output = ArrayList<Float>()
        while (sourcePosition + 1 < source.size) {
            val index = sourcePosition.toInt()
            val fraction = (sourcePosition - index).toFloat()
            output += source[index] + ((source[index + 1] - source[index]) * fraction)
            sourcePosition += ratio
        }
        sourcePosition -= (source.size - 1)
        previous = source.last()
        return output.toFloatArray()
    }

    private fun decodeAndDownmix(buffer: ByteBuffer): FloatArray {
        buffer.order(ByteOrder.nativeOrder())
        val sampleCount = when (pcmEncoding) {
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> buffer.remaining() / 4
            android.media.AudioFormat.ENCODING_PCM_8BIT -> buffer.remaining()
            else -> buffer.remaining() / 2
        }
        if (sampleCount == 0 || channelCount <= 0) return FloatArray(0)
        val frameCount = sampleCount / channelCount
        return FloatArray(frameCount) {
            var sum = 0f
            repeat(channelCount) {
                sum += when (pcmEncoding) {
                    android.media.AudioFormat.ENCODING_PCM_FLOAT -> buffer.float
                    android.media.AudioFormat.ENCODING_PCM_8BIT ->
                        ((buffer.get().toInt() and 0xff) - 128) / 128f
                    else -> buffer.short / 32768f
                }
            }
            (sum / channelCount).coerceIn(-1f, 1f)
        }
    }
}
