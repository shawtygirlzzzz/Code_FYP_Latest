package com.malacca.guide.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.malacca.guide.ui.navigation.ROUTE_HOME
import com.malacca.guide.ui.navigation.ROUTE_LOADING
import com.malacca.guide.ui.theme.BackgroundDark
import com.malacca.guide.ui.theme.ErrorRed
import com.malacca.guide.ui.theme.MalaccaTeal
import com.malacca.guide.ui.theme.TextPrimary
import com.malacca.guide.ui.theme.TextSecondary
import com.malacca.guide.ui.viewmodel.GuideViewModel
import com.malacca.guide.voice.GlassesAudioRouter
import com.malacca.guide.voice.VoiceRecorder
import kotlinx.coroutines.delay

private const val STT_TAG = "SttPipeline"

/** Settling time for the Bluetooth SCO link before recording starts. */
private const val SCO_WARMUP_MS = 600L

/**
 * Records the tourist's spoken question.
 *
 * The audio is sent to the backend rather than transcribed on the device.
 * Android's SpeechRecognizer proved unreliable over the glasses' Bluetooth link:
 * it repeatedly returned NO_MATCH with no partial results even given 2+ seconds
 * of speech, and it chose the phone's built-in microphone regardless of the
 * routing we requested. Recording here means the microphone and the stop
 * condition are both ours, and Gemini does the transcription.
 */
@Composable
fun ListeningScreen(navController: NavController, viewModel: GuideViewModel) {
    val context = LocalContext.current
    var statusText by remember { mutableStateOf<String?>(null) }

    val listeningText = when (viewModel.selectedLanguage) {
        "MS" -> "Mendengar..."
        else -> "Listening..."
    }
    val speakNowText = when (viewModel.selectedLanguage) {
        "MS" -> "Sila bercakap..."
        else -> "Speak now..."
    }
    val stopText = when (viewModel.selectedLanguage) {
        "MS" -> "BERHENTI"
        else -> "STOP"
    }
    val connectingText = when (viewModel.selectedLanguage) {
        "MS" -> "Menyambung mikrofon..."
        else -> "Connecting microphone..."
    }

    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionRequested by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        if (!granted) {
            Toast.makeText(context, "Microphone permission needed.", Toast.LENGTH_SHORT).show()
            navController.popBackStack(ROUTE_HOME, inclusive = false)
        }
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted && !permissionRequested) {
            permissionRequested = true
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(permissionGranted) {
        if (!permissionGranted) return@LaunchedEffect

        statusText = connectingText
        val onGlasses = GlassesAudioRouter.activate()
        Log.d(STT_TAG, "mic routed to glasses=$onGlasses")
        if (onGlasses) {
            // The SCO link reports itself routed before it carries clean audio.
            delay(SCO_WARMUP_MS)
        }
        statusText = null

        val device = if (onGlasses) GlassesAudioRouter.glassesInputDevice() else null
        val result = VoiceRecorder.record(context, device)

        if (result == null) {
            Log.e(STT_TAG, "recording failed")
            Toast.makeText(context, "Couldn't record your question.", Toast.LENGTH_SHORT).show()
            navController.popBackStack(ROUTE_HOME, inclusive = false)
            return@LaunchedEffect
        }

        Log.d(
            STT_TAG,
            "recorded ${result.wav.size} bytes, ${result.durationMs}ms, " +
                "peak=${result.peakAmplitude}, heardSpeech=${result.heardSpeech}"
        )

        // Silence still goes forward: the photo is already taken, and the backend
        // falls back to describing whatever the tourist is looking at.
        if (!result.heardSpeech) {
            Toast.makeText(
                context,
                "Didn't hear a question — describing what you're looking at.",
                Toast.LENGTH_SHORT
            ).show()
            viewModel.updateQuestionAudio(null)
        } else {
            viewModel.updateQuestionAudio(result.wav)
        }
        navController.navigate(ROUTE_LOADING)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            Text(
                text = statusText ?: listeningText,
                color = TextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )

            Canvas(modifier = Modifier.size(200.dp)) {
                val center = Offset(size.width / 2, size.height / 2)
                val base = size.minDimension / 6
                drawCircle(
                    color = MalaccaTeal.copy(alpha = alpha * 0.25f),
                    radius = base * scale * 3f,
                    center = center
                )
                drawCircle(
                    color = MalaccaTeal.copy(alpha = alpha * 0.45f),
                    radius = base * scale * 2f,
                    center = center
                )
                drawCircle(
                    color = MalaccaTeal,
                    radius = base,
                    center = center
                )
            }

            Text(
                text = speakNowText,
                color = TextSecondary,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )

            Button(
                onClick = { navController.popBackStack(ROUTE_HOME, inclusive = false) },
                colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
            ) {
                Text(text = stopText, color = TextPrimary, fontWeight = FontWeight.Bold)
            }
        }
    }
}
