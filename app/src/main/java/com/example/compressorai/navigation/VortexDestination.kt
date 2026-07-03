package com.example.compressorai.navigation

sealed class VortexDestination(
    val route: String,
    val label: String,
    val showInBottomBar: Boolean = true,
) {
    data object Dashboard : VortexDestination(route = "dashboard", label = "Home")
    data object History : VortexDestination(route = "history", label = "History")
    data object Image : VortexDestination(route = "image", label = "Image")
    data object Audio : VortexDestination(route = "audio", label = "Audio")
    data object Video : VortexDestination(route = "video", label = "Video")
    data object Benchmark : VortexDestination(route = "benchmark", label = "Bench", showInBottomBar = false)

    companion object {
        val bottomBarDestinations = listOf(Dashboard, History, Image, Audio, Video)
    }
}

