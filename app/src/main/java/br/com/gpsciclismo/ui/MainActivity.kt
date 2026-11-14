package br.com.gpsciclismo.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import br.com.gpsciclismo.GpsCiclismoApp
import br.com.gpsciclismo.recording.RecStatus

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as GpsCiclismoApp
        setContent {
            AppTheme {
                // Android 15 desenha atrás das barras do sistema: afasta o conteúdo delas.
                Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    val nav = rememberNavController()
                    val live by app.recorder.live.collectAsStateWithLifecycle()
                    // Tela sempre acesa durante o treino (se o celular estiver no guidão).
                    LaunchedEffect(live.status) {
                        if (live.status == RecStatus.IDLE) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    val start = remember { if (app.recorder.live.value.status == RecStatus.IDLE) "home" else "live" }
                    UpdatePrompt(enabled = live.status == RecStatus.IDLE)
                    NavHost(nav, startDestination = start) {
                        composable("home") { Centered { HomeScreen(app, nav) } }
                        composable("profile") { Centered { ProfileScreen(app) { nav.popBackStack() } } }
                        composable("sensors") { Centered { SensorsScreen(app) { nav.popBackStack() } } }
                        composable("workouts") { Centered { WorkoutsScreen(app, nav) } }
                        composable(
                            "workout/{id}",
                            arguments = listOf(navArgument("id") { type = NavType.LongType }),
                        ) { Centered { WorkoutEditorScreen(app, it.arguments?.getLong("id") ?: 0L) { nav.popBackStack() } } }
                        composable("live") { LiveRideScreen(app, nav) }
                        composable("history") { Centered { HistoryScreen(app, nav) } }
                        composable(
                            "ride/{id}",
                            arguments = listOf(navArgument("id") { type = NavType.LongType }),
                        ) { Centered(960.dp) { RideDetailScreen(app, it.arguments?.getLong("id") ?: 0L) { nav.popBackStack() } } }
                    }
                }
                }
            }
        }
    }
}
