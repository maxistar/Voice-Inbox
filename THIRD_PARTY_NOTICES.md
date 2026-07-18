# Third-party notices

Voice Inbox contains or links against third-party software. Those components
remain under their own licenses and are not relicensed under the Voice Inbox
Apache License 2.0. Copyright belongs to the respective authors and contributors
identified by each upstream project.

This inventory was reviewed against the Android `releaseRuntimeClasspath`, the
Kotlin Multiplatform source sets, the iOS link configuration, Cargo's locked
Android and iOS dependency graphs, and the static website output. Build-only
tools such as Gradle plugins, the Rust compiler, and Astro/Node packages are not
delivered in the application or static website and are intentionally excluded.

## Application and native components

| Component | Version used | License and attribution | Used by |
| --- | --- | --- | --- |
| AndroidX libraries | Resolved by `gradle/libs.versions.toml` and `releaseRuntimeClasspath` | Apache-2.0; Copyright The Android Open Source Project and contributors | Android |
| Material Components for Android | 1.13.0 | Apache-2.0; Copyright Google LLC and contributors | Android |
| Kotlin standard library, Kotlin/Native runtime, kotlinx coroutines and serialization | Resolved Kotlin runtime versions | Apache-2.0; Copyright JetBrains and Kotlin contributors | Android and iOS |
| SQLDelight runtime and Android/native drivers | 2.1.0 | Apache-2.0; Copyright 2016 Square, Inc. and contributors | Android and iOS |
| SQLite | Platform/native driver version | Public domain; see the [SQLite copyright page](https://www.sqlite.org/copyright.html) | Android and iOS |
| OkHttp | 4.12.0 | Apache-2.0; Copyright 2019 Square, Inc. and contributors | Android |
| Okio | 3.6.0 | Apache-2.0; Copyright Square, Inc. and contributors | Android |
| ONNX Runtime | 1.22.0 | MIT; Copyright Microsoft Corporation and contributors. See the [ONNX Runtime license](https://github.com/microsoft/onnxruntime/blob/v1.22.0/LICENSE) and [version-specific third-party notices](https://github.com/microsoft/onnxruntime/blob/v1.22.0/ThirdPartyNotices.txt). | Android and iOS |
| `ort` and `ort-sys` Rust crates | 2.0.0-rc.10 | MIT OR Apache-2.0; Copyright the `ort` contributors | Android and iOS native bridge |
| Android NDK `libc++_shared.so` | NDK r30 beta 1 (`30.0.14904198`) | Apache-2.0 WITH LLVM-exception; Copyright the LLVM Project contributors. See the [LLVM license](https://github.com/llvm/llvm-project/blob/main/LICENSE.TXT). | Android |
| `transcribe-rs` | 0.1.4, vendored | MIT; Copyright (c) 2025 Ilya Stupakov. The complete permission and warranty text is retained in [`transcribe-rs/LICENSE`](transcribe-rs/LICENSE). | Android and iOS native bridge |

The complete Apache License 2.0 text is available in [`LICENSE`](LICENSE).
ONNX Runtime carries its own MIT license and upstream notices; its inclusion
does not place ONNX Runtime under the Voice Inbox project license.

## Rust dependency inventory

The following packages are statically linked into, or reachable from, the
locked native dependency graphs used for Android and iOS. Versions and declared
license expressions come from Cargo package metadata for `Cargo.lock`. A slash
in older metadata is treated as the package author's dual-license declaration,
not as a new project license.

### `(MIT OR Apache-2.0) AND Unicode-3.0`

`unicode-ident 1.0.22`

### `Apache-2.0`

`hound 3.5.1`, `sync_wrapper 1.0.2`

### `Apache-2.0 / MIT`

`fnv 1.0.7`

### `Apache-2.0 AND ISC`

`ring 0.17.14`

### `Apache-2.0 OR BSL-1.0`

`ryu 1.0.20`

### `Apache-2.0 OR ISC OR MIT`

`hyper-rustls 0.27.7`, `rustls 0.23.35`, `rustls-native-certs 0.8.2`

### `Apache-2.0 OR MIT`

`atomic-waker 1.1.2`, `idna_adapter 1.2.1`, `pin-project-lite 0.2.16`,
`secrecy 0.10.3`, `utf8_iter 1.0.4`, `zeroize 1.8.2`

### `Apache-2.0/MIT`

`cesu8 1.1.0`

### `BSD-2-Clause OR Apache-2.0 OR MIT`

`zerocopy 0.8.28`

### `BSD-3-Clause`

`instant 0.1.13`, `subtle 2.6.1`

### `ISC`

`libloading 0.8.9`, `rustls-webpki 0.103.8`, `untrusted 0.9.0`

### `MIT`

`async-openai 0.29.3`, `async-openai-macros 0.1.0`, `bytes 1.11.0`,
`combine 4.6.7`, `darling 0.20.11`, `darling_core 0.20.11`,
`darling_macro 0.20.11`, `http-body 1.0.1`, `http-body-util 0.1.3`,
`hyper 1.8.1`, `hyper-util 0.1.18`, `is-terminal 0.4.17`,
`mime_guess 2.0.5`, `mio 1.1.0`, `nom 7.1.3`, `slab 0.4.11`,
`strsim 0.11.1`, `synstructure 0.13.2`, `tokio 1.47.1`,
`tokio-macros 2.5.0`, `tokio-stream 0.1.17`, `tokio-util 0.7.17`,
`tower 0.5.2`, `tower-http 0.6.6`, `tower-layer 0.3.3`,
`tower-service 0.3.3`, `tracing 0.1.41`, `tracing-attributes 0.1.30`,
`tracing-core 0.1.34`, `try-lock 0.2.5`, `want 0.3.1`

### `MIT OR Apache-2.0`

`android_log-sys 0.3.2`, `android_logger 0.13.3`, `async-trait 0.1.89`,
`base64 0.22.1`, `bitflags 2.10.0`, `cfg-if 1.0.4`,
`derive_builder 0.20.2`, `derive_builder_core 0.20.2`,
`derive_builder_macro 0.20.2`, `displaydoc 0.2.5`, `env_logger 0.10.0`,
`eventsource-stream 0.2.3`, `form_urlencoded 1.2.2`, `futures 0.3.31`,
`futures-channel 0.3.31`, `futures-core 0.3.31`,
`futures-executor 0.3.31`, `futures-io 0.3.31`, `futures-macro 0.3.31`,
`futures-sink 0.3.31`, `futures-task 0.3.31`, `futures-util 0.3.31`,
`getrandom 0.2.16`, `getrandom 0.3.4`, `http 1.3.1`, `httparse 1.10.1`,
`humantime 2.3.0`, `idna 1.1.0`, `ipnet 2.11.0`, `iri-string 0.7.9`,
`itoa 1.0.15`, `libc 0.2.177`, `log 0.4.28`, `mime 0.3.17`,
`ndarray 0.16.1`, `num-complex 0.4.6`, `num-integer 0.1.46`,
`num-traits 0.2.19`, `once_cell 1.21.3`, `ort 2.0.0-rc.10`,
`ort-sys 2.0.0-rc.10`, `percent-encoding 2.3.2`, `pin-utils 0.1.0`,
`ppv-lite86 0.2.21`, `proc-macro2 1.0.103`, `quote 1.0.42`,
`rand 0.8.5`, `rand 0.9.2`, `rand_chacha 0.3.1`, `rand_chacha 0.9.0`,
`rand_core 0.6.4`, `rand_core 0.9.3`, `regex 1.11.2`,
`regex-automata 0.4.13`, `regex-syntax 0.8.8`, `reqwest 0.12.24`,
`reqwest-eventsource 0.6.0`, `rustls-pki-types 1.13.0`, `serde 1.0.228`,
`serde_core 1.0.228`, `serde_derive 1.0.228`, `serde_json 1.0.145`,
`smallvec 1.15.1`, `smallvec 2.0.0-alpha.10`, `socket2 0.6.1`,
`stable_deref_trait 1.2.1`, `syn 2.0.110`, `thiserror 1.0.69`,
`thiserror 2.0.16`, `thiserror-impl 1.0.69`, `thiserror-impl 2.0.16`,
`tokio-rustls 0.26.4`, `unicase 2.8.1`, `url 2.5.7`

### `MIT/Apache-2.0`

`backoff 0.4.0`, `futures-timer 3.0.3`, `ident_case 1.0.1`,
`jni 0.21.1`, `jni-sys 0.3.0`, `matrixmultiply 0.3.10`,
`minimal-lexical 0.2.1`, `openssl-probe 0.1.6`, `rawpointer 0.2.1`,
`serde_urlencoded 0.7.1`

### `Unicode-3.0`

`icu_collections 2.1.1`, `icu_locale_core 2.1.1`,
`icu_normalizer 2.1.1`, `icu_normalizer_data 2.1.1`,
`icu_properties 2.1.1`, `icu_properties_data 2.1.1`,
`icu_provider 2.1.1`, `litemap 0.8.1`, `potential_utf 0.1.4`,
`tinystr 0.8.2`, `writeable 0.6.2`, `yoke 0.8.1`, `yoke-derive 0.8.1`,
`zerofrom 0.1.6`, `zerofrom-derive 0.1.6`, `zerotrie 0.2.3`,
`zerovec 0.11.5`, `zerovec-derive 0.11.2`

### `Unlicense OR MIT`

`aho-corasick 1.1.4`, `memchr 2.7.6`, `termcolor 1.4.1`

Canonical license texts can be found through the SPDX license list:

- [Apache-2.0](https://spdx.org/licenses/Apache-2.0.html)
- [MIT](https://spdx.org/licenses/MIT.html)
- [BSD-2-Clause](https://spdx.org/licenses/BSD-2-Clause.html)
- [BSD-3-Clause](https://spdx.org/licenses/BSD-3-Clause.html)
- [ISC](https://spdx.org/licenses/ISC.html)
- [BSL-1.0](https://spdx.org/licenses/BSL-1.0.html)
- [Unicode-3.0](https://spdx.org/licenses/Unicode-3.0.html)
- [Unlicense](https://spdx.org/licenses/Unlicense.html)

There were no missing license declarations or copyleft/source-offer licenses in
the reviewed locked Android and iOS Rust graphs. This inventory must be
regenerated and reviewed when `Cargo.lock`, Gradle runtime dependencies, native
frameworks, or application packaging changes.
