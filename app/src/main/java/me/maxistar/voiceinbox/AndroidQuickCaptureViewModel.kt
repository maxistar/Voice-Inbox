package me.maxistar.voiceinbox

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.maxistar.voiceinbox.core.AndroidSqlDelightAudioCatalogFactory
import me.maxistar.voiceinbox.core.SqlDelightAudioCatalogRepository

class AndroidQuickCaptureViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val catalog: SqlDelightAudioCatalogRepository =
        AndroidSqlDelightAudioCatalogFactory(application).create()
    private val _state = MutableStateFlow(QuickCaptureUiState())
    val state: StateFlow<QuickCaptureUiState> = _state.asStateFlow()
    private val controller = AndroidQuickCaptureController(
        storage = AndroidQuickCaptureStorage(application),
        catalog = SqlDelightAudioImportCatalog(catalog),
        recorder = AndroidQuickCaptureAudioRecorder(),
        onStateChanged = { value -> _state.value = value },
    )

    fun start(): Boolean = controller.start()

    fun stop(): Boolean = controller.stop()

    fun tick(): Boolean = controller.tick()

    fun cancel() {
        controller.cancel()
    }

    override fun onCleared() {
        controller.close()
        catalog.close()
        super.onCleared()
    }
}
