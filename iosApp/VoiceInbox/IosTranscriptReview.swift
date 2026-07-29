import SwiftUI
import UIKit

struct IosDisplayedTranscript: Identifiable, Equatable {
    let entryId: Int64
    let filename: String
    let text: String

    var id: Int64 { entryId }
    var shareText: String { text }
    var shareSubject: String { filename }

    init?(entryId: Int64, filename: String, text: String?) {
        guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return nil
        }
        self.entryId = entryId
        self.filename = filename
        self.text = text
    }

    init?(file: IosImportedAudioFile) {
        self.init(entryId: file.id, filename: file.displayName, text: file.transcriptText)
    }
}

enum IosTranscriptReview {
    static func presentation(
        entryId: Int64,
        files: [IosImportedAudioFile]
    ) -> IosDisplayedTranscript? {
        files.first(where: { $0.id == entryId }).flatMap { file in
            IosDisplayedTranscript(file: file)
        }
    }
}

struct IosTranscriptViewer: View {
    let transcript: IosDisplayedTranscript
    let onDismiss: () -> Void

    var body: some View {
        NavigationStack {
            IosSelectableTranscriptTextView(
                text: transcript.text,
                accessibilityLabel: "Transcript text for \(transcript.filename)"
            )
            .accessibilityIdentifier("transcript-text")
            .navigationTitle(transcript.filename)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done", action: onDismiss)
                        .accessibilityLabel("Close transcript")
                        .accessibilityIdentifier("transcript-close")
                }
                ToolbarItem(placement: .primaryAction) {
                    ShareLink(
                        item: transcript.shareText,
                        subject: Text(transcript.shareSubject)
                    ) {
                        Label("Share", systemImage: "square.and.arrow.up")
                    }
                    .accessibilityLabel("Share transcript")
                    .accessibilityIdentifier("transcript-share")
                }
            }
        }
    }
}

struct IosSelectableTranscriptTextView: UIViewRepresentable {
    let text: String
    let accessibilityLabel: String

    func makeUIView(context: Context) -> UITextView {
        let textView = UITextView()
        textView.isEditable = false
        textView.isSelectable = true
        textView.isScrollEnabled = true
        textView.backgroundColor = .clear
        textView.font = .preferredFont(forTextStyle: .body)
        textView.adjustsFontForContentSizeCategory = true
        textView.textContainerInset = UIEdgeInsets(top: 16, left: 12, bottom: 16, right: 12)
        textView.accessibilityTraits = .staticText
        return textView
    }

    func updateUIView(_ textView: UITextView, context: Context) {
        if textView.text != text {
            textView.text = text
        }
        textView.accessibilityLabel = accessibilityLabel
    }
}
