package me.maxistar.voiceinbox

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class VoiceKeyboardInputMethodService : InputMethodService() {
    private val controller = VoiceKeyboardController()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val workExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var recorder: VoiceKeyboardAudioRecorder
    private var inputActive = false
    private var requestGeneration = 0L
    private var warmUpGeneration = 0L
    private var warmUpActive = false

    private var statusView: TextView? = null
    private var progressView: ProgressBar? = null
    private var recordButton: ImageButton? = null
    private var dismissButton: Button? = null
    private var setupButton: Button? = null
    private var backspaceButton: ImageButton? = null
    private var spaceButton: ImageButton? = null
    private var enterButton: ImageButton? = null
    private var nextKeyboardButton: ImageButton? = null

    override fun onCreate() {
        super.onCreate()
        recorder = VoiceKeyboardAudioRecorder()
    }

    override fun onCreateInputView(): View {
        val view = LayoutInflater.from(this).inflate(R.layout.input_view_voice_keyboard, null)
        statusView = view.findViewById(R.id.voiceKeyboardStatus)
        progressView = view.findViewById(R.id.voiceKeyboardProgress)
        recordButton = view.findViewById(R.id.voiceKeyboardRecord)
        dismissButton = view.findViewById(R.id.voiceKeyboardDismiss)
        setupButton = view.findViewById(R.id.voiceKeyboardSetup)
        backspaceButton = view.findViewById(R.id.voiceKeyboardBackspace)
        spaceButton = view.findViewById(R.id.voiceKeyboardSpace)
        enterButton = view.findViewById(R.id.voiceKeyboardEnter)
        nextKeyboardButton = view.findViewById(R.id.voiceKeyboardNextKeyboard)

        recordButton?.setOnClickListener { handleRecordButton() }
        dismissButton?.setOnClickListener {
            controller.dismissPendingResult()
            render(R.string.voice_keyboard_ready)
        }
        setupButton?.setOnClickListener { openVoiceInboxSetup() }
        backspaceButton?.setOnClickListener { currentInputConnection?.deleteSurroundingText(1, 0) }
        spaceButton?.setOnClickListener { currentInputConnection?.commitText(" ", 1) }
        enterButton?.setOnClickListener(::performEnterAction)
        nextKeyboardButton?.setOnClickListener { switchKeyboard() }
        render(R.string.voice_keyboard_ready)
        return view
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        inputActive = true
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        inputActive = true
        flushPendingResult()
        mainHandler.post(::startVisibleWarmUp)
    }

    override fun onFinishInput() {
        inputActive = false
        warmUpGeneration += 1
        warmUpActive = false
        super.onFinishInput()
    }

    override fun onDestroy() {
        requestGeneration += 1
        warmUpGeneration += 1
        recorder.close()
        controller.cancel()
        workExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun handleRecordButton() {
        if (warmUpActive) return
        when (controller.phase) {
            VoiceKeyboardPhase.RECORDING -> stopRecording()
            VoiceKeyboardPhase.PREPARING,
            VoiceKeyboardPhase.TRANSCRIBING,
            -> cancelCurrentRequest()
            VoiceKeyboardPhase.RESULT_PENDING -> {
                controller.dismissPendingResult()
                render(R.string.voice_keyboard_ready)
            }
            VoiceKeyboardPhase.IDLE,
            VoiceKeyboardPhase.ERROR,
            -> prepareAndStartRecording()
        }
    }

    private fun prepareAndStartRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            controller.fail()
            render(R.string.voice_keyboard_microphone_permission_missing, showSetup = true)
            return
        }
        if (!controller.beginPreparation()) return
        val generation = ++requestGeneration
        render(R.string.voice_keyboard_preparing)
        workExecutor.execute {
            val preparation = prepareModelForDictation()
            mainHandler.post {
                if (generation != requestGeneration || controller.phase != VoiceKeyboardPhase.PREPARING) return@post
                preparation.onSuccess {
                    recorder.start().onSuccess {
                        if (controller.recordingStarted()) {
                            render(R.string.voice_keyboard_listening)
                            mainHandler.postDelayed({ stopForDurationLimit(generation) }, MAX_PHRASE_DURATION_MS)
                        }
                    }.onFailure {
                        controller.fail()
                        render(R.string.voice_keyboard_microphone_unavailable, showSetup = false)
                    }
                }.onFailure {
                    controller.fail()
                    render(R.string.voice_keyboard_model_unavailable, showSetup = true)
                }
            }
        }
    }

    private fun startVisibleWarmUp() {
        if (warmUpActive || controller.phase != VoiceKeyboardPhase.IDLE) return
        val generation = ++warmUpGeneration
        warmUpActive = true
        render(R.string.voice_keyboard_preparing)
        workExecutor.execute {
            val repository = SpeechModelRepository.forActive(noBackupFilesDir.resolve("models"))
            val preparation = SpeechModelWarmup.prepare(repository, retryFailed = false).get()
            mainHandler.post {
                if (generation != warmUpGeneration) return@post
                warmUpActive = false
                render(R.string.voice_keyboard_ready)
            }
        }
    }

    private fun prepareModelForDictation(): Result<Unit> = runCatching {
        val repository = SpeechModelRepository.forActive(noBackupFilesDir.resolve("models"))
        SpeechModelWarmup.prepare(repository, retryFailed = true).get().getOrThrow()
    }

    private fun stopForDurationLimit(generation: Long) {
        if (generation == requestGeneration && controller.phase == VoiceKeyboardPhase.RECORDING) {
            stopRecording()
        }
    }

    private fun stopRecording() {
        if (!controller.recordingStopped()) return
        mainHandler.removeCallbacksAndMessages(null)
        val generation = requestGeneration
        render(R.string.voice_keyboard_transcribing)
        workExecutor.execute {
            val result = recorder.stop().mapCatching { samples ->
                if (samples.isEmpty()) null else NativeTranscriptionBridge.transcribeChunk(samples)?.text
            }
            mainHandler.post {
                if (generation != requestGeneration || controller.phase != VoiceKeyboardPhase.TRANSCRIBING) return@post
                result.onSuccess { text ->
                    val completed = controller.completeTranscription(text)
                    when {
                        completed == null -> render(R.string.voice_keyboard_no_speech)
                        commitToCurrentEditor(completed) -> render(R.string.voice_keyboard_ready)
                        else -> {
                            controller.deferResult(completed)
                            render(R.string.voice_keyboard_result_pending)
                        }
                    }
                }.onFailure {
                    controller.fail()
                    render(R.string.voice_keyboard_transcription_failed)
                }
            }
        }
    }

    private fun cancelCurrentRequest() {
        requestGeneration += 1
        mainHandler.removeCallbacksAndMessages(null)
        if (controller.phase == VoiceKeyboardPhase.RECORDING) {
            workExecutor.execute { recorder.cancel() }
        }
        controller.cancel()
        render(R.string.voice_keyboard_ready)
    }

    private fun flushPendingResult() {
        val pending = controller.pendingResult() ?: return
        if (commitToCurrentEditor(pending)) {
            controller.markPendingCommitted()
            render(R.string.voice_keyboard_ready)
        }
    }

    private fun commitToCurrentEditor(text: String): Boolean {
        if (!inputActive || text.isBlank()) return false
        return currentInputConnection?.commitText(text, 1) == true
    }

    private fun performEnterAction(@Suppress("UNUSED_PARAMETER") view: View) {
        val connection = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val supportsAction = action in setOf(
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_NEXT,
        ) && info?.imeOptions?.and(EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0
        if (supportsAction) {
            connection.performEditorAction(action)
        } else {
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun switchKeyboard() {
        val restored = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && switchToPreviousInputMethod()
        if (VoiceKeyboardSwitching.returnAction(restored) == VoiceKeyboardReturnAction.SHOW_PICKER) {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }
    }

    private fun openVoiceInboxSetup() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(VoiceKeyboardSetup.EXTRA_REQUEST_MICROPHONE_PERMISSION, true),
        )
    }

    private fun render(status: Int, showSetup: Boolean = false) {
        statusView?.setText(status)
        val phase = controller.phase
        recordButton?.apply {
            val busy = warmUpActive || phase in setOf(VoiceKeyboardPhase.PREPARING, VoiceKeyboardPhase.TRANSCRIBING)
            isEnabled = phase != VoiceKeyboardPhase.RESULT_PENDING && !warmUpActive
            alpha = if (isEnabled) 1f else 0.6f
            background = getDrawable(
                when {
                    warmUpActive -> R.drawable.voice_keyboard_mic_busy
                    else -> when (phase) {
                    VoiceKeyboardPhase.RECORDING -> R.drawable.voice_keyboard_mic_recording
                    VoiceKeyboardPhase.PREPARING,
                    VoiceKeyboardPhase.TRANSCRIBING,
                    VoiceKeyboardPhase.RESULT_PENDING,
                    -> R.drawable.voice_keyboard_mic_busy
                    VoiceKeyboardPhase.ERROR -> R.drawable.voice_keyboard_mic_error
                    VoiceKeyboardPhase.IDLE -> R.drawable.voice_keyboard_mic_idle
                    }
                },
            )
            setImageResource(
                when {
                    warmUpActive -> R.drawable.ic_voice_keyboard_mic
                    else -> when (phase) {
                    VoiceKeyboardPhase.RECORDING,
                    VoiceKeyboardPhase.PREPARING,
                    VoiceKeyboardPhase.TRANSCRIBING,
                    -> R.drawable.ic_voice_keyboard_stop
                    else -> R.drawable.ic_voice_keyboard_mic
                    }
                },
            )
            contentDescription = context.getString(
                when {
                    warmUpActive -> R.string.voice_keyboard_preparing
                    else -> when (phase) {
                    VoiceKeyboardPhase.RECORDING -> R.string.voice_keyboard_stop
                    VoiceKeyboardPhase.PREPARING,
                    VoiceKeyboardPhase.TRANSCRIBING,
                    -> R.string.voice_keyboard_cancel
                    VoiceKeyboardPhase.ERROR -> R.string.voice_keyboard_record
                    else -> R.string.voice_keyboard_record
                    }
                },
            )
            keepScreenOn = phase == VoiceKeyboardPhase.RECORDING
            progressView?.visibility = if (busy) View.VISIBLE else View.GONE
        }
        dismissButton?.visibility = if (phase == VoiceKeyboardPhase.RESULT_PENDING) View.VISIBLE else View.GONE
        setupButton?.visibility = if (showSetup) View.VISIBLE else View.GONE
        val editorReady = inputActive && phase !in setOf(VoiceKeyboardPhase.PREPARING, VoiceKeyboardPhase.TRANSCRIBING)
        backspaceButton?.isEnabled = editorReady
        spaceButton?.isEnabled = editorReady
        enterButton?.isEnabled = editorReady
        nextKeyboardButton?.isEnabled = phase != VoiceKeyboardPhase.RECORDING
    }

    private companion object {
        const val MAX_PHRASE_DURATION_MS = 30_000L
    }
}
