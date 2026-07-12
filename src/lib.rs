#[cfg(any(not(target_os = "ios"), all(target_os = "ios", feature = "ios-onnx")))]
pub mod engine;

#[cfg(target_os = "ios")]
use once_cell::sync::Lazy;
#[cfg(target_os = "ios")]
use std::ffi::{CStr, CString};
#[cfg(target_os = "ios")]
use std::os::raw::{c_char, c_float};
#[cfg(all(target_os = "ios", feature = "ios-onnx"))]
use std::path::Path;
#[cfg(target_os = "ios")]
use std::sync::Mutex;

#[cfg(target_os = "android")]
use jni::objects::{JClass, JFloatArray, JString};
#[cfg(target_os = "android")]
use jni::sys::{jboolean, jstring, JNI_FALSE, JNI_TRUE};
#[cfg(target_os = "android")]
use jni::JNIEnv;
#[cfg(target_os = "android")]
use std::path::PathBuf;
#[cfg(any(not(target_os = "ios"), all(target_os = "ios", feature = "ios-onnx")))]
use transcribe_rs::TranscriptionEngine;

#[cfg(any(not(target_os = "ios"), all(target_os = "ios", feature = "ios-onnx")))]
fn serialize_chunk_result(result: transcribe_rs::TranscriptionResult) -> String {
    let words = result
        .segments
        .unwrap_or_default()
        .into_iter()
        .map(|segment| {
            serde_json::json!({
                "text": segment.text,
                "start": segment.start,
                "end": segment.end,
            })
        })
        .collect::<Vec<_>>();
    serde_json::json!({
        "text": result.text,
        "words": words,
    })
    .to_string()
}

#[cfg(target_os = "android")]
#[no_mangle]
pub unsafe extern "system" fn Java_me_maxistar_voiceinbox_NativeTranscriptionBridge_initialize(
    mut env: JNIEnv,
    _class: JClass,
    model_directory: JString,
) -> jboolean {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );
    let _ = ort::init().commit();

    let model_directory: String = match env.get_string(&model_directory) {
        Ok(path) => path.into(),
        Err(error) => {
            log::error!("Failed to read model directory from JNI: {error}");
            return JNI_FALSE;
        }
    };

    engine::configure_model_directory(PathBuf::from(model_directory));
    match engine::ensure_loaded_without_callback() {
        Ok(()) => JNI_TRUE,
        Err(error) => {
            log::error!("Failed to load speech model: {error}");
            JNI_FALSE
        }
    }
}

#[cfg(target_os = "android")]
#[no_mangle]
pub unsafe extern "system" fn Java_me_maxistar_voiceinbox_NativeTranscriptionBridge_transcribeChunkJson(
    env: JNIEnv,
    _class: JClass,
    samples: JFloatArray,
) -> jstring {
    let length = match env.get_array_length(&samples) {
        Ok(length) => length as usize,
        Err(_) => return std::ptr::null_mut(),
    };
    let mut buffer = vec![0.0f32; length];
    if env.get_float_array_region(&samples, 0, &mut buffer).is_err() {
        return std::ptr::null_mut();
    }

    let result = engine::get_engine()
        .ok_or_else(|| "Model is not loaded".to_string())
        .and_then(|engine| {
            engine
                .lock()
                .unwrap()
                .transcribe_samples(
                    buffer,
                    Some(transcribe_rs::engines::parakeet::ParakeetInferenceParams {
                        timestamp_granularity:
                            transcribe_rs::engines::parakeet::TimestampGranularity::Word,
                    }),
                )
                .map(serialize_chunk_result)
                .map_err(|error| error.to_string())
        });

    match result.and_then(|text| env.new_string(text).map_err(|error| error.to_string())) {
        Ok(text) => text.into_raw(),
        Err(error) => {
            log::error!("Chunk transcription failed: {error}");
            std::ptr::null_mut()
        }
    }
}

#[cfg(target_os = "ios")]
static IOS_LAST_ERROR: Lazy<Mutex<Option<String>>> = Lazy::new(|| Mutex::new(None));

#[cfg(target_os = "ios")]
fn set_ios_error(message: impl Into<String>) {
    *IOS_LAST_ERROR.lock().unwrap() = Some(message.into());
}

#[cfg(target_os = "ios")]
fn take_ios_error() -> String {
    IOS_LAST_ERROR
        .lock()
        .unwrap()
        .take()
        .unwrap_or_else(|| "iOS native transcription failed".to_string())
}

#[cfg(target_os = "ios")]
fn into_c_string(text: String) -> *mut c_char {
    CString::new(text)
        .unwrap_or_else(|_| CString::new("iOS native transcription returned invalid text").unwrap())
        .into_raw()
}

#[cfg(target_os = "ios")]
fn read_model_directory(model_directory: *const c_char) -> Result<String, String> {
    if model_directory.is_null() {
        return Err("Model directory was not provided".to_string());
    }

    match unsafe { CStr::from_ptr(model_directory) }.to_str() {
        Ok(path) if !path.is_empty() => Ok(path.to_string()),
        Ok(_) => Err("Model directory was empty".to_string()),
        Err(_) => Err("Model directory was not valid UTF-8".to_string()),
    }
}

#[cfg(all(target_os = "ios", feature = "ios-onnx"))]
fn validate_ios_model_directory(model_directory: &str) -> Result<(), String> {
    let path = Path::new(model_directory);
    if !path.is_dir() {
        return Err(format!("Model directory does not exist: {model_directory}"));
    }

    let missing_required = ["nemo128.onnx", "vocab.txt"]
        .into_iter()
        .filter(|file| !path.join(file).is_file())
        .collect::<Vec<_>>();
    if !missing_required.is_empty() {
        return Err(format!(
            "Model directory is missing required file(s): {}",
            missing_required.join(", ")
        ));
    }

    for (regular, quantized) in [
        ("encoder-model.onnx", "encoder-model.int8.onnx"),
        ("decoder_joint-model.onnx", "decoder_joint-model.int8.onnx"),
    ] {
        if !path.join(regular).is_file() && !path.join(quantized).is_file() {
            return Err(format!(
                "Model directory is missing {regular} or {quantized}"
            ));
        }
    }

    Ok(())
}

#[cfg(all(target_os = "ios", feature = "ios-onnx"))]
fn initialize_ios_onnx_runtime() -> Result<(), String> {
    ort::init()
        .commit()
        .map(|_| ())
        .map_err(|error| format!("Failed to initialize iOS ONNX Runtime: {error}"))
}

#[cfg(target_os = "ios")]
#[no_mangle]
pub extern "C" fn voiceinbox_transcription_backend_configured() -> bool {
    cfg!(feature = "ios-onnx")
}

#[cfg(all(target_os = "ios", feature = "ios-onnx"))]
#[no_mangle]
pub unsafe extern "C" fn voiceinbox_transcription_initialize(
    model_directory: *const c_char,
) -> bool {
    let model_directory = match read_model_directory(model_directory) {
        Ok(path) => path,
        Err(error) => {
            set_ios_error(error);
            return false;
        }
    };

    if let Err(error) = validate_ios_model_directory(&model_directory)
        .and_then(|_| initialize_ios_onnx_runtime())
        .and_then(|_| {
            engine::configure_model_directory(model_directory.into());
            engine::ensure_loaded_without_callback()
        })
    {
        set_ios_error(error);
        return false;
    }

    true
}

#[cfg(all(target_os = "ios", not(feature = "ios-onnx")))]
#[no_mangle]
pub unsafe extern "C" fn voiceinbox_transcription_initialize(
    model_directory: *const c_char,
) -> bool {
    let model_directory = match read_model_directory(model_directory) {
        Ok(path) => path,
        Err(error) => {
            set_ios_error(error);
            return false;
        }
    };

    set_ios_error(format!(
        "iOS ONNX Runtime backend is not linked yet for model directory: {model_directory}. Set VOICEINBOX_IOS_ONNX_RUNTIME_DIR or ORT_LIB_LOCATION before building the native bridge."
    ));
    false
}

#[cfg(all(target_os = "ios", feature = "ios-onnx"))]
#[no_mangle]
pub unsafe extern "C" fn voiceinbox_transcription_transcribe_chunk_json(
    samples: *const c_float,
    sample_count: usize,
) -> *mut c_char {
    if samples.is_null() || sample_count == 0 {
        set_ios_error("No PCM samples were provided");
        return std::ptr::null_mut();
    }

    let buffer = unsafe { std::slice::from_raw_parts(samples, sample_count) }.to_vec();
    let result = engine::get_engine()
        .ok_or_else(|| "Model is not loaded".to_string())
        .and_then(|engine| {
            engine
                .lock()
                .unwrap()
                .transcribe_samples(
                    buffer,
                    Some(transcribe_rs::engines::parakeet::ParakeetInferenceParams {
                        timestamp_granularity:
                            transcribe_rs::engines::parakeet::TimestampGranularity::Word,
                    }),
                )
                .map(serialize_chunk_result)
                .map_err(|error| error.to_string())
        });

    match result {
        Ok(text) => into_c_string(text),
        Err(error) => {
            set_ios_error(format!("Chunk transcription failed: {error}"));
            std::ptr::null_mut()
        }
    }
}

#[cfg(all(target_os = "ios", not(feature = "ios-onnx")))]
#[no_mangle]
pub unsafe extern "C" fn voiceinbox_transcription_transcribe_chunk_json(
    samples: *const c_float,
    sample_count: usize,
) -> *mut c_char {
    if samples.is_null() || sample_count == 0 {
        set_ios_error("No PCM samples were provided");
        return std::ptr::null_mut();
    }

    set_ios_error("iOS ONNX Runtime backend is not linked yet");
    std::ptr::null_mut()
}

#[cfg(target_os = "ios")]
#[no_mangle]
pub extern "C" fn voiceinbox_transcription_last_error() -> *mut c_char {
    into_c_string(take_ios_error())
}

#[cfg(target_os = "ios")]
#[no_mangle]
pub unsafe extern "C" fn voiceinbox_transcription_string_free(value: *mut c_char) {
    if !value.is_null() {
        drop(CString::from_raw(value));
    }
}

#[cfg(test)]
mod tests {
    use super::serialize_chunk_result;
    use transcribe_rs::{TranscriptionResult, TranscriptionSegment};

    #[test]
    fn serializes_empty_chunk_result() {
        let json = serialize_chunk_result(TranscriptionResult {
            text: String::new(),
            segments: Some(Vec::new()),
        });
        let parsed: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed["text"], "");
        assert_eq!(parsed["words"].as_array().unwrap().len(), 0);
    }

    #[test]
    fn serializes_word_boundaries() {
        let json = serialize_chunk_result(TranscriptionResult {
            text: "hello world".to_string(),
            segments: Some(vec![
                TranscriptionSegment {
                    start: 0.1,
                    end: 0.4,
                    text: "hello".to_string(),
                },
                TranscriptionSegment {
                    start: 0.5,
                    end: 0.9,
                    text: "world".to_string(),
                },
            ]),
        });
        let parsed: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed["words"][0]["text"], "hello");
        let end = parsed["words"][1]["end"].as_f64().unwrap();
        assert!((end - 0.9).abs() < 0.0001);
    }
}
