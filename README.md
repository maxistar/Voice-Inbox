# Voice Inbox

Local-first Android and iOS application for turning imported, shared, or
folder-discovered audio recordings into a plain-text (`.txt`) or Markdown
(`.md`) inbox.

**Website:** [voiceinbox.simpleditor.org](https://voiceinbox.simpleditor.org/)

**Project status:** The Android application is currently available through
Google Play closed testing. It is not a public production release yet. Eligible
testers can follow the
[closed-testing instructions](https://voiceinbox.simpleditor.org/testing/) to
join the tester group, opt in, and install the application. The iOS application
remains an active development MVP and is not available through the App Store.

## Workflow

1. Download and verify the speech model on first launch.
2. Select an existing writable `.txt` or `.md` file.
3. Select a folder containing audio recordings.
4. Review discovered recordings in the **New** tab and choose **Transcribe all**, or
   use the startup-processing policy when the app opens.
5. The application processes new recordings sequentially in foreground work and
   appends one entry per successful file containing its filename, recording
   time, and recognized text.

File selection remains disabled until the model is installed and loaded. The
application preserves existing text and separates each new entry with one blank
line. Folder scanning runs when the application starts and when **Refresh** is
selected. It scans direct children only and does not traverse nested folders.

## Automatic Processing

Android Settings provides two independent automation options:

- **Startup processing** runs after the app opens, restores its selections, and
  finishes scanning the audio folder. It defaults to asking before processing;
  it can instead transcribe automatically or leave files queued.
- **Nightly transcription** uses Android background work around the selected
  time. Android may delay this work to save battery, so exact timing is not
  guaranteed.

Both options reuse the same batch transcription worker and exclude failed files,
which remain available through their individual **Retry** actions.

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

## License

Unless otherwise noted, original Voice Inbox source code is licensed under the
[Apache License 2.0](LICENSE). Third-party components and speech-model weights
retain their own licenses and are not relicensed as Voice Inbox source code.

- [Third-party software notices](THIRD_PARTY_NOTICES.md)
- [Speech model attribution and CC BY 4.0 terms](MODEL_NOTICES.md)

The speech-model weights are downloaded separately and are not included in this
source repository or in the Android and iOS application packages.

## iOS Application

The repository includes a SwiftUI iOS application at
`iosApp/VoiceInbox.xcodeproj`. It uses the generated `Shared` Kotlin
Multiplatform framework for the shared catalog, transcription rules, and speech
model metadata, and links the Rust transcription engine through the native iOS
bridge.

The current iOS application supports:

- selecting and scanning an audio inbox folder
- importing individual audio files and receiving files through the share
  extension
- selecting an existing transcript output file
- downloading or manually installing the pinned speech model
- previewing audio and transcribing or retrying individual files
- processing queued files when the application starts, with **Ask**, **Yes**, and
  **No** startup policies
- viewing processed transcripts and persisting catalog state
- changing storage and startup-processing preferences from Settings

Both applications provide configurable startup processing. Unlike Android, iOS
does not currently schedule nightly background transcription. Its automatic
processing is evaluated when the application starts or becomes active.

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

The iOS and Android applications share workflow rules and catalog behavior, but
their platform integrations remain native: SwiftUI and Apple document pickers on
iOS, and Android views, the Storage Access Framework, and WorkManager on Android.

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
