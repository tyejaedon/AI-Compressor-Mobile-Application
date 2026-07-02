package com.example.core.ml

import android.content.Context
import com.example.core.domain.MediaType
import com.example.core.domain.ModelMetadata
import java.io.BufferedReader

class ModelReportParser(
    private val context: Context,
) {
    fun parseAll(contracts: Map<MediaType, ModelContract>): List<ModelMetadata> {
        return contracts.map { (type, contract) ->
            val assetDir = when (type) {
                MediaType.IMAGE -> "models/image"
                MediaType.AUDIO -> "models/audio"
                MediaType.VIDEO -> "models/video"
            }
            val modelAssetName = when (type) {
                MediaType.IMAGE -> "production_model.tflite"
                MediaType.AUDIO -> "audio_autoencoder.tflite"
                MediaType.VIDEO -> "video_autoencoder.tflite"
            }

            val reports = context.assets.list(assetDir)
                ?.filter { it.endsWith(".json") || it.endsWith(".txt") || it.endsWith(".md") || it.endsWith(".csv") }
                .orEmpty()

            val summary = reports.take(3).joinToString(separator = "\n\n") { reportName ->
                val text = context.assets.open("$assetDir/$reportName").bufferedReader().use(BufferedReader::readText)
                "[$reportName]\n" + text.take(500)
            }.ifBlank { "No evaluation report found in $assetDir" }

            ModelMetadata(
                mediaType = type,
                modelAssetPath = "$assetDir/$modelAssetName",
                reportSummary = summary,
                inputShape = contract.input.shape,
                outputShape = contract.output.shape,
                inputDType = contract.input.dataType.toString(),
                outputDType = contract.output.dataType.toString(),
            )
        }
    }
}

