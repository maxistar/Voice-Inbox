use once_cell::sync::Lazy;
use std::path::PathBuf;
use std::sync::{Arc, Condvar, Mutex};
use transcribe_rs::engines::parakeet::{
    ParakeetEngine, ParakeetInferenceParams, ParakeetModelParams, TimestampGranularity,
};
#[cfg(all(target_os = "android", feature = "android-dual-backend"))]
use transcribe_rs::engines::whisper::WhisperEngine;
use transcribe_rs::{TranscriptionEngine, TranscriptionResult};

#[cfg(target_os = "android")]
use jni::objects::{GlobalRef, JObject};
#[cfg(target_os = "android")]
use jni::JNIEnv;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ModelConfiguration {
    pub backend: String,
    pub identity: String,
    pub directory: PathBuf,
    pub primary_file: String,
}

pub enum ActiveSpeechEngine {
    Parakeet(ParakeetEngine),
    #[cfg(all(target_os = "android", feature = "android-dual-backend"))]
    Whisper(WhisperEngine),
}

impl ActiveSpeechEngine {
    pub fn transcribe_samples(&mut self, samples: Vec<f32>) -> Result<TranscriptionResult, String> {
        match self {
            Self::Parakeet(engine) => engine
                .transcribe_samples(
                    samples,
                    Some(ParakeetInferenceParams {
                        timestamp_granularity: TimestampGranularity::Word,
                    }),
                )
                .map_err(|error| error.to_string()),
            #[cfg(all(target_os = "android", feature = "android-dual-backend"))]
            Self::Whisper(engine) => engine
                .transcribe_samples(samples, None)
                .map_err(|error| error.to_string()),
        }
    }
}

static GLOBAL_ENGINE: Lazy<Mutex<Option<Arc<Mutex<ActiveSpeechEngine>>>>> =
    Lazy::new(|| Mutex::new(None));
static CONFIGURATION: Lazy<Mutex<Option<ModelConfiguration>>> = Lazy::new(|| Mutex::new(None));
static LOAD_STATE: Lazy<(Mutex<LoadState>, Condvar)> =
    Lazy::new(|| (Mutex::new(LoadState::Idle), Condvar::new()));

#[derive(Debug, Clone, PartialEq)]
enum LoadState {
    Idle,
    Loading,
    Done,
    Failed(String),
}

pub fn get_engine() -> Option<Arc<Mutex<ActiveSpeechEngine>>> {
    GLOBAL_ENGINE.lock().unwrap().clone()
}

fn is_engine_loaded() -> bool {
    GLOBAL_ENGINE.lock().unwrap().is_some()
}

pub fn configure_model(mut configuration: ModelConfiguration) {
    configuration.directory =
        std::fs::canonicalize(&configuration.directory).unwrap_or(configuration.directory);
    if CONFIGURATION.lock().unwrap().as_ref() == Some(&configuration) {
        return;
    }
    invalidate_loaded_model();
    *CONFIGURATION.lock().unwrap() = Some(configuration);
}

#[cfg(target_os = "ios")]
pub fn configure_model_directory(path: PathBuf) {
    configure_model(ModelConfiguration {
        backend: "PARAKEET_TDT_ONNX".to_string(),
        identity: path.to_string_lossy().into_owned(),
        directory: path,
        primary_file: "encoder-model.int8.onnx".to_string(),
    });
}

pub fn invalidate_loaded_model() {
    *GLOBAL_ENGINE.lock().unwrap() = None;
    *LOAD_STATE.0.lock().unwrap() = LoadState::Idle;
    LOAD_STATE.1.notify_all();
}

pub fn ensure_loaded_without_callback() -> Result<(), String> {
    ensure_loaded_with_status(|_| {})
}

fn ensure_loaded_with_status(mut status: impl FnMut(&str)) -> Result<(), String> {
    if is_engine_loaded() {
        status("Ready");
        return Ok(());
    }
    let (lock, cvar) = &*LOAD_STATE;
    let mut state = lock.lock().unwrap();
    if *state == LoadState::Loading {
        status("Waiting for model...");
        while *state == LoadState::Loading {
            state = cvar.wait(state).unwrap();
        }
        return match &*state {
            LoadState::Done if is_engine_loaded() => Ok(()),
            LoadState::Failed(message) => Err(message.clone()),
            _ => Err("Model loading was interrupted".to_string()),
        };
    }
    *state = LoadState::Loading;
    drop(state);
    status("Loading model...");
    let result = load_configured_engine();
    let mut state = lock.lock().unwrap();
    *state = match &result {
        Ok(()) => LoadState::Done,
        Err(message) => LoadState::Failed(message.clone()),
    };
    status(if result.is_ok() { "Ready" } else { "Model loading failed" });
    cvar.notify_all();
    result
}

fn load_configured_engine() -> Result<(), String> {
    let configuration = CONFIGURATION
        .lock().unwrap().clone().ok_or_else(|| "Model was not configured".to_string())?;
    let engine = match configuration.backend.as_str() {
        "PARAKEET_TDT_ONNX" => {
            let mut engine = ParakeetEngine::new();
            engine.load_model_with_params(&configuration.directory, ParakeetModelParams::int8())
                .map_err(|error| format!("Model error: {error}"))?;
            ActiveSpeechEngine::Parakeet(engine)
        }
        "WHISPER_CPP" => {
            #[cfg(all(target_os = "android", feature = "android-dual-backend"))]
            {
                let mut engine = WhisperEngine::new();
                engine.load_model(&configuration.directory.join(&configuration.primary_file))
                    .map_err(|error| format!("Model error: {error}"))?;
                ActiveSpeechEngine::Whisper(engine)
            }
            #[cfg(not(all(target_os = "android", feature = "android-dual-backend")))]
            return Err("Whisper backend is not available on this platform".to_string());
        }
        backend => return Err(format!("Unsupported speech backend: {backend}")),
    };
    *GLOBAL_ENGINE.lock().unwrap() = Some(Arc::new(Mutex::new(engine)));
    Ok(())
}

#[cfg(target_os = "android")]
fn notify_status(env: &mut JNIEnv, obj: &JObject, msg: &str) {
    if let Ok(jmsg) = env.new_string(msg) {
        let _ = env.call_method(obj, "onStatusUpdate", "(Ljava/lang/String;)V", &[(&jmsg).into()]);
    }
}

#[cfg(target_os = "android")]
pub fn ensure_loaded(env: &mut JNIEnv, context: &JObject) -> Result<(), String> {
    ensure_loaded_with_status(|message| notify_status(env, context, message))
}

#[cfg(target_os = "android")]
pub fn ensure_loaded_from_thread(jvm: &Arc<jni::JavaVM>, target_ref: &GlobalRef) -> Result<(), String> {
    ensure_loaded_with_status(|message| {
        if let Ok(mut env) = jvm.attach_current_thread() {
            notify_status(&mut env, target_ref.as_obj(), message);
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn same_complete_configuration_preserves_state_and_key_changes_reset_it() {
        invalidate_loaded_model();
        let first = ModelConfiguration {
            backend: "PARAKEET_TDT_ONNX".into(), identity: "one".into(),
            directory: PathBuf::from("model"), primary_file: "encoder.onnx".into(),
        };
        configure_model(first.clone());
        *LOAD_STATE.0.lock().unwrap() = LoadState::Done;
        configure_model(first);
        assert_eq!(*LOAD_STATE.0.lock().unwrap(), LoadState::Done);
        configure_model(ModelConfiguration {
            backend: "WHISPER_CPP".into(), identity: "two".into(),
            directory: PathBuf::from("model"), primary_file: "ggml.bin".into(),
        });
        assert_eq!(*LOAD_STATE.0.lock().unwrap(), LoadState::Idle);
        invalidate_loaded_model();
    }
}
