package me.maxistar.voiceinbox

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class VoiceKeyboardAudioRecorder(
    private val sampleRate: Int = SAMPLE_RATE,
    private val recorderFactory: (Int) -> AudioRecord = { bufferSize ->
        AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .build()
    },
    private val readerExecutor: ExecutorService = Executors.newSingleThreadExecutor(),
) {
    private val lock = Any()
    private val chunks = mutableListOf<FloatArray>()
    private var recorder: AudioRecord? = null
    private var reader: Future<*>? = null
    private var reading = false

    fun start(): Result<Unit> = runCatching {
        synchronized(lock) {
            check(!reading) { "Microphone recording is already active" }
            val minimum = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minimum > 0) { "This device does not support 16 kHz mono recording" }
            val created = recorderFactory(minimum * 2)
            try {
                check(created.state == AudioRecord.STATE_INITIALIZED) { "Microphone recorder could not initialize" }
                chunks.clear()
                recorder = created
                reading = true
                created.startRecording()
                reader = readerExecutor.submit { readSamples(created) }
            } catch (error: Throwable) {
                recorder = null
                reading = false
                created.release()
                throw error
            }
        }
    }

    fun stop(): Result<FloatArray> = runCatching {
        val activeRecorder = synchronized(lock) {
            reading = false
            recorder
        } ?: return@runCatching FloatArray(0)
        runCatching { activeRecorder.stop() }
        val readerResult = runCatching { reader?.get() }
        val result = synchronized(lock) {
            recorder = null
            reader = null
            activeRecorder.release()
            val sampleCount = chunks.sumOf(FloatArray::size)
            FloatArray(sampleCount).also { result ->
                var offset = 0
                chunks.forEach { chunk ->
                    chunk.copyInto(result, destinationOffset = offset)
                    offset += chunk.size
                }
                chunks.clear()
            }
        }
        readerResult.getOrThrow()
        result
    }

    fun cancel() {
        stop()
    }

    fun close() {
        cancel()
        readerExecutor.shutdownNow()
    }

    private fun readSamples(activeRecorder: AudioRecord) {
        val buffer = ShortArray(READ_BUFFER_SAMPLES)
        while (synchronized(lock) { reading }) {
            val read = activeRecorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            if (read <= 0) continue
            val normalized = FloatArray(read) { index -> buffer[index] / 32768f }
            synchronized(lock) {
                if (reading) chunks += normalized
            }
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val READ_BUFFER_SAMPLES = 2_048
    }
}
