# Voice Inbox

Android application for incrementally transcribing a folder of audio recordings
into an existing plain-text (`.txt`) or Markdown (`.md`) file.

## Workflow

1. Download and verify the speech model on first launch.
2. Select an existing writable `.txt` or `.md` file.
3. Select a folder containing audio recordings.
4. Review discovered recordings in the **New** tab and choose **Transcribe all**.
5. The application processes new recordings sequentially in foreground work and
   appends one entry per successful file containing its filename, recording
   time, and recognized text.

File selection remains disabled until the model is installed and loaded. The
application preserves existing text and separates each new entry with one blank
line. Folder scanning runs when the application starts and when **Refresh** is
selected. It scans direct children only and does not traverse nested folders.

## Incremental Catalog

The application stores folder entries and processing state in a private SQLite
database. The **New** tab contains new, changed, processing, and failed files.
The **Processed** tab contains successful files. A failed file is excluded from
**Transcribe all** until its individual **Retry** action is selected.

Files are identified by provider URI and considered changed when their reported
size or modification time changes. Providers that do not expose reliable
metadata can therefore hide a content-only change. Full content hashing is not
performed. Missing files remain in private catalog history but are hidden from
both tabs.

Every successful reprocessing appends a new entry. The application never
searches, replaces, or deletes previous Markdown content because the document
may be edited independently. A process failure after an append succeeds but
before SQLite records success can make the same file eligible again and produce
a duplicate entry; generic document providers cannot make those two updates
atomic.

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

## iOS Shell

The repository includes a minimal SwiftUI iOS shell at
`iosApp/VoiceInbox.xcodeproj`. It is a KMP wiring milestone: the app launches to
a simple screen and imports the generated `Shared` Kotlin Multiplatform
framework to display speech-model metadata from shared core.

Open the project from `notes_recognition`:

```sh
open iosApp/VoiceInbox.xcodeproj
```

Or verify the shell from the command line after full Xcode is selected:

```sh
xcodebuild -project iosApp/VoiceInbox.xcodeproj -scheme VoiceInbox -configuration Debug -sdk iphonesimulator build
```

If `xcodebuild` reports that the active developer directory is
`/Library/Developer/CommandLineTools`, switch to the installed Xcode developer
directory before building the iOS shell.

The iOS target has an Xcode build phase that runs:

```sh
./gradlew :shared:embedAndSignAppleFrameworkForXcode
```

That Gradle task builds, embeds, and signs `Shared.framework` for the active
Xcode SDK/configuration. The lower-level shared compile checks remain:

```sh
./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:compileKotlinIosX64 :shared:compileKotlinIosArm64
```

This shell intentionally does not implement document picking, folder scanning,
audio playback, catalog persistence, transcription execution, settings,
scheduling, output-file writing, or Rust/iOS native bridging.

## GitHub Actions

The repository includes two GitHub Actions workflows:

- **Android CI** runs on pull requests and pushes to `main`. It builds the
  debug APK, builds the debug androidTest APK, and runs JVM unit tests.
- **Android Release** runs manually from GitHub Actions and builds signed
  release APK and AAB artifacts.

Signed release builds require these repository secrets:

- `ANDROID_KEYSTORE_BASE64`: base64-encoded release keystore file
- `ANDROID_KEYSTORE_PASSWORD`: release keystore password
- `ANDROID_KEY_ALIAS`: release key alias
- `ANDROID_KEY_PASSWORD`: release key password

The release workflow uploads signed APK and AAB artifacts. It does not publish
to Google Play or any other app store.
