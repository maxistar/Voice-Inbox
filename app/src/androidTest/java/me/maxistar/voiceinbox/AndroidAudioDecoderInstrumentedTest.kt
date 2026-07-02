package me.maxistar.voiceinbox

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import me.maxistar.voiceinbox.test.R as TestR

@RunWith(AndroidJUnit4::class)
class AndroidAudioDecoderInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val testContext = instrumentation.context
    private val targetContext = instrumentation.targetContext

    @Test
    fun decodesGuaranteedPlatformFixtures() {
        val fixtures: List<Int> = listOf(
            TestR.raw.test_audio_wav,
            TestR.raw.test_audio_m4a,
            TestR.raw.test_audio_mp3,
            TestR.raw.test_audio_flac,
            TestR.raw.test_audio_vorbis,
            TestR.raw.test_audio_opus,
            TestR.raw.test_audio_aac,
            TestR.raw.test_audio_amr,
        )

        fixtures.forEach { resource ->
            var sampleCount = 0
            val fixture = java.io.File(targetContext.cacheDir, "audio-$resource").apply {
                testContext.resources.openRawResource(resource).use { input ->
                    outputStream().use(input::copyTo)
                }
            }
            val uri = Uri.fromFile(fixture)
            AndroidAudioDecoder(targetContext.contentResolver).decode(
                uri = uri,
                onProgress = { _, _ -> },
                onChunk = { sampleCount += it.size },
            )
            assertTrue("Expected decoded samples for resource $resource", sampleCount > 0)
            fixture.delete()
        }
    }
}
