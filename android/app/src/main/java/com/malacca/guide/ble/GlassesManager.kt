package com.malacca.guide.ble

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.scan.BleScannerHelper
import com.oudmon.ble.base.scan.ScanRecord
import com.oudmon.ble.base.scan.ScanWrapperCallback
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

data class FoundDevice(val address: String, val name: String, val rssi: Int)

object GlassesManager {

    private const val TAG = "GlassesManager"
    private const val PTAG = "ImagePipeline"
    private const val SCAN_DURATION_MS = 10_000L

    // Photo capture is a 3-protocol flow: BLE control + WiFi Direct + HTTP.
    // The glasses join our WiFi Direct subnet and serve the photo over HTTP.
    // GLASSES_STATIC_IP is only a handshake hint passed to writeIpToSoc; the
    // real IP we connect to comes from the glassesControl p2pIp callback / ARP.
    private const val GLASSES_STATIC_IP = "192.168.49.79"
    private const val GROUP_FORMED_TIMEOUT_MS = 10_000L
    private const val CAPTURE_COMPLETE_TIMEOUT_MS = 10_000L
    private const val HTTP_SETTLE_DELAY_MS = 1_000L

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

    // Signalled when the glasses report capture-complete (notify event 0x02).
    private var captureCompleteChannel: Channel<Boolean>? = null
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
            when (eventType) {
                0x02 -> {
                    Log.d(PTAG, "capture-complete notification received (event=0x02)")
                    captureCompleteChannel?.trySend(true)
                }
                0x05 -> {
                    if (response.loadData.size > 7) {
                        val battery = response.loadData[7].toInt() and 0xFF
                        Log.d(TAG, "battery=$battery%")
                    }
                }
            }
        }
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
        LargeDataHandler.getInstance().addOutDeviceListener(100, notifyListener)
        BleOperateManager.getInstance().connectDirectly(deviceAddress)
    }

    fun markConnected(address: String?) {
        val expected = connectingAddress ?: run {
            Log.d(TAG, "markConnected ignored — no pending connect")
            return
        }
        if (address == null || address != expected) {
            Log.d(TAG, "markConnected ignored — address mismatch (got=$address expected=$expected)")
            return
        }
        Log.d(TAG, "markConnected OK $address")
        _connectionState.value = ConnectionState.Connected

        // Init handshake per SDK guide 2.3.2 — sync time and device info so the
        // glasses are ready to accept feature commands (AI photo, etc.).
        LargeDataHandler.getInstance().syncTime { _, _ ->
            Log.d(TAG, "syncTime: completed")
        }
        LargeDataHandler.getInstance().syncDeviceInfo { _, _ ->
            Log.d(TAG, "syncDeviceInfo: completed")
        }
    }

    fun markDisconnected(address: String? = null) {
        val expected = connectingAddress ?: return
        if (address != null && address != expected) return
        Log.d(TAG, "markDisconnected $address")
        connectingAddress = null
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
        _connectionState.value = ConnectionState.Disconnected
    }

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

            // 1. Bring up the WiFi Direct group (phone = group owner).
            Log.d(PTAG, "takePhoto: creating WiFi Direct group")
            if (!GlassesWifiManager.createGroup()) {
                Log.e(PTAG, "takePhoto: ABORT — WiFi Direct group creation failed")
                return null
            }

            try {
                // 2. Wait for the group to actually form (no blind delay).
                val formed = GlassesWifiManager.awaitGroupFormed(GROUP_FORMED_TIMEOUT_MS)
                Log.d(PTAG, "takePhoto: groupFormed=$formed goAddress=${GlassesWifiManager.groupOwnerAddress()}")
                if (!formed) {
                    Log.e(PTAG, "takePhoto: ABORT — WiFi Direct group not formed within ${GROUP_FORMED_TIMEOUT_MS}ms")
                    return null
                }

                // 3. Arm the capture-complete waiter BEFORE triggering the photo.
                _deviceIp.value = null
                val completeCh = Channel<Boolean>(Channel.CONFLATED)
                captureCompleteChannel = completeCh

                // 4. Tell the glasses to take a photo; capture the reported p2pIp.
                Log.d(PTAG, "takePhoto: sending BLE photo command [0x02,0x01,0x01]")
                LargeDataHandler.getInstance().glassesControl(
                    byteArrayOf(0x02, 0x01, 0x01)
                ) { _, response ->
                    val ip = response?.p2pIp
                    Log.d(PTAG, "takePhoto: glassesControl resp dataType=${response?.dataType} err=${response?.errorCode} work=${response?.workTypeIng} imageCount=${response?.imageCount} p2pIp=$ip")
                    if (!ip.isNullOrBlank() && ip.count { c -> c == '.' } == 3) {
                        _deviceIp.value = ip
                        Log.d(PTAG, "takePhoto: p2pIp captured = $ip")
                    }
                }

                // 5. Handshake: hint the glasses' static IP (SDK requirement).
                Log.d(PTAG, "takePhoto: writeIpToSoc $GLASSES_STATIC_IP")
                LargeDataHandler.getInstance().writeIpToSoc(GLASSES_STATIC_IP) { _, _ ->
                    Log.d(PTAG, "takePhoto: writeIpToSoc ack")
                }

                // 6. Wait for capture-complete (notify event 0x02), 10s timeout.
                val captured = withTimeoutOrNull(CAPTURE_COMPLETE_TIMEOUT_MS) { completeCh.receive() }
                if (captured == null) {
                    Log.e(PTAG, "takePhoto: ABORT — no capture-complete (0x02) within ${CAPTURE_COMPLETE_TIMEOUT_MS}ms")
                    return null
                }
                Log.d(PTAG, "takePhoto: capture-complete confirmed; settling ${HTTP_SETTLE_DELAY_MS}ms")
                delay(HTTP_SETTLE_DELAY_MS)

                // 7. Resolve glasses IP: p2pIp -> ARP/DHCP -> probe sweep.
                val p2pIp = _deviceIp.value
                val arpIp = GlassesWifiManager.clientIpFromArp()
                Log.d(PTAG, "takePhoto: IP candidates p2pIp=$p2pIp arpIp=$arpIp")
                val ip = GlassesMediaDownloader.discoverGlassesIp(listOfNotNull(p2pIp, arpIp))
                if (ip == null) {
                    Log.e(PTAG, "takePhoto: ABORT — glasses HTTP server not found on WiFi")
                    return null
                }
                Log.d(PTAG, "takePhoto: glasses HTTP server at $ip")

                // 8. List + download the newest image.
                val images = GlassesMediaDownloader.fetchImageList(ip)
                Log.d(PTAG, "takePhoto: media.config images=$images")
                val newest = images.lastOrNull()
                if (newest == null) {
                    Log.e(PTAG, "takePhoto: ABORT — no images listed by glasses")
                    return null
                }
                val bytes = GlassesMediaDownloader.downloadFile(ip, newest)
                Log.d(PTAG, "takePhoto: DONE file=$newest downloaded=${bytes?.size ?: 0} bytes")
                return bytes
            } finally {
                captureCompleteChannel = null
                GlassesWifiManager.removeGroup()
                Log.d(PTAG, "takePhoto: WiFi Direct group removed")
            }
        } finally {
            photoInProgress.set(false)
        }
    }

    fun connectedDeviceAddress(): String? = DeviceManager.getInstance().deviceAddress
}
