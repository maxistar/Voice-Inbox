//! Disabled-by-default Whisper Tiny physical-device evaluation backend.

use once_cell::sync::Lazy;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::fs::File;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::Instant;
use transcribe_rs::engines::whisper::{WhisperEngine, WhisperInferenceParams};
use transcribe_rs::TranscriptionEngine;

pub const MODEL_ID: &str = "openai/whisper-tiny";
pub const MODEL_REPOSITORY: &str = "ggerganov/whisper.cpp";
pub const MODEL_REVISION: &str = "5359861c739e955e79d9a303bcbc70fb988958b1";
pub const MODEL_FILENAME: &str = "ggml-tiny.bin";
pub const MODEL_SIZE_BYTES: u64 = 77_691_713;
pub const MODEL_SHA256: &str = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21";
pub const SAMPLE_RATE_HZ: usize = 16_000;
pub const MAX_SAMPLE_COUNT: usize = SAMPLE_RATE_HZ * 30;

struct SpikeEngine {
    engine: WhisperEngine,
    model_path: PathBuf,
    load_ms: f64,
}

static SPIKE_ENGINE: Lazy<Mutex<Option<SpikeEngine>>> = Lazy::new(|| Mutex::new(None));

fn error(operation: &str, message: impl Into<String>) -> String {
    json!({
        "schema_version": 1,
        "backend": "whisper.cpp",
        "model_id": MODEL_ID,
        "model_revision": MODEL_REVISION,
        "operation": operation,
        "status": "error",
        "error": message.into(),
    })
    .to_string()
}

fn sha256(path: &Path) -> Result<String, String> {
    let mut file = File::open(path).map_err(|cause| format!("cannot open model: {cause}"))?;
    let mut digest = Sha256::new();
    let mut buffer = [0_u8; 64 * 1024];
    loop {
        let read = file
            .read(&mut buffer)
            .map_err(|cause| format!("cannot read model: {cause}"))?;
        if read == 0 {
            break;
        }
        digest.update(&buffer[..read]);
    }
    Ok(format!("{:x}", digest.finalize()))
}

pub fn validate_model(path: &Path) -> Result<(), String> {
    if !path.is_file() {
        return Err(format!("model file does not exist: {}", path.display()));
    }
    if path.file_name().and_then(|name| name.to_str()) != Some(MODEL_FILENAME) {
        return Err(format!("expected model filename {MODEL_FILENAME}"));
    }
    let size = path
        .metadata()
        .map_err(|cause| format!("cannot inspect model: {cause}"))?
        .len();
    if size != MODEL_SIZE_BYTES {
        return Err(format!(
            "model size mismatch: expected {MODEL_SIZE_BYTES}, got {size}"
        ));
    }
    let actual_sha256 = sha256(path)?;
    if actual_sha256 != MODEL_SHA256 {
        return Err(format!(
            "model SHA-256 mismatch: expected {MODEL_SHA256}, got {actual_sha256}"
        ));
    }
    Ok(())
}

pub fn initialize(model_path: &Path) -> String {
    if let Err(message) = validate_model(model_path) {
        return error("initialize", message);
    }

    let started = Instant::now();
    let mut engine = WhisperEngine::new();
    if let Err(cause) = engine.load_model(model_path) {
        return error("initialize", format!("Whisper model load failed: {cause}"));
    }
    let load_ms = started.elapsed().as_secs_f64() * 1_000.0;
    let canonical_path = model_path
        .canonicalize()
        .unwrap_or_else(|_| model_path.to_path_buf());
    *SPIKE_ENGINE.lock().unwrap() = Some(SpikeEngine {
        engine,
        model_path: canonical_path.clone(),
        load_ms,
    });

    json!({
        "schema_version": 1,
        "backend": "whisper.cpp",
        "model_id": MODEL_ID,
        "model_repository": MODEL_REPOSITORY,
        "model_revision": MODEL_REVISION,
        "model_filename": MODEL_FILENAME,
        "model_sha256": MODEL_SHA256,
        "model_size_bytes": MODEL_SIZE_BYTES,
        "model_path": canonical_path,
        "operation": "initialize",
        "status": "ok",
        "load_ms": load_ms,
        "peak_memory_bytes": Value::Null,
        "error": Value::Null,
    })
    .to_string()
}

pub fn reset() {
    *SPIKE_ENGINE.lock().unwrap() = None;
}

pub fn diagnostics() -> String {
    let state = SPIKE_ENGINE.lock().unwrap();
    let (loaded, path, load_ms) = state
        .as_ref()
        .map(|state| (true, json!(state.model_path), json!(state.load_ms)))
        .unwrap_or((false, Value::Null, Value::Null));
    json!({
        "schema_version": 1,
        "backend": "whisper.cpp",
        "model_id": MODEL_ID,
        "model_revision": MODEL_REVISION,
        "operation": "diagnostics",
        "status": "ok",
        "loaded": loaded,
        "model_path": path,
        "load_ms": load_ms,
        "sample_rate_hz": SAMPLE_RATE_HZ,
        "maximum_chunk_seconds": 30,
        "error": Value::Null,
    })
    .to_string()
}

pub fn transcribe(samples: Vec<f32>, language: Option<String>) -> String {
    if samples.is_empty() {
        return error("transcribe", "no PCM samples were provided");
    }
    if samples.len() > MAX_SAMPLE_COUNT {
        return error(
            "transcribe",
            format!(
                "chunk exceeds 30 seconds: {} samples at {SAMPLE_RATE_HZ} Hz",
                samples.len()
            ),
        );
    }

    let sample_count = samples.len();
    let audio_duration_seconds = sample_count as f64 / SAMPLE_RATE_HZ as f64;
    let mut state = SPIKE_ENGINE.lock().unwrap();
    let Some(state) = state.as_mut() else {
        return error("transcribe", "Whisper spike model is not loaded");
    };
    let started = Instant::now();
    let inference = state.engine.transcribe_samples(
        samples,
        Some(WhisperInferenceParams {
            language: language.filter(|value| !value.is_empty()),
            ..WhisperInferenceParams::default()
        }),
    );
    let inference_ms = started.elapsed().as_secs_f64() * 1_000.0;

    match inference {
        Ok(result) => {
            let segments = result
                .segments
                .unwrap_or_default()
                .into_iter()
                .map(|segment| {
                    json!({
                        "text": segment.text,
                        "start_seconds": segment.start,
                        "end_seconds": segment.end,
                    })
                })
                .collect::<Vec<_>>();
            json!({
                "schema_version": 1,
                "backend": "whisper.cpp",
                "model_id": MODEL_ID,
                "model_revision": MODEL_REVISION,
                "operation": "transcribe",
                "status": "ok",
                "sample_rate_hz": SAMPLE_RATE_HZ,
                "sample_count": sample_count,
                "audio_duration_seconds": audio_duration_seconds,
                "inference_ms": inference_ms,
                "real_time_factor": inference_ms / 1_000.0 / audio_duration_seconds,
                "detected_language": Value::Null,
                "transcript": result.text,
                "segments": segments,
                "peak_memory_bytes": Value::Null,
                "error": Value::Null,
            })
            .to_string()
        }
        Err(cause) => error("transcribe", format!("Whisper inference failed: {cause}")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_missing_model_without_downloading() {
        let result = initialize(Path::new("missing/ggml-tiny.bin"));
        let json: Value = serde_json::from_str(&result).unwrap();
        assert_eq!(json["status"], "error");
        assert!(json["error"].as_str().unwrap().contains("does not exist"));
    }

    #[test]
    fn rejects_empty_pcm_with_structured_diagnostic() {
        let result = transcribe(Vec::new(), None);
        let json: Value = serde_json::from_str(&result).unwrap();
        assert_eq!(json["status"], "error");
        assert_eq!(json["operation"], "transcribe");
    }

    #[test]
    fn rejects_chunks_longer_than_thirty_seconds() {
        let result = transcribe(vec![0.0; MAX_SAMPLE_COUNT + 1], None);
        let json: Value = serde_json::from_str(&result).unwrap();
        assert_eq!(json["status"], "error");
        assert!(json["error"]
            .as_str()
            .unwrap()
            .contains("exceeds 30 seconds"));
    }
}
