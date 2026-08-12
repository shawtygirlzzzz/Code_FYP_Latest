package com.malacca.guide.voice

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.malacca.guide.ble.GlassesManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Routes microphone capture (STT) and speech playback (TTS) through the glasses
 * instead of the phone.
 *
 * The glasses expose two independent Bluetooth links:
 *  - BLE (GATT), used by the Oudmon SDK for control commands and photo data.
 *  - Classic Bluetooth (A2DP + HFP), used for actual audio.
 *
 * The BLE link alone carries no audio, which is why STT/TTS stayed on the phone.
 * [GlassesManager] asks the glasses to bring up their classic-BT radio and
 * reports the MAC here; this object then pins Android's communication device to
 * that headset so `SpeechRecognizer` records from the glasses mic and
 * `TextToSpeech` plays out of the glasses speaker.
 */
object GlassesAudioRouter {

    private const val TAG = "GlassesAudio"
    private const val SCO_CONNECT_TIMEOUT_MS = 4_000L
    // Bluetooth SCO typically needs 0.5-2s to establish after the request.
    private const val ROUTE_SETTLE_POLL_MS = 100L
    private const val ROUTE_SETTLE_ATTEMPTS = 40

    private var appContext: Context? = null
    private var audioManager: AudioManager? = null
    private var previousMode: Int? = null

    private val _routed = MutableStateFlow(false)
    /** True while phone audio is pinned to the glasses headset. */
    val routed: StateFlow<Boolean> = _routed.asStateFlow()

    private val _headsetAvailable = MutableStateFlow(false)
    /** True when a Bluetooth audio device usable for STT/TTS is connected. */
    val headsetAvailable: StateFlow<Boolean> = _headsetAvailable.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        audioManager = appContext!!.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        refreshAvailability()
        Log.d(TAG, "init available=${_headsetAvailable.value}")
    }

    /** Re-checks whether a Bluetooth audio route to the glasses exists. */
    @SuppressLint("MissingPermission")
    fun refreshAvailability() {
        _headsetAvailable.value = findGlassesAudioDevice() != null
    }

    /**
     * Pins mic + speech playback to the glasses. Returns false if no Bluetooth
     * audio device is connected, in which case callers should carry on with the
     * phone mic/speaker rather than failing the interaction.
     */
    suspend fun activate(): Boolean {
        val am = audioManager ?: return false
        // Never trust the cached flag on its own: the system tears the SCO route
        // down when whoever was using it releases the audio input, without
        // telling us. Re-pin whenever the route is no longer actually live,
        // otherwise capture silently falls back to the phone's built-in mic.
        if (_routed.value) {
            if (isRouteLive()) return true
            Log.w(TAG, "activate: previous route was dropped by the system — re-pinning")
            _routed.value = false
        }

        val device = findGlassesAudioDevice()
        if (device == null) {
            Log.w(TAG, "activate: no Bluetooth audio device — staying on phone audio")
            _headsetAvailable.value = false
            return false
        }
        _headsetAvailable.value = true

        previousMode = am.mode
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            val set = am.setCommunicationDevice(device)
            Log.d(TAG, "activate: setCommunicationDevice(${device.productName}/${device.type}) -> $set")
            // setCommunicationDevice() returning true only means the request was
            // accepted; the SCO link still has to come up, and the system may
            // revert to the built-in mic in the meantime. Callers start audio
            // capture immediately after this, so wait for the route to actually
            // take effect rather than reporting success on the return value.
            set && awaitCommunicationDevice(device.id)
        } else {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            am.startBluetoothSco()
            val connected = awaitScoConnected()
            @Suppress("DEPRECATION")
            if (connected) am.isBluetoothScoOn = true
            Log.d(TAG, "activate: legacy SCO connected=$connected")
            connected
        }

        if (!ok) {
            // Undo the half-applied request so the phone isn't left pinned to a
            // route that never came up.
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
            }
            restoreMode()
            return false
        }
        _routed.value = true
        return true
    }

    /** Releases the glasses audio route and restores the previous audio mode. */
    fun deactivate() {
        val am = audioManager ?: return
        if (!_routed.value) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                am.stopBluetoothSco()
            }
        } catch (e: Exception) {
            Log.e(TAG, "deactivate error: ${e.message}")
        }
        restoreMode()
        _routed.value = false
        Log.d(TAG, "deactivate: released")
    }

    /**
     * The glasses microphone as a recording input, for
     * [android.media.AudioRecord.setPreferredDevice].
     *
     * Distinct from [findGlassesAudioDevice], which deals in output/communication
     * devices. Naming the input explicitly is what makes capture deterministic —
     * the system-wide communication device is only a hint, and Android has
     * ignored it here before.
     */
    @SuppressLint("MissingPermission")
    fun glassesInputDevice(): AudioDeviceInfo? {
        val am = audioManager ?: return null
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        if (inputs.isEmpty()) {
            Log.w(TAG, "glassesInputDevice: no Bluetooth SCO input available")
            return null
        }
        val glassesMac = GlassesManager.classicBtAddress.value
        val matched = glassesMac?.let { mac ->
            inputs.firstOrNull { runCatching { it.address }.getOrNull().equals(mac, ignoreCase = true) }
        }
        val chosen = matched ?: inputs.first()
        Log.d(TAG, "glassesInputDevice: ${chosen.productName} (type=${chosen.type})")
        return chosen
    }

    /** True only if the system currently has a Bluetooth route actually active. */
    @SuppressLint("MissingPermission")
    private fun isRouteLive(): Boolean {
        val am = audioManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.communicationDevice?.isBluetoothAudio() == true
        } else {
            @Suppress("DEPRECATION")
            am.isBluetoothScoOn
        }
    }

    /**
     * Human-readable description of where audio is currently going, for logs.
     * Reports what the system actually has pinned rather than what we asked for.
     */
    @SuppressLint("MissingPermission")
    fun activeRouteDescription(): String {
        val am = audioManager ?: return "no AudioManager"
        if (!_routed.value) return "phone mic/speaker (not routed)"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = am.communicationDevice
            if (device == null) "routed but system reports no communication device"
            else "${device.productName} (type=${device.type}, addr=${runCatching { device.address }.getOrNull()})"
        } else {
            @Suppress("DEPRECATION")
            if (am.isBluetoothScoOn) "Bluetooth SCO headset" else "routed but SCO is off"
        }
    }

    /**
     * The audio stream TTS should target. Voice-call routing follows the pinned
     * communication device; media routing does not, so it would leak back to the
     * phone speaker while the glasses are in use.
     */
    fun ttsStreamType(): Int =
        if (_routed.value) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC

    private fun restoreMode() {
        val am = audioManager ?: return
        previousMode?.let { am.mode = it }
        previousMode = null
    }

    /**
     * Prefers the headset whose MAC matches the classic-BT address the glasses
     * reported over BLE; otherwise falls back to any connected BT audio output.
     */
    @SuppressLint("MissingPermission")
    private fun findGlassesAudioDevice(): AudioDeviceInfo? {
        val am = audioManager ?: return null
        val candidates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.availableCommunicationDevices
        } else {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }
        val bluetooth = candidates.filter { it.isBluetoothAudio() }
        if (bluetooth.isEmpty()) return null

        val glassesMac = GlassesManager.classicBtAddress.value
        val matched = glassesMac?.let { mac ->
            bluetooth.firstOrNull { runCatching { it.address }.getOrNull().equals(mac, ignoreCase = true) }
        }
        val chosen = matched ?: bluetooth.first()
        Log.d(
            TAG,
            "findGlassesAudioDevice: glassesMac=$glassesMac matched=${matched != null} " +
                "chosen=${chosen.productName} type=${chosen.type}"
        )
        return chosen
    }

    private fun AudioDeviceInfo.isBluetoothAudio(): Boolean {
        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            type == AudioDeviceInfo.TYPE_BLE_HEADSET
        ) return true
        return false
    }

    /**
     * Polls until the system reports the requested device as the active
     * communication device. Returns false if it never takes effect, so the
     * caller can honestly report that audio stayed on the phone.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun awaitCommunicationDevice(expectedId: Int): Boolean {
        val am = audioManager ?: return false
        repeat(ROUTE_SETTLE_ATTEMPTS) { attempt ->
            val current = am.communicationDevice
            if (current != null && current.id == expectedId) {
                Log.d(TAG, "activate: route settled after ${attempt * ROUTE_SETTLE_POLL_MS}ms")
                return true
            }
            delay(ROUTE_SETTLE_POLL_MS)
        }
        Log.w(
            TAG,
            "activate: route never took effect, system still on " +
                "${am.communicationDevice?.productName}/${am.communicationDevice?.type}"
        )
        return false
    }

    /** Pre-API-31 SCO activation is asynchronous — wait for the state broadcast. */
    private suspend fun awaitScoConnected(): Boolean {
        val ctx = appContext ?: return false
        return withTimeoutOrNull(SCO_CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        @Suppress("DEPRECATION")
                        val state = intent?.getIntExtra(
                            AudioManager.EXTRA_SCO_AUDIO_STATE,
                            AudioManager.SCO_AUDIO_STATE_ERROR
                        ) ?: return
                        @Suppress("DEPRECATION")
                        if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                            runCatching { ctx.unregisterReceiver(this) }
                            if (cont.isActive) cont.resume(true)
                        }
                    }
                }
                @Suppress("DEPRECATION")
                val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    ctx.registerReceiver(receiver, filter)
                }
                cont.invokeOnCancellation { runCatching { ctx.unregisterReceiver(receiver) } }
            }
        } ?: false
    }
}
