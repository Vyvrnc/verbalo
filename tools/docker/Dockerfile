# Build image for Transcribro NPU: the WhisperBench image (Android SDK 35, NDK 27.3, CMake
# 3.31.6) plus the android-36 platform this app compiles against.
FROM whisperbench-build:1
RUN yes | sdkmanager --licenses >/dev/null && sdkmanager --install "platforms;android-36" >/dev/null
