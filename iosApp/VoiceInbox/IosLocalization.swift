import Foundation
import Shared

enum L10n {
    static func text(_ key: String, fallback: String) -> String {
        NSLocalizedString(key, value: fallback, comment: "")
    }

    static func format(_ key: String, fallback: String, _ arguments: CVarArg...) -> String {
        String(format: text(key, fallback: fallback), locale: .current, arguments: arguments)
    }

    static func plural(_ key: String, count: Int, fallback: String) -> String {
        format("\(key).\(pluralForm(for: count, languageCode: Locale.current.language.languageCode?.identifier))", fallback: fallback, count)
    }

    static func pluralForm(for count: Int, languageCode: String?) -> String {
        if languageCode == "ru" {
            let lastTwo = count % 100
            let last = count % 10
            return (last == 1 && lastTwo != 11) ? "one" :
                ((2...4).contains(last) && !(12...14).contains(lastTwo) ? "few" : "many")
        }
        return count == 1 ? "one" : "other"
    }
}

enum IosTaskTextResolver {
    static func resolve(_ text: TaskText) -> String {
        switch text.key.name {
        case "INSTALL_SPEECH_MODEL": return L10n.text("task.installSpeechModel", fallback: text.fallback)
        case "AUTOMATIC_TRANSCRIPT_EXPORT": return L10n.text("task.automaticTranscriptExport", fallback: text.fallback)
        case "OUTPUT_OPTIONAL_DETAIL": return L10n.text("task.outputOptionalDetail", fallback: text.fallback)
        case "REFRESH_AUDIO_FOLDER": return L10n.text("task.refreshAudioFolder", fallback: text.fallback)
        case "RESTORE_AUDIO_FOLDER_ACCESS": return L10n.text("task.restoreAudioFolderAccess", fallback: text.fallback)
        case "REQUIRED": return L10n.text("task.required", fallback: text.fallback)
        case "OPTIONAL": return L10n.text("task.optional", fallback: text.fallback)
        case "INSTALLING": return L10n.text("task.installing", fallback: text.fallback)
        case "SCANNING": return L10n.text("task.scanning", fallback: text.fallback)
        case "NEEDS_ATTENTION": return L10n.text("task.needsAttention", fallback: text.fallback)
        case "NEW": return L10n.text("task.new", fallback: text.fallback)
        case "PROCESSING": return L10n.text("task.processing", fallback: text.fallback)
        case "PROCESSED": return L10n.text("task.processed", fallback: text.fallback)
        case "FAILED": return L10n.text("task.failed", fallback: text.fallback)
        case "NO_SPEECH": return L10n.text("task.noSpeech", fallback: text.fallback)
        case "CANCEL": return L10n.text("task.cancel", fallback: text.fallback)
        case "RETRY_DOWNLOAD": return L10n.text("task.retryDownload", fallback: text.fallback)
        case "DOWNLOAD": return L10n.text("task.download", fallback: text.fallback)
        case "CREATE_NEW": return L10n.text("task.createNew", fallback: text.fallback)
        case "CHOOSE_EXISTING": return L10n.text("task.chooseExisting", fallback: text.fallback)
        case "HIDE": return L10n.text("task.hide", fallback: text.fallback)
        case "SELECT_FOLDER": return L10n.text("task.selectFolder", fallback: text.fallback)
        case "RETRY": return L10n.text("task.retry", fallback: text.fallback)
        case "TRANSCRIBE": return L10n.text("task.transcribe", fallback: text.fallback)
        case "SHOW_TEXT": return L10n.text("task.showText", fallback: text.fallback)
        case "STOP": return L10n.text("task.stop", fallback: text.fallback)
        case "PLAY": return L10n.text("task.play", fallback: text.fallback)
        case "IMPORT_AUDIO_FILES": return L10n.text("task.importAudioFiles", fallback: text.fallback)
        case "SELECT_AUDIO_FOLDER": return L10n.text("task.selectAudioFolder", fallback: text.fallback)
        case "NO_NEW_TASKS": return L10n.text("task.noNewTasks", fallback: text.fallback)
        case "NO_PROCESSED_AUDIO": return L10n.text("task.noProcessedAudio", fallback: text.fallback)
        case "NO_TASKS": return L10n.text("task.noTasks", fallback: text.fallback)
        case "INSTALLING_MODEL": return L10n.text("task.installingModel", fallback: text.fallback)
        case "SCANNING_AUDIO_FOLDER": return L10n.text("task.scanningAudioFolder", fallback: text.fallback)
        case "PREPARING_SPEECH_MODEL": return L10n.text("task.preparingSpeechModel", fallback: text.fallback)
        case "CHOOSE_MODEL": return L10n.text("task.chooseModel", fallback: text.fallback)
        default: return text.fallback
        }
    }
}
