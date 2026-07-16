import Shared
import SwiftUI

struct ContentView: View {
    private let shellState = IosMainScreenShellState()

    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var importStore = IosAudioImportStore()
    @StateObject private var outputStore = IosOutputDocumentStore()
    @StateObject private var previewPlayer = IosAudioPreviewPlayer()
    @StateObject private var speechModelStore = IosSpeechModelStore()
    @StateObject private var transcriber = IosSingleFileTranscriptionController()
    @StateObject private var startupPolicyStore = IosStartupProcessingPolicyStore()
    @State private var selectedTab = IosShellCatalogSelection.new
    @State private var showingImporter = false
    @State private var showingInboxFolderPicker = false
    @State private var showingOutputPicker = false
    @State private var showingModelImporter = false
    @State private var shownTranscript: String?
    @State private var startupProcessingChecked = false
    @State private var startupFolderRefreshChecked = false
    @State private var startupProcessingPrompt: IosStartupProcessingPrompt?

    var body: some View {
        let transcriptionBackendConfigured = transcriber.backendConfigured
        let speechModelReady = speechModelStore.isReady
        let outputReady = outputStore.isReady
        let transcriptionReady = transcriptionBackendConfigured && speechModelReady && !speechModelStore.isBusy && outputReady
        let modelDownloadAvailable = !speechModelReady && !speechModelStore.isBusy
        let screen = shellState.screen(
            selection: selectedTab,
            importedFiles: importStore.files,
            modelStatus: speechModelStore.status,
            modelMessage: speechModelStore.message,
            modelInstalling: speechModelStore.isInstalling,
            modelDownloadAvailable: modelDownloadAvailable,
            modelDownloadProgress: speechModelStore.downloadProgress?.percent,
            modelCanCancel: speechModelStore.canCancelDownload,
            outputStatus: outputStore.status,
            folderStatus: importStore.inboxFolderStatus,
            folderScanning: importStore.isScanningFolder,
            activePreviewEntryId: previewPlayer.playingFileId,
            previewState: previewPlayer.playingFileId == nil ? PreviewPlaybackState.idle : PreviewPlaybackState.playing,
            transcription: transcriber.state,
            preparationOwnerEntryId: transcriber.preparationOwnerFileId,
            prerequisiteError: transcriber.prerequisiteError,
            actionsEnabled: transcriptionReady && !transcriber.isActive && !importStore.isScanningFolder
        )

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

                Section {
                    if let emptyMessage = screen.state.emptyMessage {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(emptyMessage)
                                .foregroundStyle(.secondary)
                            ForEach(Array(screen.state.emptyActions.enumerated()), id: \.offset) { _, action in
                                Button(action.label) {
                                    perform(action: action, task: nil, screen: screen)
                                }
                                .disabled(!action.enabled)
                            }
                        }
                    } else {
                        ForEach(screen.state.tasks.filter { $0 is SetupTaskPresentation }, id: \.stableId) { task in
                            TaskListRow(task: task) { action in
                                perform(action: action, task: task, screen: screen)
                            }
                            .id(task.stableId)
                            .accessibilityIdentifier("task-row-\(task.stableId)")
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
                    }
                }

                Section {
                    Button {
                        showingImporter = true
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
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    NavigationLink {
                        SettingsView(
                            importStore: importStore,
                            outputStore: outputStore,
                            startupPolicyStore: startupPolicyStore,
                            selectInboxFolder: {
                                showingInboxFolderPicker = true
                            },
                            selectOutputFile: {
                                showingOutputPicker = true
                            }
                        )
                    } label: {
                        Image(systemName: "gearshape")
                    }
                    .accessibilityLabel("Settings")
                }
            }
            .sheet(isPresented: $showingImporter) {
                IosAudioDocumentPicker { urls in
                    importStore.importFiles(from: urls)
                    showingImporter = false
                    selectedTab = .new
                }
            }
            .sheet(isPresented: $showingInboxFolderPicker) {
                IosSpeechModelDirectoryPicker { url in
                    importStore.selectInboxFolder(url)
                    showingInboxFolderPicker = false
                    selectedTab = .new
                }
            }
            .sheet(isPresented: $showingOutputPicker) {
                IosOutputDocumentPicker { url in
                    outputStore.selectOutputFile(url)
                    showingOutputPicker = false
                }
            }
            .sheet(isPresented: $showingModelImporter) {
                IosSpeechModelDirectoryPicker { url in
                    speechModelStore.installModel(from: url)
                    showingModelImporter = false
                }
            }
            .onChange(of: importStore.files) { files in
                previewPlayer.stopIfUnavailable(availableFileIds: Set(files.map(\.id)))
            }
            .onAppear {
                refreshStartupSources()
                evaluateStartupProcessingIfNeeded()
            }
            .onChange(of: scenePhase) { phase in
                guard phase == .active else { return }
                refreshStartupSources()
                evaluateStartupProcessingIfNeeded()
            }
            .sheet(item: transcriptBinding) { transcript in
                NavigationStack {
                    ScrollView {
                        Text(transcript.text)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding()
                    }
                    .navigationTitle("Transcript")
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Done") {
                                shownTranscript = nil
                            }
                        }
                    }
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

    private var transcriptBinding: Binding<IosDisplayedTranscript?> {
        Binding(
            get: {
                shownTranscript.map(IosDisplayedTranscript.init(text:))
            },
            set: { value in
                shownTranscript = value?.text
            }
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
                return nil
            },
            set: { value in
                guard value == nil else { return }
                previewPlayer.clearError()
                importStore.importMessage = nil
            }
        )
    }

    private func perform(
        action: TaskActionPresentation,
        task: TaskPresentation?,
        screen: IosTaskListScreen
    ) {
        guard action.enabled, let route = IosTaskActionRouter.route(action.kind) else { return }
        switch route {
        case .modelDownload:
            speechModelStore.downloadModel()
        case .modelImport:
            showingModelImporter = true
        case .modelCancel:
            speechModelStore.cancelDownload()
        case .outputSelection:
            showingOutputPicker = true
        case .folderSelection:
            showingInboxFolderPicker = true
        case .folderRefresh:
            importStore.refreshInboxFolder()
            selectedTab = .new
        case .audioImport:
            showingImporter = true
        case .transcribe, .retry, .play, .stop, .showText:
            guard let audioTask = task as? AudioTaskPresentation,
                  let file = screen.filesById[audioTask.entryId] else { return }
            performAudio(action: action.kind, file: file)
        }
    }

    private func performAudio(action: TaskActionKind, file: IosImportedAudioFile) {
        switch action {
        case .play:
            previewPlayer.toggle(fileId: file.id, url: importStore.localURL(for: file))
        case .stop:
            previewPlayer.stop()
        case .showText:
            shownTranscript = file.transcriptText
        case .transcribe, .retryTranscription:
            guard let outputDocument = outputStore.currentDocument() else {
                outputStore.refreshAccess()
                return
            }
            previewPlayer.stop()
            let onSuccess: (String) -> Void = { transcript in
                shownTranscript = transcript
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

private struct IosDisplayedTranscript: Identifiable {
    let id = UUID()
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
