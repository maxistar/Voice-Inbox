package me.maxistar.voiceinbox

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import me.maxistar.voiceinbox.core.AudioCatalogEntry

internal data class AndroidTranscriptReview(
    val entryId: Long,
    val filename: String,
    val text: String,
) {
    companion object {
        fun from(entry: AudioCatalogEntry): AndroidTranscriptReview? {
            val transcript = entry.transcriptText
                ?.takeUnless { it.isBlank() }
                ?: return null
            return AndroidTranscriptReview(
                entryId = entry.id,
                filename = entry.displayName,
                text = transcript,
            )
        }
    }
}

internal object AndroidTranscriptShareIntent {
    fun create(review: AndroidTranscriptReview): Intent =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, review.text)
            .putExtra(Intent.EXTRA_SUBJECT, review.filename)

    fun chooser(review: AndroidTranscriptReview): Intent =
        Intent.createChooser(create(review), review.filename)
}

internal fun copyTranscriptToClipboard(context: Context, review: AndroidTranscriptReview) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(transcriptClipboardClip(review))
}

internal fun transcriptClipboardClip(review: AndroidTranscriptReview): ClipData =
    ClipData.newPlainText(review.filename, review.text)
