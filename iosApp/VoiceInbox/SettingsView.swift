import Shared
import SwiftUI

struct SettingsView: View {
    private let store = IosScheduledTranscriptionSettingsStore()

    @ObservedObject var importStore: IosAudioImportStore
    @ObservedObject var outputStore: IosOutputDocumentStore
    let selectInboxFolder: () -> Void
    let selectOutputFile: () -> Void

    @State private var enabled = false
    @State private var hour = Int(ScheduledTranscriptionRules.shared.DEFAULT_HOUR)
    @State private var minute = Int(ScheduledTranscriptionRules.shared.DEFAULT_MINUTE)
    @State private var loaded = false

    var body: some View {
        Form {
            Section("Storage") {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Audio inbox folder")
                        .font(.headline)
                    Text(importStore.inboxFolderStatus.title)
                    if let message = importStore.inboxFolderStatus.message {
                        Text(message)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    if importStore.isScanningFolder {
                        ProgressView()
                    }
                }

                Button {
                    selectInboxFolder()
                } label: {
                    Label(
                        importStore.inboxFolderStatus.needsSelection ? "Select Audio Folder" : "Change Audio Folder",
                        systemImage: "folder"
                    )
                }
                .disabled(importStore.isScanningFolder)

                VStack(alignment: .leading, spacing: 6) {
                    Text("Transcript output file")
                        .font(.headline)
                    Text(outputStore.status.title)
                    Text(outputStore.status.message)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Button {
                    selectOutputFile()
                } label: {
                    Label(
                        outputStore.isReady ? "Change Output File" : "Select Output File",
                        systemImage: "doc.badge.plus"
                    )
                }
            }

            Section("Scheduled transcription") {
                Toggle("Enabled", isOn: $enabled)
                    .onChange(of: enabled) { _ in saveIfLoaded() }

                Picker("Hour", selection: $hour) {
                    ForEach(0..<24) { value in
                        Text(String(format: "%02d", value)).tag(value)
                    }
                }
                .onChange(of: hour) { _ in saveIfLoaded() }

                Picker("Minute", selection: $minute) {
                    ForEach(0..<60) { value in
                        Text(String(format: "%02d", value)).tag(value)
                    }
                }
                .onChange(of: minute) { _ in saveIfLoaded() }
            }

            Section("Status") {
                Text("Selected time: \(formattedTime)")
                Text("iOS scheduled execution is not implemented yet. These settings are persisted locally and normalized through shared KMP rules.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Settings")
        .onAppear(perform: load)
    }

    private var formattedTime: String {
        "\(String(format: "%02d", hour)):\(String(format: "%02d", minute))"
    }

    private func load() {
        let settings = store.load()
        enabled = settings.enabled
        hour = Int(settings.hour)
        minute = Int(settings.minute)
        loaded = true
    }

    private func saveIfLoaded() {
        guard loaded else { return }
        let settings = store.save(enabled: enabled, hour: hour, minute: minute)
        enabled = settings.enabled
        hour = Int(settings.hour)
        minute = Int(settings.minute)
    }
}
