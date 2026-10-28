package com.batraamul.cycling.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.batraamul.cycling.ui.screens.AnalyticsScreen
import com.batraamul.cycling.ui.screens.CalibrationCentreScreen
import com.batraamul.cycling.ui.screens.GpsCalibrationIntroScreen
import com.batraamul.cycling.ui.screens.GpsCalibrationResultScreen
import com.batraamul.cycling.ui.screens.HistoryScreen
import com.batraamul.cycling.ui.screens.HomeScreen
import com.batraamul.cycling.ui.screens.OnboardingScreen
import com.batraamul.cycling.ui.screens.RideDetailScreen
import com.batraamul.cycling.ui.screens.RideScreen
import com.batraamul.cycling.ui.screens.SettingsScreen
import com.batraamul.cycling.ui.screens.StepCalibrationScreen
import com.batraamul.cycling.ui.theme.CyclingTheme

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val ANALYTICS = "analytics"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val RIDE = "ride"
    const val CALIBRATION = "calibration"
    const val CAL_STEP = "calibration/step"
    const val CAL_GPS = "calibration/gps"
    const val CAL_GPS_RESULT = "calibration/gps/result/{rideId}"
    const val RIDE_DETAIL = "ride/{rideId}"
    fun calGpsResult(id: Long) = "calibration/gps/result/$id"
    fun rideDetail(id: Long) = "ride/$id"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)
private val tabs = listOf(
    Tab(Routes.HOME, "Today", Icons.Filled.Home),
    Tab(Routes.ANALYTICS, "Analysis", Icons.Filled.DateRange),
    Tab(Routes.HISTORY, "Rides", Icons.Filled.List),
    Tab(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)

@Composable
fun CyclingAppUi(vm: AppViewModel = viewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val s = settings
    CyclingTheme(themeId = s?.themeId ?: 0) {
        if (s == null) {   // database still loading (a few ms): plain background, no flicker of the wrong screen
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            return@CyclingTheme
        }
        // Whenever the app returns to the foreground, make sure tracking is (still) running.
        LifecycleResumeEffect(s.onboardingDone) {
            vm.ensureTracking()
            onPauseOrDispose { }
        }
        val nav = rememberNavController()
        val startRoute = remember { if (s.onboardingDone) Routes.HOME else Routes.ONBOARDING }  // decided once
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
        val current by nav.currentBackStackEntryAsState()
        val route = current?.destination?.route
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (tabs.any { it.route == route }) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        tabs.forEach { t ->
                            NavigationBarItem(
                                selected = route == t.route,
                                onClick = { nav.navigateTab(t.route) },
                                icon = { Icon(t.icon, contentDescription = t.label) },
                                label = { Text(t.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(nav, startDestination = startRoute,
                modifier = Modifier.padding(bottom = padding.calculateBottomPadding())) {
                composable(Routes.ONBOARDING) {
                    OnboardingScreen(vm) { nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } } }
                }
                composable(Routes.HOME) { HomeScreen(vm, nav) }
                composable(Routes.ANALYTICS) { AnalyticsScreen(vm) }
                composable(Routes.HISTORY) { HistoryScreen(vm) { nav.navigate(Routes.rideDetail(it)) } }
                composable(Routes.SETTINGS) { SettingsScreen(vm, nav) }
                composable(Routes.RIDE) { RideScreen(vm, nav) }
                composable(Routes.CALIBRATION) { CalibrationCentreScreen(vm, nav) }
                composable(Routes.CAL_STEP) { StepCalibrationScreen(vm) { nav.popBackStack() } }
                composable(Routes.CAL_GPS) { GpsCalibrationIntroScreen(vm, nav) }
                composable(Routes.CAL_GPS_RESULT, arguments = listOf(navArgument("rideId") { type = NavType.LongType })) {
                    GpsCalibrationResultScreen(vm, it.arguments?.getLong("rideId") ?: 0) {
                        nav.navigate(Routes.CALIBRATION) { popUpTo(Routes.CALIBRATION) { inclusive = true } }
                    }
                }
                composable(Routes.RIDE_DETAIL, arguments = listOf(navArgument("rideId") { type = NavType.LongType })) {
                    RideDetailScreen(vm, it.arguments?.getLong("rideId") ?: 0) { nav.popBackStack() }
                }
            }
        }
    }
}

private fun NavHostController.navigateTab(route: String) = navigate(route) {
    popUpTo(Routes.HOME) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
