# Vortex Compressor (CompressorAI)

Neural media compression app for Android built with Kotlin, Jetpack Compose, and TensorFlow Lite.

The app supports image, audio, and video compression workflows with on-device model inference, quality metrics, and output sharing.

## Highlights

- Multi-feature compression flows for image, audio, and video.
- Image compression supports tiled inference and stitch-back to preserve source resolution for fixed-size models.
- On-device TensorFlow Lite inference with delegate fallback (`GPU` -> `NNAPI` -> `CPU`).
- Dashboard model compatibility checks and metadata display.
- Batch queue processing powered by WorkManager.
- Benchmarks and latency percentile visualization.

## Module Layout

- `app`: app shell, navigation graph, dashboard, startup checks, app-level wiring.
- `core-domain`: contracts, entities, and repository interfaces.
- `core-data`: repository implementation, media/file IO, Room history, DataStore settings, WorkManager queue.
- `core-ml`: TFLite runner, tensor codecs, compression metrics, model report parsing.
- `core-ui`: shared Compose UI components and theme.
- `feature-image`: image import, compress, compare, and share flow.
- `feature-audio`: audio import, compress, evaluate, and share flow.
- `feature-video`: video import, compress, preview strip, and share flow.
- `benchmark`: benchmark UI for percentile reporting.

## Architecture (High Level)

```text
Compose UI + Navigation
  -> Feature ViewModels
    -> core-domain contracts/use-cases
      -> CompressionRepository
        -> core-data (IO, persistence, queue)
        -> core-ml (inference, codecs, metrics)
```

## Tech Stack

- Kotlin + Gradle (multi-module Android project)
- Jetpack Compose + Material 3
- AndroidX Lifecycle / Navigation / WorkManager / DataStore / Room
- TensorFlow Lite (+ GPU delegate artifact)
- Media3, Coil, CameraX
- JUnit4, MockK, Turbine, AndroidX test

## Requirements

- macOS / Linux / Windows with Android Studio (latest stable recommended)
- Android SDK configured for project levels (`minSdk 33`, `targetSdk 36`, `compileSdk 36`)
- JDK compatible with Gradle + Android plugin setup (JDK 21 recommended)

## Model Assets

The build expects these model files in app assets after sync:

- `models/image/production_model.tflite`
- `models/audio/audio_autoencoder.tflite`
- `models/video/video_autoencoder.tflite`

Related report files (`.json`, `.txt`, `.md`, `.csv`) are also parsed for dashboard metadata.

## Local Setup

1. Clone the repository.
2. Open in Android Studio.
3. Sync Gradle.
4. Ensure model source paths in `app/build.gradle.kts` are valid for your machine.

> Note: model sync reads from `-PmodelBundleDir=<bundle_root>`. If not provided, it falls back to the current local default path in `app/build.gradle.kts`.

## Build, Install, Run

```zsh
cd /Users/tyejaedon/AndroidStudioProjects/CompressorAI
./gradlew :app:assembleDebug -PmodelBundleDir=/Users/tyejaedon/PycharmProjects/AI_Compressor/models/production_bundle/best_20260702_235656
./gradlew :app:installDebug
```

Optional app launch via adb:

```zsh
adb shell am start -n com.example.compressorai/.MainActivity
```

## Test

Run all local unit tests:

```zsh
cd /Users/tyejaedon/AndroidStudioProjects/CompressorAI
./gradlew test
```

Run focused module tests:

```zsh
./gradlew :core-ml:testDebugUnitTest
./gradlew :core-data:testDebugUnitTest
./gradlew :app:testDebugUnitTest
```

Run instrumented tests (device/emulator required):

```zsh
./gradlew :app:connectedDebugAndroidTest
```

## Media Tensor Contracts

- **Image**: `[1,64,64,3]` float32, input range `[0,1]`, output same shape.
- **Audio**: `[1,256,1]` float32, input range `[-1,1]` at 8kHz, output same shape.
- **Video**: `[1,4,24,24,3]` float32, input range `[0,1]`, output same shape.

Current models behave as autoencoders (`x -> x_hat`) using a single forward pass.

## Contributing

- Keep feature logic inside `feature-*` modules.
- Keep cross-cutting contracts in `core-domain`.
- Add tests for changed data/ML/domain behavior.
- Avoid committing local absolute paths in Gradle tasks.

## License


