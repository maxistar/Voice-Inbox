import Shared
import SwiftUI

struct ContentView: View {
    private let manifest = EmbeddedSpeechModel.shared.manifest

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Voice Inbox")
                        .font(.largeTitle)
                        .fontWeight(.bold)
                    Text("Minimal iOS shell")
                        .font(.headline)
                        .foregroundStyle(.secondary)
                }

                VStack(alignment: .leading, spacing: 10) {
                    Text("Shared framework connected")
                        .font(.title3)
                        .fontWeight(.semibold)
                    Text("Model: \(manifest.modelId)")
                    Text("Version: \(manifest.version)")
                    Text("Required free space: \(formatBytes(manifest.requiredFreeBytes))")
                }
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(.thinMaterial)
                .clipShape(RoundedRectangle(cornerRadius: 12))

                Text("Document access, playback, catalog storage, transcription, settings, scheduling, and Rust/iOS bridging are intentionally future work.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                Spacer()
            }
            .padding()
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
