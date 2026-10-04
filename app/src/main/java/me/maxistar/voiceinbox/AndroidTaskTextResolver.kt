package me.maxistar.voiceinbox

import android.content.res.Resources
import me.maxistar.voiceinbox.core.TaskText
import me.maxistar.voiceinbox.core.TaskTextKey

/** Resolves locale-neutral shared task text at the Android boundary. */
internal object AndroidTaskTextResolver {
    fun resolve(resources: Resources, value: TaskText): String = when (value.key) {
        TaskTextKey.OPAQUE -> value.fallback
        TaskTextKey.INSTALL_SPEECH_MODEL -> resources.getString(R.string.task_install_speech_model)
        TaskTextKey.AUTOMATIC_TRANSCRIPT_EXPORT -> resources.getString(R.string.task_automatic_transcript_export)
        TaskTextKey.OUTPUT_OPTIONAL_DETAIL -> resources.getString(R.string.task_output_optional_detail)
        TaskTextKey.REFRESH_AUDIO_FOLDER -> resources.getString(R.string.task_refresh_audio_folder)
        TaskTextKey.RESTORE_AUDIO_FOLDER_ACCESS -> resources.getString(R.string.task_restore_audio_folder_access)
        TaskTextKey.REQUIRED -> resources.getString(R.string.task_required)
        TaskTextKey.OPTIONAL -> resources.getString(R.string.task_optional)
        TaskTextKey.INSTALLING -> resources.getString(R.string.task_installing)
        TaskTextKey.SCANNING -> resources.getString(R.string.task_scanning)
        TaskTextKey.NEEDS_ATTENTION -> resources.getString(R.string.task_needs_attention)
        TaskTextKey.NEW -> resources.getString(R.string.new_label)
        TaskTextKey.PROCESSING -> resources.getString(R.string.task_processing)
        TaskTextKey.PROCESSED -> resources.getString(R.string.processed)
        TaskTextKey.FAILED -> resources.getString(R.string.task_failed)
        TaskTextKey.NO_SPEECH -> resources.getString(R.string.task_no_speech)
        TaskTextKey.CANCEL -> resources.getString(R.string.voice_keyboard_cancel)
        TaskTextKey.RETRY_DOWNLOAD -> resources.getString(R.string.task_retry_download)
        TaskTextKey.DOWNLOAD -> resources.getString(R.string.task_download)
        TaskTextKey.CREATE_NEW -> resources.getString(R.string.settings_output_create_new)
        TaskTextKey.CHOOSE_EXISTING -> resources.getString(R.string.settings_output_choose_existing)
        TaskTextKey.HIDE -> resources.getString(R.string.task_hide)
        TaskTextKey.SELECT_FOLDER -> resources.getString(R.string.task_select_folder)
        TaskTextKey.RETRY -> resources.getString(R.string.task_retry)
        TaskTextKey.TRANSCRIBE -> resources.getString(R.string.task_transcribe)
        TaskTextKey.SHOW_TEXT -> resources.getString(R.string.task_show_text)
        TaskTextKey.STOP -> resources.getString(R.string.voice_keyboard_stop)
        TaskTextKey.PLAY -> resources.getString(R.string.task_play)
        TaskTextKey.IMPORT_AUDIO_FILES -> resources.getString(R.string.task_import_audio_files)
        TaskTextKey.SELECT_AUDIO_FOLDER -> resources.getString(R.string.select_audio_folder)
        TaskTextKey.NO_NEW_TASKS -> resources.getString(R.string.task_no_new_tasks)
        TaskTextKey.NO_PROCESSED_AUDIO -> resources.getString(R.string.task_no_processed_audio)
        TaskTextKey.NO_TASKS -> resources.getString(R.string.task_no_tasks)
        TaskTextKey.INSTALLING_MODEL -> resources.getString(R.string.task_installing_model)
        TaskTextKey.SCANNING_AUDIO_FOLDER -> resources.getString(R.string.task_scanning_audio_folder)
        TaskTextKey.PREPARING_SPEECH_MODEL -> resources.getString(R.string.task_preparing_speech_model)
        TaskTextKey.CHOOSE_MODEL -> resources.getString(R.string.task_choose_model)
        TaskTextKey.SELECTED_MODEL_DETAIL -> resources.getString(
            R.string.task_selected_model_detail,
            value.arguments[0],
            value.arguments[1],
            value.arguments[2],
            value.arguments[3],
            value.arguments[4],
        )
    }
}
