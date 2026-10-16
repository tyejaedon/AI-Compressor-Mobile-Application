# Complete Architecture Blueprint & Android Implementation Guide for AI Compressor

## 1. Executive Summary & Model Inventory

The **AI Compressor** suite contains four specialized deep compression neural networks developed in TensorFlow/Keras and designed for on-device M1/ARM execution and edge conversion via TensorFlow Lite (`.tflite`):

| Modality / Task | Model Architecture | Training Script | Inputs / Shape | Latent Bottleneck | Key Mechanisms | TFLite Compatibility |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Image Lossy** | Convolutional Autoencoder with Quantization | [`train_autoencoder_image_lossy_local.py`](file:///Users/tyejaedon/PycharmProjects/AI_Compressor/src/training/train_autoencoder_image_lossy_local.py) | `(None, H, W, 3)` (default patch 128×128 or 96×96) | `(H/4, W/4, latent_dim)` with $N$-bit discretization | Straight-Through Quantization (STE), Soft Entropy Penalty / Magnitude Rate Loss, Annealed Quant Noise | **High** (Pure Conv2D/Conv2DTranspose, Sigmoid, STE maps to standard quantized latents) |
| **Image Standard & Remaster** | High-Capacity Residual Autoencoder | [`train_autoencoder_image_local.py`](file:///Users/tyejaedon/PycharmProjects/AI_Compressor/src/training/train_autoencoder_image_local.py) | `(None, H, W, 3)` | `(H, W, latent_dim)` dense residual representation | Sobel Edge Penalty, Hard Residual Mult, Multi-stage Residual Channel skip connections, $\tanh$ residual head | **High** (Standard Conv2D, Add, Clip) |
| **Learned Upscaler** | Deep Residual Sub-Pixel CNN (ESPCN-style) | [`train_upscaler_image_local.py`](file:///Users/tyejaedon/PycharmProjects/AI_Compressor/src/training/train_upscaler_image_local.py) | `(None, H, W, 3)` | Residual Feature Space (`base_filters=32`) | Residual Blocks, PixelShuffle (`depth_to_space`), Arbitrary Tile Inference | **Very High** (`tf.nn.depth_to_space` natively supported by TFLite NNAPI/GPU) |
| **Audio Compression** | 1D Strided Conv Autoencoder | [`train_autoencoder_audio_local.py`](file:///Users/tyejaedon/PycharmProjects/AI_Compressor/src/training/train_autoencoder_audio_local.py) | `(None, 16000, 1)` (1s PCM16 Mono) | Linear Bottleneck `Dense(latent_dim)` | 4-Stage Conv1D $\rightarrow$ Flatten $\rightarrow$ Linear Latent $\rightarrow$ Conv1DTranspose. STFT Spectral Loss | **High** (Conv1D converted to Conv2D or native 1D in TFLite) |
| **Video Compression** | 3D Spatio-Temporal Autoencoder | [`train_autoencoder_video_local.py`](file:///Users/tyejaedon/PycharmProjects/AI_Compressor/src/training/train_autoencoder_video_local.py) | `(None, 8, 64, 64, 3)` (8-frame clips) | Dense Bottleneck `Dense(latent_dim)` | Conv3D Downsampling, GlobalAveragePooling3D, 3D Transposed Upsampling | **Moderate** (Requires TFLite Flex delegate or CPU fallback for Conv3D operations) |

---

## 2. End-to-End Compression & Reconstruction Mechanics

For mobile deployment, understanding how data flows from raw bytes to compressed files is critical:

```
[Original File (PNG/JPG/WAV)]
             │
             ▼
     ┌───────────────┐
     │ Preprocessing │ (Resize, Normalize [0,1], Segment into Patches/Clips)
     └───────┬───────┘
             │
             ▼
     ┌───────────────┐
     │    Encoder    │ (TFLite Subgraph: Input Image -> Quantized Latent)
     └───────┬───────┘
             │
             ▼
    [Quantized Latent Tensor] (8-bit or 6-bit discrete symbols)
             │
             ▼
     ┌───────────────┐
     │ Entropy Coder │ (ANS / Deflate / Zstandard / Static Range Coder)
     └───────┬───────┘
             │
             ▼
  [.aic / .aicimg Custom Container File]  <--- Highly Compressed File on Disk!
             │
     ════════╪═════════════════════════════════════════════════════════════
             │ (Decompression / View Phase)
             ▼
     ┌───────────────┐
     │ Entropy Dec.  │ (Bitstream -> Latent Tensor Array)
     └───────┬───────┘
             │
             ▼
     ┌───────────────┐
     │    Decoder    │ (TFLite Subgraph: Latent -> Reconstructed Patches)
     └───────┬───────┘
             │
             ▼
     ┌───────────────┐
     │ Upscaler / SR │ (Optional: TFLite 2x/4x Sub-Pixel Upscaler)
     └───────┬───────┘
             │
             ▼
 [Reconstructed Output Image / Audio WAV]
```

### Custom Container Format Specification (`.aic` - AI Compressed)
To store compressed latents and allow instant file size comparisons, the Android app reads and writes binary files with a deterministic header:

```
┌────────────────────────────────────────────────────────┐
│ Magic Bytes: "AIC1" (4 bytes)                          │
├────────────────────────────────────────────────────────┤
│ Modality Flag: 0x01=Image, 0x02=Audio, 0x03=Video (1B) │
├────────────────────────────────────────────────────────┤
│ Model Version / Architecture ID (1 byte)               │
├────────────────────────────────────────────────────────┤
│ Bit Depth: 4, 6, 8, 16 (1 byte)                        │
├────────────────────────────────────────────────────────┤
│ Compression Flags: 0x01=ZSTD, 0x02=GZIP, 0x03=Range (1B)│
├────────────────────────────────────────────────────────┤
│ Original Width / Length (4 bytes int32)                │
├────────────────────────────────────────────────────────┤
│ Original Height / Sample Rate (4 bytes int32)          │
├────────────────────────────────────────────────────────┤
│ Latent Dimensions: [H_lat, W_lat, Channels] (12 bytes) │
├────────────────────────────────────────────────────────┤
│ Payload Byte Length (4 bytes int32)                    │
├────────────────────────────────────────────────────────┤
│ Compressed Latent Payload Data (Variable length)       │
└────────────────────────────────────────────────────────┘
```

---

## 3. Python Model Export Strategy (Desktop to TFLite)

Before building the Android app, convert the trained `.keras` checkpoints into split **Encoder** and **Decoder** TFLite models. Splitting is essential because:
1. When **compressing**, you only run the **Encoder** and save the bitstream.
2. When **decompressing/viewing**, you only run the **Decoder**.

Here is the Python export script:

```python
#!/usr/bin/env python3
"""
tools/export_split_tflite.py
Splits trained autoencoders into independent Encoder and Decoder TFLite files.
"""
import os
import tensorflow as tf

def export_split_lossy_model(full_model_path, output_dir):
    os.makedirs(output_dir, exist_ok=True)
    full_model = tf.keras.models.load_model(
        full_model_path,
        compile=False
    )
    
    # 1. Extract Encoder (Input -> bottleneck_quant)
    encoder_output = full_model.get_layer("bottleneck_quant").output
    encoder = tf.keras.Model(inputs=full_model.input, outputs=encoder_output, name="image_encoder")
    
    # 2. Extract Decoder (bottleneck_quant -> Output)
    decoder_input = tf.keras.layers.Input(
        shape=encoder_output.shape[1:], 
        name="decoder_latent_input"
    )
    
    # Trace layers after rate_penalty
    x = decoder_input
    collect = False
    for layer in full_model.layers:
        if layer.name == "rate_penalty":
            collect = True
            continue
        if collect:
            x = layer(x)
            
    decoder = tf.keras.Model(inputs=decoder_input, outputs=x, name="image_decoder")
    
    # Convert to TFLite (FP16 or Dynamic Quantization)
    for model, name in [(encoder, "image_encoder"), (decoder, "image_decoder")]:
        converter = tf.lite.TFLiteConverter.from_keras_model(model)
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        converter.target_spec.supported_types = [tf.float16]
        tflite_bytes = converter.convert()
        out_path = os.path.join(output_dir, f"{name}_fp16.tflite")
        with open(out_path, "wb") as f:
            f.write(tflite_bytes)
        print(f"[+] Exported {out_path} ({len(tflite_bytes) / 1024:.1f} KB)")

if __name__ == "__main__":
    export_split_lossy_model(
        "/Users/tyejaedon/PycharmProjects/AI_Compressor/models/smoke_test_image_lossy/20261016_201550/best_model.keras",
        "/Users/tyejaedon/PycharmProjects/AI_Compressor/models/android_assets"
    )
```

---

## 4. Android Application Architecture Blueprint

### 4.1 Tech Stack
- **Language**: Kotlin 2.0+ (Coroutines + Flow)
- **UI Framework**: Jetpack Compose + Material 3 Design
- **Architecture**: MVI / Clean Architecture (Presentation $\rightarrow$ Domain $\rightarrow$ Data)
- **Inference Runtime**: TensorFlow Lite Android (`org.tensorflow:tensorflow-lite:2.16.1`, `tensorflow-lite-gpu:2.16.1`, `tensorflow-lite-support:0.4.4`)
- **Acceleration**: Android GPU Delegate (`OpenCL`/`OpenGL ES`) + NNAPI / Qualcomm QNN
- **Storage & Entropy**: Android Storage Access Framework (SAF), Native `Zstandard` / `java.util.zip.Deflater`
- **Audio Processing**: Android AudioRecord / AudioTrack with PCM16 fast resampler to 16 kHz.
- **Image Processing**: Android Jetpack `Coil` + `Bitmap` hardware buffers.

### 4.2 Module & Package Layout
```
app/
├── src/main/
│   ├── assets/
│   │   ├── models/
│   │   │   ├── image_encoder_fp16.tflite
│   │   │   ├── image_decoder_fp16.tflite
│   │   │   ├── image_upscaler_fp16.tflite
│   │   │   └── audio_autoencoder_fp16.tflite
│   ├── java/com/aicompressor/app/
│   │   ├── data/
│   │   │   ├── container/
│   │   │   │   ├── AicContainerWriter.kt       # Serializes headers + compressed latents
│   │   │   │   └── AicContainerReader.kt       # Deserializes headers + checks magic bytes
│   │   │   ├── entropy/
│   │   │   │   ├── EntropyCoder.kt            # Interface for entropy compression
│   │   │   │   └── ZstdEntropyCoder.kt        # High-ratio dictionary zstd coder
│   │   │   └── repository/
│   │   │       └── CompressionRepositoryImpl.kt
│   │   ├── domain/
│   │   │   ├── engine/
│   │   │   │   ├── ImageCompressionEngine.kt  # TFLite inference + patch slicing
│   │   │   │   ├── AudioCompressionEngine.kt  # Audio windowing + TFLite inference
│   │   │   │   └── UpscalerEngine.kt          # Super-resolution pass
│   │   │   ├── model/
│   │   │   │   ├── CompressionResult.kt       # Original size, compressed size, PSNR, time
│   │   │   │   └── CompressionSettings.kt     # Latent depth, rate lambda, quality
│   │   │   └── usecase/
│   │   │       ├── CompressFileUseCase.kt
│   │   │       └── DecompressFileUseCase.kt
│   │   └── presentation/
│   │       ├── theme/                         # Dynamic Color Material 3
│   │       ├── components/
│   │       │   ├── BeforeAfterSlider.kt       # Interactive slider comparing original vs AI recon
│   │       │   ├── FileSizeComparisonCard.kt  # Displays byte size, compression ratio %, bpp
│   │       │   └── MetricBadge.kt             # PSNR (dB), SSIM, Latency (ms)
│   │       └── compare/
│   │           ├── ComparisonScreen.kt
│   │           └── ComparisonViewModel.kt
```

---

## 5. Production Android Implementation Code

### 5.1 The Binary Container Serializer (`AicContainerWriter.kt`)
```kotlin
package com.aicompressor.app.data.container

import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.Deflater

object AicContainerWriter {
    private val MAGIC = byteArrayOf('A'.code.toByte(), 'I'.code.toByte(), 'C'.code.toByte(), '1'.code.toByte())

    enum class Modality(val code: Byte) { IMAGE(1), AUDIO(2), VIDEO(3) }
    enum class CompressionAlgo(val code: Byte) { RAW(0), DEFLATE(1), ZSTD(2) }

    fun write(
        out: OutputStream,
        modality: Modality,
        width: Int,
        height: Int,
        latentChannels: Int,
        bitDepth: Int,
        rawLatentBytes: ByteArray
    ) {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(rawLatentBytes)
        deflater.finish()
        val buffer = ByteArray(rawLatentBytes.size + 1024)
        val compressedSize = deflater.deflate(buffer)
        deflater.end()

        DataOutputStream(out).use { dos ->
            dos.write(MAGIC)
            dos.writeByte(modality.code.toInt())
            dos.writeByte(1) // Version
            dos.writeByte(bitDepth)
            dos.writeByte(CompressionAlgo.DEFLATE.code.toInt())
            dos.writeInt(width)
            dos.writeInt(height)
            dos.writeInt(latentChannels)
            dos.writeInt(compressedSize)
            dos.write(buffer, 0, compressedSize)
            dos.flush()
        }
    }
}
```

### 5.2 TFLite Engine for On-Device Image Compression (`ImageCompressionEngine.kt`)
```kotlin
package com.aicompressor.app.domain.engine

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class ImageCompressionEngine(private val context: Context) {
    private var encoder: Interpreter? = null
    private var decoder: Interpreter? = null
    private val blockSize = 128
    private val latentDim = 64

    init {
        initializeModels()
    }

    private fun initializeModels() {
        val options = Interpreter.Options().apply {
            val compatList = CompatibilityList()
            if (compatList.isDelegateSupportedOnThisDevice) {
                addDelegate(GpuDelegate(compatList.bestOptionsForThisDevice))
            } else {
                setNumThreads(4)
            }
        }
        encoder = Interpreter(loadModelFile("models/image_encoder_fp16.tflite"), options)
        decoder = Interpreter(loadModelFile("models/image_decoder_fp16.tflite"), options)
    }

    private fun loadModelFile(path: String): ByteBuffer {
        val fileDescriptor = context.assets.openFd(path)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY, 
            fileDescriptor.startOffset, 
            fileDescriptor.declaredLength
        )
    }

    fun encodeBitmap(bitmap: Bitmap): Pair<ByteArray, LatentMeta> {
        val scaled = Bitmap.createScaledBitmap(bitmap, blockSize, blockSize, true)
        val inputBuffer = ByteBuffer.allocateDirect(1 * blockSize * blockSize * 3 * 4).apply {
            order(ByteOrder.nativeOrder())
            rewind()
        }

        val pixels = IntArray(blockSize * blockSize)
        scaled.getPixels(pixels, 0, blockSize, 0, 0, blockSize, blockSize)

        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f
            inputBuffer.putFloat(r)
            inputBuffer.putFloat(g)
            inputBuffer.putFloat(b)
        }

        val latentH = blockSize / 4
        val latentW = blockSize / 4
        val outputBuffer = ByteBuffer.allocateDirect(1 * latentH * latentW * latentDim * 4).apply {
            order(ByteOrder.nativeOrder())
            rewind()
        }

        encoder?.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()

        val totalElements = latentH * latentW * latentDim
        val quantizedBytes = ByteArray(totalElements)
        for (i in 0 until totalElements) {
            val floatVal = outputBuffer.float
            val discrete = (floatVal.coerceIn(0.0f, 1.0f) * 255.0f).toInt().toByte()
            quantizedBytes[i] = discrete
        }

        return Pair(quantizedBytes, LatentMeta(bitmap.width, bitmap.height, latentH, latentW, latentDim))
    }

    fun decodeLatents(quantizedBytes: ByteArray, meta: LatentMeta): Bitmap {
        val inputBuffer = ByteBuffer.allocateDirect(1 * meta.h * meta.w * meta.channels * 4).apply {
            order(ByteOrder.nativeOrder())
            rewind()
        }

        for (b in quantizedBytes) {
            val uint8 = b.toInt() and 0xFF
            inputBuffer.putFloat(uint8 / 255.0f)
        }
        inputBuffer.rewind()

        val outputBuffer = ByteBuffer.allocateDirect(1 * blockSize * blockSize * 3 * 4).apply {
            order(ByteOrder.nativeOrder())
            rewind()
        }

        decoder?.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()

        val outBitmap = Bitmap.createBitmap(blockSize, blockSize, Bitmap.Config.ARGB_8888)
        val outPixels = IntArray(blockSize * blockSize)
        for (i in 0 until blockSize * blockSize) {
            val r = (outputBuffer.float.coerceIn(0.0f, 1.0f) * 255.0f).toInt()
            val g = (outputBuffer.float.coerceIn(0.0f, 1.0f) * 255.0f).toInt()
            val b = (outputBuffer.float.coerceIn(0.0f, 1.0f) * 255.0f).toInt()
            outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        outBitmap.setPixels(outPixels, 0, blockSize, 0, 0, blockSize, blockSize)

        return Bitmap.createScaledBitmap(outBitmap, meta.origW, meta.origH, true)
    }

    data class LatentMeta(val origW: Int, val origH: Int, val h: Int, val w: Int, val channels: Int)
}
```

### 5.3 Jetpack Compose Size Comparison & Interactive Before/After UI
```kotlin
package com.aicompressor.app.presentation.compare

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ComparisonDashboard(
    originalSizeBytes: Long,
    compressedSizeBytes: Long,
    psnrVal: Float,
    originalBitmap: android.graphics.Bitmap,
    reconstructedBitmap: android.graphics.Bitmap,
    onSaveCompressedFile: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("AI Compression Studio", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black)
        ) {
            var sliderPosition by remember { mutableFloatStateOf(0.5f) }

            androidx.compose.foundation.Image(
                bitmap = if (sliderPosition > 0.5f) originalBitmap.asImageBitmap() else reconstructedBitmap.asImageBitmap(),
                contentDescription = "Comparison View",
                modifier = Modifier.fillMaxSize()
            )

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(4.dp)
                    .offset(x = (sliderPosition * 340).dp)
                    .background(MaterialTheme.colorScheme.primary)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            sliderPosition = (sliderPosition + dragAmount.x / 1000f).coerceIn(0.05f, 0.95f)
                        }
                    }
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Storage Benchmark", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Original File", style = MaterialTheme.typography.bodySmall)
                        Text("${originalSizeBytes / 1024} KB", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("AI Compressed (.aic)", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${compressedSizeBytes / 1024} KB",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { (compressedSizeBytes.toFloat() / originalSizeBytes.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                )

                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = "Saved ${(100f * (1f - compressedSizeBytes.toFloat() / originalSizeBytes)).toInt()}% storage",
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF2E7D32)
                    )
                    Text("PSNR: ${String.format("%.1f", psnrVal)} dB", fontWeight = FontWeight.Medium)
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onSaveCompressedFile,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Save Compressed .aic File", fontSize = 16.sp)
        }
    }
}
```

---

## 6. Complete Developer Prompt to Build the Android App End-to-End

```markdown
You are an expert Senior Android Engineer and Embedded Machine Learning Specialist. Build a complete, production-grade Android application in Kotlin using Jetpack Compose and TensorFlow Lite that runs local neural compression models ported from the Python project at /Users/tyejaedon/PycharmProjects/AI_Compressor.

### App Objectives:
1. Run all deep compression models strictly on-device without internet or remote servers:
   - Image Lossy Autoencoder with Latent Quantization (`StraightThroughQuantize`)
   - Image Edge-Preserving Remaster Autoencoder
   - Learned Sub-Pixel Image Upscaler (ESPCN `PixelShuffle` 2x/4x)
   - 1D Temporal Audio Compression Autoencoder
2. Allow users to select any image from their gallery or audio file from device storage.
3. Compress the chosen media using TFLite inference into our custom `.aic` (AI Compressed) binary container format, entropy-encoding the discrete latent tensors via Zstandard / Deflate.
4. Allow users to save `.aic` files to public storage (Downloads / Documents) via Android's Storage Access Framework (SAF).
5. Open, decompress, and render `.aic` files locally using the corresponding TFLite Decoder subgraphs.
6. Display a real-time side-by-side / interactive swipe slider comparing:
   - Original file size (in bytes/KB/MB) vs. AI-compressed `.aic` file size.
   - Compression savings percentage and bits-per-pixel (bpp).
   - Visual reconstruction quality (PSNR and SSIM metrics computed in real-time).
   - Audio SNR (Signal-to-Noise Ratio) and waveform comparison for audio files.

### Technical & Architectural Specifications:
- **Language**: Kotlin 2.0+ with modern Coroutines and StateFlow.
- **UI Framework**: Modern Jetpack Compose with Material 3 dynamic color scheme, edge-to-edge support, dark/light themes.
- **Architecture**: Clean Architecture + MVI/MVVM (`data`, `domain`, `presentation`).
- **Inference**: `org.tensorflow:tensorflow-lite:2.16.1` and `org.tensorflow:tensorflow-lite-gpu:2.16.1` with graceful CPU fallback and multi-threading (`setNumThreads(4)`).
- **Binary Header Format**: Implement `AIC1` magic byte parser and serializer storing modality, original dimensions, bit depth, and compressed latent streams.
- **TFLite Assets**: Expect models located in `app/src/main/assets/models/`:
  - `image_encoder_fp16.tflite`
  - `image_decoder_fp16.tflite`
  - `image_upscaler_fp16.tflite`
  - `audio_autoencoder_fp16.tflite`

### Code Deliverables Required:
1. `build.gradle.kts` (Project and App levels) with dependencies: Compose BOM, TFLite, TFLite GPU, Navigation Compose, Coil, Coroutines, AndroidX DocumentFile.
2. `AndroidManifest.xml` with appropriate read/write and audio permissions.
3. `AicContainer.kt`: Full binary reader and writer handling header packing/unpacking and Zstandard/Deflate compression.
4. `TFLiteCompressionEngine.kt`: Manages GPU-accelerated TFLite interpreters for both image and audio modalities, including patch extraction, normalization to `[0.0, 1.0]`, and uint8 symbol quantization.
5. `MetricsCalculator.kt`: Calculates PSNR, SSIM, and audio SNR directly on Android `Bitmap` arrays and PCM16 byte arrays.
6. `ComparisonDashboardScreen.kt`: Jetpack Compose UI with draggable before/after split viewer, storage comparison cards, progress bars, and "Export .aic" action button.
7. Full ViewModel with state management (`Idle`, `Compressing(progress)`, `Success(result)`, `Error(msg)`).

Generate clean, idiomatic, copy-paste ready Kotlin code with no placeholders or unresolved imports.
```
