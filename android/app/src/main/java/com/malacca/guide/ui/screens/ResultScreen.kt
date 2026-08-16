package com.malacca.guide.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.navigation.NavController
import com.malacca.guide.api.models.PlaceAlternative
import com.malacca.guide.ble.ConnectionState
import com.malacca.guide.ble.GlassesManager
import com.malacca.guide.ui.navigation.ROUTE_HOME
import com.malacca.guide.ui.navigation.ROUTE_LISTENING
import com.malacca.guide.ui.navigation.ROUTE_LOADING
import com.malacca.guide.ui.theme.BackgroundDark
import com.malacca.guide.ui.theme.ErrorRed
import com.malacca.guide.ui.theme.MalaccaGold
import com.malacca.guide.ui.theme.MalaccaTeal
import com.malacca.guide.ui.theme.SuccessGreen
import com.malacca.guide.ui.theme.SurfaceDark
import com.malacca.guide.ui.theme.TextPrimary
import com.malacca.guide.ui.theme.TextSecondary
import com.malacca.guide.ui.theme.WarningAmber
import com.malacca.guide.ui.viewmodel.AppMode
import com.malacca.guide.ui.viewmodel.GuideViewModel
import com.malacca.guide.voice.Earcon
import com.malacca.guide.voice.GlassesAudioRouter
import com.malacca.guide.voice.TtsManager
import com.malacca.guide.voice.VoiceRecorder
import kotlinx.coroutines.delay

private const val FOLLOW_UP_TAG = "FollowUp"

/**
 * How long to wait for a follow-up question before giving up.
 *
 * Four seconds proved too tight: a recording showed the wearer beginning to speak
 * 2.8s after the cue tone, leaving barely a second of the question before the
 * window closed. Reacting to a beep, drawing breath and starting a sentence takes
 * longer than it seems.
 */
private const val FOLLOW_UP_LISTEN_MS = 7_000L

/** Let the cue tone finish before opening the microphone, so it isn't recorded. */
private const val BEEP_SETTLE_MS = 520L

@Composable
fun ResultScreen(navController: NavController, viewModel: GuideViewModel, ttsManager: TtsManager) {

    DisposableEffect(Unit) {
        onDispose { ttsManager.stop() }
    }

    if (viewModel.appMode == AppMode.RESTAURANT) {
        RestaurantResultScreen(navController, viewModel, ttsManager)
    } else {
        LandmarkResultScreen(navController, viewModel, ttsManager)
    }
}

// ── Landmark variant (existing flow) ────────────────────────────────────────

@Composable
private fun LandmarkResultScreen(
    navController: NavController,
    viewModel: GuideViewModel,
    ttsManager: TtsManager
) {
    val context = LocalContext.current
    val glassesState by GlassesManager.connectionState.collectAsState()
    val handsFree = glassesState == ConnectionState.Connected
    val result = viewModel.analyzeResult
    val bitmap = viewModel.capturedBitmap
    val landmarkName = result?.landmarkName ?: "Unknown Landmark"
    val responseText = result?.response ?: result?.message ?: "No response received."
    val confidence = result?.confidence?.lowercase() ?: "unknown"

    // Hands-free follow-up: once the answer has been spoken, listen briefly so
    // the tourist can just carry on talking ("how much is the ticket?") instead
    // of reaching for the phone. The glasses button stays reserved for starting
    // a new landmark, so the two actions never compete.
    var speechFinished by remember { mutableStateOf(false) }
    var awaitingFollowUp by remember { mutableStateOf(false) }

    LaunchedEffect(responseText) {
        // Starting a follow-up clears the answer before navigating away, so this
        // screen briefly recomposes with no result and responseText falls back to
        // its placeholder. Speaking that means the wearer hears "No response
        // received" immediately after asking their question. Genuine error
        // messages still carry text and are still spoken.
        val spoken = result?.response ?: result?.message
        if (spoken.isNullOrBlank()) return@LaunchedEffect

        // Make sure the answer comes out of the glasses, not the phone.
        GlassesAudioRouter.activate()
        speechFinished = false
        ttsManager.speak(spoken, viewModel.selectedLanguage) { speechFinished = true }
    }

    LaunchedEffect(speechFinished) {
        if (!speechFinished) return@LaunchedEffect
        // Nothing to follow up on unless an answer actually came back. A backend
        // or AI failure still returns a result object carrying an error message,
        // and inviting a follow-up about a failure is worse than staying quiet.
        if (result == null || result.status != "success" || result.response.isNullOrBlank()) {
            Log.d(FOLLOW_UP_TAG, "no follow-up offered: status=${result?.status}")
            return@LaunchedEffect
        }

        // Re-establish the route before anything else. The system tears the SCO
        // link down when playback ends, so by the time a long answer has finished
        // speaking the route is usually gone — leaving the cue tone inaudible and
        // the recording completely silent (peak=0). activate() verifies the route
        // is genuinely live rather than trusting the cached flag.
        val onGlasses = GlassesAudioRouter.activate()

        // Recording can only start once the speaker is idle, otherwise the app
        // records its own voice and answers itself. The beep needs the same
        // treatment: the glasses microphone sits inches from their speaker, so
        // recording through the tone captures it as if it were speech.
        Earcon.playListening()
        delay(BEEP_SETTLE_MS)
        awaitingFollowUp = true
        val device = if (onGlasses) GlassesAudioRouter.glassesInputDevice() else null
        val recording = VoiceRecorder.record(
            context = context,
            preferredDevice = device,
            noSpeechTimeoutMs = FOLLOW_UP_LISTEN_MS
        )
        awaitingFollowUp = false

        if (recording == null || !recording.heardSpeech) {
            Log.d(FOLLOW_UP_TAG, "no follow-up asked, staying on the result")
            return@LaunchedEffect
        }
        Log.d(FOLLOW_UP_TAG, "follow-up captured: ${recording.durationMs}ms")

        // clearResultForFollowUp() wipes questionAudio, so set it afterwards.
        viewModel.clearResultForFollowUp()
        viewModel.updateQuestionAudio(recording.wav)
        navController.navigate(ROUTE_LOADING)
    }

    val (badgeColor, badgeLabel) = when (confidence) {
        "high"   -> SuccessGreen to when (viewModel.selectedLanguage) { "MS" -> "Tinggi"; else -> "High" }
        "medium" -> WarningAmber to when (viewModel.selectedLanguage) { "MS" -> "Sederhana"; else -> "Medium" }
        "low"    -> ErrorRed to when (viewModel.selectedLanguage) { "MS" -> "Rendah"; else -> "Low" }
        else     -> TextSecondary to when (viewModel.selectedLanguage) { "MS" -> "Tidak diketahui"; else -> "Unknown" }
    }

    val backText    = when (viewModel.selectedLanguage) { "MS" -> "← Kembali"; else -> "← Back" }
    val replayText  = when (viewModel.selectedLanguage) { "MS" -> "Main semula"; else -> "Replay" }
    val followText  = when (viewModel.selectedLanguage) { "MS" -> "Tanya lanjut"; else -> "Ask follow-up" }
    val anotherText = when (viewModel.selectedLanguage) { "MS" -> "Tanya mercu tanda lain"; else -> "Ask another landmark" }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = {
                    viewModel.clearForNewSession()
                    navController.popBackStack(ROUTE_HOME, inclusive = false)
                }) { Text(text = backText, color = MalaccaTeal) }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(modifier = Modifier.fillMaxWidth(), color = SurfaceDark, shape = RoundedCornerShape(12.dp)) {
                Column {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth().height(180.dp),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = landmarkName, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Surface(color = badgeColor.copy(alpha = 0.2f), shape = RoundedCornerShape(16.dp)) {
                            Text(text = badgeLabel, color = badgeColor, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                        }
                    }
                }
            }

            Surface(modifier = Modifier.fillMaxWidth(), color = SurfaceDark, shape = RoundedCornerShape(12.dp)) {
                Text(text = responseText, color = TextSecondary, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(16.dp))
            }

            if (awaitingFollowUp) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MalaccaTeal.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = when (viewModel.selectedLanguage) {
                            "MS" -> "Mendengar — tanya soalan lanjut sekarang"
                            else -> "Listening — ask a follow-up now"
                        },
                        color = MalaccaTeal,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            // Replay always stays: it is the only recovery if the spoken answer
            // was missed, and the only route in when audio output fails.
            Button(onClick = { ttsManager.speak(responseText, viewModel.selectedLanguage) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark)) {
                Text(text = replayText, color = MalaccaTeal, fontWeight = FontWeight.Medium)
            }

            // The other two are redundant once the glasses are driving the flow:
            // follow-ups are spoken after the cue tone, and the temple button
            // already starts a new landmark from this screen. They reappear
            // whenever the glasses are not connected, which is also the phone
            // camera fallback path.
            if (!handsFree) {
                Button(onClick = { viewModel.clearResultForFollowUp(); navController.navigate(ROUTE_LISTENING) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MalaccaTeal)) {
                    Text(text = followText, color = TextPrimary, fontWeight = FontWeight.Medium)
                }
                Button(onClick = { viewModel.clearForNewSession(); navController.popBackStack(ROUTE_HOME, inclusive = false) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark)) {
                    Text(text = anotherText, color = TextPrimary, fontWeight = FontWeight.Medium)
                }
            } else {
                Text(
                    text = when (viewModel.selectedLanguage) {
                        "MS" -> "Tekan butang pada cermin mata untuk mercu tanda lain"
                        else -> "Press the glasses button for another landmark"
                    },
                    color = TextSecondary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── Restaurant variant ───────────────────────────────────────────────────────

@Composable
private fun RestaurantResultScreen(
    navController: NavController,
    viewModel: GuideViewModel,
    ttsManager: TtsManager
) {
    val restaurant = viewModel.restaurantResult
    val nearby = viewModel.nearbyResult
    val bitmap = viewModel.capturedBitmap

    val responseText = nearby?.response ?: restaurant?.response ?: restaurant?.message ?: "No response received."

    LaunchedEffect(responseText) {
        GlassesAudioRouter.activate()
        ttsManager.speak(responseText, viewModel.selectedLanguage)
    }

    // Speak nearby response automatically when it arrives
    LaunchedEffect(nearby) {
        nearby?.response?.let {
            GlassesAudioRouter.activate()
            ttsManager.speak(it, viewModel.selectedLanguage)
        }
    }

    val backText   = when (viewModel.selectedLanguage) { "MS" -> "← Kembali"; else -> "← Back" }
    val replayText = when (viewModel.selectedLanguage) { "MS" -> "Main semula"; else -> "Replay" }
    val nearbyText = when (viewModel.selectedLanguage) { "MS" -> "Restoran Berdekatan"; else -> "Find Nearby" }
    val anotherText = when (viewModel.selectedLanguage) { "MS" -> "Imbas restoran lain"; else -> "Scan another restaurant" }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = {
                    viewModel.clearForNewSession()
                    navController.popBackStack(ROUTE_HOME, inclusive = false)
                }) { Text(text = backText, color = MalaccaTeal) }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Restaurant card (hidden when nearby results are showing)
            if (nearby == null && restaurant != null) {
                Surface(modifier = Modifier.fillMaxWidth(), color = SurfaceDark, shape = RoundedCornerShape(12.dp)) {
                    Column {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth().height(180.dp),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(text = restaurant.restaurantName ?: "Unknown Restaurant", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(text = "★ ${restaurant.rating ?: "-"}", color = MalaccaGold, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text(text = reviewsLabel(restaurant.reviewCount, viewModel.selectedLanguage), color = TextSecondary, fontSize = 14.sp)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(text = priceLevelLabel(restaurant.priceLevel, viewModel.selectedLanguage), color = TextSecondary, fontSize = 13.sp)
                                Text(text = "·", color = TextSecondary, fontSize = 13.sp)
                                Text(text = restaurant.cuisine ?: "", color = TextSecondary, fontSize = 13.sp)
                            }
                            Text(text = restaurant.openingHours ?: "", color = if (isOpenNow(restaurant.openingHours, viewModel.selectedLanguage)) SuccessGreen else ErrorRed, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            // Nearby results list
            if (nearby != null) {
                val nearbyTitle = when (viewModel.selectedLanguage) {
                    "MS" -> "Pilihan Berdekatan"; else -> "Nearby Alternatives"
                }
                Text(text = nearbyTitle, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                nearby.alternatives.forEach { place ->
                    NearbyPlaceCard(place, viewModel.selectedLanguage)
                }
            }

            // Response text box
            Surface(modifier = Modifier.fillMaxWidth(), color = SurfaceDark, shape = RoundedCornerShape(12.dp)) {
                Text(text = responseText, color = TextSecondary, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(16.dp))
            }

            // Replay button
            Button(
                onClick = { ttsManager.speak(responseText, viewModel.selectedLanguage) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark)
            ) {
                Text(text = replayText, color = MalaccaTeal, fontWeight = FontWeight.Medium)
            }

            // Find Nearby button (only shown before nearby results load)
            if (nearby == null && restaurant?.placeId != null) {
                if (viewModel.isSearchingNearby) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp), color = MalaccaTeal, strokeWidth = 3.dp)
                    }
                } else {
                    Button(
                        onClick = { viewModel.findNearby(restaurant.placeId) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MalaccaTeal)
                    ) {
                        Text(text = nearbyText, color = TextPrimary, fontWeight = FontWeight.Medium)
                    }
                }
            }

            // Scan another restaurant
            Button(
                onClick = { viewModel.clearForNewSession(); navController.popBackStack(ROUTE_HOME, inclusive = false) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark)
            ) {
                Text(text = anotherText, color = TextPrimary, fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun NearbyPlaceCard(place: PlaceAlternative, language: String) {
    Surface(modifier = Modifier.fillMaxWidth(), color = SurfaceDark, shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = place.name, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "★ ${place.rating}", color = MalaccaGold, fontSize = 13.sp)
                Text(text = awayLabel(place.distanceM, language), color = TextSecondary, fontSize = 13.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = priceLevelLabel(place.priceLevel, language), color = TextSecondary, fontSize = 12.sp)
                Text(text = "·", color = TextSecondary, fontSize = 12.sp)
                Text(text = place.cuisine, color = TextSecondary, fontSize = 12.sp)
            }
            Text(
                text = place.openingHours,
                color = if (isOpenNow(place.openingHours, language)) SuccessGreen else ErrorRed,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

private fun priceLevelLabel(level: Int?, language: String = "EN") = when (language) {
    "MS" -> when (level) {
        1 -> "Berpatutan"; 2 -> "Harga sederhana"; 3 -> "Mahal"; 4 -> "Sangat mahal"
        else -> "Harga tidak dinyatakan"
    }
    else -> when (level) {
        1 -> "Affordable"; 2 -> "Moderately priced"; 3 -> "Pricey"; 4 -> "Very expensive"
        else -> "Price not listed"
    }
}

private fun reviewsLabel(count: Int?, language: String) = when (language) {
    "MS" -> "${count ?: 0} ulasan"
    else -> "${count ?: 0} reviews"
}

private fun isOpenNow(hours: String?, language: String): Boolean {
    val h = hours ?: return false
    return when (language) {
        "MS" -> h.contains("dibuka", ignoreCase = true)
        else -> h.contains("Open", ignoreCase = true)
    }
}

private fun awayLabel(distanceM: Int, language: String) = when (language) {
    "MS" -> "${distanceM}m dari sini"
    else -> "${distanceM}m away"
}
