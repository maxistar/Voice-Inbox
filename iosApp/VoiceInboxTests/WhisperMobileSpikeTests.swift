import Darwin
import Foundation
import XCTest

/// Opt-in developer harness. Native spike symbols are resolved dynamically so
/// ordinary app and test builds do not require the experimental backend.
final class WhisperMobileSpikeTests: XCTestCase {
    private typealias InitializeFunction = @convention(c) (
        UnsafePointer<CChar>?
    ) -> UnsafeMutablePointer<CChar>?
    private typealias TranscribeFunction = @convention(c) (
        UnsafePointer<Float>?, Int, UnsafePointer<CChar>?
    ) -> UnsafeMutablePointer<CChar>?
    private typealias ResetFunction = @convention(c) () -> Void
    private typealias FreeFunction = @convention(c) (UnsafeMutablePointer<CChar>?) -> Void

    func testPhysicalDeviceSmokeTranscription() throws {
        #if targetEnvironment(simulator)
        throw XCTSkip("The simulator is build evidence only; run this test on a physical iPhone")
        #else
        let environment = ProcessInfo.processInfo.environment
        let resultURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("ios-whisper-spike-smoke.json")
        var result = baseResult(environment: environment)
        let availableMemoryBefore = os_proc_available_memory()

        guard let modelPathValue = environment["WHISPER_MODEL_PATH"], !modelPathValue.isEmpty else {
            try fail(
                "WHISPER_MODEL_PATH was not provided; use an absolute path or a path relative to the test runner container",
                result: &result,
                resultURL: resultURL
            )
        }
        guard let pcmPathValue = environment["WHISPER_PCM_PATH"], !pcmPathValue.isEmpty else {
            try fail(
                "WHISPER_PCM_PATH was not provided; physical evidence requires representative float32 mono 16 kHz PCM",
                result: &result,
                resultURL: resultURL
            )
        }
        let modelPath = provisionedPath(modelPathValue)
        let pcmPath = provisionedPath(pcmPathValue)

        guard
            let initialize: InitializeFunction = resolve("voiceinbox_whisper_spike_initialize_json"),
            let transcribe: TranscribeFunction = resolve("voiceinbox_whisper_spike_transcribe_json"),
            let reset: ResetFunction = resolve("voiceinbox_whisper_spike_reset"),
            let free: FreeFunction = resolve("voiceinbox_transcription_string_free")
        else {
            try fail(
                "Whisper spike symbols are missing; build with VOICEINBOX_WHISPER_MOBILE_SPIKE=1 and force-load the spike archive members",
                result: &result,
                resultURL: resultURL
            )
        }
        defer { reset() }

        let initialization = modelPath.withCString { path in
            takeJSON(initialize(path), free: free)
        }
        result["load_ms"] = initialization["load_ms"] ?? NSNull()
        guard initialization["status"] as? String == "ok" else {
            try fail(
                initialization["error"] as? String ?? "Whisper initialization failed without a diagnostic",
                result: &result,
                resultURL: resultURL,
                diagnostics: ["initialization": initialization]
            )
        }

        let samples: [Float]
        do {
            samples = try loadSamples(path: pcmPath)
        } catch {
            try fail(
                "Could not load PCM input: \(error.localizedDescription)",
                result: &result,
                resultURL: resultURL,
                diagnostics: ["initialization": initialization]
            )
        }
        let language = environment["WHISPER_LANGUAGE"] ?? ""
        let chunks = chunk(samples: samples)
        var chunkResults = [[String: Any]]()
        for chunk in chunks {
            let chunkResult = chunk.withUnsafeBufferPointer { samples in
                language.withCString { language in
                    takeJSON(transcribe(samples.baseAddress, samples.count, language), free: free)
                }
            }
            guard chunkResult["status"] as? String == "ok" else {
                try fail(
                    chunkResult["error"] as? String ?? "Whisper inference failed without a diagnostic",
                    result: &result,
                    resultURL: resultURL,
                    diagnostics: [
                        "initialization": initialization,
                        "completed_chunks": chunkResults,
                        "failed_chunk": chunkResult,
                    ]
                )
            }
            chunkResults.append(chunkResult)
        }

        let inferenceTimes = chunkResults.compactMap { $0["inference_ms"] as? Double }
        let inferenceMilliseconds = inferenceTimes.reduce(0, +)
        let audioDurationSeconds = Double(samples.count) / 16_000.0
        let transcript = chunkResults
            .compactMap { $0["transcript"] as? String }
            .joined(separator: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)

        result["status"] = "ok"
        result["load_ms"] = initialization["load_ms"] ?? NSNull()
        result["chunk_inference_ms"] = inferenceTimes
        result["inference_ms"] = inferenceMilliseconds
        result["audio_duration_seconds"] = audioDurationSeconds
        result["real_time_factor"] = inferenceMilliseconds / 1_000.0 / audioDurationSeconds
        result["detected_language"] = chunkResults.compactMap { $0["detected_language"] as? String }.last ?? NSNull()
        result["transcript"] = transcript
        result["error"] = NSNull()
        result["diagnostics"] = [
            "initialization": initialization,
            "sample_rate_hz": 16_000,
            "sample_count": samples.count,
            "chunk_count": chunks.count,
            "available_memory_before_bytes": availableMemoryBefore,
            "available_memory_after_bytes": os_proc_available_memory(),
            "memory_note": "Peak process memory is unavailable in this XCTest harness; available process memory is recorded before and after the run.",
            "test_result": "Physical XCTest completed without host-process termination.",
        ]
        try write(result, to: resultURL)
        print("Whisper spike result: \(resultURL.path)")
        XCTAssertFalse(transcript.isEmpty, "Physical speech input produced an empty transcript; result: \(resultURL.path)")
        #endif
    }

    func testChunkingUsesThirtySecondsWithOneSecondOverlap() {
        let samples = Array(repeating: Float.zero, count: 31 * 16_000)
        let chunks = chunk(samples: samples)
        XCTAssertEqual(chunks.map(\.count), [30 * 16_000, 2 * 16_000])
    }

    func testRelativeProvisioningPathUsesTestRunnerHome() {
        XCTAssertEqual(
            provisionedPath("Documents/whisper/ggml-tiny.bin"),
            URL(fileURLWithPath: NSHomeDirectory())
                .appendingPathComponent("Documents/whisper/ggml-tiny.bin").path
        )
    }

    private func baseResult(environment: [String: String]) -> [String: Any] {
        [
            "schema_version": 1,
            "backend": "whisper.cpp",
            "model_id": "openai/whisper-tiny",
            "model_revision": "5359861c739e955e79d9a303bcbc70fb988958b1",
            "platform": "ios",
            "device": environment["WHISPER_DEVICE_NAME"] ?? "physical iPhone",
            "os_version": ProcessInfo.processInfo.operatingSystemVersionString,
            "build_revision": environment["WHISPER_BUILD_REVISION"] ?? "workspace",
            "build_profile": "release-native/xctest",
            "corpus_item_id": environment["WHISPER_CORPUS_ITEM_ID"] ?? "physical-ios-smoke",
            "status": "error",
            "load_ms": NSNull(),
            "chunk_inference_ms": NSNull(),
            "inference_ms": NSNull(),
            "audio_duration_seconds": 0.0,
            "real_time_factor": NSNull(),
            "peak_memory_bytes": NSNull(),
            "detected_language": NSNull(),
            "transcript": NSNull(),
            "quality": [
                "wer": NSNull(),
                "cer": NSNull(),
                "punctuation": NSNull(),
                "capitalization": NSNull(),
                "silence_hallucination": NSNull(),
                "overlap_boundary": NSNull(),
            ],
            "diagnostics": NSNull(),
            "error": "Physical XCTest did not complete",
        ]
    }

    private func fail(
        _ message: String,
        result: inout [String: Any],
        resultURL: URL,
        diagnostics: [String: Any] = [:],
        file: StaticString = #filePath,
        line: UInt = #line
    ) throws -> Never {
        result["status"] = "error"
        result["error"] = message
        result["diagnostics"] = diagnostics
        try write(result, to: resultURL)
        XCTFail("\(message). Structured result: \(resultURL.path)", file: file, line: line)
        throw NSError(domain: "WhisperMobileSpike", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
    }

    private func write(_ result: [String: Any], to url: URL) throws {
        let data = try JSONSerialization.data(withJSONObject: result, options: [.prettyPrinted, .sortedKeys])
        try data.write(to: url, options: .atomic)
    }

    private func provisionedPath(_ value: String) -> String {
        if value.hasPrefix("/") { return value }
        return URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent(value).path
    }

    private func resolve<T>(_ symbol: String) -> T? {
        guard let handle = dlopen(nil, RTLD_NOW), let pointer = dlsym(handle, symbol) else {
            return nil
        }
        return unsafeBitCast(pointer, to: T.self)
    }

    private func takeJSON(
        _ pointer: UnsafeMutablePointer<CChar>?,
        free: FreeFunction
    ) -> [String: Any] {
        guard let pointer else {
            return ["status": "error", "error": "native function returned null"]
        }
        defer { free(pointer) }
        let data = Data(String(cString: pointer).utf8)
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
            ?? ["status": "error", "error": "native function returned invalid JSON"]
    }

    private func loadSamples(path: String) throws -> [Float] {
        let data = try Data(contentsOf: URL(fileURLWithPath: path))
        guard !data.isEmpty, data.count.isMultiple(of: MemoryLayout<Float>.size) else {
            throw NSError(
                domain: "WhisperMobileSpike",
                code: 2,
                userInfo: [NSLocalizedDescriptionKey: "PCM must be non-empty little-endian float32"]
            )
        }
        return data.withUnsafeBytes { bytes in
            Array(bytes.bindMemory(to: Float.self))
        }
    }

    private func chunk(samples: [Float]) -> [[Float]] {
        let chunkSamples = 16_000 * 30
        let overlapSamples = 16_000
        var result = [[Float]]()
        var start = 0
        while start < samples.count {
            let end = min(start + chunkSamples, samples.count)
            result.append(Array(samples[start..<end]))
            if end == samples.count { break }
            start = end - overlapSamples
        }
        return result
    }
}
