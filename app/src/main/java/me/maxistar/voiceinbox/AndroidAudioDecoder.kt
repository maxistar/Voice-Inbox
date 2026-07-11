package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.*

import android.content.ContentResolver
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.IOException

data class DecodedAudioInfo(
    val durationUs: Long?,
    val embeddedRecordingTimeMillis: Long?,
)

class AndroidAudioDecoder(
    private val resolver: ContentResolver,
) {
    fun decode(
        uri: Uri,
        onProgress: (processedUs: Long, durationUs: Long?) -> Unit,
        onChunk: (FloatArray) -> Unit,
    ): DecodedAudioInfo {
        val extractor = MediaExtractor()
        val descriptor = resolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Unable to open the selected audio file")
        descriptor.use {
            extractor.setDataSource(it.fileDescriptor)
        }

        val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run {
            extractor.release()
            throw IOException("The selected document has no supported audio track")
        }
        val inputFormat = extractor.getTrackFormat(trackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)
            ?: throw IOException("The selected audio track has no MIME type")
        if (inputFormat.containsKey(KEY_IS_DRM) &&
            inputFormat.getInteger(KEY_IS_DRM) != 0
        ) {
            extractor.release()
            throw IOException("DRM-protected audio is not supported")
        }
        val durationUs = inputFormat.getLongOrNull(MediaFormat.KEY_DURATION)
        extractor.selectTrack(trackIndex)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inputFormat, null, null, 0)
        codec.start()
        val chunker = AudioChunker(consumer = onChunk)
        var normalizer = PcmNormalizer(
            sourceSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
            channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
            pcmEncoding = AudioFormat.ENCODING_PCM_16BIT,
        )
        var inputDone = false
        var outputDone = false
        var processedUs = 0L
        val info = MediaCodec.BufferInfo()
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex)
                            ?: throw IOException("Decoder input buffer is unavailable")
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                processedUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val timestamp = extractor.sampleTime.coerceAtLeast(0)
                            codec.queueInputBuffer(inputIndex, 0, size, timestamp, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        normalizer = PcmNormalizer(
                            sourceSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                            channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                            pcmEncoding = format.getIntegerOrDefault(
                                MediaFormat.KEY_PCM_ENCODING,
                                AudioFormat.ENCODING_PCM_16BIT,
                            ),
                        )
                    }
                    else -> if (outputIndex >= 0) {
                        codec.getOutputBuffer(outputIndex)?.let { output ->
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            chunker.add(normalizer.normalize(output.slice()))
                        }
                        processedUs = maxOf(processedUs, info.presentationTimeUs)
                        onProgress(processedUs, durationUs)
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            chunker.finish()
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
        return DecodedAudioInfo(durationUs, readEmbeddedDate(uri))
    }

    private fun readEmbeddedDate(uri: Uri): Long? {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            resolver.openFileDescriptor(uri, "r")?.use {
                retriever.setDataSource(it.fileDescriptor)
            }
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DATE)
                ?.let(MediaDateParser::parse)
        } catch (_: Throwable) {
            null
        } finally {
            retriever.release()
        }
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
        private const val KEY_IS_DRM = "is-drm"
    }
}

private fun MediaFormat.getLongOrNull(key: String): Long? =
    if (containsKey(key)) getLong(key) else null

private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int =
    if (containsKey(key)) getInteger(key) else default
