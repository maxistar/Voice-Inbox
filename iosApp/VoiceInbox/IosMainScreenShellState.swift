import Foundation
import Shared

enum IosShellCatalogSelection: String, CaseIterable, Identifiable {
    case new
    case processed
    case all

    var id: String { rawValue }

    var title: String {
        switch self {
        case .new: "New"
        case .processed: "Processed"
        case .all: "All"
        }
    }

    var sharedFilter: TaskListFilter {
        switch self {
        case .new: .theNew
        case .processed: .processed
        case .all: .all
        }
    }
}

struct IosTaskListScreen {
    let state: TaskListState
    let filesById: [Int64: IosImportedAudioFile]
}

enum IosTaskActionRoute: Equatable {
    case modelDownload
    case modelImport
    case modelCancel
    case outputSelection
    case folderSelection
    case folderRefresh
    case transcribe
    case retry
    case play
    case stop
    case showText
    case audioImport
}

enum IosTaskActionRouter {
    static func route(_ kind: TaskActionKind) -> IosTaskActionRoute? {
        switch kind {
        case .downloadModel, .retryModelDownload: .modelDownload
        case .importModel: .modelImport
        case .cancelModelDownload: .modelCancel
        case .selectOutput: .outputSelection
        case .selectFolder: .folderSelection
        case .refreshFolder: .folderRefresh
        case .transcribe: .transcribe
        case .retryTranscription: .retry
        case .play: .play
        case .stop: .stop
        case .showText: .showText
        case .importAudio: .audioImport
        default: nil
        }
    }
}

final class IosMainScreenShellState {
    func screen(
        selection: IosShellCatalogSelection,
        importedFiles: [IosImportedAudioFile],
        modelStatus: IosSpeechModelStatus,
        modelMessage: String?,
        modelInstalling: Bool,
        modelDownloadAvailable: Bool,
        modelDownloadProgress: Int?,
        modelCanCancel: Bool,
        outputStatus: IosOutputDocumentStatus,
        folderStatus: IosInboxFolderStatus,
        folderScanning: Bool,
        activePreviewEntryId: Int64?,
        previewState: PreviewPlaybackState,
        transcription: IosSingleFileTranscriptionState,
        preparationOwnerEntryId: Int64?,
        prerequisiteError: String?,
        actionsEnabled: Bool
    ) -> IosTaskListScreen {
        let input = TaskListInput(
            filter: selection.sharedFilter,
            model: modelSnapshot(
                status: modelStatus,
                message: modelMessage,
                installing: modelInstalling,
                downloadAvailable: modelDownloadAvailable,
                progress: modelDownloadProgress,
                canCancel: modelCanCancel
            ),
            output: outputSnapshot(outputStatus),
            folder: folderSnapshot(folderStatus, scanning: folderScanning),
            audio: importedFiles.map { file in
                AudioTaskSnapshot(
                    entryId: file.id,
                    title: file.displayName,
                    detail: subtitle(for: file),
                    state: file.status.sharedState,
                    importedAtMillis: Int64(file.importedAt.timeIntervalSince1970 * 1000),
                    terminalAtMillis: file.processedAt.map {
                        KotlinLong(longLong: Int64($0.timeIntervalSince1970 * 1000))
                    },
                    lastError: file.lastError,
                    hasTranscriptText: file.transcriptText?.isEmpty == false,
                    noSpeech: Self.isNoSpeech(file.lastError),
                    eligibleForTranscription: actionsEnabled
                )
            },
            preview: PreviewTaskSnapshot(
                activeEntryId: activePreviewEntryId.map(KotlinLong.init(longLong:)),
                state: previewState
            ),
            transcription: TranscriptionTaskSnapshot(
                active: transcription.active,
                activeEntryId: transcription.fileId.map(KotlinLong.init(longLong:)),
                preparationOwnerEntryId: preparationOwnerEntryId.map(KotlinLong.init(longLong:)),
                phase: transcription.phase,
                percent: transcription.progressPercent.map { KotlinInt(int: Int32($0)) },
                processedUs: transcription.processedUs > 0 ? KotlinLong(longLong: transcription.processedUs) : nil,
                durationUs: transcription.durationUs > 0 ? KotlinLong(longLong: transcription.durationUs) : nil,
                completedFiles: transcription.totalFiles > 0 ? KotlinInt(int: transcription.completedFiles) : nil,
                totalFiles: transcription.totalFiles > 0 ? KotlinInt(int: transcription.totalFiles) : nil,
                failedFiles: transcription.failedFiles > 0 ? KotlinInt(int: transcription.failedFiles) : nil,
                prerequisiteError: prerequisiteError
            )
        )
        return IosTaskListScreen(
            state: TaskListPresentationController.shared.state(input: input),
            filesById: Dictionary(uniqueKeysWithValues: importedFiles.map { ($0.id, $0) })
        )
    }

    private func modelSnapshot(
        status: IosSpeechModelStatus,
        message: String?,
        installing: Bool,
        downloadAvailable: Bool,
        progress: Int?,
        canCancel: Bool
    ) -> ModelSetupSnapshot {
        let state: ModelSetupSnapshotState
        if installing {
            state = .installing
        } else if status.isReady {
            state = .ready
        } else if status.installationState == .invalid {
            state = .invalid
        } else {
            state = .required
        }
        return ModelSetupSnapshot(
            state: state,
            detail: status.detail ?? message,
            progressPercent: progress.map { KotlinInt(int: Int32($0)) },
            downloadAvailable: downloadAvailable,
            canCancel: canCancel
        )
    }

    private func outputSnapshot(_ status: IosOutputDocumentStatus) -> OutputSetupSnapshot {
        let state: OutputSetupSnapshotState = status.ready
            ? .ready
            : (status.displayName == nil ? .required : .invalid)
        return OutputSetupSnapshot(state: state, detail: status.message)
    }

    private func folderSnapshot(
        _ status: IosInboxFolderStatus,
        scanning: Bool
    ) -> FolderSetupSnapshot {
        let state: FolderSetupSnapshotState
        if scanning {
            state = .scanning
        } else if status.hasError {
            state = .error
        } else if status.needsSelection, status.displayName != nil {
            state = .error
        } else if status.needsSelection {
            state = .unselected
        } else {
            state = .ready
        }
        return FolderSetupSnapshot(state: state, detail: status.message)
    }

    private func subtitle(for file: IosImportedAudioFile) -> String {
        var parts = ["Imported", file.formattedSize]
        if let durationUs = file.durationUs, durationUs > 0 {
            let totalSeconds = durationUs / 1_000_000
            parts.append("\(totalSeconds / 60):\(String(format: "%02d", totalSeconds % 60))")
        }
        return parts.joined(separator: " • ")
    }

    static func isNoSpeech(_ message: String?) -> Bool {
        guard let message else { return false }
        return message.localizedCaseInsensitiveContains("no text") ||
            message.localizedCaseInsensitiveContains("no speech")
    }
}
