import Shared
import SwiftUI

struct ContentView: View {
    private let manifest = EmbeddedSpeechModel.shared.manifest

    var body: some View {
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
                    Text("Document access, playback, catalog storage, transcription, scheduling execution, and Rust/iOS bridging are intentionally future work.")
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
