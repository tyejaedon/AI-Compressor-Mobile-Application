package com.example.compressorai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
                        TopAppBar(title = { Text(stringResource(id = R.string.app_name)) })
                    },
                    bottomBar = {
                        NavigationBar {
                            VortexDestination.bottomBarDestinations.forEach { destination ->
                                NavigationBarItem(
                                    selected = currentRoute == destination.route,
                                    onClick = {
                                        navController.navigate(destination.route) {
                                            popUpTo(navController.graph.startDestinationId) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    label = { Text(destination.label) },
                                    icon = { },
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
