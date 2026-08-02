import Foundation
import Shared
import SwiftUI

struct ContentView: View {
    private let shellState = IosMainScreenShellState()
    private let onboardingStore: IosOnboardingHintStore

    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var importStore = IosAudioImportStore()
    @StateObject private var outputStore = IosOutputDocumentStore()
    @StateObject private var previewPlayer = IosAudioPreviewPlayer()
    @StateObject private var speechModelStore = IosSpeechModelStore()
    @StateObject private var transcriber = IosSingleFileTranscriptionController()
    @StateObject private var startupPolicyStore = IosStartupProcessingPolicyStore()
    @State private var selectedTab = IosShellCatalogSelection.new
    @State private var presentedPicker: IosPresentedPicker?
    @State private var shownTranscript: IosDisplayedTranscript?
    @State private var startupProcessingChecked = false
    @State private var startupFolderRefreshChecked = false
    @State private var startupProcessingPrompt: IosStartupProcessingPrompt?
    @State private var onboardingLifecycle: IosOnboardingHintLifecycle
    @State private var setupHydration = IosSetupHydration.pending

    init(onboardingDefaults: UserDefaults = .standard) {
        let store = IosOnboardingHintStore(defaults: onboardingDefaults)
        onboardingStore = store
        _onboardingLifecycle = State(initialValue: store.load())
    }

    var body: some View {
        let screen = currentScreen()

        NavigationStack {
            List {
                Section {
                    Picker("Catalog", selection: $selectedTab) {
                        ForEach(IosShellCatalogSelection.allCases) { tab in
                            Text(tab.title).tag(tab)
                        }
                    }
                    .pickerStyle(.segmented)
                    .accessibilityIdentifier("task-list-filter")
                }

                if let candidate = speechModelStore.pendingCandidate {
                    Section("Detected Speech Model") {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(candidate.descriptor.displayName)
                                .font(.headline)
                            Text(
                                "\(candidate.descriptor.languageSummary) · " +
                                "\(candidate.descriptor.maturity) · " +
                                ByteCountFormatter.string(
                                    fromByteCount: candidate.descriptor.totalSizeBytes,
                                    countStyle: .file
                                )
                            )
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            Text("Installing this model replaces the currently installed model.")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }

                        HStack {
                            Button("Install") {
                                speechModelStore.confirmPendingInstallation(
                                    candidate: candidate,
                                    replacementAllowed: !transcriber.isActive
                                )
                            }
                            .buttonStyle(.borderedProminent)
                            .disabled(speechModelStore.isBusy || transcriber.isActive)

                            Button("Cancel", role: .cancel) {
                                speechModelStore.cancelPendingInstallation()
                            }
                            .buttonStyle(.bordered)
                        }
                    }
                }


                if !speechModelStore.isReady && !speechModelStore.isBusy {
                    Section("Download Speech Model") {
                        Picker("Model", selection: $speechModelStore.selectedDownloadModel) {
                            ForEach(speechModelStore.availableModels.filter(\.networkDownloadAvailable)) { model in
                                Text(model.displayName).tag(model)
                            }
                        }
                        VStack(alignment: .leading, spacing: 4) {
                            let model = speechModelStore.selectedDownloadModel
                            Text(model.languageSummary).font(.subheadline)
                            Text("\(model.maturity) · \(ByteCountFormatter.string(fromByteCount: model.totalSizeBytes, countStyle: .file)) download · \(ByteCountFormatter.string(fromByteCount: model.totalSizeBytes + model.safetyMarginBytes, countStyle: .file)) free")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            if speechModelStore.activeDescriptor != nil {
                                Text("Downloading this model replaces the currently installed model.")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }

                Section {
                    ForEach(screen.state.tasks.filter { $0 is SetupTaskPresentation }, id: \.stableId) { task in
                        TaskListRow(task: task) { action in
                            perform(action: action, task: task, screen: screen)
                        }
                        .id(task.stableId)
                        .accessibilityIdentifier("task-row-\(task.stableId)")
                    }

                    if screen.onboardingHint.visible {
                        IosInlineOnboardingRow(
                            presentation: screen.onboardingHint,
                            onDismiss: dismissOnboarding,
                            onAction: { action in
                                performOnboarding(action: action)
                            }
                        )
                        .id(IosOnboardingHintPresentation.stableId)
                    }

                    if screen.state.batchAction.visible {
                        Button {
                            transcribeAll()
                        } label: {
                            Label(
                                "Transcribe All (\(screen.state.batchAction.eligibleCount))",
                                systemImage: "text.badge.checkmark"
                            )
                        }
                        .disabled(!screen.state.batchAction.enabled)
                        .accessibilityIdentifier("transcribe-all")
                    }

                    ForEach(screen.state.tasks.filter { $0 is AudioTaskPresentation }, id: \.stableId) { task in
                        TaskListRow(task: task) { action in
                            perform(action: action, task: task, screen: screen)
                        }
                        .id(task.stableId)
                        .accessibilityIdentifier("task-row-\(task.stableId)")
                    }

                    if let emptyMessage = screen.state.emptyMessage {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(emptyMessage)
                                .foregroundStyle(.secondary)
                            ForEach(Array(screen.state.emptyActions.enumerated()), id: \.offset) { _, action in
                                Button(action.label) {
                                    perform(action: action, task: nil, screen: screen)
                                }
                                .buttonStyle(.borderless)
                                .disabled(!action.enabled)
                            }
                        }
                    }
                }

                Section {
                    Button {
                        presentPicker(.audioFiles)
                    } label: {
                        Label("Import Audio Files", systemImage: "square.and.arrow.down")
                    }
                    if !importStore.inboxFolderStatus.needsSelection {
                        Button {
                            importStore.refreshInboxFolder()
                            selectedTab = .new
                        } label: {
                            Label("Refresh Audio Folder", systemImage: "arrow.clockwise")
                        }
                        .disabled(importStore.isScanningFolder)
                    }
                }
            }
            .navigationTitle("Voice Inbox")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    NavigationLink {
                        SettingsView(
                            importStore: importStore,
                            outputStore: outputStore,
                            startupPolicyStore: startupPolicyStore,
                            speechModelStore: speechModelStore,
                            selectInboxFolder: {
                                presentPicker(.audioFolder)
                            },
                            selectOutputFile: {
                                presentPicker(.outputFile)
                            },
                            installModelPackage: {
                                guard !transcriber.isActive else {
                                    speechModelStore.message = "Wait for transcription to finish before replacing the speech model."
                                    return
                                }
                                presentPicker(.speechModelFolder)
                            }
                        )
                    } label: {
                        Image(systemName: "gearshape")
                    }
                    .accessibilityLabel("Settings")
                }
            }
            .sheet(item: $presentedPicker) { picker in
                switch picker {
                case .audioFiles:
                    IosAudioDocumentPicker { urls in
                        importStore.importFiles(from: urls)
                        presentedPicker = nil
                        selectedTab = .new
                    }
                case .audioFolder:
                    IosSpeechModelDirectoryPicker { url in
                        importStore.selectInboxFolder(url)
                        presentedPicker = nil
                        selectedTab = .new
                    }
                case .outputFile:
                    IosOutputDocumentPicker { url in
                        outputStore.selectOutputFile(url)
                        presentedPicker = nil
                    }
                case .speechModelFolder:
                    IosSpeechModelDirectoryPicker { url in
                        speechModelStore.inspectModelPackage(from: url)
                        presentedPicker = nil
                    }
                }
            }
            .onChange(of: importStore.files) { files in
                previewPlayer.stopIfUnavailable(availableFileIds: Set(files.map(\.id)))
            }
            .onAppear {
                refreshStartupSources()
                setupHydration = .known
                completeOnboardingIfNeeded()
                evaluateStartupProcessingIfNeeded()
            }
            .onChange(of: screen.onboardingShouldComplete) { shouldComplete in
                guard shouldComplete else { return }
                completeOnboardingIfNeeded()
            }
            .onChange(of: scenePhase) { phase in
                guard phase == .active else { return }
                refreshStartupSources()
                completeOnboardingIfNeeded()
                evaluateStartupProcessingIfNeeded()
            }
            .sheet(item: $shownTranscript) { transcript in
                IosTranscriptViewer(transcript: transcript) {
                    shownTranscript = nil
                }
            }
            .sheet(item: $startupProcessingPrompt) { prompt in
                StartupProcessingPromptView(
                    pendingCount: prompt.pendingCount,
                    onYes: { alwaysAtStartup in
                        startupProcessingPrompt = nil
                        if alwaysAtStartup {
                            startupPolicyStore.policy = .yes
                        }
                        startStartupProcessing()
                    },
                    onNo: { alwaysAtStartup in
                        startupProcessingPrompt = nil
                        if alwaysAtStartup {
                            startupPolicyStore.policy = .no
                        }
                    }
                )
            }
            .alert(item: globalMessageBinding) { message in
                Alert(
                    title: Text(message.title),
                    message: Text(message.text),
                    dismissButton: .default(Text("OK"))
                )
            }
        }
    }

    private func currentScreen() -> IosTaskListScreen {
        let speechModelReady = speechModelStore.isReady
        let outputReady = outputStore.isReady
        let transcriptionReady = transcriber.backendConfigured &&
            speechModelReady &&
            !speechModelStore.isBusy &&
            outputReady
        return shellState.screen(
            selection: selectedTab,
            importedFiles: importStore.files,
            modelStatus: speechModelStore.status,
            modelMessage: speechModelStore.message,
            modelInstalling: speechModelStore.isInstalling,
            modelInstallationPhase: speechModelStore.downloadProgress?.message ?? speechModelStore.message,
            modelDownloadAvailable: !speechModelReady && !speechModelStore.isBusy,
            modelDownloadProgress: speechModelStore.downloadProgress?.percent,
            modelCanCancel: speechModelStore.canCancelDownload,
            outputStatus: outputStore.status,
            folderStatus: importStore.inboxFolderStatus,
            folderScanning: importStore.isScanningFolder,
            activePreviewEntryId: previewPlayer.playingFileId,
            previewState: previewPlayer.playingFileId == nil ? .idle : .playing,
            transcription: transcriber.state,
            preparationOwnerEntryId: transcriber.preparationOwnerFileId,
            prerequisiteError: transcriber.prerequisiteError,
            actionsEnabled: transcriptionReady && !transcriber.isActive && !importStore.isScanningFolder,
            onboardingLifecycle: onboardingLifecycle,
            setupHydration: setupHydration
        )
    }

    private var globalMessageBinding: Binding<IosGlobalMessage?> {
        Binding(
            get: {
                if let error = previewPlayer.errorMessage {
                    return IosGlobalMessage(title: "Playback", text: error)
                }
                if let message = importStore.importMessage {
                    return IosGlobalMessage(title: "Voice Inbox", text: message)
                }
                if let message = speechModelStore.actionableErrorMessage {
                    return IosGlobalMessage(title: "Speech Model", text: message)
                }
                return nil
            },
            set: { value in
                guard value == nil else { return }
                previewPlayer.clearError()
                importStore.importMessage = nil
                speechModelStore.clearActionableError()
            }
        )
    }

    private func dismissOnboarding() {
        guard onboardingLifecycle == .active else { return }
        onboardingLifecycle = .dismissed
        onboardingStore.save(.dismissed)
    }

    private func completeOnboardingIfNeeded() {
        guard onboardingLifecycle == .active else { return }
        let screen = currentScreen()
        guard screen.onboardingShouldComplete else { return }
        onboardingLifecycle = .completed
        onboardingStore.save(.completed)
    }

    private func performOnboarding(action: IosOnboardingHintAction) {
        let current = currentScreen()
        let request = IosOnboardingActionRequest(
            stableId: IosOnboardingHintPresentation.stableId,
            kind: action.kind
        )
        guard let route = IosOnboardingActionAuthorizer.route(
            request: request,
            presentation: current.onboardingHint
        ) else { return }

        switch route {
        case .modelDownload:
            speechModelStore.downloadModel(speechModelStore.selectedDownloadModel)
        case .modelImport:
            guard !transcriber.isActive else {
                speechModelStore.message = "Wait for transcription to finish before replacing the speech model."
                return
            }
            presentPicker(.speechModelFolder)
        case .outputSelection:
            presentPicker(.outputFile)
        case .folderSelection:
            presentPicker(.audioFolder)
        default:
            break
        }
    }

    private func perform(
        action: TaskActionPresentation,
        task: TaskPresentation?,
        screen: IosTaskListScreen
    ) {
        guard action.enabled, let route = IosTaskActionRouter.route(action.kind) else { return }
        switch route {
        case .modelDownload:
            speechModelStore.downloadModel(speechModelStore.selectedDownloadModel)
        case .modelImport:
            guard !transcriber.isActive else {
                speechModelStore.message = "Wait for transcription to finish before replacing the speech model."
                return
            }
            presentPicker(.speechModelFolder)
        case .modelCancel:
            speechModelStore.cancelDownload()
        case .outputSelection:
            presentPicker(.outputFile)
        case .folderSelection:
            presentPicker(.audioFolder)
        case .folderRefresh:
            importStore.refreshInboxFolder()
            selectedTab = .new
        case .audioImport:
            presentPicker(.audioFiles)
        case .transcribe, .retry, .play, .stop, .showText:
            guard let audioTask = task as? AudioTaskPresentation,
                  let file = screen.filesById[audioTask.entryId] else { return }
            performAudio(action: action.kind, file: file)
        }
    }

    private func presentPicker(_ picker: IosPresentedPicker) {
        guard presentedPicker == nil else { return }
        presentedPicker = picker
    }

    private func performAudio(action: TaskActionKind, file: IosImportedAudioFile) {
        switch action {
        case .play:
            previewPlayer.toggle(fileId: file.id, url: importStore.localURL(for: file))
        case .stop:
            previewPlayer.stop()
        case .showText:
            shownTranscript = IosTranscriptReview.presentation(
                entryId: file.id,
                files: importStore.files
            )
        case .transcribe, .retryTranscription:
            guard let outputDocument = outputStore.currentDocument() else {
                outputStore.refreshAccess()
                return
            }
            previewPlayer.stop()
            let onSuccess: (String) -> Void = { transcript in
                shownTranscript = IosTranscriptReview.presentation(
                    entryId: file.id,
                    files: importStore.files
                )
                    ?? IosDisplayedTranscript(
                        entryId: file.id,
                        filename: file.displayName,
                        text: transcript
                    )
                selectedTab = .processed
            }
            if action == .retryTranscription {
                transcriber.retry(
                    file: file,
                    localURL: importStore.localURL(for: file),
                    modelDirectory: speechModelStore.modelDirectory,
                    modelStore: speechModelStore,
                    outputDocument: outputDocument,
                    store: importStore,
                    onSuccess: onSuccess
                )
            } else {
                transcriber.transcribe(
                    file: file,
                    localURL: importStore.localURL(for: file),
                    modelDirectory: speechModelStore.modelDirectory,
                    modelStore: speechModelStore,
                    outputDocument: outputDocument,
                    store: importStore,
                    onSuccess: onSuccess
                )
            }
        default:
            break
        }
    }

    private func transcribeAll() {
        guard let outputDocument = outputStore.currentDocument() else {
            outputStore.refreshAccess()
            return
        }
        previewPlayer.stop()
        transcriber.transcribeAll(
            modelDirectory: speechModelStore.modelDirectory,
            modelStore: speechModelStore,
            outputDocument: outputDocument,
            store: importStore,
            onFinished: { selectedTab = .processed }
        )
    }

    private func refreshStartupSources() {
        var foundNewWork = false
        if importStore.ingestSharedImports()?.imported ?? 0 > 0 {
            foundNewWork = true
        }

        if !startupFolderRefreshChecked,
           !importStore.inboxFolderStatus.needsSelection {
            startupFolderRefreshChecked = true
            let pendingBeforeRefresh = importStore.pendingCount
            importStore.refreshInboxFolder()
            foundNewWork = foundNewWork || importStore.pendingCount > pendingBeforeRefresh
        }

        if foundNewWork {
            selectedTab = .new
            startupProcessingChecked = false
        }
    }

    private func evaluateStartupProcessingIfNeeded() {
        guard !startupProcessingChecked else { return }
        startupProcessingChecked = true
        guard importStore.pendingCount > 0 else { return }

        switch startupPolicyStore.policy {
        case .ask:
            startupProcessingPrompt = IosStartupProcessingPrompt(pendingCount: importStore.pendingCount)
        case .yes:
            startStartupProcessing()
        case .no:
            break
        }
    }

    private func startStartupProcessing() {
        guard importStore.pendingCount > 0 else { return }
        guard !transcriber.isActive else {
            importStore.importMessage = "Found files to process, but transcription is already running."
            return
        }
        guard transcriber.backendConfigured else {
            importStore.importMessage = "Found files to process, but the iOS transcription backend is not configured."
            return
        }
        guard speechModelStore.isReady, !speechModelStore.isBusy else {
            importStore.importMessage = "Found files to process, but the speech model is not ready."
            return
        }
        guard let outputDocument = outputStore.currentDocument() else {
            outputStore.refreshAccess()
            importStore.importMessage = "Found files to process, but the output file is not ready."
            return
        }

        previewPlayer.stop()
        selectedTab = .new
        transcriber.transcribeAll(
            modelDirectory: speechModelStore.modelDirectory,
            modelStore: speechModelStore,
            outputDocument: outputDocument,
            store: importStore,
            onFinished: {
                selectedTab = .processed
            }
        )
    }

}

private enum IosPresentedPicker: String, Identifiable {
    case audioFiles
    case audioFolder
    case outputFile
    case speechModelFolder

    var id: String { rawValue }
}

private struct TaskListRow: View {
    let task: TaskPresentation
    let onAction: (TaskActionPresentation) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(task.title)
                        .font(.headline)
                    if let detail = task.detail, !detail.isEmpty {
                        Text(detail)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
                Text(task.badge)
                    .font(.caption)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(.thinMaterial)
                    .clipShape(Capsule())
            }

            if let error = task.errorMessage, !error.isEmpty {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(.red)
            }

            if let progress = task.progress {
                VStack(alignment: .leading, spacing: 4) {
                    if let percent = progress.percent?.int32Value {
                        ProgressView(value: Double(percent), total: 100)
                        .accessibilityIdentifier("task-progress-\(task.stableId)")
                    } else {
                        ProgressView()
                            .accessibilityIdentifier("task-progress-\(task.stableId)")
                    }
                    Text(progressLabel(progress))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }

            if !task.actions.isEmpty {
                HStack {
                    ForEach(Array(task.actions.enumerated()), id: \.offset) { _, action in
                        Button(action.label) { onAction(action) }
                            .disabled(!action.enabled)
                            .accessibilityIdentifier("task-action-\(task.stableId)-\(action.kind.name.lowercased())")
                    }
                }
                .buttonStyle(.bordered)
                .font(.caption)
            }
        }
        .padding(.vertical, 4)
    }

    private func progressLabel(_ progress: TaskProgressPresentation) -> String {
        var parts = [progress.phase]
        if let completed = progress.completedFiles?.int32Value,
           let total = progress.totalFiles?.int32Value,
           total > 0 {
            parts.append("\(completed) / \(total) files")
        }
        if let failed = progress.failedFiles?.int32Value, failed > 0 {
            parts.append("\(failed) failed")
        }
        return parts.joined(separator: " • ")
    }
}

private struct IosGlobalMessage: Identifiable {
    let id = UUID()
    let title: String
    let text: String
}

private struct IosStartupProcessingPrompt: Identifiable {
    let id = UUID()
    let pendingCount: Int
}

private struct StartupProcessingPromptView: View {
    let pendingCount: Int
    let onYes: (Bool) -> Void
    let onNo: (Bool) -> Void

    @State private var alwaysAtStartup = false

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Found files to process. Process now?")
                        .font(.title3)
                        .fontWeight(.semibold)
                    Text(summary)
                        .foregroundStyle(.secondary)
                }

                Toggle("Always do this at startup", isOn: $alwaysAtStartup)

                Spacer()
            }
            .padding()
            .navigationTitle("Process Files")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("No") {
                        onNo(alwaysAtStartup)
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Yes") {
                        onYes(alwaysAtStartup)
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
        }
        .presentationDetents([.medium])
    }

    private var summary: String {
        if pendingCount == 1 {
            return "1 file is waiting in New."
        }
        return "\(pendingCount) files are waiting in New."
    }
}
