import Foundation
import Shared

enum IosShellCatalogSelection: String, CaseIterable, Identifiable {
    case new
    case processed

    var id: String { rawValue }

    var title: String {
        switch self {
        case .new:
            "New"
        case .processed:
            "Processed"
        }
    }

    var sharedTab: MainScreenCatalogTab {
        switch self {
        case .new:
            MainScreenCatalogTab.theNew
        case .processed:
            MainScreenCatalogTab.processed
        }
    }
}

struct IosShellMainScreen {
    let state: MainScreenState
    let rows: [IosShellAudioRow]
}

struct IosShellAudioRow: Identifiable {
    let id: Int64
    let title: String
    let subtitle: String
    let badge: String
    let imported: Bool
    let status: IosImportedAudioStatus?
    let transcriptText: String?
    let lastError: String?
    let state: MainScreenRowState
}

final class IosMainScreenShellState {
    func screen(
        selection: IosShellCatalogSelection,
        importedFiles: [IosImportedAudioFile],
        runtimeReady: Bool,
        modelReady: Bool,
        modelInstalling: Bool,
        modelDownloadAvailable: Bool,
        modelDownloadProgress: Int?,
        modelMessage: String,
        outputReady: Bool,
        activePreviewEntryId: Int64?,
        previewState: PreviewPlaybackState,
        transcription: IosSingleFileTranscriptionState? = nil
    ) -> IosShellMainScreen {
        let rowsForSelection = displayRows(for: selection, importedFiles: importedFiles)
        let rows = rowsForSelection.map { $0.input }
        let pendingCount = importedFiles.count { $0.status == .pending }
        let state = MainScreenStateController.shared.state(
            input: input(
                selectedTab: selection.sharedTab,
                pendingCount: pendingCount,
                displayedRowCount: Int32(rows.count),
                runtimeReady: runtimeReady,
                modelReady: modelReady,
                modelInstalling: modelInstalling,
                modelDownloadAvailable: modelDownloadAvailable,
                modelDownloadProgress: modelDownloadProgress,
                modelMessage: modelMessage,
                outputReady: outputReady,
                activePreviewEntryId: activePreviewEntryId,
                previewState: previewState,
                transcription: transcription,
                rows: rows
            )
        )
        let sharedRowsById = Dictionary(uniqueKeysWithValues: state.rows.map { ($0.entryId, $0) })
        let displayRows = rowsForSelection.compactMap { row -> IosShellAudioRow? in
            guard let rowState = sharedRowsById[row.input.entryId] else { return nil }
            return IosShellAudioRow(
                id: row.input.entryId,
                title: row.title,
                subtitle: row.subtitle,
                badge: row.badge,
                imported: row.imported,
                status: row.status,
                transcriptText: row.transcriptText,
                lastError: row.lastError,
                state: rowState
            )
        }
        return IosShellMainScreen(
            state: state,
            rows: displayRows
        )
    }

    private func displayRows(for selection: IosShellCatalogSelection, importedFiles: [IosImportedAudioFile]) -> [DisplayRow] {
        switch selection {
        case .new:
            importedFiles.filter { $0.status == .pending || $0.status == .processing }.map { file in
                DisplayRow(
                    input: MainScreenRowInput(
                        entryId: file.id,
                        state: file.status.sharedState,
                        hasTranscriptText: file.transcriptText?.isEmpty == false
                    ),
                    title: file.displayName,
                    subtitle: subtitle(for: file),
                    badge: file.status.badge,
                    imported: true,
                    status: file.status,
                    transcriptText: file.transcriptText,
                    lastError: file.lastError
                )
            }
        case .processed:
            importedFiles.filter { $0.status == .processed || $0.status == .failed }.map { file in
                DisplayRow(
                    input: MainScreenRowInput(
                        entryId: file.id,
                        state: file.status.sharedState,
                        hasTranscriptText: file.transcriptText?.isEmpty == false
                    ),
                    title: file.displayName,
                    subtitle: subtitle(for: file),
                    badge: file.status.badge,
                    imported: true,
                    status: file.status,
                    transcriptText: file.transcriptText,
                    lastError: file.lastError
                )
            }
        }
    }

    private func subtitle(for file: IosImportedAudioFile) -> String {
        var parts = ["Imported", file.formattedSize]
        if let lastError = file.lastError, !lastError.isEmpty {
            parts.append(lastError)
        } else if let durationUs = file.durationUs, durationUs > 0 {
            parts.append(formatDuration(microseconds: durationUs))
        }
        return parts.joined(separator: " • ")
    }

    private func formatDuration(microseconds: Int64) -> String {
        let totalSeconds = microseconds / 1_000_000
        let minutes = totalSeconds / 60
        let seconds = String(format: "%02d", totalSeconds % 60)
        return "\(minutes):\(seconds)"
    }

    private func input(
        selectedTab: MainScreenCatalogTab,
        pendingCount: Int,
        displayedRowCount: Int32,
        runtimeReady: Bool,
        modelReady: Bool,
        modelInstalling: Bool,
        modelDownloadAvailable: Bool,
        modelDownloadProgress: Int?,
        modelMessage: String,
        outputReady: Bool,
        activePreviewEntryId: Int64?,
        previewState: PreviewPlaybackState,
        transcription: IosSingleFileTranscriptionState?,
        rows: [MainScreenRowInput]
    ) -> MainScreenInput {
        let active = transcription?.active == true
        let processedUs = transcription?.processedUs ?? 0
        let durationUs = transcription?.durationUs ?? 0
        let installationState: SpeechModelInstallationState = modelInstalling
            ? .installing
            : (modelReady && runtimeReady ? .installed : .notInstalled)
        let runtimeState: SpeechModelRuntimeState = transcription?.active == true &&
            transcription?.phase?.localizedCaseInsensitiveContains("model") == true
            ? .loading
            : .unloaded
        return MainScreenInput(
            modelMessage: modelMessage,
            modelInstallationState: installationState,
            modelRuntimeState: runtimeState,
            modelDownloadAvailable: modelDownloadAvailable,
            modelDownloadProgress: modelDownloadProgress.map { KotlinInt(int: Int32($0)) },

            outputSelected: outputReady,
            folderSelected: true,
            pendingCount: Int32(pendingCount),
            folderChecking: false,
            folderScanQueued: false,
            scanning: false,
            scanMessage: nil,
            transcriptionState: active ? TranscriptionObservationState.active : TranscriptionObservationState.idle,
            transcriptionPhase: transcription?.phase,
            transcriptionFilename: transcription?.fileName,
            transcriptionIndeterminate: active && transcription?.progressPercent == nil,
            transcriptionProgress: Int32(transcription?.progressPercent ?? 0),
            processedUs: processedUs,
            durationUs: durationUs,
            completedFiles: transcription?.completedFiles ?? 0,
            totalFiles: transcription?.totalFiles ?? 0,
            failedFiles: transcription?.failedFiles ?? 0,
            errorMessage: nil,
            selectedTab: selectedTab,
            displayedRowCount: displayedRowCount,
            activePreviewEntryId: activePreviewEntryId.map { KotlinLong(longLong: $0) },
            previewState: previewState,
            rows: rows
        )
    }

    private struct DisplayRow {
        let input: MainScreenRowInput
        let title: String
        let subtitle: String
        let badge: String
        let imported: Bool
        let status: IosImportedAudioStatus?
        let transcriptText: String?
        let lastError: String?
    }
}
