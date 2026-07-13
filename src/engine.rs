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
    *MODEL_DIRECTORY.lock().unwrap() = Some(path);
    *GLOBAL_ENGINE.lock().unwrap() = None;
    *LOAD_STATE.0.lock().unwrap() = LoadState::Idle;
}

pub fn ensure_loaded_without_callback() -> Result<(), String> {
    if is_engine_loaded() {
        return Ok(());
    }

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
    *LOAD_STATE.0.lock().unwrap() = LoadState::Done;
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
    if is_engine_loaded() {
        notify_status(env, context, "Ready");
        return Ok(());
    }

    let (lock, cvar) = &*LOAD_STATE;
    let mut state = lock.lock().unwrap();

    if is_engine_loaded() {
        notify_status(env, context, "Ready");
        return Ok(());
    }

    match &*state {
        LoadState::Loading => {
            notify_status(env, context, "Waiting for model...");
            while *state == LoadState::Loading {
                state = cvar.wait(state).unwrap();
            }
            drop(state);

            if is_engine_loaded() {
                notify_status(env, context, "Ready");
                Ok(())
            } else {
                let msg = "Model failed to load".to_string();
                notify_status(env, context, &format!("Error: {}", msg));
                Err(msg)
            }
        }
        LoadState::Done => {
            notify_status(env, context, "Ready");
            Ok(())
        }
        LoadState::Idle | LoadState::Failed(_) => {
            *state = LoadState::Loading;
            drop(state);

            let result = do_load(env, context);

            let mut state = lock.lock().unwrap();
            match &result {
                Ok(()) => *state = LoadState::Done,
                Err(msg) => *state = LoadState::Failed(msg.clone()),
            }
            cvar.notify_all();
            result
        }
    }
}

#[cfg(target_os = "android")]
pub fn ensure_loaded_from_thread(
    jvm: &Arc<jni::JavaVM>,
    target_ref: &GlobalRef,
) -> Result<(), String> {
    if is_engine_loaded() {
        if let Ok(mut env) = jvm.attach_current_thread() {
            notify_status(&mut env, target_ref.as_obj(), "Ready");
        }
        return Ok(());
    }

    let (lock, cvar) = &*LOAD_STATE;
    let mut state = lock.lock().unwrap();

    if is_engine_loaded() {
        if let Ok(mut env) = jvm.attach_current_thread() {
            notify_status(&mut env, target_ref.as_obj(), "Ready");
        }
        return Ok(());
    }

    match &*state {
        LoadState::Loading => {
            if let Ok(mut env) = jvm.attach_current_thread() {
                notify_status(&mut env, target_ref.as_obj(), "Waiting for model...");
            }
            while *state == LoadState::Loading {
                state = cvar.wait(state).unwrap();
            }
            drop(state);

            if is_engine_loaded() {
                if let Ok(mut env) = jvm.attach_current_thread() {
                    notify_status(&mut env, target_ref.as_obj(), "Ready");
                }
                Ok(())
            } else {
                let msg = "Model failed to load".to_string();
                if let Ok(mut env) = jvm.attach_current_thread() {
                    notify_status(&mut env, target_ref.as_obj(), &format!("Error: {}", msg));
                }
                Err(msg)
            }
        }
        LoadState::Done => {
            if let Ok(mut env) = jvm.attach_current_thread() {
                notify_status(&mut env, target_ref.as_obj(), "Ready");
            }
            Ok(())
        }
        LoadState::Idle | LoadState::Failed(_) => {
            *state = LoadState::Loading;
            drop(state);

            let result = if let Ok(mut env) = jvm.attach_current_thread() {
                let obj = target_ref.as_obj();
                do_load(&mut env, obj)
            } else {
                Err("Failed to attach JNI thread".to_string())
            };

            let mut state = lock.lock().unwrap();
            match &result {
                Ok(()) => *state = LoadState::Done,
                Err(msg) => *state = LoadState::Failed(msg.clone()),
            }
            cvar.notify_all();
            result
        }
    }
}

#[cfg(target_os = "android")]
fn do_load(env: &mut JNIEnv, context: &JObject) -> Result<(), String> {
    let path = MODEL_DIRECTORY
        .lock()
        .unwrap()
        .clone()
        .ok_or_else(|| "Model directory was not configured".to_string())?;

    notify_status(env, context, "Loading model...");

    let mut eng = ParakeetEngine::new();
    match eng.load_model_with_params(
        &path,
        transcribe_rs::engines::parakeet::ParakeetModelParams::int8(),
    ) {
        Ok(_) => {
            *GLOBAL_ENGINE.lock().unwrap() = Some(Arc::new(Mutex::new(eng)));
            notify_status(env, context, "Ready");
            Ok(())
        }
        Err(e) => {
            let msg = format!("Model error: {}", e);
            notify_status(env, context, &format!("Error: {}", msg));
            Err(msg)
        }
    }
}
