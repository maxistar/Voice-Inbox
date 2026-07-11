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
    let state: MainScreenRowState
}

final class IosMainScreenShellState {
    func screen(selection: IosShellCatalogSelection) -> IosShellMainScreen {
        let samples = sampleRows(for: selection)
        let rows = samples.map { $0.input }
        let state = MainScreenStateController.shared.state(
            input: input(
                selectedTab: selection.sharedTab,
                pendingCount: newSamples.count,
                displayedRowCount: Int32(rows.count),
                rows: rows
            )
        )
        let sharedRowsById = Dictionary(uniqueKeysWithValues: state.rows.map { ($0.entryId, $0) })
        let displayRows = samples.compactMap { sample -> IosShellAudioRow? in
            guard let rowState = sharedRowsById[sample.input.entryId] else { return nil }
            return IosShellAudioRow(
                id: sample.input.entryId,
                title: sample.title,
                subtitle: sample.subtitle,
                badge: sample.badge,
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

    private var newSamples: [SampleRow] {
        [
            SampleRow(
                input: MainScreenRowInput(entryId: 101, state: AudioFileState.pending, hasTranscriptText: false),
                title: "meeting-note.m4a",
                subtitle: "Pending • 1.8 MB",
                badge: "New"
            ),
            SampleRow(
                input: MainScreenRowInput(entryId: 102, state: AudioFileState.failed, hasTranscriptText: false),
                title: "noisy-capture.wav",
                subtitle: "Failed • Retry available",
                badge: "Failed"
            ),
        ]
    }

    private var processedSamples: [SampleRow] {
        [
            SampleRow(
                input: MainScreenRowInput(entryId: 201, state: AudioFileState.processed, hasTranscriptText: true),
                title: "daily-idea.m4a",
                subtitle: "Processed • Transcript available",
                badge: "Processed"
            ),
        ]
    }

    private func sampleRows(for selection: IosShellCatalogSelection) -> [SampleRow] {
        switch selection {
        case .new:
            newSamples
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
            modelMessage: "Model ready",
            modelLoading: false,
            modelDownloadAvailable: false,
            modelDownloadProgress: nil,
            modelReady: true,
            outputSelected: true,
            folderSelected: true,
            pendingCount: Int32(pendingCount),
            folderChecking: false,
            folderScanQueued: false,
            scanning: false,
            scanMessage: "iOS shell sample catalog",
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

    private struct SampleRow {
        let input: MainScreenRowInput
        let title: String
        let subtitle: String
        let badge: String
    }
}
