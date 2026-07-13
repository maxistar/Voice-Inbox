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
DEFAULT_IOS_ONNX_RUNTIME_DIR="${ROOT_DIR}/ios-onnx-runtime"
IOS_ONNX_RUNTIME_DIR="${VOICEINBOX_IOS_ONNX_RUNTIME_DIR:-}"
if [[ -z "${IOS_ONNX_RUNTIME_DIR}" && -d "${DEFAULT_IOS_ONNX_RUNTIME_DIR}" ]]; then
  IOS_ONNX_RUNTIME_DIR="${DEFAULT_IOS_ONNX_RUNTIME_DIR}"
fi
IOS_ONNX_RUNTIME_XCFRAMEWORK=""
CARGO_FEATURE_ARGS=(--no-default-features)

echo "VoiceInbox native bridge build"
echo "  ROOT_DIR=${ROOT_DIR}"
echo "  CONFIGURATION=${CONFIGURATION}"
echo "  SDK_NAME=${SDK_NAME}"
echo "  ARCHS=${ARCHS}"
echo "  CURRENT_ARCH=${CURRENT_ARCH}"
echo "  OUT_DIR=${OUT_DIR}"

if [[ -n "${IOS_ONNX_RUNTIME_DIR}" ]]; then
  if [[ -d "${IOS_ONNX_RUNTIME_DIR}/onnxruntime.xcframework" ]]; then
    IOS_ONNX_RUNTIME_XCFRAMEWORK="${IOS_ONNX_RUNTIME_DIR}/onnxruntime.xcframework"
  elif [[ "${IOS_ONNX_RUNTIME_DIR}" == *.xcframework && -d "${IOS_ONNX_RUNTIME_DIR}" ]]; then
    IOS_ONNX_RUNTIME_XCFRAMEWORK="${IOS_ONNX_RUNTIME_DIR}"
  else
    echo "iOS ONNX Runtime artifact was configured but onnxruntime.xcframework was not found: ${IOS_ONNX_RUNTIME_DIR}" >&2
    exit 1
  fi

  CARGO_FEATURE_ARGS=(--no-default-features --features ios-onnx)
  echo "  iOS ONNX Runtime artifact=${IOS_ONNX_RUNTIME_XCFRAMEWORK}"
else
  echo "  iOS ONNX Runtime artifact not configured; building placeholder backend."
  echo "  Set VOICEINBOX_IOS_ONNX_RUNTIME_DIR or place onnxruntime.xcframework under ${DEFAULT_IOS_ONNX_RUNTIME_DIR} to enable the ios-onnx feature."
fi

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
  local cargo_env=()
  echo "Building Rust target ${target}"

  if [[ -n "${IOS_ONNX_RUNTIME_XCFRAMEWORK}" ]]; then
    local slice=""
    local arch=""
    case "${target}" in
      aarch64-apple-ios)
        slice="ios-arm64"
        arch="arm64"
        ;;
      aarch64-apple-ios-sim)
        slice="ios-arm64_x86_64-simulator"
        arch="arm64"
        ;;
      x86_64-apple-ios)
        slice="ios-arm64_x86_64-simulator"
        arch="x86_64"
        ;;
      *)
        echo "Unsupported iOS ONNX Runtime target: ${target}" >&2
        exit 1
        ;;
    esac

    local framework_binary="${IOS_ONNX_RUNTIME_XCFRAMEWORK}/${slice}/onnxruntime.framework/onnxruntime"
    if [[ ! -f "${framework_binary}" ]]; then
      echo "ONNX Runtime framework binary not found for ${target}: ${framework_binary}" >&2
      exit 1
    fi

    local link_dir="${OUT_DIR}/ort-link/${target}"
    mkdir -p "${link_dir}"
    lipo "${framework_binary}" -thin "${arch}" -output "${link_dir}/libonnxruntime.a"
    cargo_env=(env ORT_LIB_LOCATION="${link_dir}" ORT_SKIP_DOWNLOAD=1 ORT_PREFER_DYNAMIC_LINK=0)
  fi

  if [[ ${#cargo_env[@]} -gt 0 ]]; then
    if [[ -n "${PROFILE_ARG}" ]]; then
      "${cargo_env[@]}" cargo rustc --crate-type staticlib --target "${target}" "${PROFILE_ARG}" "${CARGO_FEATURE_ARGS[@]}"
    else
      "${cargo_env[@]}" cargo rustc --crate-type staticlib --target "${target}" "${CARGO_FEATURE_ARGS[@]}"
    fi
  else
    if [[ -n "${PROFILE_ARG}" ]]; then
      cargo rustc --crate-type staticlib --target "${target}" "${PROFILE_ARG}" "${CARGO_FEATURE_ARGS[@]}"
    else
      cargo rustc --crate-type staticlib --target "${target}" "${CARGO_FEATURE_ARGS[@]}"
    fi
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
