# Notes Recognition

Android application for offline transcription of existing audio recordings into
an existing plain-text (`.txt`) or Markdown (`.md`) file.

## Workflow

1. Download and verify the speech model on first launch.
2. Select an existing writable `.txt` or `.md` file.
3. Select an audio file.
4. The application transcribes the recording in foreground work and appends one
   entry containing the audio filename, recording time, and recognized text.

File selection remains disabled until the model is installed and loaded. The
application preserves existing text and separates each new entry with one blank
line.

## Supported Audio

The application uses Android platform decoders and adds no third-party media
decoder. The tested input set is:

- 8-bit and 16-bit linear PCM WAV
- AAC-LC and HE-AAC in M4A/MP4
- MP3
- ADTS AAC
- mono or stereo FLAC
- Ogg Vorbis
- Ogg Opus
- AMR-NB and AMR-WB

Decoded audio is downmixed and resampled to 16 kHz mono before recognition.
Encrypted, DRM-protected, malformed, and unsupported multichannel inputs are
rejected.

## Model And Device Requirements

- ARM64 Android device
- Android 7.0 (API 24) or later
- About 704 MiB free for first model installation
- Network access for the first model download

The pinned int8 Parakeet model download is about 640 MiB. After verification it
is stored in the application's private no-backup directory. Recognition runs
locally after installation.

## Build

Requirements:

- Android SDK and NDK `30.0.14904198`
- Rust toolchain
- `cargo-ndk`

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

The Gradle build extracts ONNX Runtime, builds the Rust JNI library for
`arm64-v8a`, and packages the required native libraries.
