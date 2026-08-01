# Compatibility wrapper for whisper-rs-sys/cmake-rs Android cross-compilation.
set(ANDROID_USE_LEGACY_TOOLCHAIN_FILE FALSE CACHE BOOL "Use CMake's Android toolchain support" FORCE)
set(CMAKE_ANDROID_ARCH_ABI "arm64-v8a" CACHE STRING "Voice Inbox Whisper ABI" FORCE)
set(ANDROID_ABI "arm64-v8a" CACHE STRING "Voice Inbox Whisper ABI compatibility" FORCE)
set(ANDROID_PLATFORM "android-24" CACHE STRING "Voice Inbox minimum API" FORCE)

if(NOT DEFINED ENV{ANDROID_NDK_HOME})
  message(FATAL_ERROR "ANDROID_NDK_HOME is required")
endif()

include("$ENV{ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake")
