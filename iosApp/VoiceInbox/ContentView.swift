import Shared
import SwiftUI

struct ContentView: View {
    private let manifest = EmbeddedSpeechModel.shared.manifest
    private let shellState = IosMainScreenShellState()

    @StateObject private var importStore = IosAudioImportStore()
    @StateObject private var previewPlayer = IosAudioPreviewPlayer()
    @State private var selectedTab = IosShellCatalogSelection.new
    @State private var showingImporter = false

    var body: some View {
        let screen = shellState.screen(selection: selectedTab, importedFiles: importStore.files)

        NavigationStack {
            List {
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Voice Inbox")
                            .font(.largeTitle)
                            .fontWeight(.bold)
                        Text("iOS shell")
                            .font(.headline)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 8)
                }

                Section("Shared main screen state") {
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
                                    }

                                    if row.state.retryVisible {
                                        Text("Retry: future")
                                            .foregroundStyle(.secondary)
                                    }

                                    if row.state.showTextVisible {
                                        Button("Show Text") {}
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

                Section("Shared empty state preview") {
                    Text(screen.emptyStatePreview.emptyMessage)
                    Text("Transcribe All visible: \(screen.emptyStatePreview.transcribeAllVisible ? "yes" : "no")")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
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

                Section("Future work") {
                    Text("Imported files are copied into app-local storage for shell verification only. Playback, transcription, output writing, scheduling execution, and Rust/iOS bridging are intentionally future work.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
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
            .onChange(of: importStore.files) { files in
                previewPlayer.stopIfUnavailable(availableFileIds: Set(files.map(\.id)))
            }
        }
    }

    private func importedFile(for row: IosShellAudioRow) -> IosImportedAudioFile? {
        guard row.imported else { return nil }
        return importStore.files.first { $0.id == row.id }
    }

    private func formatBytes(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useMB, .useGB]
        formatter.countStyle = .file
        return formatter.string(fromByteCount: bytes)
    }
}
