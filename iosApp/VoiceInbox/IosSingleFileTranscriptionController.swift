import AVFoundation
import Foundation
import Shared

enum IosTranscriptionPreparationGate {
    @MainActor
    static func prepareAndClaim(
        prepare: () async -> Bool,
        claim: () -> Void
    ) async -> Bool {
        guard await prepare() else { return false }
        claim()
        return true
    }
}

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

@_silgen_name("voiceinbox_transcription_backend_configured")
private func voiceinbox_transcription_backend_configured() -> Bool

@_silgen_name("voiceinbox_transcription_reset")
private func voiceinbox_transcription_reset()

struct IosSingleFileTranscriptionState {
    let active: Bool
    let fileId: Int64?
    let fileName: String?
    let phase: String?
    let processedUs: Int64
    let durationUs: Int64
    let completedFiles: Int32
    let totalFiles: Int32
    let failedFiles: Int32
    let explicitProgress: Int32?
    let summary: String?

    var progressPercent: Int? {
        if let explicitProgress {
            return Int(explicitProgress)
        }
        guard durationUs > 0 else { return nil }
        return Int((max(0, min(processedUs, durationUs)) * 100) / durationUs)
    }

    static let idle = IosSingleFileTranscriptionState(
        active: false,
        fileId: nil,
        fileName: nil,
        phase: nil,
        processedUs: 0,
        durationUs: 0,
        completedFiles: 0,
        totalFiles: 0,
        failedFiles: 0,
        explicitProgress: nil,
        summary: nil
    )
}

@MainActor
final class IosSingleFileTranscriptionController: ObservableObject {
    @Published private(set) var state = IosSingleFileTranscriptionState.idle
    @Published var message: String?

    private var task: Task<Void, Never>?
    static let backendUnavailableMessage = "iOS transcription backend is not configured yet."

    var activeFileId: Int64? {
        state.active ? state.fileId : nil
    }

    var isActive: Bool {
        state.active
    }

    var backendConfigured: Bool {
        IosNativeTranscriber.isRuntimeConfigured
    }

    func transcribe(
        file: IosImportedAudioFile,
        localURL: URL,
        modelDirectory: URL,
        modelStore: IosSpeechModelStore,
        outputDocument: IosSelectedOutputDocument,
        store: IosAudioImportStore,
        onSuccess: ((String) -> Void)? = nil
    ) {
        task?.cancel()
        state = IosSingleFileTranscriptionState(
            active: true,
            fileId: file.id,
            fileName: file.displayName,
            phase: "Preparing speech model",
            processedUs: 0,
            durationUs: 0,
            completedFiles: 0,
            totalFiles: 1,
            failedFiles: 0,
            explicitProgress: nil,
            summary: nil
        )
        message = nil

        task = Task {
            let prepared = await IosTranscriptionPreparationGate.prepareAndClaim(
                prepare: { await modelStore.prepareForTranscription() != nil },
                claim: { store.markProcessing(fileId: file.id) }
            )
            guard prepared else {
                message = modelStore.message ?? "Speech model preparation failed."
                state = .idle
                return
            }
            guard !Task.isCancelled else {
                state = .idle
                return
            }
            let outcome = await Task.detached(priority: .userInitiated) {
                Self.runSharedTranscription(
                    file: file,
                    localURL: localURL,
                    modelDirectory: modelDirectory.path,
                    outputDocument: outputDocument,
                    modelPrepared: true
                ) { progress in
                    Task { @MainActor in
                        self.state = IosSingleFileTranscriptionState(
                            active: true,
                            fileId: file.id,
                            fileName: file.displayName,
                            phase: progress.phase,
                            processedUs: progress.processedUs?.int64Value ?? 0,
                            durationUs: progress.durationUs?.int64Value ?? 0,
                            completedFiles: 0,
                            totalFiles: 1,
                            failedFiles: 0,
                            explicitProgress: nil,
                            summary: nil
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
                onSuccess?(result.transcriptText)
            } else {
                let error = outcome.errorMessage ?? "iOS transcription failed."
                store.markFailed(fileId: file.id, error: error)
                message = error
            }
            state = .idle
        }
    }

    func transcribeAll(
        modelDirectory: URL,
        modelStore: IosSpeechModelStore,
        outputDocument: IosSelectedOutputDocument,
        store: IosAudioImportStore,
        onFinished: (() -> Void)? = nil
    ) {
        guard !state.active else { return }
        task?.cancel()
        state = IosSingleFileTranscriptionState(
            active: true,
            fileId: nil,
            fileName: nil,
            phase: "Preparing speech model",
            processedUs: 0,
            durationUs: 0,
            completedFiles: 0,
            totalFiles: Int32(store.files.filter { $0.status == .pending }.count),
            failedFiles: 0,
            explicitProgress: nil,
            summary: nil
        )
        message = nil

        task = Task {
            guard await modelStore.prepareForTranscription() != nil else {
                message = modelStore.message ?? "Speech model preparation failed."
                state = .idle
                return
            }
            guard !Task.isCancelled else {
                state = .idle
                return
            }
            let outcome = await Task.detached(priority: .userInitiated) {
                Self.runSharedBatchTranscription(
                    modelDirectory: modelDirectory.path,
                    outputDocument: outputDocument
                ) { progress in
                    Task { @MainActor in
                        self.state = IosSingleFileTranscriptionState(
                            active: true,
                            fileId: nil,
                            fileName: progress.filename,
                            phase: progress.phase,
                            processedUs: progress.processedUs?.int64Value ?? 0,
                            durationUs: progress.durationUs?.int64Value ?? 0,
                            completedFiles: progress.completed,
                            totalFiles: progress.total,
                            failedFiles: progress.failed,
                            explicitProgress: progress.progress?.int32Value,
                            summary: progress.filename == nil ? progress.phase : nil
                        )
                    }
                }
            }.value

            store.refresh()
            guard !Task.isCancelled else {
                state = .idle
                return
            }

            message = outcome.summary
            state = IosSingleFileTranscriptionState(
                active: false,
                fileId: nil,
                fileName: nil,
                phase: outcome.summary,
                processedUs: 0,
                durationUs: 0,
                completedFiles: outcome.completed,
                totalFiles: outcome.total,
                failedFiles: outcome.failed,
                explicitProgress: outcome.progress,
                summary: outcome.summary
            )
            onFinished?()
        }
    }

    func retry(
        file: IosImportedAudioFile,
        localURL: URL,
        modelDirectory: URL,
        modelStore: IosSpeechModelStore,
        outputDocument: IosSelectedOutputDocument,
        store: IosAudioImportStore,
        onSuccess: ((String) -> Void)? = nil
    ) {
        store.resetToPending(fileId: file.id)
        transcribe(
            file: file,
            localURL: localURL,
            modelDirectory: modelDirectory,
            modelStore: modelStore,
            outputDocument: outputDocument,
            store: store,
            onSuccess: onSuccess
        )
    }

    nonisolated fileprivate static func runSharedTranscription(
        file: IosImportedAudioFile,
        localURL: URL,
        modelDirectory: String,
        outputDocument: IosSelectedOutputDocument,
        modelPrepared: Bool = false,
        onProgress: @escaping (SingleFileTranscriptionProgress) -> Void
    ) -> SingleFileTranscriptionOutcome {
        let decoder = IosPlatformAudioDecoder(localURL: localURL)
        let transcriber = IosNativeTranscriber(modelDirectory: modelDirectory)
        guard modelPrepared || transcriber.initialize(modelDirectory: transcriber.modelDirectory) else {
            return SingleFileTranscriptionOutcome(
                success: false,
                result: nil,
                errorMessage: transcriber.lastError ?? "iOS speech recognition is not available yet."
            )
        }
        let staging = IosTranscriptStaging()
        let formatter = IosTimestampLabelFormatter()
        let output = CallbackTranscriptOutput(
            readTailBlock: { _ in
                IosOutputDocumentStore.readTail(outputDocument.url)
            },
            appendBlock: { _, text in
                IosOutputDocumentStore.append(text, to: outputDocument.url)
            }
        )
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
                outputId: outputDocument.id,
                fallbackRecordingTimeMillis: KotlinLong(
                    longLong: Int64(file.importedAt.timeIntervalSince1970 * 1000)
                )
            ),
            onProgress: onProgress
        )
    }

    nonisolated private static func runSharedBatchTranscription(
        modelDirectory: String,
        outputDocument: IosSelectedOutputDocument,
        onProgress: @escaping (BatchTranscriptionProgress) -> Void
    ) -> BatchTranscriptionResult {
        let catalog = IosSqlDelightAudioCatalogFactory().create(
            databaseName: IosAudioCatalogConstants.databaseName
        )
        defer { catalog.close() }
        let transcriber = IosBatchEntryOutcomeTranscriber(
            modelDirectory: modelDirectory,
            outputDocument: outputDocument
        )
        let useCase = OutcomeBatchTranscriptionUseCase(
            catalog: catalog,
            transcriber: transcriber,
            clock: IosBatchClock()
        )
        return useCase.transcribe(
            input: BatchTranscriptionInput(
                folderId: IosAudioCatalogConstants.importedFolderUri,
                outputId: outputDocument.id,
                runId: UUID().uuidString,
                retryEntryId: nil
            ),
            onProgress: onProgress
        )
    }
}

private final class IosBatchEntryOutcomeTranscriber: OutcomeBatchEntryTranscriber {
    private let modelDirectory: String
    private let outputDocument: IosSelectedOutputDocument

    init(modelDirectory: String, outputDocument: IosSelectedOutputDocument) {
        self.modelDirectory = modelDirectory
        self.outputDocument = outputDocument
    }

    func transcribe(
        entry: AudioCatalogEntry,
        outputId: String,
        runId: String,
        onProgress: @escaping (SingleFileTranscriptionProgress) -> Void
    ) -> SingleFileTranscriptionOutcome {
        IosSingleFileTranscriptionController.runSharedTranscription(
            file: IosImportedAudioFile(
                id: entry.id,
                displayName: entry.displayName,
                localFileName: entry.documentUri,
                sizeBytes: entry.fingerprint.sizeBytes?.int64Value ?? 0,
                importedAt: Date(timeIntervalSince1970: Double(entry.fingerprint.modifiedMillis?.int64Value ?? 0) / 1000),
                status: IosImportedAudioStatus(sharedState: entry.state),
                transcriptText: entry.transcriptText,
                lastError: entry.lastError
            ),
            localURL: Self.localURL(documentUri: entry.documentUri),
            modelDirectory: modelDirectory,
            outputDocument: outputDocument,
            modelPrepared: true,
            onProgress: onProgress
        )
    }

    private static func localURL(documentUri: String) -> URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("ImportedAudio", isDirectory: true)
            .appendingPathComponent(documentUri)
    }
}

private final class IosBatchClock: BatchClock {
    func currentTimeMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}

private final class IosPlatformAudioDecoder: PlatformAudioDecoder {
    private let localURL: URL
    private static let targetSampleRate = 16_000.0

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
            let samples = try Self.normalizedMonoSamples(from: buffer, sourceFormat: file.processingFormat)
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

    private static func normalizedMonoSamples(
        from buffer: AVAudioPCMBuffer,
        sourceFormat: AVAudioFormat
    ) throws -> [Float] {
        guard let targetFormat = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: targetSampleRate,
            channels: 1,
            interleaved: false
        ) else {
            return []
        }

        if sourceFormat.sampleRate == targetSampleRate,
           sourceFormat.channelCount == 1,
           sourceFormat.commonFormat == .pcmFormatFloat32,
           let channelData = buffer.floatChannelData {
            return Array(UnsafeBufferPointer(start: channelData[0], count: Int(buffer.frameLength)))
        }

        guard let converter = AVAudioConverter(from: sourceFormat, to: targetFormat) else {
            return monoSamples(from: buffer)
        }

        let ratio = targetSampleRate / sourceFormat.sampleRate
        let targetCapacity = AVAudioFrameCount((Double(buffer.frameLength) * ratio).rounded(.up)) + 1024
        guard let convertedBuffer = AVAudioPCMBuffer(
            pcmFormat: targetFormat,
            frameCapacity: max(targetCapacity, 1)
        ) else {
            return []
        }

        var didProvideInput = false
        var conversionError: NSError?
        let status = converter.convert(to: convertedBuffer, error: &conversionError) { _, outStatus in
            if didProvideInput {
                outStatus.pointee = .noDataNow
                return nil
            }
            didProvideInput = true
            outStatus.pointee = .haveData
            return buffer
        }

        if status == .error, let conversionError {
            throw conversionError
        }

        return monoSamples(from: convertedBuffer)
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

final class IosNativeTranscriber: PlatformNativeTranscriber {
    let modelDirectory: String
    private(set) var lastError: String?

    init(modelDirectory: String) {
        self.modelDirectory = modelDirectory
    }

    static var isRuntimeConfigured: Bool {
        voiceinbox_transcription_backend_configured()
    }

    static func prepare(modelDirectory: String) -> Bool {
        modelDirectory.withCString { voiceinbox_transcription_initialize($0) }
    }

    static func resetModel() {
        voiceinbox_transcription_reset()
    }

    static func consumeLastError() -> String? {
        guard let pointer = voiceinbox_transcription_last_error() else { return nil }
        defer { voiceinbox_transcription_string_free(pointer) }
        return String(cString: pointer)
    }

    func initialize(modelDirectory: String) -> Bool {
        let ok = Self.prepare(modelDirectory: modelDirectory)
        if !ok {
            lastError = Self.consumeLastError() ?? "iOS native transcription failed."
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
            lastError = Self.consumeLastError() ?? "iOS native transcription failed."
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
        guard let recordingTimeMillis else { return nil }
        let date = Date(timeIntervalSince1970: Double(recordingTimeMillis.int64Value) / 1000)
        return Self.formatter.string(from: date)
    }

    private static let formatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .medium
        return formatter
    }()
}
