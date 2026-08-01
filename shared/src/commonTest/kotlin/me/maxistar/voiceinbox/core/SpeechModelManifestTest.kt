package me.maxistar.voiceinbox.core


import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class SpeechModelManifestTest {
    @Test
    fun productionCatalogContainsOnlyStableParakeetDefault() {
        val descriptor = SpeechModelCatalog.defaultModel
        val manifest = descriptor.manifest

        assertEquals(listOf(descriptor), SpeechModelCatalog.models)
        assertEquals("parakeet-tdt-0.6b-v3-int8", descriptor.catalogId)
        assertEquals(SpeechModelBackend.PARAKEET_TDT_ONNX, descriptor.backend)
        assertEquals(SpeechModelMaturity.STABLE, descriptor.maturity)
        assertEquals(
            setOf(SpeechModelPlatform.ANDROID, SpeechModelPlatform.IOS),
            descriptor.supportedPlatforms,
        )
        assertTrue(descriptor.distribution.networkDownloadAvailable)
        assertTrue(descriptor.distribution.localImportAvailable)
        assertTrue(descriptor.languages.languageTags.containsAll(listOf("en", "de", "ru", "uk")))
        assertEquals("CC BY 4.0", descriptor.attribution.licenseName)

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
        assertEquals(manifest.totalSizeBytes, descriptor.approximateDownloadBytes)
        assertEquals(manifest.requiredFreeBytes, descriptor.requiredStorageBytes)
        assertEquals(
            "https://huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx/resolve/" +
                "8f23f0c03c8761650bdb5b40aaf3e40d2c15f1ce/encoder-model.int8.onnx?download=true",
            manifest.downloadUrl(manifest.files.first()),
        )
    }
}
