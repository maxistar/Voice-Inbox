package me.maxistar.voiceinbox

import android.content.Context
import androidx.core.content.FileProvider
import me.maxistar.voiceinbox.core.SqlDelightAudioCatalogRepository
import java.io.Closeable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Future
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal object AndroidQuickCaptureFileRegistry {
    private val lock = Any()
    private val protectedPaths = mutableSetOf<String>()

    fun protect(destination: QuickCaptureDestination) {
        synchronized(lock) {
            protectedPaths += key(destination.temporaryFile)
            protectedPaths += key(destination.finalFile)
        }
    }

    fun release(destination: QuickCaptureDestination) {
        synchronized(lock) {
            protectedPaths -= key(destination.temporaryFile)
            protectedPaths -= key(destination.finalFile)
        }
    }

    fun isProtected(file: File): Boolean = synchronized(lock) {
        key(file) in protectedPaths
    }

    private fun key(file: File): String =
        runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
}

data class QuickCaptureDestination(
    val temporaryFile: File,
    val finalFile: File,
    val documentId: String,
    val displayName: String,
    val mimeType: String = "audio/wav",
)

interface QuickCaptureStorage {
    fun destination(recordedAtMillis: Long): QuickCaptureDestination

    fun delete(destination: QuickCaptureDestination)

    fun release(destination: QuickCaptureDestination) = Unit
}

class AndroidQuickCaptureStorage(
    private val context: Context,
) : QuickCaptureStorage {
    private val directory = File(context.applicationContext.filesDir, AndroidAudioImportConstants.DIRECTORY_NAME)

    override fun destination(recordedAtMillis: Long): QuickCaptureDestination {
        check(directory.exists() || directory.mkdirs()) {
            "Unable to create imported-audio storage"
        }
        val token = UUID.randomUUID().toString()
        val physicalStamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(recordedAtMillis))
        val displayName = AndroidQuickCaptureNames.displayName(recordedAtMillis)
        val finalFile = File(directory, "voice-note-$physicalStamp-${token.take(8)}.wav")
        val temporaryFile = File(directory, ".${token}.part")
        val documentId = FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            finalFile,
        ).toString()
        return QuickCaptureDestination(
            temporaryFile = temporaryFile,
            finalFile = finalFile,
            documentId = documentId,
            displayName = displayName,
        ).also(AndroidQuickCaptureFileRegistry::protect)
    }

    override fun delete(destination: QuickCaptureDestination) {
        destination.temporaryFile.delete()
        destination.finalFile.delete()
        AndroidQuickCaptureFileRegistry.release(destination)
    }

    override fun release(destination: QuickCaptureDestination) {
        AndroidQuickCaptureFileRegistry.release(destination)
    }
}

internal object AndroidQuickCaptureNames {
    private val technicalName = Regex("voice-note-\\d{8}-\\d{6}-[0-9a-fA-F]{8}\\.wav")

    fun displayName(recordedAtMillis: Long): String =
        "Voice note — " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Date(recordedAtMillis))

    fun isTechnicalName(name: String): Boolean = technicalName.matches(name)

    fun backfill(catalog: SqlDelightAudioCatalogRepository) {
        catalog.importedFiles(AndroidAudioImportConstants.SOURCE_ID)
            .filter { isTechnicalName(it.displayName) }
            .forEach { file ->
                val timestamp = file.modifiedMillis ?: return@forEach
                catalog.renameEntry(file.id, displayName(timestamp))
            }
    }
}

data class QuickCaptureUiState(
    val session: QuickCaptureSessionState = QuickCaptureSessionState(),
    val remainingSeconds: Long? = null,
) {
    val phase: QuickCapturePhase
        get() = session.phase
}

class AndroidQuickCaptureController(
    private val storage: QuickCaptureStorage,
    private val catalog: AudioImportCatalog,
    private val recorder: QuickCaptureAudioRecorder,
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val deadlineExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
    private val maxDurationMillis: Long = QuickCaptureSessionController.DEFAULT_MAX_DURATION_MILLIS,
    private val nowElapsedMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val wallClockMillis: () -> Long = { System.currentTimeMillis() },
    private val onStateChanged: (QuickCaptureUiState) -> Unit,
) : Closeable {
    private val operationLock = Any()
    private val session = QuickCaptureSessionController(
        nowElapsedMillis = nowElapsedMillis,
        maxDurationMillis = maxDurationMillis,
    )
    private val deadlineStopRequested = AtomicBoolean(false)
    private var destination: QuickCaptureDestination? = null
    private var finalizationFuture: Future<*>? = null
    private var deadlineFuture: ScheduledFuture<*>? = null

    fun state(): QuickCaptureUiState = uiState()

    fun start(): Boolean {
        synchronized(operationLock) {
            val started = session.start() ?: return false
            val nextDestination = runCatching { storage.destination(wallClockMillis()) }
                .getOrElse { error ->
                    session.fail(error.message ?: "Unable to prepare recording", started.generation)
                    publish()
                    return false
                }
            destination = nextDestination
            val result = runCatching {
                recorder.start(nextDestination.temporaryFile, nextDestination.finalFile).getOrThrow()
            }
            result.onFailure { error ->
                storage.delete(nextDestination)
                destination = null
                session.fail(error.message ?: "Microphone is unavailable", started.generation)
                publish()
            }.onSuccess {
                deadlineFuture = deadlineExecutor.schedule(
                    {
                        if (session.deadlineReached() && stop(QuickCaptureStopReason.DEADLINE)) {
                            deadlineStopRequested.set(true)
                        }
                    },
                    maxDurationMillis,
                    TimeUnit.MILLISECONDS,
                )
                publish()
            }
            return session.state().phase == QuickCapturePhase.RECORDING
        }
    }

    fun stop(): Boolean = stop(QuickCaptureStopReason.EXPLICIT)

    private fun stop(reason: QuickCaptureStopReason): Boolean {
        synchronized(operationLock) {
            val finalizing = session.stop(reason = reason) ?: return false
            deadlineFuture?.cancel(false)
            deadlineFuture = null
            publish()
            val recordingGeneration = finalizing.generation
            val recordingDestination = destination
            if (recordingDestination == null) {
                session.fail("Recording destination is unavailable", recordingGeneration)
                publish()
                return false
            }
            finalizationFuture = runCatching {
                ioExecutor.submit {
                    val result = runCatching {
                        val recording = recorder.stop().getOrThrow()
                        check(recording.sampleCount > 0L) { "No audio was captured" }
                        catalog.insertPending(
                            documentId = recordingDestination.documentId,
                            displayName = recordingDestination.displayName,
                            mimeType = recordingDestination.mimeType,
                            sizeBytes = recording.sizeBytes,
                            importedAtMillis = wallClockMillis(),
                        )
                    }
                    result.onSuccess {
                        storage.release(recordingDestination)
                        destination = null
                        session.complete(recordingGeneration)
                        publish()
                    }.onFailure { error ->
                        storage.delete(recordingDestination)
                        destination = null
                        session.fail(error.message ?: "Unable to save recording", recordingGeneration)
                        publish()
                    }
                }
            }.getOrElse { error ->
                recorder.cancel()
                storage.delete(recordingDestination)
                destination = null
                session.fail(error.message ?: "Unable to save recording", recordingGeneration)
                publish()
                return false
            }
            return true
        }
    }

    fun tick(): Boolean {
        if (deadlineStopRequested.compareAndSet(true, false)) {
            publish()
            return true
        }
        if (session.deadlineReached()) {
            val stopped = stop(QuickCaptureStopReason.DEADLINE)
            if (stopped) return true
        }
        publish()
        return false
    }

    fun cancel(): Boolean {
        val canceled = synchronized(operationLock) {
            val current = session.state()
            if (current.phase != QuickCapturePhase.RECORDING) return false
            deadlineFuture?.cancel(false)
            deadlineFuture = null
            session.cancel(current.generation)
            true
        }
        if (!canceled) return false
        recorder.cancel()
        destination?.let(storage::delete)
        destination = null
        publish()
        return true
    }

    override fun close() {
        cancel()
        synchronized(operationLock) { finalizationFuture }
            ?.let { runCatching { it.get() } }
        recorder.close()
        ioExecutor.shutdown()
        deadlineExecutor.shutdownNow()
    }

    private fun publish() {
        onStateChanged(uiState())
    }

    private fun uiState(): QuickCaptureUiState {
        val current = session.state()
        val remaining = if (current.phase == QuickCapturePhase.RECORDING) {
            current.deadlineElapsedMillis
                ?.let { deadline -> ((deadline - nowElapsedMillis()).coerceAtLeast(0L) + 999L) / 1000L }
        } else {
            null
        }
        return QuickCaptureUiState(current, remaining)
    }
}
