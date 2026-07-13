import Shared
import SwiftUI

enum IosStartupProcessingPolicy: String, CaseIterable, Identifiable {
    case ask
    case yes
    case no

    var id: String { rawValue }

    var title: String {
        switch self {
        case .ask:
            "Ask"
        case .yes:
            "Yes"
        case .no:
            "No"
        }
    }

    var detail: String {
        switch self {
        case .ask:
            "Ask before processing queued files when Voice Inbox starts."
        case .yes:
            "Automatically process queued files when Voice Inbox starts."
        case .no:
            "Do not process queued files automatically at startup."
        }
    }
}

@MainActor
final class IosStartupProcessingPolicyStore: ObservableObject {
    @Published var policy: IosStartupProcessingPolicy {
        didSet {
            defaults.set(policy.rawValue, forKey: Self.policyKey)
        }
    }

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if let rawValue = defaults.string(forKey: Self.policyKey),
           let storedPolicy = IosStartupProcessingPolicy(rawValue: rawValue) {
            policy = storedPolicy
        } else {
            policy = .ask
        }
    }

    private static let policyKey = "iosStartupProcessingPolicy"
}

struct SettingsView: View {
    @ObservedObject var importStore: IosAudioImportStore
    @ObservedObject var outputStore: IosOutputDocumentStore
    @ObservedObject var startupPolicyStore: IosStartupProcessingPolicyStore
    let selectInboxFolder: () -> Void
    let selectOutputFile: () -> Void

    private let websiteURL = URL(string: "https://projects.maxistar.me/Voice-Inbox/")!
    private let legalURL = URL(string: "https://projects.maxistar.me/Voice-Inbox/legal/")!

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

            Section("Startup Processing") {
                Picker("When queued files are found", selection: $startupPolicyStore.policy) {
                    ForEach(IosStartupProcessingPolicy.allCases) { policy in
                        Text(policy.title).tag(policy)
                    }
                }

                Text(startupPolicyStore.policy.detail)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section("About") {
                LabeledContent("Version", value: appVersion)
                Link("Website", destination: websiteURL)
                Link("Legal information", destination: legalURL)
            }
        }
        .navigationTitle("Settings")
    }

    private var appVersion: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String
        if let version, !version.isEmpty {
            return version
        }
        if let build, !build.isEmpty {
            return build
        }
        return "Unknown"
    }
}
