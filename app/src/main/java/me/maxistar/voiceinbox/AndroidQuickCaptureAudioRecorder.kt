package me.maxistar.voiceinbox

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

data class QuickCaptureRecording(
    val file: File,
    val sampleCount: Long,
    val sizeBytes: Long,
)

interface QuickCaptureAudioRecorder : java.io.Closeable {
    fun start(temporaryFile: File, finalFile: File): Result<Unit>

    fun stop(): Result<QuickCaptureRecording>

    fun cancel()
}

class AndroidQuickCaptureAudioRecorder(
    private val sampleRate: Int = PcmWavWriter.SAMPLE_RATE,
    private val recorderFactory: (Int) -> AudioRecord = { bufferSize ->
        AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .build()
    },
    private val readerExecutor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val writerFactory: (File, File) -> PcmWavWriter = { temporary, final ->
        PcmWavWriter(temporary, final, sampleRate = sampleRate)
    },
) : QuickCaptureAudioRecorder {
    private val lock = Any()
    private var recorder: AudioRecord? = null
    private var writer: PcmWavWriter? = null
    private var reader: Future<*>? = null
    private var reading = false
    private var readerFailure: Throwable? = null
    private var sampleCount = 0L

    override fun start(temporaryFile: File, finalFile: File): Result<Unit> = runCatching {
        synchronized(lock) {
            check(!reading) { "Microphone recording is already active" }
            val minimum = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minimum > 0) { "This device does not support 16 kHz mono recording" }
            val created = recorderFactory(minimum * 2)
            val createdWriter = writerFactory(temporaryFile, finalFile)
            try {
                check(created.state == AudioRecord.STATE_INITIALIZED) {
                    "Microphone recorder could not initialize"
                }
                createdWriter.open()
                recorder = created
                writer = createdWriter
                readerFailure = null
                sampleCount = 0L
                reading = true
                created.startRecording()
                reader = readerExecutor.submit { readSamples(created, createdWriter) }
            } catch (error: Throwable) {
                recorder = null
                writer = null
                reading = false
                runCatching { created.release() }
                createdWriter.abort()
                throw error
            }
        }
    }

    override fun stop(): Result<QuickCaptureRecording> = runCatching {
        val activeRecorder = synchronized(lock) {
            reading = false
            recorder
        } ?: error("Microphone recording is not active")
        runCatching { activeRecorder.stop() }
        val readerResult = runCatching { reader?.get() }
        val activeWriter = synchronized(lock) {
            val failure = readerFailure
            failure?.let { throw it }
            readerResult.getOrThrow()
            writer ?: error("WAV writer is unavailable")
        }
        val sizeBytes = activeWriter.finish()
        val recording = synchronized(lock) {
            recorder = null
            writer = null
            reader = null
            activeRecorder.release()
            QuickCaptureRecording(
                file = activeWriter.finalFileForResult(),
                sampleCount = sampleCount,
                sizeBytes = sizeBytes,
            )
        }
        recording
    }.onFailure {
        synchronized(lock) {
            runCatching { recorder?.release() }
            recorder = null
            writer?.abort()
            writer = null
            reader = null
            reading = false
        }
    }

    override fun cancel() {
        val activeRecorder = synchronized(lock) {
            reading = false
            recorder
        } ?: return
        runCatching { activeRecorder.stop() }
        runCatching { reader?.get() }
        synchronized(lock) {
            runCatching { activeRecorder.release() }
            recorder = null
            writer?.abort()
            writer = null
            reader = null
            reading = false
        }
    }

    override fun close() {
        cancel()
        readerExecutor.shutdownNow()
    }

    private fun readSamples(activeRecorder: AudioRecord, activeWriter: PcmWavWriter) {
        val buffer = ShortArray(READ_BUFFER_SAMPLES)
        try {
            while (synchronized(lock) { reading }) {
                val read = activeRecorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                check(read >= 0) { "Microphone read failed: $read" }
                if (read == 0) continue
                synchronized(lock) {
                    if (reading) {
                        activeWriter.append(buffer, read)
                        sampleCount += read
                    }
                }
            }
        } catch (error: Throwable) {
            synchronized(lock) {
                readerFailure = error
                reading = false
            }
        }
    }

    private companion object {
        const val READ_BUFFER_SAMPLES = 2_048
    }
}
