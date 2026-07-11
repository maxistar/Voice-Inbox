package me.maxistar.voiceinbox

import me.maxistar.voiceinbox.core.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechModelManifestTest {
    @Test
    fun embeddedManifestPinsAllRuntimeFiles() {
        val manifest = EmbeddedSpeechModel.manifest

        assertEquals(40, manifest.repositoryRevision.length)
        assertEquals(
            setOf(
                "encoder-model.int8.onnx",
                "decoder_joint-model.int8.onnx",
                "nemo128.onnx",
                "vocab.txt",
                "config.json",
            ),
            manifest.files.map { it.name }.toSet(),
        )
        assertTrue(manifest.files.all { it.sizeBytes > 0 })
        assertTrue(manifest.files.all { it.sha256.matches(Regex("[0-9a-f]{64}")) })
        assertEquals(670_619_803, manifest.totalSizeBytes)
    }
}
