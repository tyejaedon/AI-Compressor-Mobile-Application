package com.example.core.data.io

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

class MediaIo(
    private val context: Context,
) {
    private val resolver: ContentResolver = context.contentResolver

    fun readBitmap(uri: Uri): Bitmap {
        return resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open image stream" }
            requireNotNull(BitmapFactory.decodeStream(input)) { "Unable to decode bitmap" }
        }
    }

    fun saveBitmap(bitmap: Bitmap, baseName: String): Uri {
        val file = File(context.cacheDir, "$baseName-${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }

    fun readWavMonoFloat(uri: Uri): FloatArray {
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Unable to read WAV")
        require(bytes.size > 44) { "Invalid WAV file" }
        val pcm = bytes.copyOfRange(44, bytes.size)
        val shortBuffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val out = FloatArray(shortBuffer.remaining())
        var i = 0
        while (shortBuffer.hasRemaining()) {
            out[i++] = shortBuffer.get().toFloat() / Short.MAX_VALUE.toFloat()
        }
        return out
    }

    fun writeWavMonoFloat(samples: FloatArray, baseName: String, sampleRate: Int = 16_000): Uri {
        val file = File(context.cacheDir, "$baseName-${System.currentTimeMillis()}.wav")
        val pcmBytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { value ->
            val s = (value.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            pcmBytes.putShort(s)
        }
        val data = pcmBytes.array()
        FileOutputStream(file).use { out ->
            out.write(wavHeader(data.size, sampleRate))
            out.write(data)
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }

    fun readVideoFrames(uri: Uri, targetFrames: Int = 4): List<Bitmap> {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val intervalUs = max(1L, (durationMs * 1000L) / targetFrames)
        val frames = buildList {
            for (i in 0 until targetFrames) {
                val frame = retriever.getFrameAtTime(i * intervalUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) add(frame)
            }
        }
        retriever.release()
        return frames
    }

    fun saveFramesAsPreviewStrip(frames: List<Bitmap>, baseName: String): Uri {
        val width = frames.sumOf { it.width }
        val height = frames.maxOfOrNull { it.height } ?: 1
        val strip = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(strip)
        var x = 0f
        frames.forEach { frame ->
            canvas.drawBitmap(frame, x, 0f, null)
            x += frame.width
        }
        return saveBitmap(strip, baseName)
    }

    private fun wavHeader(dataSize: Int, sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val chunkSize = 36 + dataSize

        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(chunkSize)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray())
            putInt(dataSize)
        }.array()
    }
}

