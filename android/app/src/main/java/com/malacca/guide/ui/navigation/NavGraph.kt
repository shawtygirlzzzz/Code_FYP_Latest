package com.malacca.guide.ui.navigation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.android.gms.location.LocationServices
import com.malacca.guide.ble.GlassesEvent
import com.malacca.guide.ble.GlassesManager
import com.malacca.guide.ui.screens.HomeScreen
import com.malacca.guide.ui.screens.ListeningScreen
import com.malacca.guide.ui.screens.LoadingScreen
import com.malacca.guide.ui.screens.ResultScreen
import com.malacca.guide.ui.screens.SplashScreen
import com.malacca.guide.ui.screens.scaleBitmap
import com.malacca.guide.ui.viewmodel.AppMode
import com.malacca.guide.ui.viewmodel.GuideViewModel
import com.malacca.guide.voice.TtsManager
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

const val ROUTE_SPLASH    = "splash"
const val ROUTE_HOME      = "home"
const val ROUTE_LISTENING = "listening"
const val ROUTE_LOADING   = "loading"
const val ROUTE_RESULT    = "result"

private const val TAG = "HandsFree"

@Composable
fun AppNavGraph() {
    val navController = rememberNavController()
    val viewModel: GuideViewModel = viewModel()
    val context = LocalContext.current
    val ttsManager = remember { TtsManager(context) }

    DisposableEffect(Unit) {
        onDispose { ttsManager.shutdown() }
    }

    GlassesButtonHandler(navController, viewModel)

    NavHost(navController = navController, startDestination = ROUTE_SPLASH) {
        composable(ROUTE_SPLASH)    { SplashScreen(navController) }
        composable(ROUTE_HOME)      { HomeScreen(navController, viewModel) }
        composable(ROUTE_LISTENING) { ListeningScreen(navController, viewModel) }
        composable(ROUTE_LOADING)   { LoadingScreen(navController, viewModel) }
        composable(ROUTE_RESULT)    { ResultScreen(navController, viewModel, ttsManager) }
    }
}

/**
 * Drives the hands-free flow when the wearer presses the capture button on the
 * glasses.
 *
 * The glasses take and store the photo themselves, then raise a device-notify
 * event over BLE. Previously that event was dropped unless the app had already
 * started a capture, so the button appeared dead. Here it starts a full session:
 * pull the photo, then hand off to the normal Listening -> Loading -> Result
 * flow so STT, the backend call and TTS all behave exactly as they do for the
 * on-screen button.
 *
 * Collected at the NavGraph level rather than inside a screen so the event is
 * still handled while the wearer is looking at a landmark instead of the phone.
 */
@Composable
private fun GlassesButtonHandler(
    navController: NavHostController,
    viewModel: GuideViewModel,
) {
    val context = LocalContext.current
    val busy = remember { AtomicBoolean(false) }

    LaunchedEffect(Unit) {
        GlassesManager.events.collect { event ->
            when (event) {
                is GlassesEvent.ShutterPressed -> {
                    val route = navController.currentBackStackEntry?.destination?.route
                    // Only start a new session from a resting screen, otherwise a
                    // stray press mid-interaction would restart the flow.
                    if (route != ROUTE_HOME && route != ROUTE_RESULT) {
                        Log.d(TAG, "button press ignored, mid-session on route=$route")
                        return@collect
                    }
                    if (!busy.compareAndSet(false, true)) {
                        Log.d(TAG, "button press ignored, already handling one")
                        return@collect
                    }
                    try {
                        Log.d(TAG, "glasses button pressed on route=$route — starting session")
                        viewModel.clearForNewSession()

                        if (viewModel.appMode == AppMode.RESTAURANT) {
                            val loc = lastKnownLocation(context)
                            viewModel.updateLocation(loc?.first ?: 0.0, loc?.second ?: 0.0)
                        }

                        val bytes = GlassesManager.fetchLastPhoto()
                        val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        if (bitmap == null) {
                            Log.e(TAG, "could not pull the photo the glasses just took")
                            Toast.makeText(
                                context,
                                "Couldn't get the photo from your glasses. Try again.",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@collect
                        }
                        Log.d(TAG, "photo pulled ${bitmap.width}x${bitmap.height} — going to Listening")
                        viewModel.storeBitmap(scaleBitmap(bitmap, 1280))
                        navController.navigate(ROUTE_LISTENING)
                    } finally {
                        busy.set(false)
                    }
                }

                is GlassesEvent.Unknown -> {
                    // Not silently dropped: if the button reports a different
                    // code on another firmware, it is visible in logcat here.
                    Log.d(TAG, "unmapped glasses event code=0x${event.code.toString(16)} raw=[${event.raw}]")
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
private suspend fun lastKnownLocation(context: Context): Pair<Double, Double>? {
    val granted = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    if (!granted) {
        Log.w(TAG, "location permission not granted — restaurant lookup will have no GPS")
        return null
    }
    return suspendCancellableCoroutine { cont ->
        LocationServices.getFusedLocationProviderClient(context).lastLocation
            .addOnSuccessListener { loc ->
                if (cont.isActive) {
                    cont.resume(loc?.let { it.latitude to it.longitude })
                }
            }
            .addOnFailureListener {
                if (cont.isActive) cont.resume(null)
            }
    }
}
