package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.*

import android.content.Context
import android.net.Uri
import java.text.DateFormat
import java.util.Date
import java.io.File
import java.util.Locale
import java.util.TimeZone

data class FileTranscriptionProgress(
    val phase: String,
    val processedUs: Long? = null,
    val durationUs: Long? = null,
)

data class FileTranscriptionResult(
    val durationUs: Long?,
    val transcriptLength: Int,
    val transcriptText: String,
)

class SingleFileTranscriber(
    private val context: Context,
) {
    fun transcribe(
        entry: AudioCatalogEntry,
        outputUri: Uri,
        workId: String,
        onProgress: (FileTranscriptionProgress) -> Unit,
    ): FileTranscriptionResult {
        val staging = File(context.cacheDir, "transcript-$workId-${entry.id}.txt")
        val access = DocumentAccess(context.contentResolver)
        val result = SingleFileTranscriptionUseCase(
            audioDecoder = AndroidPlatformAudioDecoder(context),
            nativeTranscriber = AndroidPlatformNativeTranscriber,
            staging = FileTranscriptStaging(staging),
            timestampLabelFormatter = AndroidTimestampLabelFormatter,
            transcriptOutput = AndroidTranscriptOutput(access),
        ).transcribe(
            input = SingleFileTranscriptionInput(
                audioId = entry.documentUri,
                audioName = entry.displayName,
                outputId = outputUri.toString(),
                fallbackRecordingTimeMillis = entry.fingerprint.modifiedMillis,
            ),
            onProgress = { progress ->
                onProgress(
                    FileTranscriptionProgress(
                        phase = progress.phase,
                        processedUs = progress.processedUs,
                        durationUs = progress.durationUs,
                    ),
                )
            },
        )
        return FileTranscriptionResult(
            durationUs = result.durationUs,
            transcriptLength = result.transcriptLength,
            transcriptText = result.transcriptText,
        )
    }
}

private class AndroidPlatformAudioDecoder(
    private val context: Context,
) : PlatformAudioDecoder {
    override fun decode(
        audioId: String,
        onProgress: (processedUs: Long, durationUs: Long?) -> Unit,
        onChunk: (samples: FloatArray) -> Unit,
    ): PlatformAudioInfo {
        val info = AndroidAudioDecoder(context.contentResolver).decode(
            uri = Uri.parse(audioId),
            onProgress = onProgress,
            onChunk = onChunk,
        )
        return PlatformAudioInfo(
            durationUs = info.durationUs,
            embeddedRecordingTimeMillis = info.embeddedRecordingTimeMillis,
        )
    }
}

private object AndroidPlatformNativeTranscriber : PlatformNativeTranscriber {
    override fun initialize(modelDirectory: String): Boolean =
        NativeTranscriptionBridge.initialize(modelDirectory)

    override fun transcribeChunk(samples: FloatArray): String? =
        NativeTranscriptionBridge.transcribeChunk(samples)?.text
}

private class FileTranscriptStaging(
    private val file: File,
) : PlatformTranscriptStaging {
    override fun write(transcript: String) {
        file.writeText(transcript)
    }

    override fun cleanup() {
        file.delete()
    }
}

private object AndroidTimestampLabelFormatter : PlatformTimestampLabelFormatter {
    override fun formatRecordingTime(recordingTimeMillis: Long?): String? {
        if (recordingTimeMillis == null) return null
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, Locale.getDefault())
            .apply { timeZone = TimeZone.getDefault() }
            .format(Date(recordingTimeMillis))
    }
}

private class AndroidTranscriptOutput(
    private val access: DocumentAccess,
) : PlatformTranscriptOutput {
    override fun readTail(outputId: String): String =
        access.readTail(Uri.parse(outputId))

    override fun append(outputId: String, text: String) {
        access.append(Uri.parse(outputId), text)
    }
}
