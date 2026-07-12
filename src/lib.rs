#[cfg(not(target_os = "ios"))]
pub mod engine;

#[cfg(target_os = "ios")]
use once_cell::sync::Lazy;
#[cfg(target_os = "ios")]
use std::ffi::{CStr, CString};
#[cfg(target_os = "ios")]
use std::os::raw::{c_char, c_float};
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
#[cfg(not(target_os = "ios"))]
use transcribe_rs::TranscriptionEngine;

#[cfg(not(target_os = "ios"))]
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
#[no_mangle]
pub unsafe extern "C" fn voiceinbox_transcription_initialize(
    model_directory: *const c_char,
) -> bool {
    if model_directory.is_null() {
        set_ios_error("Model directory was not provided");
        return false;
    }

    let model_directory = match CStr::from_ptr(model_directory).to_str() {
        Ok(path) if !path.is_empty() => path,
        Ok(_) => {
            set_ios_error("Model directory was empty");
            return false;
        }
        Err(_) => {
            set_ios_error("Model directory was not valid UTF-8");
            return false;
        }
    };

    set_ios_error(format!(
        "iOS ONNX Runtime backend is not linked yet for model directory: {model_directory}"
    ));
    false
}

#[cfg(target_os = "ios")]
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
