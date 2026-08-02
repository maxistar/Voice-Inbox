#!/usr/bin/env bash
set -euo pipefail

EXPECTED_NAME="ggml-tiny.bin"
EXPECTED_SIZE="77691713"
EXPECTED_SHA256="be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21"
MODEL_PATH="${1:-}"

if [[ -z "${MODEL_PATH}" ]]; then
  echo "Usage: $0 /absolute/path/to/${EXPECTED_NAME}" >&2
  exit 2
fi
if [[ ! -f "${MODEL_PATH}" ]]; then
  echo "Model file does not exist: ${MODEL_PATH}" >&2
  exit 1
fi
if [[ "$(basename "${MODEL_PATH}")" != "${EXPECTED_NAME}" ]]; then
  echo "Expected filename ${EXPECTED_NAME}, got $(basename "${MODEL_PATH}")" >&2
  exit 1
fi

ACTUAL_SIZE="$(wc -c < "${MODEL_PATH}" | tr -d ' ')"
if [[ "${ACTUAL_SIZE}" != "${EXPECTED_SIZE}" ]]; then
  echo "Size mismatch: expected ${EXPECTED_SIZE}, got ${ACTUAL_SIZE}" >&2
  exit 1
fi

if command -v shasum >/dev/null 2>&1; then
  ACTUAL_SHA256="$(shasum -a 256 "${MODEL_PATH}" | awk '{print $1}')"
elif command -v sha256sum >/dev/null 2>&1; then
  ACTUAL_SHA256="$(sha256sum "${MODEL_PATH}" | awk '{print $1}')"
else
  echo "Neither shasum nor sha256sum is available" >&2
  exit 2
fi

if [[ "${ACTUAL_SHA256}" != "${EXPECTED_SHA256}" ]]; then
  echo "SHA-256 mismatch: expected ${EXPECTED_SHA256}, got ${ACTUAL_SHA256}" >&2
  exit 1
fi

echo "Validated ${MODEL_PATH} (${ACTUAL_SIZE} bytes, SHA-256 ${ACTUAL_SHA256})"
