package com.example.compressorai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.compressorai.navigation.VortexDestination
import com.example.compressorai.navigation.VortexNavGraph
import com.example.core.ui.VortexTheme

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VortexTheme {
                val navController = rememberNavController()
                val snackbarHostState = remember { SnackbarHostState() }
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = backStackEntry?.destination?.route

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    text = stringResource(id = R.string.app_name),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 19.sp,
                                )
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                titleContentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    },
                    bottomBar = {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            tonalElevation = 6.dp,
                        ) {
                            VortexDestination.bottomBarDestinations.forEach { destination ->
                                val isSelected = currentRoute == destination.route
                                NavigationBarItem(
                                    selected = isSelected,
                                    onClick = {
                                        navController.navigate(destination.route) {
                                            popUpTo(navController.graph.startDestinationId) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    label = {
                                        Text(
                                            text = destination.label,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            fontSize = 11.sp,
                                        )
                                    },
                                    icon = {
                                        DestinationIcon(
                                            destination = destination,
                                            isSelected = isSelected,
                                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                    ),
                                )
                            }
                        }
                    },
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                ) { innerPadding ->
                    VortexNavGraph(
                        navController = navController,
                        snackbarHostState = snackbarHostState,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

@Composable
fun DestinationIcon(
    destination: VortexDestination,
    isSelected: Boolean,
    tint: Color,
) {
    Canvas(modifier = Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height

        when (destination) {
            VortexDestination.Dashboard -> {
                // House outline
                val path = Path().apply {
                    moveTo(w * 0.5f, h * 0.15f)
                    lineTo(w * 0.9f, h * 0.5f)
                    lineTo(w * 0.78f, h * 0.5f)
                    lineTo(w * 0.78f, h * 0.88f)
                    lineTo(w * 0.22f, h * 0.88f)
                    lineTo(w * 0.22f, h * 0.5f)
                    lineTo(w * 0.1f, h * 0.5f)
                    close()
                }
                if (isSelected) {
                    drawPath(path, color = tint)
                } else {
                    drawPath(path, color = tint, style = Stroke(width = 2.dp.toPx()))
                }
            }
            VortexDestination.Image -> {
                // Photo frame with mountain peak
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.12f, h * 0.15f),
                    size = Size(w * 0.76f, h * 0.70f),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                    style = if (isSelected) Stroke(width = 2.5.dp.toPx()) else Stroke(width = 1.8.dp.toPx()),
                )
                // Mountain
                val path = Path().apply {
                    moveTo(w * 0.22f, h * 0.72f)
                    lineTo(w * 0.42f, h * 0.42f)
                    lineTo(w * 0.58f, h * 0.62f)
                    lineTo(w * 0.78f, h * 0.35f)
                    lineTo(w * 0.84f, h * 0.72f)
                    close()
                }
                drawPath(path, color = tint)
                // Sun
                drawCircle(color = tint, radius = 2.5.dp.toPx(), center = Offset(w * 0.35f, h * 0.32f))
            }
            VortexDestination.Audio -> {
                // Audio waveform bars
                val barWidth = 3.dp.toPx()
                val heights = listOf(0.4f, 0.75f, 0.5f, 0.9f, 0.35f)
                val spacing = w / 6f
                heights.forEachIndexed { idx, frac ->
                    val x = (idx + 1) * spacing
                    val barH = h * frac
                    val topY = (h - barH) / 2f
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(x - barWidth / 2f, topY),
                        size = Size(barWidth, barH),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                }
            }
            VortexDestination.AicStudio -> {
                // Binary container box
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.15f, h * 0.20f),
                    size = Size(w * 0.70f, h * 0.65f),
                    cornerRadius = CornerRadius(5.dp.toPx()),
                    style = if (isSelected) Stroke(width = 2.5.dp.toPx()) else Stroke(width = 1.8.dp.toPx()),
                )
                // Divider line inside container
                drawLine(
                    color = tint,
                    start = Offset(w * 0.15f, h * 0.45f),
                    end = Offset(w * 0.85f, h * 0.45f),
                    strokeWidth = 2.dp.toPx(),
                )
                // Small latch
                drawRect(
                    color = tint,
                    topLeft = Offset(w * 0.42f, h * 0.40f),
                    size = Size(w * 0.16f, h * 0.14f),
                )
            }
            VortexDestination.Benchmark -> {
                // Speedometer gauge
                val center = Offset(w * 0.5f, h * 0.65f)
                drawArc(
                    color = tint,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(w * 0.15f, h * 0.15f),
                    size = Size(w * 0.70f, w * 0.70f),
                    style = Stroke(width = 2.5.dp.toPx()),
                )
                // Needle
                drawLine(
                    color = tint,
                    start = center,
                    end = Offset(w * 0.72f, h * 0.32f),
                    strokeWidth = 2.5.dp.toPx(),
                )
                drawCircle(color = tint, radius = 3.dp.toPx(), center = center)
            }
            else -> {
                drawCircle(color = tint, radius = 5.dp.toPx(), center = Offset(w * 0.5f, h * 0.5f))
            }
        }
    }
}
