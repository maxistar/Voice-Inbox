import SwiftUI
import UniformTypeIdentifiers

struct IosAudioDocumentPicker: UIViewControllerRepresentable {
    let onPick: ([URL]) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let controller = UIDocumentPickerViewController(forOpeningContentTypes: [.audio], asCopy: false)
        controller.allowsMultipleSelection = true
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(onPick: onPick)
    }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        private let onPick: ([URL]) -> Void

        init(onPick: @escaping ([URL]) -> Void) {
            self.onPick = onPick
        }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            onPick(urls)
        }
    }
}

struct IosOutputDocumentPicker: UIViewControllerRepresentable {
    let onPick: (URL) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let markdown = UTType(filenameExtension: "md") ?? .plainText
        let markdownLong = UTType(filenameExtension: "markdown") ?? markdown
        let controller = UIDocumentPickerViewController(
            forOpeningContentTypes: [.plainText, markdown, markdownLong],
            asCopy: false
        )
        controller.allowsMultipleSelection = false
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(onPick: onPick)
    }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        private let onPick: (URL) -> Void

        init(onPick: @escaping (URL) -> Void) {
            self.onPick = onPick
        }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            guard let url = urls.first else { return }
            onPick(url)
        }
    }
}

struct IosSelectedOutputDocument {
    let id: String
    let url: URL
    let displayName: String
}

struct IosOutputDocumentStatus {
    let displayName: String?
    let message: String
    let ready: Bool

    var title: String {
        displayName.map { "Output: \($0)" } ?? "Output file not selected"
    }
}

@MainActor
final class IosOutputDocumentStore: ObservableObject {
    @Published private(set) var status = IosOutputDocumentStatus(
        displayName: nil,
        message: "Choose a text or Markdown file for transcripts.",
        ready: false
    )

    private let userDefaults: UserDefaults
    private var selectedDocument: IosSelectedOutputDocument?

    init(userDefaults: UserDefaults = .standard) {
        self.userDefaults = userDefaults
        restoreOutputDocument()
    }

    var isReady: Bool {
        status.ready && selectedDocument != nil
    }

    func currentDocument() -> IosSelectedOutputDocument? {
        guard isReady else { return nil }
        return selectedDocument
    }

    func selectOutputFile(_ url: URL) {
        guard Self.isSupportedOutput(url) else {
            status = IosOutputDocumentStatus(
                displayName: url.lastPathComponent,
                message: "Choose a .txt, .md, or .markdown file.",
                ready: false
            )
            return
        }

        let didStartAccessing = url.startAccessingSecurityScopedResource()
        defer {
            if didStartAccessing {
                url.stopAccessingSecurityScopedResource()
            }
        }

        do {
            try Self.requireWritable(url)
            let bookmark = try url.bookmarkData(
                options: [],
                includingResourceValuesForKeys: nil,
                relativeTo: nil
            )
            userDefaults.set(bookmark, forKey: Self.outputBookmarkKey)
            userDefaults.set(url.lastPathComponent, forKey: Self.outputDisplayNameKey)
            selectedDocument = IosSelectedOutputDocument(
                id: url.absoluteString,
                url: url,
                displayName: url.lastPathComponent
            )
            status = IosOutputDocumentStatus(
                displayName: url.lastPathComponent,
                message: "Ready to append transcripts.",
                ready: true
            )
        } catch {
            selectedDocument = nil
            status = IosOutputDocumentStatus(
                displayName: url.lastPathComponent,
                message: "Output file is not writable. Choose another file.",
                ready: false
            )
        }
    }

    func refreshAccess() {
        restoreOutputDocument()
    }

    private func restoreOutputDocument() {
        guard let bookmark = userDefaults.data(forKey: Self.outputBookmarkKey) else {
            selectedDocument = nil
            status = IosOutputDocumentStatus(
                displayName: nil,
                message: "Choose a text or Markdown file for transcripts.",
                ready: false
            )
            return
        }

        let savedName = userDefaults.string(forKey: Self.outputDisplayNameKey)
        do {
            var stale = false
            let url = try URL(
                resolvingBookmarkData: bookmark,
                options: [],
                relativeTo: nil,
                bookmarkDataIsStale: &stale
            )
            guard Self.isSupportedOutput(url), !stale else {
                selectedDocument = nil
                status = IosOutputDocumentStatus(
                    displayName: savedName,
                    message: "Output access needs to be refreshed. Choose the file again.",
                    ready: false
                )
                return
            }
            try Self.requireWritableWithSecurityScope(url)
            selectedDocument = IosSelectedOutputDocument(
                id: url.absoluteString,
                url: url,
                displayName: url.lastPathComponent
            )
            status = IosOutputDocumentStatus(
                displayName: url.lastPathComponent,
                message: "Ready to append transcripts.",
                ready: true
            )
        } catch {
            selectedDocument = nil
            status = IosOutputDocumentStatus(
                displayName: savedName,
                message: "Could not write to the selected output file. Choose it again.",
                ready: false
            )
        }
    }

    nonisolated static func readTail(_ url: URL, maxBytes: Int = 4096) -> String {
        let didStartAccessing = url.startAccessingSecurityScopedResource()
        defer {
            if didStartAccessing {
                url.stopAccessingSecurityScopedResource()
            }
        }

        guard let data = try? Data(contentsOf: url), !data.isEmpty else { return "" }
        let tail = data.suffix(maxBytes)
        return String(data: tail, encoding: .utf8) ?? ""
    }

    nonisolated static func append(_ text: String, to url: URL) -> String? {
        let didStartAccessing = url.startAccessingSecurityScopedResource()
        defer {
            if didStartAccessing {
                url.stopAccessingSecurityScopedResource()
            }
        }

        do {
            let handle = try FileHandle(forWritingTo: url)
            defer {
                try? handle.close()
            }
            try handle.seekToEnd()
            guard let data = text.data(using: .utf8) else {
                return "Transcript text could not be encoded as UTF-8."
            }
            try handle.write(contentsOf: data)
            return nil
        } catch {
            return "Could not append to the selected output file."
        }
    }

    private static func requireWritableWithSecurityScope(_ url: URL) throws {
        let didStartAccessing = url.startAccessingSecurityScopedResource()
        defer {
            if didStartAccessing {
                url.stopAccessingSecurityScopedResource()
            }
        }
        try requireWritable(url)
    }

    private static func requireWritable(_ url: URL) throws {
        let handle = try FileHandle(forWritingTo: url)
        try handle.close()
    }

    private static func isSupportedOutput(_ url: URL) -> Bool {
        ["txt", "md", "markdown"].contains(url.pathExtension.lowercased())
    }

    private static let outputBookmarkKey = "iosOutputDocumentBookmark"
    private static let outputDisplayNameKey = "iosOutputDocumentDisplayName"
}
