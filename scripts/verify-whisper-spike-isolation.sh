#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ARTIFACT="${1:-}"

cd "${ROOT_DIR}"

if [[ -n "${ARTIFACT}" ]]; then
  if [[ ! -f "${ARTIFACT}" ]]; then
    echo "Artifact does not exist: ${ARTIFACT}" >&2
    exit 2
  fi
  if command -v nm >/dev/null 2>&1 && nm -g "${ARTIFACT}" 2>/dev/null | grep -q 'voiceinbox_whisper_spike'; then
    echo "Ordinary artifact unexpectedly exports Whisper evaluation symbols: ${ARTIFACT}" >&2
    exit 1
  fi
  case "${ARTIFACT}" in
    *.app|*.ipa|*.apk|*.aab|*.zip)
      if unzip -l "${ARTIFACT}" 2>/dev/null | grep -q 'ggml-tiny\.bin'; then
        echo "Default package unexpectedly contains ggml-tiny.bin: ${ARTIFACT}" >&2
        exit 1
      fi
      ;;
  esac
fi

if rg -l --hidden --glob '!target/**' --glob '!NativeBuild/**' 'ggml-tiny\.bin' iosApp/VoiceInbox 2>/dev/null | grep -q .; then
  echo "Production iOS sources unexpectedly reference a bundled Whisper model" >&2
  exit 1
fi

echo "Build contains no Whisper evaluation exports or model weights"
