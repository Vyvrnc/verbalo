# libnpuwhisper.so: Whisper large-v3-turbo on the Hexagon NPU (QNN HTP) + its JNI.
# QNN itself is not linked: libQnnHtp.so / libQnnSystem.so / libcdsprpc.so are dlopen'ed
# at runtime, so only the QNN C API headers are needed (tools/fetch-qnn-headers.sh).

cmake_minimum_required(VERSION 3.22)
project(npuwhisper CXX)

add_library(npuwhisper SHARED npu_whisper.cpp whisper_mel.cpp)
target_compile_features(npuwhisper PRIVATE cxx_std_17)
# fp16 conversions use _Float16; Oryon (SM8750) has the ARMv8.2 FP16 extension.
target_compile_options(npuwhisper PRIVATE -O3 -march=armv8.2-a+fp16)
target_link_libraries(npuwhisper PRIVATE log)

if (DEFINED ENV{QNN_INCLUDE_DIR} AND EXISTS "$ENV{QNN_INCLUDE_DIR}/QnnInterface.h")
    target_include_directories(npuwhisper PRIVATE $ENV{QNN_INCLUDE_DIR})
    target_compile_definitions(npuwhisper PRIVATE WB_HAVE_QNN)
    message(STATUS "npuwhisper: QNN headers in $ENV{QNN_INCLUDE_DIR}")
else()
    message(FATAL_ERROR "npuwhisper: QNN headers not found (QNN_INCLUDE_DIR), run tools/build-apk.sh")
endif()
