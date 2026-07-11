import Shared
import SwiftUI

struct SettingsView: View {
    private let store = IosScheduledTranscriptionSettingsStore()

    @State private var enabled = false
    @State private var hour = Int(ScheduledTranscriptionRules.shared.DEFAULT_HOUR)
    @State private var minute = Int(ScheduledTranscriptionRules.shared.DEFAULT_MINUTE)
    @State private var loaded = false

    var body: some View {
        Form {
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
