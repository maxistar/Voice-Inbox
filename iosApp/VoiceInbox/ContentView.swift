import Shared
import SwiftUI

struct ContentView: View {
    private let manifest = EmbeddedSpeechModel.shared.manifest
    private let shellState = IosMainScreenShellState()

    @StateObject private var importStore = IosAudioImportStore()
    @StateObject private var previewPlayer = IosAudioPreviewPlayer()
    @StateObject private var speechModelStore = IosSpeechModelStore()
    @StateObject private var transcriber = IosSingleFileTranscriptionController()
    @State private var selectedTab = IosShellCatalogSelection.new
    @State private var showingImporter = false
    @State private var showingModelImporter = false
    @State private var shownTranscript: String?

    var body: some View {
        let transcriptionBackendConfigured = transcriber.backendConfigured
        let speechModelReady = speechModelStore.isReady
        let transcriptionReady = transcriptionBackendConfigured && speechModelReady && !speechModelStore.isInstalling
        let modelStatusMessage = iOSModelStatusMessage(
            transcriptionBackendConfigured: transcriptionBackendConfigured,
            speechModelReady: speechModelReady
        )
        let screen = shellState.screen(
            selection: selectedTab,
            importedFiles: importStore.files,
            runtimeReady: transcriptionBackendConfigured,
            modelReady: speechModelReady,
            modelInstalling: speechModelStore.isInstalling,
            modelMessage: modelStatusMessage,
            activePreviewEntryId: previewPlayer.playingFileId,
            previewState: previewPlayer.playingFileId == nil ? PreviewPlaybackState.idle : PreviewPlaybackState.playing,
            transcription: transcriber.state
        )

        NavigationStack {
            List {

                Section("Status") {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(screen.state.status.title)
                            .font(.headline)
                        if let detail = screen.state.status.detail {
                            Text(detail)
                                .foregroundStyle(.secondary)
                        }
                        if let meta = screen.state.status.meta {
                            Text(meta)
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    }

                    Picker("Catalog", selection: $selectedTab) {
                        ForEach(IosShellCatalogSelection.allCases) { tab in
                            Text(tab.title).tag(tab)
                        }
                    }
                    .pickerStyle(.segmented)

                    if screen.state.list.transcribeAllVisible {
                        Button("Transcribe All") {}
                            .disabled(!screen.state.list.transcribeAllEnabled)
                    }

                    Button {
                        showingImporter = true
                    } label: {
                        Label("Import Audio Files", systemImage: "square.and.arrow.down")
                    }
                }

                Section(selectedTab.title) {
                    if screen.state.list.emptyVisible {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(screen.state.list.emptyMessage)
                                .foregroundStyle(.secondary)
                            Button {
                                showingImporter = true
                            } label: {
                                Label("Import Audio Files", systemImage: "square.and.arrow.down")
                            }
                        }
                    } else {
                        ForEach(screen.rows) { row in
                            VStack(alignment: .leading, spacing: 8) {
                                HStack(alignment: .top) {
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(row.title)
                                            .font(.headline)
                                        Text(row.subtitle)
                                            .font(.subheadline)
                                            .foregroundStyle(.secondary)
                                    }

                                    Spacer()

                                    Text(row.badge)
                                        .font(.caption)
                                        .padding(.horizontal, 8)
                                        .padding(.vertical, 4)
                                        .background(.thinMaterial)
                                        .clipShape(Capsule())
                                }

                                HStack {
                                    if let importedFile = importedFile(for: row) {
                                        Button {
                                            previewPlayer.toggle(
                                                fileId: importedFile.id,
                                                url: importStore.localURL(for: importedFile)
                                            )
                                        } label: {
                                            Label(
                                                previewPlayer.playingFileId == importedFile.id ? "Stop" : row.state.preview.label,
                                                systemImage: previewPlayer.playingFileId == importedFile.id ? "stop.fill" : "play.fill"
                                            )
                                        }
                                        .disabled(!row.state.preview.enabled)

                                        if importedFile.status == .pending {
                                            Button {
                                                transcriber.transcribe(
                                                    file: importedFile,
                                                    localURL: importStore.localURL(for: importedFile),
                                                    modelDirectory: speechModelStore.modelDirectory,
                                                    store: importStore,
                                                    onSuccess: { transcript in
                                                        shownTranscript = transcript
                                                        selectedTab = .processed
                                                    }
                                                )
                                            } label: {
                                                Label("Transcribe", systemImage: "text.badge.checkmark")
                                            }
                                            .disabled(
                                                transcriber.activeFileId != nil ||
                                                    !transcriptionReady
                                            )
                                        }

                                        if importedFile.status == .failed {
                                            Button {
                                                transcriber.retry(
                                                    file: importedFile,
                                                    localURL: importStore.localURL(for: importedFile),
                                                    modelDirectory: speechModelStore.modelDirectory,
                                                    store: importStore,
                                                    onSuccess: { transcript in
                                                        shownTranscript = transcript
                                                        selectedTab = .processed
                                                    }
                                                )
                                            } label: {
                                                Label("Retry", systemImage: "arrow.clockwise")
                                            }
                                            .disabled(
                                                transcriber.activeFileId != nil ||
                                                    !transcriptionReady
                                            )
                                        }
                                    }

                                    if row.state.showTextVisible {
                                        Button("Show Text") {
                                            shownTranscript = row.transcriptText
                                        }
                                    }
                                }
                                .buttonStyle(.bordered)
                                .font(.caption)
                            }
                            .padding(.vertical, 4)
                        }
                    }
                }

                if let importMessage = importStore.importMessage {
                    Section("Import result") {
                        Text(importMessage)
                            .foregroundStyle(.secondary)
                    }
                }

                if let playbackError = previewPlayer.errorMessage {
                    Section("Playback") {
                        Text(playbackError)
                            .foregroundStyle(.secondary)
                        Button("Dismiss") {
                            previewPlayer.clearError()
                        }
                    }
                }

                if !transcriptionBackendConfigured || !speechModelReady || speechModelStore.isInstalling || speechModelStore.message != nil || transcriber.message != nil {
                    Section("Transcription Setup") {
                        if !transcriptionBackendConfigured {
                            Text(IosSingleFileTranscriptionController.backendUnavailableMessage)
                                .foregroundStyle(.secondary)
                        }
                        VStack(alignment: .leading, spacing: 6) {
                            Text(speechModelStore.status.summary)
                            if speechModelStore.isInstalling {
                                ProgressView()
                            }
                            if let detail = speechModelStore.status.detail {
                                Text(detail)
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                            }
                            if let modelMessage = speechModelStore.message {
                                Text(modelMessage)
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        Button {
                            showingModelImporter = true
                        } label: {
                            Label(speechModelReady ? "Replace Speech Model" : "Install Speech Model", systemImage: "square.and.arrow.down")
                        }
                        .disabled(speechModelStore.isInstalling)

                        if let transcriptionMessage = transcriber.message {
                            Text(transcriptionMessage)
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                Section("Shared framework") {
                    LabeledContent("Model", value: manifest.modelId)
                    LabeledContent("Version", value: manifest.version)
                    LabeledContent("Required free space", value: formatBytes(manifest.requiredFreeBytes))
                }

                Section {
                    NavigationLink {
                        SettingsView()
                    } label: {
                        Label("Settings", systemImage: "gearshape")
                    }
                }
            }
            .navigationTitle("Voice Inbox")
            .sheet(isPresented: $showingImporter) {
                IosAudioDocumentPicker { urls in
                    importStore.importFiles(from: urls)
                    showingImporter = false
                    selectedTab = .new
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

    private func importedFile(for row: IosShellAudioRow) -> IosImportedAudioFile? {
        guard row.imported else { return nil }
        return importStore.files.first { $0.id == row.id }
    }

    private func iOSModelStatusMessage(
        transcriptionBackendConfigured: Bool,
        speechModelReady: Bool
    ) -> String {
        if !transcriptionBackendConfigured {
            return IosSingleFileTranscriptionController.backendUnavailableMessage
        }
        if speechModelStore.isInstalling {
            return "Installing speech model..."
        }
        if !speechModelReady {
            return speechModelStore.status.summary
        }
        return "Ready"
    }

    private func formatBytes(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useMB, .useGB]
        formatter.countStyle = .file
        return formatter.string(fromByteCount: bytes)
    }
}

private struct IosDisplayedTranscript: Identifiable {
    let id = UUID()
    let text: String
}
