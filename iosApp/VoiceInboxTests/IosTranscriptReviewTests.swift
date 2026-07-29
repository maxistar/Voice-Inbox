import Foundation
import Shared
import XCTest
@testable import VoiceInbox

final class IosTranscriptReviewTests: XCTestCase {
    func testMetadataFormatterCombinesLocalizedTimestampSizeAndDuration() throws {
        let locale = Locale(identifier: "en_US_POSIX")
        let timeZone = try XCTUnwrap(TimeZone(secondsFromGMT: 0))
        let timestamp = Date(timeIntervalSince1970: 1_704_207_840)
        let expectedDateFormatter = DateFormatter()
        expectedDateFormatter.locale = locale
        expectedDateFormatter.timeZone = timeZone
        expectedDateFormatter.dateStyle = .medium
        expectedDateFormatter.timeStyle = .short

        let metadata = try XCTUnwrap(IosAudioMetadataFormatter.format(
            timestamp: timestamp,
            sizeBytes: 2_000_000,
            durationUs: 65_000_000,
            locale: locale,
            timeZone: timeZone
        ))

        XCTAssertTrue(metadata.hasPrefix(expectedDateFormatter.string(from: timestamp)))
        XCTAssertTrue(metadata.contains("2 MB"))
        XCTAssertTrue(metadata.hasSuffix("1:05"))
        XCTAssertEqual(metadata.components(separatedBy: " • ").count, 3)
        XCTAssertFalse(metadata.contains("Created"))
        XCTAssertFalse(metadata.contains("Recorded"))
    }

    func testMetadataFormatterOmitsUnavailableAndZeroComponents() {
        XCTAssertNil(IosAudioMetadataFormatter.format(
            timestamp: nil,
            sizeBytes: nil,
            durationUs: nil
        ))
        XCTAssertNil(IosAudioMetadataFormatter.format(
            timestamp: Date(timeIntervalSince1970: 0),
            sizeBytes: 0,
            durationUs: 0
        ))
        XCTAssertEqual(
            IosAudioMetadataFormatter.format(
                timestamp: nil,
                sizeBytes: nil,
                durationUs: 3_661_000_000
            ),
            "1:01:01"
        )
    }

    func testTranscriptPresentationPreservesExactShareTextAndFilenameContext() throws {
        let text = "  recognized text\n"
        let presentation = try XCTUnwrap(IosDisplayedTranscript(
            entryId: 7,
            filename: "voice.m4a",
            text: text
        ))

        XCTAssertEqual(presentation.id, 7)
        XCTAssertEqual(presentation.shareText, text)
        XCTAssertEqual(presentation.shareSubject, "voice.m4a")
        XCTAssertFalse(presentation.shareText.contains(presentation.filename))
        XCTAssertNil(IosDisplayedTranscript(entryId: 8, filename: "empty.m4a", text: " \n "))
    }

    func testTranscriptReviewRejectsMissingOrStaleCatalogText() throws {
        let files = [
            file(id: 1, status: .processed, transcript: "stored transcript"),
            file(id: 2, status: .processed, transcript: nil),
        ]

        XCTAssertEqual(
            try XCTUnwrap(IosTranscriptReview.presentation(entryId: 1, files: files)).text,
            "stored transcript"
        )
        XCTAssertNil(IosTranscriptReview.presentation(entryId: 2, files: files))
        XCTAssertNil(IosTranscriptReview.presentation(entryId: 99, files: files))
    }

    @MainActor
    func testMetadataDetailUsesSamePolicyAcrossCatalogFilters() throws {
        let pending = file(id: 3, status: .pending, transcript: nil)
        let processed = file(id: 4, status: .processed, transcript: "done")

        let newDetail = try XCTUnwrap(audioTask(in: screen(selection: .new, files: [pending]), id: 3).detail)
        let processedDetail = try XCTUnwrap(
            audioTask(in: screen(selection: .processed, files: [processed]), id: 4).detail
        )
        let allScreen = screen(selection: .all, files: [pending, processed])

        XCTAssertEqual(newDetail, audioTask(in: allScreen, id: 3).detail)
        XCTAssertEqual(processedDetail, audioTask(in: allScreen, id: 4).detail)
        XCTAssertTrue(newDetail.contains(" • "))
    }

    private func file(
        id: Int64,
        status: IosImportedAudioStatus,
        transcript: String?
    ) -> IosImportedAudioFile {
        IosImportedAudioFile(
            id: id,
            displayName: "\(id).m4a",
            localFileName: "\(id).m4a",
            sizeBytes: 2_000_000,
            importedAt: Date(timeIntervalSince1970: 1_704_207_840),
            status: status,
            transcriptText: transcript,
            durationUs: 65_000_000,
            processedAt: status == .processed ? Date(timeIntervalSince1970: 1_704_207_900) : nil
        )
    }

    @MainActor
    private func screen(
        selection: IosShellCatalogSelection,
        files: [IosImportedAudioFile]
    ) -> IosTaskListScreen {
        IosMainScreenShellState().screen(
            selection: selection,
            importedFiles: files,
            modelStatus: IosSpeechModelStatus(
                directory: FileManager.default.temporaryDirectory,
                installationState: .installedVerified,
                missingFiles: []
            ),
            modelMessage: nil,
            modelInstalling: false,
            modelInstallationPhase: nil,
            modelDownloadAvailable: false,
            modelDownloadProgress: nil,
            modelCanCancel: false,
            outputStatus: IosOutputDocumentStatus(displayName: "notes.md", message: "Ready", ready: true),
            folderStatus: IosInboxFolderStatus(displayName: nil, message: nil, needsSelection: true),
            folderScanning: false,
            activePreviewEntryId: nil,
            previewState: .idle,
            transcription: .idle,
            preparationOwnerEntryId: nil,
            prerequisiteError: nil,
            actionsEnabled: true
        )
    }

    private func audioTask(in screen: IosTaskListScreen, id: Int64) -> AudioTaskPresentation {
        screen.state.tasks
            .compactMap { $0 as? AudioTaskPresentation }
            .first { $0.entryId == id }!
    }
}
