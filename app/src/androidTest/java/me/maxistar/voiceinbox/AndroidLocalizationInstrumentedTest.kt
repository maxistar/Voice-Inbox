package me.maxistar.voiceinbox

import android.content.Context
import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.maxistar.voiceinbox.core.TaskText
import me.maxistar.voiceinbox.core.TaskTextKey
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class AndroidLocalizationInstrumentedTest {
    @Test
    fun taskTextResolverAndPluralResourcesFollowTheRequestedLocale() {
        val english = localizedContext(Locale.US)
        val russian = localizedContext(Locale("ru", "RU"))
        val taskTitle = TaskText(TaskTextKey.INSTALL_SPEECH_MODEL, "Install Speech Model")

        assertEquals("Install Speech Model", AndroidTaskTextResolver.resolve(english.resources, taskTitle))
        assertEquals("Установить речевую модель", AndroidTaskTextResolver.resolve(russian.resources, taskTitle))
        assertEquals("Расшифровать все (1 файл)", russian.resources.getQuantityString(R.plurals.transcribe_all_count, 1, 1))
        assertEquals("Расшифровать все (2 файла)", russian.resources.getQuantityString(R.plurals.transcribe_all_count, 2, 2))
        assertEquals("Расшифровать все (5 файлов)", russian.resources.getQuantityString(R.plurals.transcribe_all_count, 5, 5))
    }

    private fun localizedContext(locale: Locale): Context {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(configuration)
    }
}
