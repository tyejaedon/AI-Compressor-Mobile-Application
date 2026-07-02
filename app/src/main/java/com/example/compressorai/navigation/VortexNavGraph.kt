package com.example.compressorai.navigation

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.benchmark.BenchmarkScreen
import com.example.compressorai.MainViewModel
import com.example.compressorai.ui.DashboardScreen
import com.example.feature.audio.AudioCompressionRoute
import com.example.feature.image.ImageCompressionRoute
import com.example.feature.video.VideoCompressionRoute

@Composable
fun VortexNavGraph(
    navController: NavHostController,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    mainViewModel: MainViewModel = viewModel(),
) {
    val state = mainViewModel.uiState.collectAsState()

    NavHost(
        navController = navController,
        startDestination = VortexDestination.Dashboard.route,
        modifier = modifier,
    ) {
        composable(VortexDestination.Dashboard.route) {
            DashboardScreen(
                state = state.value,
                onNavigate = { destination -> navController.navigate(destination.route) },
            )
        }
        composable(VortexDestination.Image.route) {
            ImageCompressionRoute(snackbarHostState = snackbarHostState)
        }
        composable(VortexDestination.Audio.route) {
            AudioCompressionRoute(snackbarHostState = snackbarHostState)
        }
        composable(VortexDestination.Video.route) {
            VideoCompressionRoute(snackbarHostState = snackbarHostState)
        }
        composable(VortexDestination.Benchmark.route) {
            BenchmarkScreen(latenciesMs = remember { listOf(7.2, 8.1, 7.8, 9.4, 7.0, 8.8, 9.0) })
        }
    }
}
