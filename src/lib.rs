pub mod engine;

use jni::objects::{JClass, JFloatArray, JString};
use jni::sys::{jboolean, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::path::PathBuf;
use transcribe_rs::TranscriptionEngine;

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

#[no_mangle]
pub unsafe extern "system" fn Java_me_maxistar_watchface_notesrecognition_NativeTranscriptionBridge_initialize(
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

#[no_mangle]
pub unsafe extern "system" fn Java_me_maxistar_watchface_notesrecognition_NativeTranscriptionBridge_transcribeChunkJson(
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
