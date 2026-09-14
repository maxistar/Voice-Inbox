package me.maxistar.voiceinbox

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class VoiceKeyboardInputMethodService : InputMethodService() {
    private val controller = VoiceKeyboardController()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recordingCountdown = VoiceKeyboardRecordingCountdown(
        durationMillis = MAX_PHRASE_DURATION_MS,
        clock = VoiceKeyboardMonotonicClock(SystemClock::elapsedRealtime),
        scheduler = object : VoiceKeyboardCountdownScheduler {
            override fun postDelayed(runnable: Runnable, delayMillis: Long) {
                mainHandler.postDelayed(runnable, delayMillis)
            }

            override fun removeCallbacks(runnable: Runnable) {
                mainHandler.removeCallbacks(runnable)
            }
        },
        onTick = ::showRecordingCountdown,
        onExpired = ::stopForDurationLimit,
    )
    private val workExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val passiveWarmupScheduler = VoiceKeyboardWarmupScheduler(
        scheduler = object : VoiceKeyboardDelayScheduler {
            override fun postDelayed(runnable: Runnable, delayMillis: Long) {
                mainHandler.postDelayed(runnable, delayMillis)
            }

            override fun removeCallbacks(runnable: Runnable) {
                mainHandler.removeCallbacks(runnable)
            }
        },
        delayMillis = PASSIVE_WARMUP_DELAY_MS,
    )
    private val recordGestureCoordinator = HybridRecordGestureCoordinator(
        HybridRecordGesturePolicy(ViewConfiguration.getLongPressTimeout().toLong()),
    )
    private val backspaceRepeater = VoiceKeyboardBackspaceRepeater(
        scheduler = object : VoiceKeyboardRepeatScheduler {
            override fun postDelayed(runnable: Runnable, delayMillis: Long) {
                mainHandler.postDelayed(runnable, delayMillis)
            }

            override fun removeCallbacks(runnable: Runnable) {
                mainHandler.removeCallbacks(runnable)
            }
        },
        deleteOne = ::deleteOneBackspace,
    )
    private lateinit var recorder: VoiceKeyboardAudioRecorder
    private var inputActive = false
    private var inputViewActive = false
    private var requestGeneration = 0L
    private var requestPreparation: Future<Result<File>>? = null
    private var recordingStatusResource: Int? = null
    private var activeTouchPointerId: Int? = null
    private var activeTouchGeneration: Long? = null
    private var handledRecordingStopTouch = false
    private var suppressNextRecordClick = false

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
        applyNavigationBarInsets(view)
        statusView = view.findViewById(R.id.voiceKeyboardStatus)
        progressView = view.findViewById(R.id.voiceKeyboardProgress)
        recordButton = view.findViewById(R.id.voiceKeyboardRecord)
        dismissButton = view.findViewById(R.id.voiceKeyboardDismiss)
        setupButton = view.findViewById(R.id.voiceKeyboardSetup)
        backspaceButton = view.findViewById(R.id.voiceKeyboardBackspace)
        spaceButton = view.findViewById(R.id.voiceKeyboardSpace)
        enterButton = view.findViewById(R.id.voiceKeyboardEnter)
        nextKeyboardButton = view.findViewById(R.id.voiceKeyboardNextKeyboard)

        recordButton?.setOnClickListener {
            if (suppressNextRecordClick) {
                suppressNextRecordClick = false
            } else {
                handleRecordButtonActivation()
            }
        }
        recordButton?.setOnTouchListener(::handleRecordButtonTouch)
        dismissButton?.setOnClickListener {
            controller.dismissPendingResult()
            render(R.string.voice_keyboard_ready)
        }
        setupButton?.setOnClickListener { openVoiceInboxSetup() }
        backspaceButton?.apply {
            setOnClickListener {
                if (!backspaceRepeater.isRepeating()) deleteOneBackspace()
            }
            setOnLongClickListener {
                if (isEditorReady()) backspaceRepeater.start()
                true
            }
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    backspaceRepeater.cancel()
                }
                false
            }
        }
        spaceButton?.setOnClickListener { currentInputConnection?.commitText(" ", 1) }
        enterButton?.setOnClickListener(::performEnterAction)
        nextKeyboardButton?.setOnClickListener { switchKeyboard() }
        render(R.string.voice_keyboard_ready)
        return view
    }

    private fun applyNavigationBarInsets(view: View) {
        val initialPaddingLeft = view.paddingLeft
        val initialPaddingTop = view.paddingTop
        val initialPaddingRight = view.paddingRight
        val initialPaddingBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val navigationBarBottom = insets
                .getInsets(WindowInsetsCompat.Type.navigationBars())
                .bottom
            target.setPadding(
                initialPaddingLeft,
                initialPaddingTop,
                initialPaddingRight,
                initialPaddingBottom + navigationBarBottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        inputActive = true
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        inputActive = true
        inputViewActive = true
        flushPendingResult()
        schedulePassiveWarmUp()
    }

    override fun onFinishInput() {
        inputActive = false
        inputViewActive = false
        passiveWarmupScheduler.cancel()
        backspaceRepeater.cancel()
        cancelActiveDictationForLifecycle()
        super.onFinishInput()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        inputViewActive = false
        passiveWarmupScheduler.cancel()
        backspaceRepeater.cancel()
        cancelActiveDictationForLifecycle()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        requestGeneration += 1
        inputViewActive = false
        passiveWarmupScheduler.cancel()
        cancelRecordingCountdown()
        backspaceRepeater.cancel()
        recordGestureCoordinator.clear()
        recorder.close()
        controller.cancel()
        workExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun handleRecordButtonActivation() {
        when (controller.phase) {
            VoiceKeyboardPhase.RECORDING -> stopRecording()
            VoiceKeyboardPhase.WAITING_FOR_MODEL,
            VoiceKeyboardPhase.TRANSCRIBING,
            -> cancelCurrentRequest()
            VoiceKeyboardPhase.RESULT_PENDING -> {
                controller.dismissPendingResult()
                render(R.string.voice_keyboard_ready)
            }
            VoiceKeyboardPhase.IDLE,
            VoiceKeyboardPhase.ERROR,
            -> startRecordingAndPrepare(requestGeneration + 1, touchInitiated = false)
        }
    }

    private fun handleRecordButtonTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handledRecordingStopTouch = false
                if (controller.phase == VoiceKeyboardPhase.RECORDING) {
                    handledRecordingStopTouch = true
                    stopRecording()
                    return true
                }
                if (controller.phase !in setOf(VoiceKeyboardPhase.IDLE, VoiceKeyboardPhase.ERROR)) return true
                val pointerId = event.getPointerId(event.actionIndex)
                val generation = requestGeneration + 1
                if (!recordGestureCoordinator.begin(pointerId, generation, event.eventTime)) return true
                activeTouchPointerId = pointerId
                activeTouchGeneration = generation
                startRecordingAndPrepare(generation, touchInitiated = true)
            }

            MotionEvent.ACTION_UP -> {
                if (handledRecordingStopTouch) {
                    handledRecordingStopTouch = false
                    announceHandledTouchClick(view)
                    return true
                }
                finishRecordButtonTouch(view, event.getPointerId(event.actionIndex), event.eventTime)
            }

            MotionEvent.ACTION_CANCEL -> {
                val pointerId = activeTouchPointerId
                val generation = activeTouchGeneration
                activeTouchPointerId = null
                activeTouchGeneration = null
                handledRecordingStopTouch = false
                if (pointerId != null && generation != null && recordGestureCoordinator.cancel(pointerId, generation)) {
                    cancelCurrentRequest(generation)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                finishRecordButtonTouch(view, event.getPointerId(event.actionIndex), event.eventTime)
            }

            MotionEvent.ACTION_POINTER_DOWN -> Unit
        }
        return true
    }

    private fun finishRecordButtonTouch(view: View, pointerId: Int, eventTimeMillis: Long) {
        val activePointerId = activeTouchPointerId ?: return
        val generation = activeTouchGeneration ?: return
        if (pointerId != activePointerId) return
        val release = recordGestureCoordinator.release(pointerId, generation, eventTimeMillis)
        activeTouchPointerId = null
        activeTouchGeneration = null
        when (release?.release) {
            HybridRecordRelease.LATCH -> {
                if (generation == requestGeneration) {
                    when (controller.phase) {
                        VoiceKeyboardPhase.RECORDING -> {
                            recordingStatusResource = R.string.voice_keyboard_listening_latched_countdown
                            recordingCountdown.remainingSeconds()?.let { showRecordingCountdown(generation, it) }
                        }
                        else -> Unit
                    }
                }
                announceHandledTouchClick(view)
            }
            HybridRecordRelease.STOP_AND_TRANSCRIBE -> {
                when {
                    generation != requestGeneration -> recordGestureCoordinator.finish(generation)
                    controller.phase == VoiceKeyboardPhase.RECORDING -> stopRecording(generation)
                    else -> recordGestureCoordinator.finish(generation)
                }
            }
            null -> Unit
        }
    }

    private fun announceHandledTouchClick(view: View) {
        suppressNextRecordClick = true
        if (!view.performClick()) suppressNextRecordClick = false
    }

    private fun startRecordingAndPrepare(generation: Long, touchInitiated: Boolean) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            recordGestureCoordinator.finish(generation)
            controller.fail()
            render(R.string.voice_keyboard_microphone_permission_missing, showSetup = true)
            return
        }

        val repository = SpeechModelRepository.forActive(noBackupFilesDir.resolve("models"))
        if (repository.inspectLightweight() !is InstalledSpeechModelState.Ready) {
            recordGestureCoordinator.finish(generation)
            controller.fail()
            render(R.string.voice_keyboard_model_unavailable, showSetup = true)
            return
        }
        if (!controller.beginRecording()) return

        requestGeneration = generation
        passiveWarmupScheduler.cancel()
        requestPreparation = SpeechModelWarmup.prepare(repository, retryFailed = true)
        val preparationAction = if (touchInitiated) {
            recordGestureCoordinator.preparationAction(generation)
        } else {
            HybridRecordPreparationAction.START_LATCHED
        }
        if (preparationAction in setOf(
                HybridRecordPreparationAction.CANCEL,
                HybridRecordPreparationAction.IGNORE,
            )
        ) {
            cancelCurrentRequest(generation)
            return
        }
        recorder.start().onSuccess {
            recordingStatusResource =
                if (preparationAction == HybridRecordPreparationAction.START_HELD) {
                    R.string.voice_keyboard_listening_held_countdown
                } else {
                    R.string.voice_keyboard_listening_latched_countdown
                }
            recordingCountdown.start(generation)
        }.onFailure {
            recordGestureCoordinator.finish(generation)
            requestPreparation = null
            controller.fail()
            render(R.string.voice_keyboard_microphone_unavailable, showSetup = false)
        }
    }

    private fun schedulePassiveWarmUp() {
        passiveWarmupScheduler.schedule {
            if (!inputViewActive || controller.phase != VoiceKeyboardPhase.IDLE) return@schedule
            val repository = SpeechModelRepository.forActive(noBackupFilesDir.resolve("models"))
            if (repository.inspectLightweight() is InstalledSpeechModelState.Ready) {
                SpeechModelWarmup.prepare(repository, retryFailed = false)
            }
        }
    }

    private fun showRecordingCountdown(generation: Long, remainingSeconds: Long) {
        if (generation != requestGeneration || controller.phase != VoiceKeyboardPhase.RECORDING) return
        val status = recordingStatusResource ?: return
        render(
            status,
            recordingTime = VoiceKeyboardRecordingCountdown.formatRemaining(remainingSeconds),
        )
    }

    private fun cancelRecordingCountdown() {
        recordingCountdown.cancel()
        recordingStatusResource = null
    }

    private fun stopForDurationLimit(generation: Long) {
        if (generation == requestGeneration && controller.phase == VoiceKeyboardPhase.RECORDING) {
            stopRecording(generation)
        }
    }

    private fun stopRecording(expectedGeneration: Long = requestGeneration) {
        if (expectedGeneration != requestGeneration) return
        if (!controller.recordingStopped()) return
        recordGestureCoordinator.finish(expectedGeneration)
        activeTouchPointerId = null
        activeTouchGeneration = null
        cancelRecordingCountdown()
        val generation = expectedGeneration
        val preparation = requestPreparation
        render(
            if (preparation?.isDone == true && runCatching { preparation.get().isSuccess }.getOrDefault(false)) {
                R.string.voice_keyboard_transcribing
            } else {
                R.string.voice_keyboard_preparing
            },
        )
        workExecutor.execute {
            val samplesResult = recorder.stop()
            val samples = samplesResult.getOrElse { error ->
                mainHandler.post {
                    if (generation != requestGeneration) return@post
                    controller.fail()
                    requestPreparation = null
                    render(R.string.voice_keyboard_transcription_failed)
                }
                return@execute
            }
            if (samples.isEmpty()) {
                mainHandler.post {
                    if (generation != requestGeneration) return@post
                    requestPreparation = null
                    controller.cancel()
                    render(R.string.voice_keyboard_no_speech)
                }
                return@execute
            }
            val preparationResult = runCatching {
                checkNotNull(preparation) { "Speech model preparation was not started" }
                preparation.get().getOrThrow()
            }
            mainHandler.post {
                if (generation != requestGeneration || controller.phase != VoiceKeyboardPhase.WAITING_FOR_MODEL) {
                    return@post
                }
                preparationResult.onFailure {
                    requestPreparation = null
                    controller.fail()
                    render(R.string.voice_keyboard_model_unavailable, showSetup = true)
                }.onSuccess {
                    if (!controller.modelReady()) return@onSuccess
                    render(R.string.voice_keyboard_transcribing)
                    transcribeCapturedSamples(generation, samples)
                }
            }
        }
    }

    private fun transcribeCapturedSamples(generation: Long, samples: FloatArray) {
        workExecutor.execute {
            val result = runCatching { NativeTranscriptionBridge.transcribeChunk(samples)?.text }
            mainHandler.post {
                if (generation != requestGeneration || controller.phase != VoiceKeyboardPhase.TRANSCRIBING) return@post
                requestPreparation = null
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

    private fun cancelCurrentRequest(expectedGeneration: Long = requestGeneration) {
        if (expectedGeneration != requestGeneration) return
        requestGeneration += 1
        cancelRecordingCountdown()
        if (controller.phase == VoiceKeyboardPhase.RECORDING) {
            workExecutor.execute { recorder.cancel() }
        }
        requestPreparation = null
        recordGestureCoordinator.finish(expectedGeneration)
        activeTouchPointerId = null
        activeTouchGeneration = null
        controller.cancel()
        render(R.string.voice_keyboard_ready)
    }

    private fun cancelActiveDictationForLifecycle() {
        if (controller.phase in setOf(
                VoiceKeyboardPhase.RECORDING,
                VoiceKeyboardPhase.WAITING_FOR_MODEL,
                VoiceKeyboardPhase.TRANSCRIBING,
            )
        ) {
            cancelCurrentRequest()
        } else {
            recordGestureCoordinator.clear()
            activeTouchPointerId = null
            activeTouchGeneration = null
        }
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

    private fun deleteOneBackspace(): Boolean {
        if (!isEditorReady()) return false
        return VoiceKeyboardBackspace.deleteOne(currentInputConnection)
    }

    private fun isEditorReady(): Boolean = inputActive && controller.phase !in setOf(
        VoiceKeyboardPhase.WAITING_FOR_MODEL,
        VoiceKeyboardPhase.TRANSCRIBING,
    )

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

    private fun render(status: Int, showSetup: Boolean = false, recordingTime: String? = null) {
        statusView?.apply {
            if (recordingTime == null) {
                setText(status)
            } else {
                text = getString(status, recordingTime)
            }
        }
        val phase = controller.phase
        recordButton?.apply {
            val busy = phase in setOf(VoiceKeyboardPhase.WAITING_FOR_MODEL, VoiceKeyboardPhase.TRANSCRIBING)
            isEnabled = phase != VoiceKeyboardPhase.RESULT_PENDING
            alpha = if (isEnabled) 1f else 0.6f
            background = getDrawable(
                when (phase) {
                    VoiceKeyboardPhase.RECORDING -> R.drawable.voice_keyboard_mic_recording
                    VoiceKeyboardPhase.WAITING_FOR_MODEL,
                    VoiceKeyboardPhase.TRANSCRIBING,
                    VoiceKeyboardPhase.RESULT_PENDING,
                    -> R.drawable.voice_keyboard_mic_busy
                    VoiceKeyboardPhase.ERROR -> R.drawable.voice_keyboard_mic_error
                    VoiceKeyboardPhase.IDLE -> R.drawable.voice_keyboard_mic_idle
                },
            )
            setImageResource(
                when (phase) {
                    VoiceKeyboardPhase.RECORDING,
                    VoiceKeyboardPhase.WAITING_FOR_MODEL,
                    VoiceKeyboardPhase.TRANSCRIBING,
                    -> R.drawable.ic_voice_keyboard_stop
                    else -> R.drawable.ic_voice_keyboard_mic
                },
            )
            contentDescription = context.getString(
                when (phase) {
                    VoiceKeyboardPhase.RECORDING -> R.string.voice_keyboard_stop
                    VoiceKeyboardPhase.WAITING_FOR_MODEL,
                    VoiceKeyboardPhase.TRANSCRIBING,
                    -> R.string.voice_keyboard_cancel
                    VoiceKeyboardPhase.ERROR -> R.string.voice_keyboard_record
                    else -> R.string.voice_keyboard_record
                },
            )
            keepScreenOn = phase == VoiceKeyboardPhase.RECORDING
            progressView?.visibility = if (busy) View.VISIBLE else View.GONE
        }
        dismissButton?.visibility = if (phase == VoiceKeyboardPhase.RESULT_PENDING) View.VISIBLE else View.GONE
        setupButton?.visibility = if (showSetup) View.VISIBLE else View.GONE
        val editorReady = isEditorReady()
        if (!editorReady) backspaceRepeater.cancel()
        backspaceButton?.isEnabled = editorReady
        spaceButton?.isEnabled = editorReady
        enterButton?.isEnabled = editorReady
        nextKeyboardButton?.isEnabled = phase != VoiceKeyboardPhase.RECORDING
    }

    private companion object {
        const val PASSIVE_WARMUP_DELAY_MS = 400L
        const val MAX_PHRASE_DURATION_MS = 60_000L
    }
}
