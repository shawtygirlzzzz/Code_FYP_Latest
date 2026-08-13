package com.malacca.guide.ui.viewmodel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.malacca.guide.api.ApiClient
import com.malacca.guide.api.models.AnalyzeResponse
import com.malacca.guide.api.models.NearbyRequest
import com.malacca.guide.api.models.NearbyResponse
import com.malacca.guide.api.models.RestaurantResponse
import com.malacca.guide.ble.ConnectionState
import com.malacca.guide.ble.GlassesManager
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

enum class AppMode { LANDMARK, RESTAURANT }

private const val TAG = "GuideViewModel"

class GuideViewModel : ViewModel() {

    var selectedLanguage by mutableStateOf("EN")
        private set
    var appMode by mutableStateOf(AppMode.LANDMARK)
        private set
    var transcript by mutableStateOf("")
        private set

    /**
     * The recorded question as WAV, sent to the backend for Gemini to interpret.
     * Null when nothing was heard, in which case the backend describes the scene.
     */
    var questionAudio by mutableStateOf<ByteArray?>(null)
        private set
    var capturedBitmap by mutableStateOf<Bitmap?>(null)
        private set
    var isAnalyzing by mutableStateOf(false)
        private set

    // Landmark state
    var analyzeResult by mutableStateOf<AnalyzeResponse?>(null)
        private set
    var analyzeError by mutableStateOf<String?>(null)
        private set
    private var landmarkContext = ""
    var isFollowUp by mutableStateOf(false)
        private set

    /** True while a low-confidence result is being retried at full resolution. */
    var isRetryingHighRes by mutableStateOf(false)
        private set
    private var triedFullRes = false

    // Restaurant state
    var restaurantResult by mutableStateOf<RestaurantResponse?>(null)
        private set
    var restaurantError by mutableStateOf<String?>(null)
        private set
    var nearbyResult by mutableStateOf<NearbyResponse?>(null)
        private set
    var nearbyError by mutableStateOf<String?>(null)
        private set
    var isSearchingNearby by mutableStateOf(false)
        private set
    var currentLat by mutableStateOf(0.0)
        private set
    var currentLng by mutableStateOf(0.0)
        private set

    fun setLanguage(lang: String) { selectedLanguage = lang }
    fun setMode(mode: AppMode) { appMode = mode }
    fun updateTranscript(text: String) { transcript = text }
    fun updateQuestionAudio(wav: ByteArray?) { questionAudio = wav }
    fun storeBitmap(bitmap: Bitmap) { capturedBitmap = bitmap }
    fun updateLocation(lat: Double, lng: Double) {
        currentLat = lat
        currentLng = lng
    }

    fun clearForNewSession() {
        analyzeResult = null
        analyzeError = null
        restaurantResult = null
        restaurantError = null
        nearbyResult = null
        nearbyError = null
        capturedBitmap = null
        transcript = ""
        questionAudio = null
        landmarkContext = ""
        isFollowUp = false
        isRetryingHighRes = false
        triedFullRes = false
    }

    fun clearResultForFollowUp() {
        landmarkContext = analyzeResult?.landmarkName ?: ""
        isFollowUp = true
        analyzeResult = null
        analyzeError = null
        transcript = ""
        questionAudio = null
    }

    private fun compressBitmap(): ByteArray? = capturedBitmap?.let { compress(it) }

    private fun compress(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        return stream.toByteArray()
    }

    private fun langCode() = when (selectedLanguage) {
        "MS" -> "ms"
        else -> "en"
    }

    fun analyze() {
        val bytes = compressBitmap() ?: run {
            Log.e(TAG, "analyze: capturedBitmap is null — no image to send")
            analyzeError = "No image captured"
            return
        }
        Log.d(TAG, "analyze: starting, bitmap size=${bytes.size}, transcript='$transcript'")
        viewModelScope.launch {
            isAnalyzing = true
            analyzeResult = null
            analyzeError = null
            try {
                var result = callAnalyze(bytes)

                // The BLE thumbnail is low resolution, which is usually the
                // reason Gemini can't place a landmark. Before giving the
                // tourist a shrug, retake the shot at full resolution over
                // WiFi Direct and ask once more.
                if (shouldRetryAtFullResolution(result)) {
                    Log.d(TAG, "analyze: confidence=${result?.confidence} — retrying at full resolution")
                    triedFullRes = true
                    isRetryingHighRes = true
                    try {
                        val hiRes = GlassesManager.captureFullResolution()
                        val bitmap = hiRes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        if (bitmap != null) {
                            Log.d(TAG, "analyze: full-res capture ${bitmap.width}x${bitmap.height}")
                            capturedBitmap = bitmap
                            val retry = callAnalyze(compress(bitmap))
                            if (retry != null) {
                                result = retry
                                analyzeError = null
                            }
                        } else {
                            Log.w(TAG, "analyze: full-res capture failed, keeping thumbnail result")
                        }
                    } finally {
                        isRetryingHighRes = false
                    }
                }

                if (result != null) {
                    analyzeResult = result
                    Log.d(TAG, "analyze: result status=${result.status}, confidence=${result.confidence}, response=${result.response?.take(80)}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "analyze: exception ${e::class.simpleName}: ${e.message}", e)
                analyzeError = e.message ?: "Network error"
            } finally {
                isAnalyzing = false
            }
        }
    }

    /** Returns the parsed body, or null after recording the failure. */
    private suspend fun callAnalyze(bytes: ByteArray): AnalyzeResponse? {
        val imagePart = MultipartBody.Part.createFormData(
            "image", "photo.jpg",
            bytes.toRequestBody("image/jpeg".toMediaType())
        )
        // The spoken question goes up as audio when we have it; the text query is
        // only a fallback for when nothing was recorded.
        val audioPart = questionAudio?.let { wav ->
            MultipartBody.Part.createFormData(
                "audio", "question.wav",
                wav.toRequestBody("audio/wav".toMediaType())
            )
        }
        val query = transcript.ifBlank { "What is this building? Tell me about it." }
        Log.d(TAG, "callAnalyze: image=${bytes.size}B audio=${questionAudio?.size ?: 0}B query='$query'")
        val response = ApiClient.apiService.analyze(
            imagePart,
            query.toRequestBody("text/plain".toMediaType()),
            langCode().toRequestBody("text/plain".toMediaType()),
            landmarkContext.toRequestBody("text/plain".toMediaType()),
            audioPart
        )
        Log.d(TAG, "callAnalyze: code=${response.code()}, successful=${response.isSuccessful}")
        if (response.isSuccessful) return response.body()
        val err = "Server error ${response.code()}"
        Log.e(TAG, "callAnalyze: $err — body=${response.errorBody()?.string()}")
        analyzeError = err
        return null
    }

    private fun shouldRetryAtFullResolution(result: AnalyzeResponse?): Boolean {
        if (triedFullRes) return false
        // Follow-ups reuse the known landmark, so a sharper photo buys nothing.
        if (landmarkContext.isNotBlank()) return false
        if (GlassesManager.connectionState.value != ConnectionState.Connected) return false

        // Only a weak *identification* is worth another photo. A network failure
        // or an AI outage is not: retrying spends six seconds and an extra
        // capture on a problem a sharper image cannot solve.
        if (result == null || result.status != "success") return false
        val confidence = result.confidence?.lowercase()
        return confidence == "low" || confidence == "unknown"
    }

    fun analyzeRestaurant() {
        val bytes = compressBitmap() ?: run {
            Log.e(TAG, "analyzeRestaurant: capturedBitmap is null — no image to send")
            restaurantError = "No image captured"
            return
        }
        Log.d(TAG, "analyzeRestaurant: starting, bitmap size=${bytes.size}, lat=$currentLat, lng=$currentLng")
        viewModelScope.launch {
            isAnalyzing = true
            restaurantResult = null
            restaurantError = null
            try {
                val imagePart = MultipartBody.Part.createFormData(
                    "image", "photo.jpg",
                    bytes.toRequestBody("image/jpeg".toMediaType())
                )
                val response = ApiClient.apiService.analyzeRestaurant(
                    imagePart,
                    currentLat.toString().toRequestBody("text/plain".toMediaType()),
                    currentLng.toString().toRequestBody("text/plain".toMediaType()),
                    langCode().toRequestBody("text/plain".toMediaType())
                )
                Log.d(TAG, "analyzeRestaurant: response code=${response.code()}, successful=${response.isSuccessful}")
                if (response.isSuccessful) {
                    restaurantResult = response.body()
                    Log.d(TAG, "analyzeRestaurant: result status=${restaurantResult?.status}, name=${restaurantResult?.restaurantName}")
                } else {
                    val err = "Server error ${response.code()}"
                    Log.e(TAG, "analyzeRestaurant: $err — body=${response.errorBody()?.string()}")
                    restaurantError = err
                }
            } catch (e: Exception) {
                Log.e(TAG, "analyzeRestaurant: exception ${e::class.simpleName}: ${e.message}", e)
                restaurantError = e.message ?: "Network error"
            } finally {
                isAnalyzing = false
            }
        }
    }

    fun findNearby(excludePlaceId: String) {
        viewModelScope.launch {
            isSearchingNearby = true
            nearbyResult = null
            nearbyError = null
            try {
                val response = ApiClient.apiService.nearbyRestaurants(
                    NearbyRequest(
                        lat = currentLat,
                        lng = currentLng,
                        excludePlaceId = excludePlaceId,
                        language = langCode()
                    )
                )
                if (response.isSuccessful) nearbyResult = response.body()
                else nearbyError = "Server error ${response.code()}"
            } catch (e: Exception) {
                nearbyError = e.message ?: "Network error"
            } finally {
                isSearchingNearby = false
            }
        }
    }
}
