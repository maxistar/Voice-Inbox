#!/usr/bin/env bash
set -euo pipefail

if [[ -f "${HOME}/.cargo/env" ]]; then
  # Xcode launched from Finder/Dock does not inherit the interactive shell PATH.
  # Loading Cargo's env file keeps the native build phase independent of launch method.
  # shellcheck source=/dev/null
  source "${HOME}/.cargo/env"
fi
export PATH="${HOME}/.cargo/bin:${PATH}"

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
CONFIGURATION="${CONFIGURATION:-Debug}"
SDK_NAME="${SDK_NAME:-iphonesimulator}"
ARCHS="${ARCHS:-arm64}"
CURRENT_ARCH="${CURRENT_ARCH:-}"
OUT_DIR="${ROOT_DIR}/iosApp/NativeBuild/${CONFIGURATION}/${SDK_NAME}"

echo "VoiceInbox native bridge build"
echo "  ROOT_DIR=${ROOT_DIR}"
echo "  CONFIGURATION=${CONFIGURATION}"
echo "  SDK_NAME=${SDK_NAME}"
echo "  ARCHS=${ARCHS}"
echo "  CURRENT_ARCH=${CURRENT_ARCH}"
echo "  OUT_DIR=${OUT_DIR}"

if ! command -v cargo >/dev/null 2>&1; then
  echo "Cargo was not found. Install Rust or make sure ${HOME}/.cargo/bin is available to Xcode." >&2
  exit 1
fi

echo "  cargo=$(command -v cargo)"
cargo --version

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
  echo "Building Rust target ${target}"
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

echo "Wrote ${OUT_DIR}/libnotes_recognition.a"
