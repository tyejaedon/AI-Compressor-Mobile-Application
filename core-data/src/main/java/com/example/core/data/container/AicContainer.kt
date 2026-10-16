package com.example.core.data.container

import com.example.core.domain.AicCompressionAlgo
import com.example.core.domain.AicHeader
import com.example.core.domain.AicModality
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

object AicContainer {
    val MAGIC = byteArrayOf('A'.code.toByte(), 'I'.code.toByte(), 'C'.code.toByte(), '1'.code.toByte())
    const val CURRENT_VERSION: Byte = 1

    fun write(
        out: OutputStream,
        modality: AicModality,
        width: Int,
        height: Int,
        latentH: Int,
        latentW: Int,
        latentChannels: Int,
        bitDepth: Int = 8,
        algo: AicCompressionAlgo = AicCompressionAlgo.DEFLATE,
        rawLatentBytes: ByteArray,
    ) {
        val (compressedBytes, actualAlgo) = when (algo) {
            AicCompressionAlgo.DEFLATE -> {
                val deflater = Deflater(Deflater.BEST_COMPRESSION)
                deflater.setInput(rawLatentBytes)
                deflater.finish()
                val bos = ByteArrayOutputStream(rawLatentBytes.size / 2)
                val buffer = ByteArray(8192)
                while (!deflater.finished()) {
                    val count = deflater.deflate(buffer)
                    bos.write(buffer, 0, count)
                }
                deflater.end()
                bos.toByteArray() to AicCompressionAlgo.DEFLATE
            }
            AicCompressionAlgo.RAW -> rawLatentBytes to AicCompressionAlgo.RAW
            AicCompressionAlgo.ZSTD -> {
                // Fallback to Deflate if native ZSTD library is not bundled
                val deflater = Deflater(Deflater.BEST_COMPRESSION)
                deflater.setInput(rawLatentBytes)
                deflater.finish()
                val bos = ByteArrayOutputStream(rawLatentBytes.size / 2)
                val buffer = ByteArray(8192)
                while (!deflater.finished()) {
                    val count = deflater.deflate(buffer)
                    bos.write(buffer, 0, count)
                }
                deflater.end()
                bos.toByteArray() to AicCompressionAlgo.DEFLATE
            }
        }

        DataOutputStream(out).apply {
            write(MAGIC)
            writeByte(modality.code.toInt())
            writeByte(CURRENT_VERSION.toInt())
            writeByte(bitDepth)
            writeByte(actualAlgo.code.toInt())
            writeInt(width)
            writeInt(height)
            writeInt(latentH)
            writeInt(latentW)
            writeInt(latentChannels)
            writeInt(compressedBytes.size)
            write(compressedBytes)
            flush()
        }
    }

    fun readHeader(input: InputStream): AicHeader {
        val dis = DataInputStream(input)
        val magic = ByteArray(4)
        dis.readFully(magic)
        require(magic.contentEquals(MAGIC)) {
            "Invalid AIC magic header: expected 'AIC1', got '${String(magic, Charsets.US_ASCII)}'"
        }

        val modalityCode = dis.readByte()
        val version = dis.readByte()
        val bitDepth = dis.readByte()
        val algoCode = dis.readByte()
        val origW = dis.readInt()
        val origH = dis.readInt()
        val latentH = dis.readInt()
        val latentW = dis.readInt()
        val latentChannels = dis.readInt()
        val payloadLen = dis.readInt()

        return AicHeader(
            modality = AicModality.fromCode(modalityCode),
            version = version,
            bitDepth = bitDepth,
            compressionAlgo = AicCompressionAlgo.fromCode(algoCode),
            originalWidth = origW,
            originalHeight = origH,
            latentH = latentH,
            latentW = latentW,
            latentChannels = latentChannels,
            payloadByteLength = payloadLen,
        )
    }

    fun read(input: InputStream): Pair<AicHeader, ByteArray> {
        val dis = DataInputStream(input)
        val header = readHeader(dis)

        val payload = ByteArray(header.payloadByteLength)
        dis.readFully(payload)

        val uncompressedLatentBytes = when (header.compressionAlgo) {
            AicCompressionAlgo.RAW -> payload
            AicCompressionAlgo.DEFLATE -> {
                val inflater = Inflater()
                inflater.setInput(payload)
                val bos = ByteArrayOutputStream(header.latentH * header.latentW * header.latentChannels)
                val buffer = ByteArray(8192)
                while (!inflater.finished() && !inflater.needsInput()) {
                    val count = inflater.inflate(buffer)
                    if (count > 0) {
                        bos.write(buffer, 0, count)
                    }
                }
                inflater.end()
                bos.toByteArray()
            }
            AicCompressionAlgo.ZSTD -> {
                val inflater = Inflater()
                inflater.setInput(payload)
                val bos = ByteArrayOutputStream(header.latentH * header.latentW * header.latentChannels)
                val buffer = ByteArray(8192)
                while (!inflater.finished() && !inflater.needsInput()) {
                    val count = inflater.inflate(buffer)
                    if (count > 0) {
                        bos.write(buffer, 0, count)
                    }
                }
                inflater.end()
                bos.toByteArray()
            }
        }

        return header to uncompressedLatentBytes
    }
}
