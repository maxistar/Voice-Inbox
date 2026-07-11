package me.maxistar.voiceinbox.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SingleFileTranscriptionUseCaseTest {
    @Test
    fun transcribesMultipleChunksMergesPublishesAndReturnsResult() {
        val decoder = FakeAudioDecoder(
            progress = listOf(5L to 10L, 10L to 10L),
            chunks = listOf(floatArrayOf(1f), floatArrayOf(2f)),
            info = PlatformAudioInfo(durationUs = 10L, embeddedRecordingTimeMillis = 100L),
        )
        val transcriber = FakeNativeTranscriber("one two three", "two three four")
        val staging = FakeStaging()
        val output = FakeTranscriptOutput(existingTail = "existing")
        val progress = mutableListOf<SingleFileTranscriptionProgress>()

        val result = useCase(decoder, transcriber, staging, output).transcribe(input()) {
            progress += it
        }

        assertEquals("one two three four", result.transcriptText)
        assertEquals(18, result.transcriptLength)
        assertEquals(10L, result.durationUs)
        assertEquals(listOf("one two three", "one two three four"), staging.writes)
        assertEquals(
            "\n\nvoice.m4a\nRecorded at 100\none two three four",
            output.appended.single(),
        )
        assertTrue(staging.cleaned)
        assertEquals(
            listOf(
                SingleFileTranscriptionUseCase.PHASE_DECODING_AUDIO,
                SingleFileTranscriptionUseCase.PHASE_TRANSCRIBING,
                SingleFileTranscriptionUseCase.PHASE_TRANSCRIBING,
                SingleFileTranscriptionUseCase.PHASE_APPENDING_TRANSCRIPT,
            ),
            progress.map { it.phase },
        )
        assertEquals(5L, progress[1].processedUs)
        assertEquals(10L, progress[1].durationUs)
    }

    @Test
    fun usesFallbackRecordingTimeWhenDecoderHasNoEmbeddedTime() {
        val output = FakeTranscriptOutput()

        useCase(
            decoder = FakeAudioDecoder(
                chunks = listOf(floatArrayOf(1f)),
                info = PlatformAudioInfo(durationUs = null, embeddedRecordingTimeMillis = null),
            ),
            transcriber = FakeNativeTranscriber("hello"),
            output = output,
        ).transcribe(input(fallbackRecordingTimeMillis = 200L)) {}

        assertEquals("voice.m4a\nRecorded at 200\nhello", output.appended.single())
    }

    @Test
    fun blankTranscriptFailsWithoutPublishingAndCleansUp() {
        val staging = FakeStaging()
        val output = FakeTranscriptOutput()

        val error = assertFailsWith<IllegalStateException> {
            useCase(
                decoder = FakeAudioDecoder(chunks = listOf(floatArrayOf(1f))),
                transcriber = FakeNativeTranscriber("   "),
                staging = staging,
                output = output,
            ).transcribe(input()) {}
        }

        assertEquals(SingleFileTranscriptionUseCase.ERROR_NO_TEXT_RECOGNIZED, error.message)
        assertEquals(emptyList(), output.appended)
        assertTrue(staging.cleaned)
    }

    @Test
    fun nativeTranscriptionFailureFailsWithoutPublishingAndCleansUp() {
        val staging = FakeStaging()
        val output = FakeTranscriptOutput()

        val error = assertFailsWith<IllegalStateException> {
            useCase(
                decoder = FakeAudioDecoder(chunks = listOf(floatArrayOf(1f))),
                transcriber = FakeNativeTranscriber(null),
                staging = staging,
                output = output,
            ).transcribe(input()) {}
        }

        assertEquals(SingleFileTranscriptionUseCase.ERROR_SPEECH_RECOGNITION_FAILED, error.message)
        assertEquals(emptyList(), staging.writes)
        assertEquals(emptyList(), output.appended)
        assertTrue(staging.cleaned)
    }

    private fun useCase(
        decoder: FakeAudioDecoder = FakeAudioDecoder(chunks = listOf(floatArrayOf(1f))),
        transcriber: FakeNativeTranscriber = FakeNativeTranscriber("hello"),
        staging: FakeStaging = FakeStaging(),
        output: FakeTranscriptOutput = FakeTranscriptOutput(),
    ) = SingleFileTranscriptionUseCase(
        audioDecoder = decoder,
        nativeTranscriber = transcriber,
        staging = staging,
        timestampLabelFormatter = FakeTimestampFormatter,
        transcriptOutput = output,
    )

    private fun input(fallbackRecordingTimeMillis: Long? = null) = SingleFileTranscriptionInput(
        audioId = "content://audio/1",
        audioName = "voice.m4a",
        outputId = "content://output",
        fallbackRecordingTimeMillis = fallbackRecordingTimeMillis,
    )

    private class FakeAudioDecoder(
        private val progress: List<Pair<Long, Long?>> = emptyList(),
        private val chunks: List<FloatArray>,
        private val info: PlatformAudioInfo = PlatformAudioInfo(
            durationUs = null,
            embeddedRecordingTimeMillis = null,
        ),
    ) : PlatformAudioDecoder {
        override fun decode(
            audioId: String,
            onProgress: (processedUs: Long, durationUs: Long?) -> Unit,
            onChunk: (samples: FloatArray) -> Unit,
        ): PlatformAudioInfo {
            progress.forEach { (processed, duration) -> onProgress(processed, duration) }
            chunks.forEach(onChunk)
            return info
        }
    }

    private class FakeNativeTranscriber(
        private vararg val responses: String?,
    ) : PlatformNativeTranscriber {
        private var index = 0

        override fun initialize(modelDirectory: String): Boolean = true

        override fun transcribeChunk(samples: FloatArray): String? =
            responses.getOrNull(index++)
    }

    private class FakeStaging : PlatformTranscriptStaging {
        val writes = mutableListOf<String>()
        var cleaned = false

        override fun write(transcript: String) {
            writes += transcript
        }

        override fun cleanup() {
            cleaned = true
        }
    }

    private object FakeTimestampFormatter : PlatformTimestampLabelFormatter {
        override fun formatRecordingTime(recordingTimeMillis: Long?): String? =
            recordingTimeMillis?.let { "Recorded at $it" }
    }

    private class FakeTranscriptOutput(
        private val existingTail: String = "",
    ) : PlatformTranscriptOutput {
        val appended = mutableListOf<String>()

        override fun readTail(outputId: String): String = existingTail

        override fun append(outputId: String, text: String) {
            appended += text
        }
    }
}
