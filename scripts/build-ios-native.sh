#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
CONFIGURATION="${CONFIGURATION:-Debug}"
SDK_NAME="${SDK_NAME:-iphonesimulator}"
ARCHS="${ARCHS:-arm64}"
CURRENT_ARCH="${CURRENT_ARCH:-}"
OUT_DIR="${ROOT_DIR}/iosApp/NativeBuild/${CONFIGURATION}/${SDK_NAME}"

case "${SDK_NAME}" in
  iphoneos*)
    RUST_TARGET="aarch64-apple-ios"
    ;;
  iphonesimulator*)
    RUST_TARGET="simulator-universal"
    ;;
  *)
    echo "Unsupported SDK_NAME: ${SDK_NAME}" >&2
    exit 1
    ;;
esac

PROFILE_DIR="debug"
PROFILE_ARG=""
if [[ "${CONFIGURATION}" == "Release" ]]; then
  PROFILE_DIR="release"
  PROFILE_ARG="--release"
fi

mkdir -p "${OUT_DIR}"
cd "${ROOT_DIR}"

build_target() {
  local target="$1"
  if [[ -n "${PROFILE_ARG}" ]]; then
    cargo build --target "${target}" "${PROFILE_ARG}"
  else
    cargo build --target "${target}"
  fi
}

if [[ "${RUST_TARGET}" == "simulator-universal" ]]; then
  build_target "aarch64-apple-ios-sim"
  build_target "x86_64-apple-ios"
  lipo -create \
    "${ROOT_DIR}/target/aarch64-apple-ios-sim/${PROFILE_DIR}/libnotes_recognition.a" \
    "${ROOT_DIR}/target/x86_64-apple-ios/${PROFILE_DIR}/libnotes_recognition.a" \
    -output "${OUT_DIR}/libnotes_recognition.a"
else
  build_target "${RUST_TARGET}"
  cp "${ROOT_DIR}/target/${RUST_TARGET}/${PROFILE_DIR}/libnotes_recognition.a" \
    "${OUT_DIR}/libnotes_recognition.a"
fi
