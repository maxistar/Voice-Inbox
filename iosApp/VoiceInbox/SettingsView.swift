import Shared
import SwiftUI

struct SettingsView: View {
    @ObservedObject var importStore: IosAudioImportStore
    @ObservedObject var outputStore: IosOutputDocumentStore
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
