#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ARTIFACT="${1:-}"

cd "${ROOT_DIR}"

if [[ -n "${ARTIFACT}" ]]; then
  if [[ ! -e "${ARTIFACT}" ]]; then
    echo "Artifact does not exist: ${ARTIFACT}" >&2
    exit 2
  fi
  SYMBOL_ARTIFACT="${ARTIFACT}"
  if [[ -d "${ARTIFACT}" && -f "${ARTIFACT}/VoiceInbox" ]]; then
    SYMBOL_ARTIFACT="${ARTIFACT}/VoiceInbox"
  fi
  if command -v nm >/dev/null 2>&1 && nm -g "${SYMBOL_ARTIFACT}" 2>/dev/null | grep -q 'voiceinbox_whisper_spike'; then
    echo "Ordinary artifact unexpectedly exports Whisper evaluation symbols: ${ARTIFACT}" >&2
    exit 1
  fi
  case "${ARTIFACT}" in
    *.app)
      if find "${ARTIFACT}" -type f \( -name 'ggml-tiny.bin' -o -name '*.pcm' -o -name 'encoder-model*.onnx' \) | grep -q .; then
        echo "Application bundle unexpectedly contains model weights or test PCM: ${ARTIFACT}" >&2
        exit 1
      fi
      ;;
    *.ipa|*.apk|*.aab|*.zip)
      if unzip -l "${ARTIFACT}" 2>/dev/null | grep -q 'ggml-tiny\.bin'; then
        echo "Default package unexpectedly contains ggml-tiny.bin: ${ARTIFACT}" >&2
        exit 1
      fi
      ;;
  esac
fi

echo "Build contains no Whisper evaluation exports or model weights"
