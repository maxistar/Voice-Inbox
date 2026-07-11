package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.*

import android.content.Context
import android.net.Uri
import java.text.DateFormat
import java.util.Date
import java.io.File
import java.io.IOException
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
        try {
            onProgress(FileTranscriptionProgress("Decoding audio"))
            var transcript = ""
            var durationUs: Long? = null
            val info = AndroidAudioDecoder(context.contentResolver).decode(
                uri = Uri.parse(entry.documentUri),
                onProgress = { processed, total ->
                    durationUs = total
                    onProgress(
                        FileTranscriptionProgress(
                            phase = "Transcribing",
                            processedUs = processed,
                            durationUs = total,
                        ),
                    )
                },
                onChunk = { samples ->
                    val result = NativeTranscriptionBridge.transcribeChunk(samples)
                        ?: throw IOException("Speech recognition failed")
                    transcript = TranscriptMerger.merge(transcript, result.text)
                    staging.writeText(transcript)
                },
            )
            durationUs = info.durationUs ?: durationUs
            if (transcript.isBlank()) throw IOException("No text was recognized")
            val finalTranscript = transcript.trim()

            onProgress(FileTranscriptionProgress("Appending transcript", durationUs, durationUs))
            val formatted = TranscriptOutput.formatEntry(
                audioName = entry.displayName,
                recordingTimeLabel = formatRecordingTime(
                    info.embeddedRecordingTimeMillis ?: entry.fingerprint.modifiedMillis,
                ),
                transcript = finalTranscript,
            )
            AppendPublication.publish(access.readTail(outputUri), formatted) { text ->
                access.append(outputUri, text)
            }
            return FileTranscriptionResult(
                durationUs = durationUs,
                transcriptLength = finalTranscript.length,
                transcriptText = finalTranscript,
            )
        } finally {
            staging.delete()
        }
    }

    private fun formatRecordingTime(recordingTimeMillis: Long?): String? {
        if (recordingTimeMillis == null) return null
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, Locale.getDefault())
            .apply { timeZone = TimeZone.getDefault() }
            .format(Date(recordingTimeMillis))
    }
}
