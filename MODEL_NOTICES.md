# Speech model notice

Voice Inbox can download and use the following speech-recognition model. The
model files are third-party material and are not licensed under the Voice Inbox
Apache License 2.0.

## Model lineage

- Base model: [NVIDIA Parakeet TDT 0.6B v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3)
- ONNX/INT8 conversion: [`istupakov/parakeet-tdt-0.6b-v3-onnx`](https://huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx)
- Revision used by Voice Inbox: [`8f23f0c03c8761650bdb5b40aaf3e40d2c15f1ce`](https://huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx/tree/8f23f0c03c8761650bdb5b40aaf3e40d2c15f1ce)
- License: [Creative Commons Attribution 4.0 International (CC BY 4.0)](https://creativecommons.org/licenses/by/4.0/)
- Full license text: [CC BY 4.0 legal code](https://creativecommons.org/licenses/by/4.0/legalcode.en)

The upstream derivative converts the NVIDIA model to ONNX and provides INT8
quantized model files. Voice Inbox downloads the files from the pinned revision
without claiming authorship, ownership, sponsorship, or endorsement by NVIDIA
or the converter.

## Distribution boundary

The model weights are not stored in this source repository and are not bundled
in the Android or iOS application package. They are downloaded separately at
the user's request, or installed from a folder selected by the user. Voice
Inbox verifies downloaded files against the sizes and SHA-256 hashes declared
in `shared/src/commonMain/kotlin/me/maxistar/voiceinbox/core/SpeechModelManifest.kt`.

If the model identifier, pinned revision, conversion, or quantization changes,
this notice must be reviewed before publishing the affected release.
