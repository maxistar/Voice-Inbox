package me.maxistar.voiceinbox

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class QuickCaptureTest {
    private val temporaryRoot = Files.createTempDirectory("quick-capture-test").toFile()

    @After
    fun cleanup() {
        temporaryRoot.deleteRecursively()
    }

    @Test
    fun wavWriterWritesValidPcmHeaderAndSamples() {
        val temporary = File(temporaryRoot, "recording.part")
        val finalFile = File(temporaryRoot, "recording.wav")
        val writer = PcmWavWriter(temporary, finalFile)

        writer.open()
        writer.append(shortArrayOf(0x0102, -2))
        assertEquals(4L, writer.finish())

        val bytes = finalFile.readBytes()
        assertEquals(48, bytes.size)
        assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WAVE", bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertEquals(16_000, littleEndianInt(bytes, 24))
        assertEquals(1, littleEndianShort(bytes, 22))
        assertEquals(16, littleEndianShort(bytes, 34))
        assertEquals(4, littleEndianInt(bytes, 40))
        assertArrayEquals(byteArrayOf(0x02, 0x01, 0xfe.toByte(), 0xff.toByte()), bytes.copyOfRange(44, 48))
        assertFalse(temporary.exists())
    }

    @Test
    fun wavWriterAbortRemovesTemporaryAndFinalFiles() {
        val temporary = File(temporaryRoot, "recording.part")
        val finalFile = File(temporaryRoot, "recording.wav")
        val writer = PcmWavWriter(temporary, finalFile)

        writer.open()
        writer.append(shortArrayOf(1))
        writer.abort()

        assertFalse(temporary.exists())
        assertFalse(finalFile.exists())
    }

    @Test
    fun sessionControllerUsesToggleAndDeadlineWithoutDuplicateStarts() {
        var elapsed = 1_000L
        val controller = QuickCaptureSessionController(
            nowElapsedMillis = { elapsed },
            maxDurationMillis = 100L,
        )

        val started = controller.start()
        assertNotNull(started)
        assertEquals(QuickCapturePhase.RECORDING, controller.state().phase)
        assertNull(controller.start())

        elapsed = 1_100L
        assertTrue(controller.deadlineReached())
        assertNotNull(controller.stop())
        assertEquals(QuickCapturePhase.FINALIZING, controller.state().phase)
        assertNull(controller.stop())
    }

    @Test
    fun activeDestinationRemainsProtectedUntilReleased() {
        val destination = QuickCaptureDestination(
            temporaryFile = File(temporaryRoot, "active.part"),
            finalFile = File(temporaryRoot, "active.wav"),
            documentId = "content://fake/active.wav",
            displayName = "active.wav",
        )

        AndroidQuickCaptureFileRegistry.protect(destination)
        assertTrue(AndroidQuickCaptureFileRegistry.isProtected(destination.temporaryFile))
        assertTrue(AndroidQuickCaptureFileRegistry.isProtected(destination.finalFile))

        AndroidQuickCaptureFileRegistry.release(destination)
        assertFalse(AndroidQuickCaptureFileRegistry.isProtected(destination.temporaryFile))
        assertFalse(AndroidQuickCaptureFileRegistry.isProtected(destination.finalFile))
    }

    @Test
    fun controllerStopsAtDeadlineWithoutUiTicks() {
        val storage = FakeQuickCaptureStorage(temporaryRoot)
        val catalog = FakeAudioImportCatalog()
        val executor = Executors.newSingleThreadExecutor()
        val controller = AndroidQuickCaptureController(
            storage = storage,
            catalog = catalog,
            recorder = FakeQuickCaptureRecorder(sampleCount = 3L, sizeBytes = 50L),
            ioExecutor = executor,
            maxDurationMillis = 40L,
            nowElapsedMillis = { System.nanoTime() / 1_000_000L },
            onStateChanged = {},
        )

        assertTrue(controller.start())
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (controller.state().phase != QuickCapturePhase.SUCCESS && System.nanoTime() < deadlineNanos) {
            Thread.yield()
        }

        assertEquals(QuickCapturePhase.SUCCESS, controller.state().phase)
        assertEquals(QuickCaptureStopReason.DEADLINE, controller.state().sessionStopReason())
        assertEquals(1, catalog.rows.size)
        controller.close()
    }

    @Test
    fun closeWaitsForFinalizationBeforeClosingRecorder() {
        val recorder = BlockingQuickCaptureRecorder()
        val controller = AndroidQuickCaptureController(
            storage = FakeQuickCaptureStorage(temporaryRoot),
            catalog = FakeAudioImportCatalog(),
            recorder = recorder,
            nowElapsedMillis = { System.nanoTime() / 1_000_000L },
            onStateChanged = {},
        )
        val closeExecutor = Executors.newSingleThreadExecutor()

        assertTrue(controller.start())
        assertTrue(controller.stop())
        assertTrue(recorder.stopStarted.await(2, TimeUnit.SECONDS))
        val closeFuture = closeExecutor.submit { controller.close() }

        Thread.sleep(50L)
        assertFalse(closeFuture.isDone)
        assertFalse(recorder.closed)

        recorder.allowStop.countDown()
        closeFuture.get(2, TimeUnit.SECONDS)
        assertTrue(recorder.closed)
        closeExecutor.shutdownNow()
    }

    @Test
    fun controllerCreatesPendingCatalogEntryOnlyAfterSuccessfulRecording() {
        val storage = FakeQuickCaptureStorage(temporaryRoot)
        val catalog = FakeAudioImportCatalog()
        val executor = Executors.newSingleThreadExecutor()
        val controller = AndroidQuickCaptureController(
            storage = storage,
            catalog = catalog,
            recorder = FakeQuickCaptureRecorder(sampleCount = 3L, sizeBytes = 50L),
            ioExecutor = executor,
            nowElapsedMillis = { 10L },
            wallClockMillis = { 20L },
            onStateChanged = {},
        )

        assertTrue(controller.start())
        assertTrue(controller.stop())
        executor.shutdown()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))

        assertEquals(1, catalog.rows.size)
        assertEquals(AudioFileExpectation.PENDING, catalog.rows.single())
        assertEquals(QuickCapturePhase.SUCCESS, controller.state().phase)
        controller.close()
    }

    @Test
    fun controllerCleansUpWhenRecordingHasNoSamplesOrCatalogFails() {
        val emptyStorage = FakeQuickCaptureStorage(temporaryRoot)
        val emptyExecutor = Executors.newSingleThreadExecutor()
        val emptyController = AndroidQuickCaptureController(
            storage = emptyStorage,
            catalog = FakeAudioImportCatalog(),
            recorder = FakeQuickCaptureRecorder(sampleCount = 0L, sizeBytes = 44L),
            ioExecutor = emptyExecutor,
            nowElapsedMillis = { 10L },
            onStateChanged = {},
        )
        assertTrue(emptyController.start())
        assertTrue(emptyController.stop())
        emptyExecutor.shutdown()
        assertTrue(emptyExecutor.awaitTermination(2, TimeUnit.SECONDS))
        assertEquals(QuickCapturePhase.ERROR, emptyController.state().phase)
        assertTrue(emptyStorage.deleted)
        emptyController.close()

        val failedStorage = FakeQuickCaptureStorage(temporaryRoot)
        val failedExecutor = Executors.newSingleThreadExecutor()
        val failedController = AndroidQuickCaptureController(
            storage = failedStorage,
            catalog = FakeAudioImportCatalog(failInsert = true),
            recorder = FakeQuickCaptureRecorder(sampleCount = 2L, sizeBytes = 48L),
            ioExecutor = failedExecutor,
            nowElapsedMillis = { 10L },
            onStateChanged = {},
        )
        assertTrue(failedController.start())
        assertTrue(failedController.stop())
        failedExecutor.shutdown()
        assertTrue(failedExecutor.awaitTermination(2, TimeUnit.SECONDS))
        assertEquals(QuickCapturePhase.ERROR, failedController.state().phase)
        assertTrue(failedStorage.deleted)
        failedController.close()
    }

    private class FakeQuickCaptureStorage(
        root: File,
    ) : QuickCaptureStorage {
        private val destination = QuickCaptureDestination(
            temporaryFile = File(root, "fake.part"),
            finalFile = File(root, "fake.wav"),
            documentId = "content://fake/recording.wav",
            displayName = "voice-note-test.wav",
        )
        var deleted = false

        override fun destination(recordedAtMillis: Long): QuickCaptureDestination = destination

        override fun delete(destination: QuickCaptureDestination) {
            deleted = true
        }
    }

    private class FakeQuickCaptureRecorder(
        private val sampleCount: Long,
        private val sizeBytes: Long,
    ) : QuickCaptureAudioRecorder {
        private var started = false

        override fun start(temporaryFile: File, finalFile: File): Result<Unit> {
            started = true
            return Result.success(Unit)
        }

        override fun stop(): Result<QuickCaptureRecording> {
            check(started)
            started = false
            return Result.success(QuickCaptureRecording(File("recording.wav"), sampleCount, sizeBytes))
        }

        override fun cancel() {
            started = false
        }

        override fun close() = Unit
    }

    private class BlockingQuickCaptureRecorder : QuickCaptureAudioRecorder {
        val stopStarted = CountDownLatch(1)
        val allowStop = CountDownLatch(1)
        var closed = false
        private var started = false

        override fun start(temporaryFile: File, finalFile: File): Result<Unit> {
            started = true
            return Result.success(Unit)
        }

        override fun stop(): Result<QuickCaptureRecording> {
            check(started)
            stopStarted.countDown()
            check(allowStop.await(2, TimeUnit.SECONDS)) { "Timed out waiting to stop" }
            started = false
            return Result.success(QuickCaptureRecording(File("recording.wav"), 2L, 48L))
        }

        override fun cancel() {
            started = false
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeAudioImportCatalog(
        private val failInsert: Boolean = false,
    ) : AudioImportCatalog {
        val rows = mutableListOf<AudioFileExpectation>()

        override fun contains(documentId: String): Boolean = false

        override fun insertPending(
            documentId: String,
            displayName: String,
            mimeType: String?,
            sizeBytes: Long,
            importedAtMillis: Long,
        ) {
            if (failInsert) error("catalog unavailable")
            rows += AudioFileExpectation.PENDING
        }
    }

    private enum class AudioFileExpectation {
        PENDING,
    }

    private fun QuickCaptureUiState.sessionStopReason(): QuickCaptureStopReason? =
        session.stopReason

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
}
