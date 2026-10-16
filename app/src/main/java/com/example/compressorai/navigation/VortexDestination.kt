package com.example.compressorai.navigation

sealed class VortexDestination(
    val route: String,
    val label: String,
    val showInBottomBar: Boolean = true,
) {
    data object Dashboard : VortexDestination(route = "dashboard", label = "Home")
    data object Image : VortexDestination(route = "image", label = "Image")
    data object Audio : VortexDestination(route = "audio", label = "Audio")
    data object AicStudio : VortexDestination(route = "aic_studio", label = "AIC Studio")
    data object Benchmark : VortexDestination(route = "benchmark", label = "Benchmark")
    data object History : VortexDestination(route = "history", label = "History", showInBottomBar = false)
    data object Video : VortexDestination(route = "video", label = "Video", showInBottomBar = false)

    companion object {
        val bottomBarDestinations = listOf(Dashboard, Image, Audio, AicStudio, Benchmark)
    }
}
