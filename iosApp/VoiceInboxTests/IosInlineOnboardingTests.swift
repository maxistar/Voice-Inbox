import Foundation
import Shared
import XCTest
@testable import VoiceInbox

final class IosInlineOnboardingTests: XCTestCase {
    func testLifecycleStoreDefaultsUnknownValuesToActiveAndPersistsTerminalStates() throws {
        let suiteName = "IosInlineOnboardingTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let store = IosOnboardingHintStore(defaults: defaults)

        XCTAssertEqual(store.load(), .active)
        defaults.set("future-value", forKey: IosOnboardingHintStore.lifecycleKey)
        XCTAssertEqual(store.load(), .active)

        store.save(.dismissed)
        XCTAssertEqual(store.load(), .dismissed)
        store.save(.completed)
        XCTAssertEqual(store.load(), .completed)
    }

    func testPresenterWaitsForHydrationAndHidesTerminalAndNonNewLifecycles() {
        XCTAssertFalse(present(hydration: .pending).visible)
        XCTAssertFalse(present(lifecycle: .dismissed).visible)
        XCTAssertFalse(present(lifecycle: .completed).visible)
        XCTAssertFalse(present(selection: .processed).visible)
        XCTAssertFalse(present(selection: .all).visible)
        XCTAssertTrue(present().visible)
    }

    func testPresenterUsesNetworkDownloadRetryAndLocalImportWithoutInventingActions() {
        var presentation = present(model: model(.required, downloadAvailable: true))
        XCTAssertEqual(presentation.action?.kind, .downloadModel)
        XCTAssertEqual(presentation.action?.label, "Start setup")
        XCTAssertNotNil(presentation.downloadDisclosure)

        presentation = present(model: model(.invalid, downloadAvailable: true))
        XCTAssertEqual(presentation.action?.kind, .retryModelDownload)
        XCTAssertEqual(presentation.action?.label, "Retry setup")
        XCTAssertNotNil(presentation.downloadDisclosure)

        presentation = present(model: model(.required, downloadAvailable: false))
        XCTAssertEqual(presentation.action?.kind, .importModel)
        XCTAssertEqual(presentation.action?.label, "Install model from folder")
        XCTAssertNil(presentation.downloadDisclosure)
    }

    func testPresenterKeepsInstallationDetailsInSetupTaskAndAdvancesThroughOutputAndFolder() throws {
        var presentation = present(model: model(.installing, downloadAvailable: false))
        XCTAssertEqual(presentation.action?.kind, .downloadModel)
        XCTAssertFalse(try XCTUnwrap(presentation.action).enabled)
        XCTAssertEqual(presentation.action?.label, "Installing speech model…")

        presentation = present(model: model(.ready), output: output(.required))
        XCTAssertEqual(presentation.action?.kind, .selectOutput)
        XCTAssertEqual(presentation.action?.label, "Select Output File")

        presentation = present(
            model: model(.ready),
            output: output(.ready),
            folder: folder(.unselected)
        )
        XCTAssertEqual(presentation.action?.kind, .selectFolder)
        XCTAssertTrue(try XCTUnwrap(presentation.steps.first { $0.kind == .folder }).optional)
        XCTAssertTrue(try XCTUnwrap(presentation.steps.first { $0.kind == .model }).complete)
        XCTAssertTrue(try XCTUnwrap(presentation.steps.first { $0.kind == .output }).complete)
    }

    func testConfiguredUserCompletesBeforeRenderingAndLaterInvalidationDoesNotReplayTerminalLifecycle() {
        let readyModel = model(.ready)
        let readyOutput = output(.ready)
        let readyFolder = folder(.ready)

        XCTAssertTrue(IosOnboardingHintPresenter.shouldComplete(
            lifecycle: .active,
            hydration: .known,
            model: readyModel,
            output: readyOutput,
            folder: readyFolder
        ))
        XCTAssertFalse(present(model: readyModel, output: readyOutput, folder: readyFolder).visible)
        XCTAssertFalse(present(
            lifecycle: .completed,
            model: model(.required),
            output: readyOutput,
            folder: readyFolder
        ).visible)
    }

    func testActionAuthorizerAcceptsOnlyCurrentVisibleEnabledStableAction() throws {
        let presentation = present(model: model(.required, downloadAvailable: true))
        let action = try XCTUnwrap(presentation.action)
        let current = IosOnboardingActionRequest(
            stableId: IosOnboardingHintPresentation.stableId,
            kind: action.kind
        )
        XCTAssertEqual(
            IosOnboardingActionAuthorizer.route(request: current, presentation: presentation),
            .modelDownload
        )

        XCTAssertNil(IosOnboardingActionAuthorizer.route(
            request: IosOnboardingActionRequest(stableId: "stale", kind: action.kind),
            presentation: presentation
        ))
        XCTAssertNil(IosOnboardingActionAuthorizer.route(
            request: IosOnboardingActionRequest(
                stableId: IosOnboardingHintPresentation.stableId,
                kind: .selectOutput
            ),
            presentation: presentation
        ))
        XCTAssertNil(IosOnboardingActionAuthorizer.route(
            request: current,
            presentation: .hidden
        ))

        let disabled = present(model: model(.installing, downloadAvailable: false))
        XCTAssertNil(IosOnboardingActionAuthorizer.route(
            request: IosOnboardingActionRequest(
                stableId: IosOnboardingHintPresentation.stableId,
                kind: .downloadModel
            ),
            presentation: disabled
        ))
    }

    @MainActor
    func testScreenOrdersHintAfterSetupAndBeforeAudio() {
        let file = IosImportedAudioFile(
            id: 7,
            displayName: "voice.m4a",
            localFileName: "voice.m4a",
            sizeBytes: 10,
            importedAt: Date(),
            status: .pending
        )
        let screen = makeScreen(
            files: [file],
            modelState: .missing,
            outputReady: false,
            folderSelected: false,
            actionsEnabled: false
        )

        XCTAssertEqual(
            screen.displayItemStableIds,
            ["setup:model", "setup:output", IosOnboardingHintPresentation.stableId, "audio:7"]
        )
    }

    @MainActor
    func testScreenOrdersHintBeforeBatchAudioAndEmptyAndOmitsItFromOtherFilters() {
        let file = IosImportedAudioFile(
            id: 9,
            displayName: "ready.m4a",
            localFileName: "ready.m4a",
            sizeBytes: 10,
            importedAt: Date(),
            status: .pending
        )
        var screen = makeScreen(
            files: [file],
            modelState: .installedVerified,
            outputReady: true,
            folderSelected: false,
            actionsEnabled: true
        )
        XCTAssertEqual(
            screen.displayItemStableIds,
            [IosOnboardingHintPresentation.stableId, "batch:transcribe-all", "audio:9"]
        )

        screen = makeScreen(
            modelState: .installedVerified,
            outputReady: true,
            folderSelected: false,
            actionsEnabled: true
        )
        XCTAssertEqual(
            screen.displayItemStableIds,
            [IosOnboardingHintPresentation.stableId, "empty:new"]
        )

        screen = makeScreen(
            selection: .all,
            modelState: .installedVerified,
            outputReady: true,
            folderSelected: false,
            actionsEnabled: true
        )
        XCTAssertEqual(screen.displayItemStableIds, ["empty:all"])

        screen = makeScreen(
            modelState: .installedVerified,
            outputReady: true,
            folderSelected: false,
            actionsEnabled: true,
            lifecycle: .dismissed
        )
        XCTAssertEqual(screen.displayItemStableIds, ["empty:new"])
    }

    func testChecklistAccessibilityDoesNotRelyOnVisualSymbols() throws {
        let presentation = present(
            model: model(.ready),
            output: output(.required),
            folder: folder(.unselected)
        )
        XCTAssertEqual(
            try XCTUnwrap(presentation.steps.first { $0.kind == .model }).accessibilityLabel,
            "Completed: Install speech model"
        )
        XCTAssertEqual(
            try XCTUnwrap(presentation.steps.first { $0.kind == .folder }).accessibilityLabel,
            "Not completed: Select audio folder, optional"
        )
    }

    private func present(
        lifecycle: IosOnboardingHintLifecycle = .active,
        selection: IosShellCatalogSelection = .new,
        hydration: IosSetupHydration = .known,
        model: ModelSetupSnapshot? = nil,
        output: OutputSetupSnapshot? = nil,
        folder: FolderSetupSnapshot? = nil
    ) -> IosOnboardingHintPresentation {
        IosOnboardingHintPresenter.present(
            lifecycle: lifecycle,
            selection: selection,
            hydration: hydration,
            model: model ?? self.model(.required, downloadAvailable: true),
            output: output ?? self.output(.required),
            folder: folder ?? self.folder(.unselected)
        )
    }

    private func model(
        _ state: ModelSetupSnapshotState,
        downloadAvailable: Bool = false
    ) -> ModelSetupSnapshot {
        ModelSetupSnapshot(
            state: state,
            detail: nil,
            installationPhase: nil,
            progressPercent: nil,
            downloadAvailable: downloadAvailable,
            canCancel: false,
            selectedModel: nil,
            downloadChoices: []
        )
    }

    private func output(_ state: OutputSetupSnapshotState) -> OutputSetupSnapshot {
        OutputSetupSnapshot(state: state, detail: nil)
    }

    private func folder(_ state: FolderSetupSnapshotState) -> FolderSetupSnapshot {
        FolderSetupSnapshot(state: state, detail: nil)
    }

    @MainActor
    private func makeScreen(
        selection: IosShellCatalogSelection = .new,
        files: [IosImportedAudioFile] = [],
        modelState: IosSpeechModelInstallationState,
        outputReady: Bool,
        folderSelected: Bool,
        actionsEnabled: Bool,
        lifecycle: IosOnboardingHintLifecycle = .active
    ) -> IosTaskListScreen {
        IosMainScreenShellState().screen(
            selection: selection,
            importedFiles: files,
            modelStatus: IosSpeechModelStatus(
                directory: FileManager.default.temporaryDirectory,
                installationState: modelState,
                missingFiles: modelState == .missing ? ["model.onnx"] : []
            ),
            modelMessage: nil,
            modelInstalling: false,
            modelInstallationPhase: nil,
            modelDownloadAvailable: modelState != .installedVerified,
            modelDownloadProgress: nil,
            modelCanCancel: false,
            outputStatus: IosOutputDocumentStatus(
                displayName: outputReady ? "notes.md" : nil,
                message: outputReady ? "Ready" : "Choose output",
                ready: outputReady
            ),
            folderStatus: IosInboxFolderStatus(
                displayName: folderSelected ? "Inbox" : nil,
                message: nil,
                needsSelection: !folderSelected
            ),
            folderScanning: false,
            activePreviewEntryId: nil,
            previewState: .idle,
            transcription: .idle,
            preparationOwnerEntryId: nil,
            prerequisiteError: nil,
            actionsEnabled: actionsEnabled,
            onboardingLifecycle: lifecycle,
            setupHydration: .known
        )
    }
}
