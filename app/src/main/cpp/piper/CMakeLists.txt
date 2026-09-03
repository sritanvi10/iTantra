cmake_minimum_required(VERSION 3.22.1)
project(piper_jni)

set(CMAKE_CXX_STANDARD 17)
set(PIPER_LIB_DIR ${CMAKE_SOURCE_DIR}/../../../../third_party/piper1-gpl/libpiper)
# ONNXRUNTIME_AAR_DIR must point at an extracted onnxruntime-android AAR,
# e.g. .../onnxruntime-android-1.22.0/  containing:
#   headers/onnxruntime_cxx_api.h ...
#   jni/${ANDROID_ABI}/libonnxruntime.so
#
# ESPEAK_NG_NDK_DIR must point at an espeak-ng install cross-compiled for
# Android for this ABI, containing:
#   include/espeak-ng/speak_lib.h
#   lib/${ANDROID_ABI}/libespeak-ng.a  (or .so)
#
# Both are passed from app/build.gradle.kts as CMake arguments -
# see docs/NATIVE_BUILD.md for how to obtain/build them.

add_library(piper_jni SHARED
    piper_jni.cpp
    ${PIPER_LIB_DIR}/src/piper.cpp
    ${PIPER_LIB_DIR}/src/chinese_phonemizer.cpp
)

target_include_directories(piper_jni PRIVATE
    ${PIPER_LIB_DIR}/include
    ${ONNXRUNTIME_AAR_DIR}/headers
    ${ESPEAK_NG_NDK_DIR}/include
)

find_library(LOG_LIB log)

add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES
    IMPORTED_LOCATION ${ONNXRUNTIME_AAR_DIR}/jni/${ANDROID_ABI}/libonnxruntime.so)

add_library(espeak-ng STATIC IMPORTED)
set_target_properties(espeak-ng PROPERTIES
    IMPORTED_LOCATION ${ESPEAK_NG_NDK_DIR}/lib/${ANDROID_ABI}/libespeak-ng.a)

target_link_libraries(piper_jni
    ${LOG_LIB}
    android
    onnxruntime
    espeak-ng
)

target_compile_definitions(piper_jni PRIVATE BUILDING_LIBPIPER)
