import AVFoundation
import Foundation
import Shared

@_silgen_name("voiceinbox_transcription_initialize")
private func voiceinbox_transcription_initialize(_ modelDirectory: UnsafePointer<CChar>) -> Bool

@_silgen_name("voiceinbox_transcription_transcribe_chunk_json")
private func voiceinbox_transcription_transcribe_chunk_json(
    _ samples: UnsafePointer<Float>?,
    _ sampleCount: Int
) -> UnsafeMutablePointer<CChar>?

@_silgen_name("voiceinbox_transcription_last_error")
private func voiceinbox_transcription_last_error() -> UnsafeMutablePointer<CChar>?

@_silgen_name("voiceinbox_transcription_string_free")
private func voiceinbox_transcription_string_free(_ value: UnsafeMutablePointer<CChar>?)

struct IosSingleFileTranscriptionState {
    let active: Bool
    let fileId: Int64?
    let fileName: String?
    let phase: String?
    let processedUs: Int64
    let durationUs: Int64

    var progressPercent: Int? {
        guard durationUs > 0 else { return nil }
        return Int((max(0, min(processedUs, durationUs)) * 100) / durationUs)
    }

    static let idle = IosSingleFileTranscriptionState(
        active: false,
        fileId: nil,
        fileName: nil,
        phase: nil,
        processedUs: 0,
        durationUs: 0
    )
}

@MainActor
final class IosSingleFileTranscriptionController: ObservableObject {
    @Published private(set) var state = IosSingleFileTranscriptionState.idle
    @Published var message: String?

    private var task: Task<Void, Never>?

    var activeFileId: Int64? {
        state.active ? state.fileId : nil
    }

    func transcribe(file: IosImportedAudioFile, localURL: URL, store: IosAudioImportStore) {
        task?.cancel()
        store.markProcessing(fileId: file.id)
        state = IosSingleFileTranscriptionState(
            active: true,
            fileId: file.id,
            fileName: file.displayName,
            phase: SingleFileTranscriptionUseCase.companion.PHASE_DECODING_AUDIO,
            processedUs: 0,
            durationUs: 0
        )
        message = nil

        task = Task {
            let outcome = await Task.detached(priority: .userInitiated) {
                Self.runSharedTranscription(file: file, localURL: localURL) { progress in
                    Task { @MainActor in
                        self.state = IosSingleFileTranscriptionState(
                            active: true,
                            fileId: file.id,
                            fileName: file.displayName,
                            phase: progress.phase,
                            processedUs: progress.processedUs?.int64Value ?? 0,
                            durationUs: progress.durationUs?.int64Value ?? 0
                        )
                    }
                }
            }.value

            guard !Task.isCancelled else {
                store.resetToPending(fileId: file.id)
                state = .idle
                return
            }

            if outcome.success, let result = outcome.result {
                store.markProcessed(
                    fileId: file.id,
                    transcriptText: result.transcriptText,
                    durationUs: result.durationUs?.int64Value
                )
                message = "Transcribed \(file.displayName)"
            } else {
                let error = outcome.errorMessage ?? "iOS transcription failed."
                store.markFailed(fileId: file.id, error: error)
                message = error
            }
            state = .idle
        }
    }

    func retry(file: IosImportedAudioFile, localURL: URL, store: IosAudioImportStore) {
        store.resetToPending(fileId: file.id)
        transcribe(file: file, localURL: localURL, store: store)
    }

    nonisolated private static func runSharedTranscription(
        file: IosImportedAudioFile,
        localURL: URL,
        onProgress: @escaping (SingleFileTranscriptionProgress) -> Void
    ) -> SingleFileTranscriptionOutcome {
        let decoder = IosPlatformAudioDecoder(localURL: localURL)
        let transcriber = IosNativeTranscriber(modelDirectory: IosNativeTranscriber.defaultModelDirectory())
        guard transcriber.initialize(modelDirectory: transcriber.modelDirectory) else {
            return SingleFileTranscriptionOutcome(
                success: false,
                result: nil,
                errorMessage: transcriber.lastError ?? "iOS speech recognition is not available yet."
            )
        }
        let staging = IosTranscriptStaging()
        let formatter = IosTimestampLabelFormatter()
        let output = IosShellTranscriptOutput()
        let useCase = SingleFileTranscriptionUseCase(
            audioDecoder: decoder,
            nativeTranscriber: transcriber,
            staging: staging,
            timestampLabelFormatter: formatter,
            transcriptOutput: output
        )
        return useCase.tryTranscribe(
            input: SingleFileTranscriptionInput(
                audioId: localURL.path,
                audioName: file.displayName,
                outputId: "ios-shell-output",
                fallbackRecordingTimeMillis: nil
            ),
            onProgress: onProgress
        )
    }
}

private final class IosPlatformAudioDecoder: PlatformAudioDecoder {
    private let localURL: URL

    init(localURL: URL) {
        self.localURL = localURL
    }

    func decode(
        audioId: String,
        onProgress: @escaping (KotlinLong, KotlinLong?) -> Void,
        onChunk: @escaping (KotlinFloatArray) -> Void
    ) -> PlatformAudioInfo {
        do {
            let file = try AVAudioFile(forReading: localURL)
            let frameCount = AVAudioFrameCount(file.length)
            let durationUs = Int64((Double(file.length) / file.fileFormat.sampleRate) * 1_000_000)

            guard let buffer = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: frameCount) else {
                return PlatformAudioInfo(
                    durationUs: KotlinLong(longLong: durationUs),
                    embeddedRecordingTimeMillis: nil
                )
            }

            try file.read(into: buffer)
            let samples = Self.monoSamples(from: buffer)
            let kotlinSamples = KotlinFloatArray(size: Int32(samples.count))
            for (index, sample) in samples.enumerated() {
                kotlinSamples.set(index: Int32(index), value: sample)
            }
            onProgress(KotlinLong(longLong: durationUs), KotlinLong(longLong: durationUs))
            onChunk(kotlinSamples)
            return PlatformAudioInfo(
                durationUs: KotlinLong(longLong: durationUs),
                embeddedRecordingTimeMillis: nil
            )
        } catch {
            return PlatformAudioInfo(durationUs: nil, embeddedRecordingTimeMillis: nil)
        }
    }

    private static func monoSamples(from buffer: AVAudioPCMBuffer) -> [Float] {
        guard let channelData = buffer.floatChannelData else { return [] }
        let frameLength = Int(buffer.frameLength)
        let channelCount = Int(buffer.format.channelCount)
        guard frameLength > 0, channelCount > 0 else { return [] }

        if channelCount == 1 {
            return Array(UnsafeBufferPointer(start: channelData[0], count: frameLength))
        }

        var samples = Array(repeating: Float(0), count: frameLength)
        for channel in 0..<channelCount {
            let data = UnsafeBufferPointer(start: channelData[channel], count: frameLength)
            for index in 0..<frameLength {
                samples[index] += data[index] / Float(channelCount)
            }
        }
        return samples
    }
}

private final class IosNativeTranscriber: PlatformNativeTranscriber {
    let modelDirectory: String
    private(set) var lastError: String?

    init(modelDirectory: String) {
        self.modelDirectory = modelDirectory
    }

    static func defaultModelDirectory() -> String {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)
            .first ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent("SpeechModel", isDirectory: true).path
    }

    func initialize(modelDirectory: String) -> Bool {
        let ok = modelDirectory.withCString { pointer in
            voiceinbox_transcription_initialize(pointer)
        }
        if !ok {
            lastError = Self.consumeNativeError()
        }
        return ok
    }

    func transcribeChunk(samples: KotlinFloatArray) -> String? {
        var buffer = [Float]()
        buffer.reserveCapacity(Int(samples.size))
        for index in 0..<samples.size {
            buffer.append(samples.get(index: index))
        }

        let result = buffer.withUnsafeBufferPointer { pointer in
            voiceinbox_transcription_transcribe_chunk_json(pointer.baseAddress, pointer.count)
        }
        guard let result else {
            lastError = Self.consumeNativeError()
            return nil
        }
        defer { voiceinbox_transcription_string_free(result) }

        let json = String(cString: result)
        guard let data = json.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let text = object["text"] as? String
        else {
            lastError = "Native transcription returned invalid JSON."
            return nil
        }
        return text
    }

    private static func consumeNativeError() -> String {
        guard let pointer = voiceinbox_transcription_last_error() else {
            return "iOS native transcription failed."
        }
        defer { voiceinbox_transcription_string_free(pointer) }
        return String(cString: pointer)
    }
}

private final class IosTranscriptStaging: PlatformTranscriptStaging {
    private var transcript = ""

    func write(transcript: String) {
        self.transcript = transcript
    }

    func cleanup() {
        transcript = ""
    }
}

private final class IosTimestampLabelFormatter: PlatformTimestampLabelFormatter {
    func formatRecordingTime(recordingTimeMillis: KotlinLong?) -> String? {
        nil
    }
}

private final class IosShellTranscriptOutput: PlatformTranscriptOutput {
    private static var output = ""

    func readTail(outputId: String) -> String {
        String(Self.output.suffix(4096))
    }

    func append(outputId: String, text: String) {
        Self.output.append(text)
    }
}
