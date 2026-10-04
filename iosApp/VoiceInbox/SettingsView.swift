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
            L10n.text("settings.startup.ask", fallback: "Ask")
        case .yes:
            L10n.text("settings.startup.yes", fallback: "Yes")
        case .no:
            L10n.text("settings.startup.no", fallback: "No")
        }
    }

    var detail: String {
        switch self {
        case .ask:
            L10n.text("settings.startup.askDetail", fallback: "Ask before processing queued files when Voice Inbox starts.")
        case .yes:
            L10n.text("settings.startup.yesDetail", fallback: "Automatically process queued files when Voice Inbox starts.")
        case .no:
            L10n.text("settings.startup.noDetail", fallback: "Do not process queued files automatically at startup.")
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
    @ObservedObject var speechModelStore: IosSpeechModelStore
    let selectInboxFolder: () -> Void
    let selectOutputFile: () -> Void
    let disableExport: () -> Void
    let installModelPackage: () -> Void

    private let websiteURL = URL(string: "https://voiceinbox.simpleditor.org/")!
    private let documentationURL = URL(string: "https://voiceinbox.simpleditor.org/docs/")!
    private let legalURL = URL(string: "https://voiceinbox.simpleditor.org/legal/")!

    var body: some View {
        Form {
            Section(L10n.text("settings.storage", fallback: "Storage")) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(L10n.text("settings.audioInbox", fallback: "Audio inbox folder"))
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
                        importStore.inboxFolderStatus.needsSelection
                            ? L10n.text("settings.selectAudioFolder", fallback: "Select Audio Folder")
                            : L10n.text("settings.changeAudioFolder", fallback: "Change Audio Folder"),
                        systemImage: "folder"
                    )
                }
                .disabled(importStore.isScanningFolder)

                VStack(alignment: .leading, spacing: 6) {
                    Text(L10n.text("settings.outputFile", fallback: "Transcript output file"))
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
                        outputStore.isReady
                            ? L10n.text("settings.changeOutput", fallback: "Change Output File")
                            : L10n.text("settings.selectOutput", fallback: "Select Output File"),
                        systemImage: "doc.badge.plus"
                    )
                }

                Button(L10n.text("settings.doNotExport", fallback: "Do not export"), role: .destructive) {
                    disableExport()
                }
            }

            Section(L10n.text("settings.startup", fallback: "Startup Processing")) {
                Picker(L10n.text("settings.whenQueued", fallback: "When queued files are found"), selection: $startupPolicyStore.policy) {
                    ForEach(IosStartupProcessingPolicy.allCases) { policy in
                        Text(policy.title).tag(policy)
                    }
                }

                Text(startupPolicyStore.policy.detail)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section(L10n.text("settings.speechModel", fallback: "Speech Model")) {
                if let active = speechModelStore.activeDescriptor {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(active.displayName).font(.headline)
                        Text("\(active.languageSummary) · \(active.maturity)")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                } else {
                    Text(L10n.text("settings.noModel", fallback: "No speech model installed"))
                }
                Button {
                    installModelPackage()
                } label: {
                    Label(L10n.text("settings.installModel", fallback: "Install model package from folder"), systemImage: "folder.badge.plus")
                }
                .disabled(speechModelStore.isBusy)
            }

            Section(L10n.text("settings.about", fallback: "About")) {
                LabeledContent(L10n.text("settings.version", fallback: "Version"), value: appVersion)
                Link(L10n.text("settings.website", fallback: "Website"), destination: websiteURL)
                Link(L10n.text("settings.documentation", fallback: "Documentation"), destination: documentationURL)
                Link(L10n.text("settings.legal", fallback: "Legal information"), destination: legalURL)
            }
        }
        .navigationTitle(L10n.text("settings.title", fallback: "Settings"))
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
        return L10n.text("settings.unknown", fallback: "Unknown")
    }
}
