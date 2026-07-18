use once_cell::sync::Lazy;
use std::path::PathBuf;
use std::sync::{Arc, Condvar, Mutex};
use transcribe_rs::engines::parakeet::ParakeetEngine;
use transcribe_rs::TranscriptionEngine;

#[cfg(target_os = "android")]
use jni::objects::{GlobalRef, JObject};
#[cfg(target_os = "android")]
use jni::JNIEnv;

static GLOBAL_ENGINE: Lazy<Mutex<Option<Arc<Mutex<ParakeetEngine>>>>> =
    Lazy::new(|| Mutex::new(None));
static MODEL_DIRECTORY: Lazy<Mutex<Option<PathBuf>>> = Lazy::new(|| Mutex::new(None));
static LOAD_STATE: Lazy<(Mutex<LoadState>, Condvar)> =
    Lazy::new(|| (Mutex::new(LoadState::Idle), Condvar::new()));

#[derive(Debug, Clone, PartialEq)]
#[allow(dead_code)]
enum LoadState {
    Idle,
    Loading,
    Done,
    Failed(String),
}

pub fn get_engine() -> Option<Arc<Mutex<ParakeetEngine>>> {
    GLOBAL_ENGINE.lock().unwrap().clone()
}

pub fn is_engine_loaded() -> bool {
    GLOBAL_ENGINE.lock().unwrap().is_some()
}

pub fn configure_model_directory(path: PathBuf) {
    let path = std::fs::canonicalize(&path).unwrap_or(path);
    let unchanged = MODEL_DIRECTORY.lock().unwrap().as_ref() == Some(&path);
    if unchanged {
        return;
    }
    invalidate_loaded_model();
    *MODEL_DIRECTORY.lock().unwrap() = Some(path);
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
    if is_engine_loaded() {
        status("Ready");
        return Ok(());
    }

    if *state == LoadState::Loading {
        status("Waiting for model...");
        while *state == LoadState::Loading {
            state = cvar.wait(state).unwrap();
        }
        return match &*state {
            LoadState::Done if is_engine_loaded() => {
                status("Ready");
                Ok(())
            }
            LoadState::Failed(message) => {
                status(&format!("Error: {message}"));
                Err(message.clone())
            }
            _ => Err("Model loading was interrupted".to_string()),
        };
    }

    *state = LoadState::Loading;
    drop(state);
    status("Loading model...");

    let result = load_configured_engine();
    let mut state = lock.lock().unwrap();
    match &result {
        Ok(()) => {
            *state = LoadState::Done;
            status("Ready");
        }
        Err(message) => {
            *state = LoadState::Failed(message.clone());
            status(&format!("Error: {message}"));
        }
    }
    cvar.notify_all();
    result
}

fn load_configured_engine() -> Result<(), String> {
    let path = MODEL_DIRECTORY
        .lock()
        .unwrap()
        .clone()
        .ok_or_else(|| "Model directory was not configured".to_string())?;
    let mut engine = ParakeetEngine::new();
    engine
        .load_model_with_params(
            &path,
            transcribe_rs::engines::parakeet::ParakeetModelParams::int8(),
        )
        .map_err(|error| format!("Model error: {error}"))?;
    *GLOBAL_ENGINE.lock().unwrap() = Some(Arc::new(Mutex::new(engine)));
    Ok(())
}

#[cfg(target_os = "android")]
fn notify_status(env: &mut JNIEnv, obj: &JObject, msg: &str) {
    if let Ok(jmsg) = env.new_string(msg) {
        let _ = env.call_method(
            obj,
            "onStatusUpdate",
            "(Ljava/lang/String;)V",
            &[(&jmsg).into()],
        );
    }
}

#[cfg(target_os = "android")]
pub fn ensure_loaded(env: &mut JNIEnv, context: &JObject) -> Result<(), String> {
    ensure_loaded_with_status(|message| notify_status(env, context, message))
}

#[cfg(target_os = "android")]
pub fn ensure_loaded_from_thread(
    jvm: &Arc<jni::JavaVM>,
    target_ref: &GlobalRef,
) -> Result<(), String> {
    ensure_loaded_with_status(|message| {
        if let Ok(mut env) = jvm.attach_current_thread() {
            notify_status(&mut env, target_ref.as_obj(), message);
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    static TEST_LOCK: Lazy<Mutex<()>> = Lazy::new(|| Mutex::new(()));

    #[test]
    fn configuring_same_directory_preserves_load_state() {
        let _guard = TEST_LOCK.lock().unwrap();
        invalidate_loaded_model();
        configure_model_directory(PathBuf::from("test-model"));
        *LOAD_STATE.0.lock().unwrap() = LoadState::Done;

        configure_model_directory(PathBuf::from("test-model"));

        assert_eq!(*LOAD_STATE.0.lock().unwrap(), LoadState::Done);
        invalidate_loaded_model();
    }

    #[test]
    fn changing_directory_and_explicit_invalidation_reset_state() {
        let _guard = TEST_LOCK.lock().unwrap();
        invalidate_loaded_model();
        configure_model_directory(PathBuf::from("first-model"));
        *LOAD_STATE.0.lock().unwrap() = LoadState::Done;

        configure_model_directory(PathBuf::from("second-model"));
        assert_eq!(*LOAD_STATE.0.lock().unwrap(), LoadState::Idle);

        *LOAD_STATE.0.lock().unwrap() = LoadState::Failed("failed".to_string());
        invalidate_loaded_model();
        assert_eq!(*LOAD_STATE.0.lock().unwrap(), LoadState::Idle);
    }
}
