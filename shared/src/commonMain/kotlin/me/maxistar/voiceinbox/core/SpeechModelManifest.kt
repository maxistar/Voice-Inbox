package me.maxistar.voiceinbox.core

data class SpeechModelFile(
    val name: String,
    val sizeBytes: Long,
    val sha256: String,
)

data class SpeechModelManifest(
    val modelId: String,
    val version: String,
    val repositoryRevision: String,
    val files: List<SpeechModelFile>,
    val safetyMarginBytes: Long,
) {
    val totalSizeBytes: Long = files.sumOf { it.sizeBytes }
    val requiredFreeBytes: Long = totalSizeBytes + safetyMarginBytes

    fun downloadUrl(file: SpeechModelFile): String {
        return "https://huggingface.co/$modelId/resolve/$repositoryRevision/${file.name}?download=true"
    }
}

enum class SpeechModelBackend {
    PARAKEET_TDT_ONNX,
    WHISPER_CPP,
}

enum class SpeechModelMaturity {
    STABLE,
    EXPERIMENTAL,
}

enum class SpeechModelPlatform {
    ANDROID,
    IOS,
}

data class SpeechModelDistribution(
    val networkDownloadAvailable: Boolean,
    val localImportAvailable: Boolean,
)

data class SpeechModelLanguageCoverage(
    val summary: String,
    val languageTags: List<String>,
)

data class SpeechModelAttribution(
    val sourceName: String,
    val sourceUrl: String,
    val upstreamName: String,
    val upstreamUrl: String,
    val licenseName: String,
    val licenseUrl: String,
)

data class SpeechModelDescriptor(
    val catalogId: String,
    val displayName: String,
    val backend: SpeechModelBackend,
    val manifest: SpeechModelManifest,
    val distribution: SpeechModelDistribution,
    val languages: SpeechModelLanguageCoverage,
    val maturity: SpeechModelMaturity,
    val attribution: SpeechModelAttribution,
    val supportedPlatforms: Set<SpeechModelPlatform>,
) {
    val approximateDownloadBytes: Long = manifest.totalSizeBytes
    val requiredStorageBytes: Long = manifest.requiredFreeBytes
}

object SpeechModelCatalog {
    val parakeetTdt06bV3Int8 = SpeechModelDescriptor(
        catalogId = "parakeet-tdt-0.6b-v3-int8",
        displayName = "Parakeet TDT 0.6B v3 INT8",
        backend = SpeechModelBackend.PARAKEET_TDT_ONNX,
        manifest = SpeechModelManifest(
        modelId = "istupakov/parakeet-tdt-0.6b-v3-onnx",
        version = "parakeet-tdt-0.6b-v3-int8-r1",
        repositoryRevision = "8f23f0c03c8761650bdb5b40aaf3e40d2c15f1ce",
        files = listOf(
            SpeechModelFile(
                name = "encoder-model.int8.onnx",
                sizeBytes = 652_183_999,
                sha256 = "6139d2fa7e1b086097b277c7149725edbab89cc7c7ae64b23c741be4055aff09",
            ),
            SpeechModelFile(
                name = "decoder_joint-model.int8.onnx",
                sizeBytes = 18_202_004,
                sha256 = "eea7483ee3d1a30375daedc8ed83e3960c91b098812127a0d99d1c8977667a70",
            ),
            SpeechModelFile(
                name = "nemo128.onnx",
                sizeBytes = 139_764,
                sha256 = "a9fde1486ebfcc08f328d75ad4610c67835fea58c73ba57e3209a6f6cf019e9f",
            ),
            SpeechModelFile(
                name = "vocab.txt",
                sizeBytes = 93_939,
                sha256 = "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d",
            ),
            SpeechModelFile(
                name = "config.json",
                sizeBytes = 97,
                sha256 = "666903c76b9798caf2c210afd4f6cd60b08a8dbf9800ec8d7a3bc0d2148ac466",
            ),
        ),
        safetyMarginBytes = 64L * 1024L * 1024L,
        ),
        distribution = SpeechModelDistribution(
            networkDownloadAvailable = true,
            localImportAvailable = true,
        ),
        languages = SpeechModelLanguageCoverage(
            summary = "25 European languages",
            languageTags = listOf(
                "bg", "cs", "da", "de", "el", "en", "es", "et", "fi", "fr", "hr", "hu",
                "it", "lt", "lv", "mt", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "uk",
            ),
        ),
        maturity = SpeechModelMaturity.STABLE,
        attribution = SpeechModelAttribution(
            sourceName = "istupakov/parakeet-tdt-0.6b-v3-onnx",
            sourceUrl = "https://huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx",
            upstreamName = "NVIDIA Parakeet TDT 0.6B v3",
            upstreamUrl = "https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3",
            licenseName = "CC BY 4.0",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
        ),
        supportedPlatforms = setOf(SpeechModelPlatform.ANDROID, SpeechModelPlatform.IOS),
    )

    val models: List<SpeechModelDescriptor> = listOf(parakeetTdt06bV3Int8)
    val defaultModel: SpeechModelDescriptor = parakeetTdt06bV3Int8
}
