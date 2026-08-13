package com.malacca.guide.ble

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.malacca.guide.BuildConfig
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.scan.BleScannerHelper
import com.oudmon.ble.base.scan.ScanRecord
import com.oudmon.ble.base.scan.ScanWrapperCallback
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

data class FoundDevice(val address: String, val name: String, val rssi: Int)

/** Something the glasses told us about, unprompted. */
sealed interface GlassesEvent {
    /**
     * The wearer pressed the capture button / touch pad on the glasses. The
     * photo is already stored on the glasses at this point — pull it with
     * [GlassesManager.fetchLastPhoto], do NOT send another shutter command.
     */
    data object ShutterPressed : GlassesEvent

    /** Any other device notify, surfaced so unmapped button codes are visible. */
    data class Unknown(val code: Int, val raw: String) : GlassesEvent
}

object GlassesManager {

    private const val TAG = "GlassesManager"
    private const val PTAG = "ImagePipeline"
    private const val SCAN_DURATION_MS = 10_000L

    // Capture is BLE-only: trigger the shutter, wait for the 0x02 capture event,
    // then pull the JPEG thumbnail over BLE via getPictureThumbnails.
    private const val PHOTO_CAPTURE_DELAY_MS = 2_000L
    // A 74KB thumbnail took ~5s end to end (2s to prepare, 3s to stream ~1KB
    // chunks over BLE), so allow generous headroom for larger images.
    private const val IMAGE_READY_TIMEOUT_MS = 15_000L
    private const val THUMBNAIL_TIMEOUT_MS = 25_000L
    private const val THUMBNAIL_SIZE_SETTLE_MS = 300L
    // Thumbnail size 0..6 (larger = higher resolution for Gemini).
    private const val THUMBNAIL_SIZE: Byte = 0x06

    // Full-resolution fallback goes over WiFi Direct + the glasses' HTTP server.
    // GLASSES_STATIC_IP is only a handshake hint passed to writeIpToSoc; the
    // real IP comes from the glassesControl p2pIp callback / ARP / probe sweep.
    private const val GLASSES_STATIC_IP = "192.168.49.79"
    private const val GROUP_FORMED_TIMEOUT_MS = 10_000L
    private const val CAPTURE_COMPLETE_TIMEOUT_MS = 10_000L
    private const val HTTP_SETTLE_DELAY_MS = 1_000L

    // Device-notify frame layout, confirmed against W610 firmware:
    //   [0]=0xBC magic, [1]=0x73 action, [2..3]=payload length LE,
    //   [4..5]=checksum, [6]=event code, [7..]=event payload.
    // Verified with the battery frame BC 73 03 00 54 61 | 05 4E 00 -> 0x4E = 78%.
    private const val EVENT_BATTERY = 0x05

    /** Key under which our battery listener sits in the SDK's callback map. */
    private const val BATTERY_CALLBACK_KEY = "malacca_guide"

    // A photo was captured on the glasses, followed by a little-endian image
    // count: BC 73 08 00 .. | 01 05 00 00 00 00 00 01. Observed incrementing by
    // exactly one per button press (2 -> 3 -> 4 -> 5).
    private const val EVENT_PHOTO_TAKEN = 0x01

    // The glasses have finished preparing image data and are about to stream it,
    // followed by a little-endian byte count: BC 73 05 00 .. | 02 00 23 01 00
    // -> 0x00012300 = 74496 bytes. Arrives ~2s after the thumbnail request.
    private const val EVENT_IMAGE_READY = 0x02

    // HeyCyan glasses advertise with these model-name prefixes
    // (e.g. W610_F83A for the W610; also G300, G3, M01s, QCY models).
    private val GLASSES_NAME_PREFIXES = listOf("W610", "G300", "G3", "M01", "QCY")

    private var appContext: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanStopRunnable: Runnable? = null
    private val photoInProgress = AtomicBoolean(false)

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _scanResults = MutableStateFlow<List<FoundDevice>>(emptyList())
    val scanResults: StateFlow<List<FoundDevice>> = _scanResults.asStateFlow()

    private val _events = MutableSharedFlow<GlassesEvent>(extraBufferCapacity = 8)
    /** Unprompted device notifies — collect this to drive the hands-free flow. */
    val events: SharedFlow<GlassesEvent> = _events.asSharedFlow()

    private val _classicBtAddress = MutableStateFlow<String?>(null)
    /** Classic-BT MAC of the glasses headset, used to pin audio routing. */
    val classicBtAddress: StateFlow<String?> = _classicBtAddress.asStateFlow()

    private val _classicBtName = MutableStateFlow<String?>(null)
    val classicBtName: StateFlow<String?> = _classicBtName.asStateFlow()

    private val _batteryPercent = MutableStateFlow<Int?>(null)
    val batteryPercent: StateFlow<Int?> = _batteryPercent.asStateFlow()

    // Receives the JPEG bytes pulled over BLE via getPictureThumbnails.
    private var photoBytesChannel: Channel<ByteArray?>? = null
    // Signalled when the glasses report capture-complete during the WiFi flow.
    private var captureCompleteChannel: Channel<Boolean>? = null
    // Carries the byte count from the 0x02 "image ready" notify.
    private var imageReadyChannel: Channel<Int>? = null
    private val _deviceIp = MutableStateFlow<String?>(null)
    private var connectingAddress: String? = null

    fun init(application: Application) {
        appContext = application.applicationContext
        Log.d(TAG, "init")
    }

    private val notifyListener = object : GlassesDeviceNotifyListener() {
        override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
            val raw = response.loadData?.joinToString(" ") { "%02X".format(it) } ?: "null"
            Log.d(TAG, "parseData cmdType=$cmdType len=${response.loadData?.size ?: -1} raw=[$raw]")
            if (response.loadData.size <= 6) return
            val eventType = response.loadData[6].toInt() and 0xFF
            Log.d(TAG, "parseData cmdType=$cmdType event=0x${eventType.toString(16)}")
            when {
                eventType == EVENT_PHOTO_TAKEN -> {
                    Log.d(PTAG, "photo taken, imageCount=${readUInt32(response.loadData, 7)}")
                    // A WiFi capture waits on this to know the shot is stored.
                    captureCompleteChannel?.trySend(true)
                    // Nothing in flight means the wearer pressed the glasses button.
                    if (photoBytesChannel == null && captureCompleteChannel == null) {
                        val emitted = _events.tryEmit(GlassesEvent.ShutterPressed)
                        Log.d(PTAG, "wearer pressed the glasses button, ShutterPressed emitted=$emitted")
                    }
                }
                eventType == EVENT_IMAGE_READY -> {
                    val size = readUInt32(response.loadData, 7) ?: 0
                    Log.d(PTAG, "image ready, $size bytes available")
                    imageReadyChannel?.trySend(size)
                    captureCompleteChannel?.trySend(true)
                }
                eventType == EVENT_BATTERY -> {
                    if (response.loadData.size > 7) {
                        val battery = response.loadData[7].toInt() and 0xFF
                        _batteryPercent.value = battery
                        Log.d(TAG, "battery=$battery%")
                    }
                }
                else -> {
                    // Surfaced rather than dropped: if the physical button turns
                    // out to report a different code on other firmware, it shows
                    // up here instead of silently doing nothing.
                    // Known-but-unused on W610: 0x03 = voice recording started,
                    // 0x0A = recording finished (the audio itself streams over
                    // ACTION_GPT_UPLOAD, which this app does not consume — STT
                    // runs off the glasses' HFP mic instead).
                    _events.tryEmit(GlassesEvent.Unknown(eventType, raw))
                }
            }
        }
    }

    /** Little-endian uint32 payload field, or null if the frame is too short. */
    private fun readUInt32(loadData: ByteArray?, offset: Int): Int? {
        if (loadData == null || loadData.size < offset + 4) return null
        return (loadData[offset].toInt() and 0xFF) or
            ((loadData[offset + 1].toInt() and 0xFF) shl 8) or
            ((loadData[offset + 2].toInt() and 0xFF) shl 16) or
            ((loadData[offset + 3].toInt() and 0xFF) shl 24)
    }

    private val scanCallback = object : ScanWrapperCallback {
        override fun onStart() {
            Log.d(TAG, "scan started")
        }

        override fun onStop() {
            Log.d(TAG, "scan stopped")
        }

        override fun onLeScan(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?) {
            val d = device ?: return
            val name = runCatching { d.name }.getOrNull()
            Log.d(TAG, "scanned device name=$name address=${d.address} rssi=$rssi")
            if (name.isNullOrBlank()) return
            if (GLASSES_NAME_PREFIXES.none { name.startsWith(it, ignoreCase = true) }) return
            val existing = _scanResults.value
            if (existing.any { it.address == d.address }) return
            _scanResults.value = existing + FoundDevice(d.address, name, rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "scan failed code=$errorCode")
            if (_connectionState.value == ConnectionState.Scanning) {
                _connectionState.value = ConnectionState.Disconnected
            }
        }

        override fun onParsedData(device: BluetoothDevice?, scanRecord: ScanRecord?) {}

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {}
    }

    fun startScan() {
        val ctx = appContext ?: run {
            Log.e(TAG, "startScan: no context")
            return
        }
        Log.d(TAG, "startScan")
        _scanResults.value = emptyList()
        _connectionState.value = ConnectionState.Scanning
        try {
            BleScannerHelper.getInstance().scanDevice(ctx, null, scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "startScan error: ${e.message}")
            _connectionState.value = ConnectionState.Disconnected
            return
        }
        scanStopRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { stopScan() }
        scanStopRunnable = r
        mainHandler.postDelayed(r, SCAN_DURATION_MS)
    }

    fun stopScan() {
        val ctx = appContext ?: return
        Log.d(TAG, "stopScan")
        try {
            BleScannerHelper.getInstance().stopScan(ctx)
        } catch (e: Exception) {
            Log.e(TAG, "stopScan error: ${e.message}")
        }
        scanStopRunnable?.let { mainHandler.removeCallbacks(it) }
        scanStopRunnable = null
        if (_connectionState.value == ConnectionState.Scanning) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    fun connect(deviceAddress: String) {
        Log.d(TAG, "connect $deviceAddress")
        stopScan()
        connectingAddress = deviceAddress
        _connectionState.value = ConnectionState.Connecting
        BleOperateManager.getInstance().connectDirectly(deviceAddress)
    }

    /**
     * Called from MyBluetoothReceiver.onServiceDiscovered() AFTER the SDK has
     * run initEnable(). Only now is the large-data channel ready, so this is
     * the correct point to mark Connected, register the notify listener, and
     * run the init handshake.
     */
    fun onSdkReady() {
        Log.d(TAG, "onSdkReady: marking Connected + handshake")
        _connectionState.value = ConnectionState.Connected

        LargeDataHandler.getInstance().addOutDeviceListener(100, notifyListener)

        // Init handshake — sync time and device info so the glasses are ready
        // to accept feature commands (photo, thumbnail, etc.).
        LargeDataHandler.getInstance().syncTime { _, _ ->
            Log.d(TAG, "syncTime: completed")
        }
        LargeDataHandler.getInstance().syncDeviceInfo { _, _ ->
            Log.d(TAG, "syncDeviceInfo: completed")
        }

        // syncBattery() does NOT answer on the device-notify channel. It installs
        // an internal handler that forwards to the separate registry filled by
        // addBatteryCallBack(), so without this the reply is parsed and dropped
        // and the level only appears when the glasses volunteer one (event 0x05,
        // every few minutes).
        LargeDataHandler.getInstance().addBatteryCallBack(BATTERY_CALLBACK_KEY) { _, response ->
            val level = response?.battery
            Log.d(TAG, "battery callback: $level% charging=${response?.isCharging}")
            if (level != null && level in 0..100) _batteryPercent.value = level
        }
        refreshBattery()

        enableGlassesAudio()
    }

    /** Asks the glasses for their current battery level. Safe to call repeatedly. */
    fun refreshBattery() {
        if (_connectionState.value != ConnectionState.Connected) return
        runCatching { LargeDataHandler.getInstance().syncBattery() }
            .onFailure { Log.w(TAG, "refreshBattery failed: ${it.message}") }
    }

    /**
     * Brings up the glasses' classic-Bluetooth radio and learns its MAC.
     *
     * The BLE link the SDK uses carries control data only — no audio. Audio
     * (glasses mic for STT, glasses speaker for TTS) rides the separate A2DP/HFP
     * classic-BT profile, which stays off until we ask for it here.
     */
    private fun enableGlassesAudio() {
        try {
            LargeDataHandler.getInstance().openBT()
            // Deliberately NOT calling speakSoundSwitch here: the SDK ships no
            // docs for it and the on/off byte polarity is ambiguous, so a wrong
            // guess would mute the speaker we're trying to play TTS through.
            // The firmware default already has the speaker enabled.
            LargeDataHandler.getInstance().syncClassicBluetooth { _, response ->
                val mac = response?.btAddress
                val name = response?.btName
                Log.d(TAG, "syncClassicBluetooth: mac=$mac name=$name")
                if (!mac.isNullOrBlank()) _classicBtAddress.value = mac
                if (!name.isNullOrBlank()) _classicBtName.value = name
            }
        } catch (e: Exception) {
            Log.e(TAG, "enableGlassesAudio error: ${e.message}")
        }
    }

    fun markDisconnected(address: String? = null) {
        val expected = connectingAddress ?: return
        if (address != null && address != expected) return
        Log.d(TAG, "markDisconnected $address")
        connectingAddress = null
        _classicBtAddress.value = null
        _classicBtName.value = null
        _batteryPercent.value = null
        _connectionState.value = ConnectionState.Disconnected
    }

    fun disconnect() {
        Log.d(TAG, "disconnect")
        try {
            BleOperateManager.getInstance().unBindDevice()
        } catch (e: Exception) {
            Log.e(TAG, "disconnect error: ${e.message}")
        }
        connectingAddress = null
        _classicBtAddress.value = null
        _classicBtName.value = null
        _batteryPercent.value = null
        _connectionState.value = ConnectionState.Disconnected
    }

    /**
     * App-initiated capture: trigger the shutter over BLE, then pull the JPEG
     * thumbnail. Falls back to the full-resolution WiFi path if BLE yields
     * nothing.
     */
    suspend fun takePhoto(): ByteArray? {
        if (!photoInProgress.compareAndSet(false, true)) {
            Log.d(PTAG, "takePhoto: ignored, capture already in progress")
            return null
        }
        try {
            Log.d(PTAG, "takePhoto: START connectionState=${_connectionState.value}")
            if (_connectionState.value != ConnectionState.Connected) {
                Log.e(PTAG, "takePhoto: ABORT — glasses not connected")
                return null
            }

            // Arm the photo-bytes waiter BEFORE triggering.
            val bytesCh = Channel<ByteArray?>(Channel.CONFLATED)
            photoBytesChannel = bytesCh

            val bytes = try {
                // 1. Set a larger thumbnail size so Gemini gets decent resolution.
                configureThumbnailSize()

                // 2. Trigger the shutter over BLE.
                Log.d(PTAG, "takePhoto: sending BLE photo command [0x02,0x01,0x01]")
                LargeDataHandler.getInstance().glassesControl(
                    byteArrayOf(0x02, 0x01, 0x01)
                ) { _, it ->
                    Log.d(PTAG, "takePhoto: photo resp dataType=${it?.dataType} err=${it?.errorCode} work=${it?.workTypeIng} imageCount=${it?.imageCount}")
                }

                // 3. Give the glasses time to capture & store the image. The
                //    capture event has only been observed for wearer-initiated
                //    presses, not for this command, so treat the delay as a floor
                //    rather than waiting on an event that may never arrive. If it
                //    does arrive, the armed channel delivers the bytes sooner.
                Log.d(PTAG, "takePhoto: waiting ${PHOTO_CAPTURE_DELAY_MS}ms for capture to complete")
                delay(PHOTO_CAPTURE_DELAY_MS)

                // 4. Pull the JPEG thumbnail directly over BLE.
                pullThumbnail(bytesCh)
            } finally {
                photoBytesChannel = null
            }

            if (bytes != null) return bytes
            Log.w(PTAG, "takePhoto: BLE thumbnail failed — falling back to WiFi full resolution")
            return captureOverWifi()
        } finally {
            photoInProgress.set(false)
        }
    }

    /**
     * Pulls the photo the wearer just took with the glasses button. The image is
     * already captured and stored, so this only fetches the thumbnail — sending
     * another shutter command here would take a second, redundant photo.
     */
    suspend fun fetchLastPhoto(): ByteArray? {
        if (!photoInProgress.compareAndSet(false, true)) {
            Log.d(PTAG, "fetchLastPhoto: ignored, capture already in progress")
            return null
        }
        try {
            Log.d(PTAG, "fetchLastPhoto: START connectionState=${_connectionState.value}")
            if (_connectionState.value != ConnectionState.Connected) {
                Log.e(PTAG, "fetchLastPhoto: ABORT — glasses not connected")
                return null
            }
            val bytesCh = Channel<ByteArray?>(Channel.CONFLATED)
            photoBytesChannel = bytesCh
            return try {
                // The wearer's press stored the photo but left no thumbnail
                // prepared, so size has to be configured before pulling.
                configureThumbnailSize()
                pullThumbnail(bytesCh)
            } finally {
                photoBytesChannel = null
            }
        } finally {
            photoInProgress.set(false)
        }
    }

    /**
     * Asks the glasses to prepare a thumbnail of the requested size. Without
     * this the device answers a thumbnail request with "0 packets available"
     * (`bcfd05..` / `0--0` in GLASSES_LOG) and the SDK never fires its callback,
     * so the pull times out.
     */
    private suspend fun configureThumbnailSize() {
        Log.d(PTAG, "configureThumbnailSize: size=$THUMBNAIL_SIZE")
        LargeDataHandler.getInstance().glassesControl(
            byteArrayOf(0x02, 0x01, 0x06, THUMBNAIL_SIZE, THUMBNAIL_SIZE, 0x02)
        ) { _, it ->
            Log.d(PTAG, "configureThumbnailSize: resp dataType=${it?.dataType} err=${it?.errorCode} work=${it?.workTypeIng}")
        }
        delay(THUMBNAIL_SIZE_SETTLE_MS)
    }

    /**
     * Pulls the stored image over BLE. This is a two-phase exchange, which the
     * SDK does not model:
     *
     *  1. The first thumbnail request makes the glasses *generate* the image.
     *     They answer it with "0 packets available" and go quiet for ~2s.
     *  2. They then raise the 0x02 "image ready" notify with a byte count.
     *  3. Only a request issued *after* that notify is answered with the actual
     *     data, streamed as ~1KB chunks.
     *
     * Each chunk arrives through the callback with success=false, and the final
     * one with success=true, carrying only its own slice — so the chunks are
     * concatenated here. Keeping just the last one yields a truncated JPEG that
     * decodes to null.
     */
    private suspend fun pullThumbnail(bytesCh: Channel<ByteArray?>): ByteArray? {
        val buffer = ByteArrayOutputStream()

        fun requestChunks() {
            LargeDataHandler.getInstance().getPictureThumbnails { _, success, data ->
                val accumulated = synchronized(buffer) {
                    if (data != null && data.isNotEmpty()) buffer.write(data, 0, data.size)
                    buffer.size()
                }
                Log.d(PTAG, "pullThumbnail: chunk success=$success size=${data?.size ?: 0} accumulated=$accumulated")
                if (success) {
                    bytesCh.trySend(synchronized(buffer) { buffer.toByteArray() })
                }
            }
        }

        val readyCh = Channel<Int>(Channel.CONFLATED)
        imageReadyChannel = readyCh
        try {
            // Phase 1 — ask, which starts image generation on the glasses.
            Log.d(PTAG, "pullThumbnail: phase 1, priming image generation")
            requestChunks()

            val size = withTimeoutOrNull(IMAGE_READY_TIMEOUT_MS) { readyCh.receive() }
            if (size == null) {
                Log.e(PTAG, "pullThumbnail: glasses never reported image ready within ${IMAGE_READY_TIMEOUT_MS}ms")
                return null
            }

            // Phase 2 — ask again now that the data exists; this is the request
            // the chunks are actually sent in response to.
            Log.d(PTAG, "pullThumbnail: phase 2, fetching $size bytes")
            synchronized(buffer) { buffer.reset() }
            requestChunks()
        } finally {
            imageReadyChannel = null
        }

        val bytes = withTimeoutOrNull(THUMBNAIL_TIMEOUT_MS) { bytesCh.receive() }
        if (bytes == null || bytes.isEmpty()) {
            Log.e(PTAG, "pullThumbnail: no thumbnail bytes within ${THUMBNAIL_TIMEOUT_MS}ms")
            return null
        }
        Log.d(PTAG, "pullThumbnail: received ${bytes.size} bytes over BLE")
        saveDebugCopy(bytes)
        return bytes
    }

    /**
     * Debug builds only: keeps the last pulled JPEG on disk so the reassembled
     * image can be inspected off-device (`adb pull`). Chunk reassembly can
     * produce a file that still decodes but is visually corrupt, which would
     * look identical to "Gemini couldn't identify the landmark".
     */
    private fun saveDebugCopy(bytes: ByteArray) {
        if (!BuildConfig.DEBUG) return
        val ctx = appContext ?: return
        runCatching {
            val dir = ctx.getExternalFilesDir("captures") ?: return
            val file = java.io.File(dir, "last_capture.jpg")
            file.writeBytes(bytes)
            Log.d(PTAG, "saveDebugCopy: wrote ${file.absolutePath}")
        }.onFailure { Log.w(PTAG, "saveDebugCopy failed: ${it.message}") }
    }

    /**
     * Full-resolution capture for when the low-res BLE thumbnail was not good
     * enough to identify the landmark. Takes a fresh photo and pulls it over
     * WiFi Direct from the glasses' HTTP server — several seconds slower than
     * the BLE path, so it is a fallback rather than the default.
     */
    suspend fun captureFullResolution(): ByteArray? {
        if (!photoInProgress.compareAndSet(false, true)) {
            Log.d(PTAG, "captureFullResolution: ignored, capture already in progress")
            return null
        }
        try {
            return captureOverWifi()
        } finally {
            photoInProgress.set(false)
        }
    }

    /** Caller must already hold [photoInProgress]. */
    private suspend fun captureOverWifi(): ByteArray? {
        Log.d(PTAG, "wifiCapture: START connectionState=${_connectionState.value}")
        if (_connectionState.value != ConnectionState.Connected) {
            Log.e(PTAG, "wifiCapture: ABORT — glasses not connected")
            return null
        }

        // 1. Bring up the WiFi Direct group (phone = group owner).
        Log.d(PTAG, "wifiCapture: creating WiFi Direct group")
        if (!GlassesWifiManager.createGroup()) {
            Log.e(PTAG, "wifiCapture: ABORT — WiFi Direct group creation failed")
            return null
        }

        try {
            // 2. Wait for the group to actually form (no blind delay).
            val formed = GlassesWifiManager.awaitGroupFormed(GROUP_FORMED_TIMEOUT_MS)
            Log.d(PTAG, "wifiCapture: groupFormed=$formed goAddress=${GlassesWifiManager.groupOwnerAddress()}")
            if (!formed) {
                Log.e(PTAG, "wifiCapture: ABORT — group not formed within ${GROUP_FORMED_TIMEOUT_MS}ms")
                return null
            }

            // 3. Arm the capture-complete waiter BEFORE triggering the photo.
            _deviceIp.value = null
            val completeCh = Channel<Boolean>(Channel.CONFLATED)
            captureCompleteChannel = completeCh

            try {
                // 4. Tell the glasses to take a photo; capture the reported p2pIp.
                Log.d(PTAG, "wifiCapture: sending BLE photo command [0x02,0x01,0x01]")
                LargeDataHandler.getInstance().glassesControl(
                    byteArrayOf(0x02, 0x01, 0x01)
                ) { _, response ->
                    val ip = response?.p2pIp
                    Log.d(PTAG, "wifiCapture: glassesControl resp dataType=${response?.dataType} err=${response?.errorCode} work=${response?.workTypeIng} imageCount=${response?.imageCount} p2pIp=$ip")
                    if (!ip.isNullOrBlank() && ip.count { c -> c == '.' } == 3) {
                        _deviceIp.value = ip
                        Log.d(PTAG, "wifiCapture: p2pIp captured = $ip")
                    }
                }

                // 5. Handshake: hint the glasses' static IP (SDK requirement).
                Log.d(PTAG, "wifiCapture: writeIpToSoc $GLASSES_STATIC_IP")
                LargeDataHandler.getInstance().writeIpToSoc(GLASSES_STATIC_IP) { _, _ ->
                    Log.d(PTAG, "wifiCapture: writeIpToSoc ack")
                }

                // 6. Wait for capture-complete (notify event 0x02).
                val captured = withTimeoutOrNull(CAPTURE_COMPLETE_TIMEOUT_MS) { completeCh.receive() }
                if (captured == null) {
                    Log.e(PTAG, "wifiCapture: ABORT — no capture-complete within ${CAPTURE_COMPLETE_TIMEOUT_MS}ms")
                    return null
                }
            } finally {
                captureCompleteChannel = null
            }

            Log.d(PTAG, "wifiCapture: capture-complete confirmed; settling ${HTTP_SETTLE_DELAY_MS}ms")
            delay(HTTP_SETTLE_DELAY_MS)

            // 7. Resolve glasses IP: p2pIp -> ARP/DHCP -> probe sweep.
            val p2pIp = _deviceIp.value
            val arpIp = GlassesWifiManager.clientIpFromArp()
            Log.d(PTAG, "wifiCapture: IP candidates p2pIp=$p2pIp arpIp=$arpIp")
            val ip = GlassesMediaDownloader.discoverGlassesIp(listOfNotNull(p2pIp, arpIp))
            if (ip == null) {
                Log.e(PTAG, "wifiCapture: ABORT — glasses HTTP server not found on WiFi")
                return null
            }
            Log.d(PTAG, "wifiCapture: glasses HTTP server at $ip")

            // 8. List + download the newest image.
            val images = GlassesMediaDownloader.fetchImageList(ip)
            Log.d(PTAG, "wifiCapture: media.config images=$images")
            val newest = images.lastOrNull()
            if (newest == null) {
                Log.e(PTAG, "wifiCapture: ABORT — no images listed by glasses")
                return null
            }
            val bytes = GlassesMediaDownloader.downloadFile(ip, newest)
            Log.d(PTAG, "wifiCapture: DONE file=$newest downloaded=${bytes?.size ?: 0} bytes")
            return bytes
        } finally {
            GlassesWifiManager.removeGroup()
            Log.d(PTAG, "wifiCapture: WiFi Direct group removed")
        }
    }

    fun connectedDeviceAddress(): String? = DeviceManager.getInstance().deviceAddress
}
