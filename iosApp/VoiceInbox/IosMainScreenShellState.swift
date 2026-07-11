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
    let emptyStatePreview: MainScreenListState
}

struct IosShellAudioRow: Identifiable {
    let id: Int64
    let title: String
    let subtitle: String
    let badge: String
    let imported: Bool
    let state: MainScreenRowState
}

final class IosMainScreenShellState {
    func screen(selection: IosShellCatalogSelection, importedFiles: [IosImportedAudioFile]) -> IosShellMainScreen {
        let rowsForSelection = displayRows(for: selection, importedFiles: importedFiles)
        let rows = rowsForSelection.map { $0.input }
        let state = MainScreenStateController.shared.state(
            input: input(
                selectedTab: selection.sharedTab,
                pendingCount: importedFiles.count,
                displayedRowCount: Int32(rows.count),
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
                state: rowState
            )
        }
        let emptyPreview = MainScreenStateController.shared.state(
            input: input(
                selectedTab: MainScreenCatalogTab.theNew,
                pendingCount: 0,
                displayedRowCount: 0,
                rows: []
            )
        ).list
        return IosShellMainScreen(
            state: state,
            rows: displayRows,
            emptyStatePreview: emptyPreview
        )
    }

    private var processedSamples: [DisplayRow] {
        [
            DisplayRow(
                input: MainScreenRowInput(entryId: 201, state: AudioFileState.processed, hasTranscriptText: true),
                title: "daily-idea.m4a",
                subtitle: "Sample processed row • Transcript available",
                badge: "Sample",
                imported: false
            ),
        ]
    }

    private func displayRows(for selection: IosShellCatalogSelection, importedFiles: [IosImportedAudioFile]) -> [DisplayRow] {
        switch selection {
        case .new:
            importedFiles.map { file in
                DisplayRow(
                    input: MainScreenRowInput(entryId: file.id, state: AudioFileState.pending, hasTranscriptText: false),
                    title: file.displayName,
                    subtitle: "Imported • \(file.formattedSize)",
                    badge: "New",
                    imported: true
                )
            }
        case .processed:
            processedSamples
        }
    }

    private func input(
        selectedTab: MainScreenCatalogTab,
        pendingCount: Int,
        displayedRowCount: Int32,
        rows: [MainScreenRowInput]
    ) -> MainScreenInput {
        MainScreenInput(
            modelMessage: "iOS transcription is future work",
            modelLoading: false,
            modelDownloadAvailable: false,
            modelDownloadProgress: nil,
            modelReady: false,
            outputSelected: true,
            folderSelected: true,
            pendingCount: Int32(pendingCount),
            folderChecking: false,
            folderScanQueued: false,
            scanning: false,
            scanMessage: "iOS imported audio prototype",
            transcriptionState: TranscriptionObservationState.idle,
            transcriptionPhase: nil,
            transcriptionFilename: nil,
            transcriptionIndeterminate: false,
            transcriptionProgress: 0,
            processedUs: 0,
            durationUs: 0,
            completedFiles: 0,
            totalFiles: 0,
            failedFiles: 0,
            errorMessage: nil,
            selectedTab: selectedTab,
            displayedRowCount: displayedRowCount,
            activePreviewEntryId: nil,
            previewState: PreviewPlaybackState.idle,
            rows: rows
        )
    }

    private struct DisplayRow {
        let input: MainScreenRowInput
        let title: String
        let subtitle: String
        let badge: String
        let imported: Bool
    }
}
