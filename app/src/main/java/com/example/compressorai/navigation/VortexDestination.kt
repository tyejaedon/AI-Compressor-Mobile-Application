package com.example.compressorai.navigation

sealed class VortexDestination(val route: String) {
    data object Dashboard : VortexDestination("dashboard")
    data object Image : VortexDestination("image")
    data object Audio : VortexDestination("audio")
    data object Video : VortexDestination("video")
    data object Benchmark : VortexDestination("benchmark")
}

