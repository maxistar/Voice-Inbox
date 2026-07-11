import Shared
import SwiftUI

struct ContentView: View {
    private let manifest = EmbeddedSpeechModel.shared.manifest
    private let shellState = IosMainScreenShellState()

    @State private var selectedTab = IosShellCatalogSelection.new

    var body: some View {
        let screen = shellState.screen(selection: selectedTab)

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
                }

                Section(selectedTab.title) {
                    if screen.state.list.emptyVisible {
                        Text(screen.state.list.emptyMessage)
                            .foregroundStyle(.secondary)
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
                                    Button(row.state.preview.label) {}
                                        .disabled(!row.state.preview.enabled)

                                    if row.state.retryVisible {
                                        Button("Retry") {}
                                            .disabled(!row.state.retryEnabled)
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
                    Text("Sample rows are shell verification only. Document access, playback, catalog storage, transcription, scheduling execution, output writing, and Rust/iOS bridging are intentionally future work.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Voice Inbox")
        }
    }

    private func formatBytes(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useMB, .useGB]
        formatter.countStyle = .file
        return formatter.string(fromByteCount: bytes)
    }
}
