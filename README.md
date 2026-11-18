# AndroidLLM

On-device AI chat application for Android (`com.slopware.androidllm`, minSdk 26) built with Jetpack Compose and LiteRT-LM.

[![Build & Release SlopwareAI](https://github.com/nuclearBulldog/android-llm/actions/workflows/build-apk.yml/badge.svg?event=release)](https://github.com/nuclearBulldog/android-llm/actions/workflows/build-apk.yml)

## Features

- On-device text and multimodal chat with streaming responses and collapsible thinking blocks
- Model selection via in-app HuggingFace downloads, local file import (`.litertlm`, `.tflite`, `.task`), or `/data/local/tmp/llm/`
- CPU / GPU / NPU backend selection, sampling parameters, and local chat history
- Light, dark, and system themes with accessibility options

## Build & Test

Requires JDK 21 and an Android SDK.

```bash
# Build debug APK
./gradlew assembleDebug

# Run JVM unit tests
./gradlew testDebugUnitTest
```

On Windows, replace `./gradlew` with `.\gradlew.bat`.

## Loading a Model

1. Open **Models** in the app to browse or download a compatible model from HuggingFace, or import one from device storage.
2. Or push a model file via ADB and refresh the model list:

```bash
adb shell mkdir -p /data/local/tmp/llm
adb push model.litertlm /data/local/tmp/llm/
```

## License

Licensed under the GNU General Public License v3.0. See [LICENSE](LICENSE).
