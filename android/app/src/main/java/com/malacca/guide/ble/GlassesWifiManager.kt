package com.malacca.guide.ble

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import java.io.File

/**
 * Manages the WiFi Direct (P2P) group used to receive media from the glasses.
 * The phone acts as the group owner; the glasses join as a client and report
 * their IP back over BLE (see GlassesManager's BLE->IP bridge).
 */
object GlassesWifiManager {

    private const val TAG = "GlassesWifiManager"

    private var appContext: Context? = null
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    private val _groupInfo = MutableStateFlow<WifiP2pInfo?>(null)
    /** Non-null with groupFormed == true once the P2P group is up. */
    val groupInfo: StateFlow<WifiP2pInfo?> = _groupInfo.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        val mgr = appContext!!.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        manager = mgr
        channel = mgr?.initialize(appContext, Looper.getMainLooper(), null)
        Log.d(TAG, "init, available=${mgr != null}")
    }

    @SuppressLint("MissingPermission")
    suspend fun createGroup(): Boolean {
        val mgr = manager ?: run { Log.e(TAG, "createGroup: WifiP2pManager unavailable"); return false }
        val ch = channel ?: run { Log.e(TAG, "createGroup: channel unavailable"); return false }
        registerReceiver()
        return suspendCancellableCoroutine { cont ->
            mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "createGroup: success")
                    if (cont.isActive) cont.resume(true)
                }

                override fun onFailure(reason: Int) {
                    // BUSY (2) means a group already exists — reuse it.
                    val ok = reason == WifiP2pManager.BUSY
                    Log.w(TAG, "createGroup: failure reason=$reason treatedAsOk=$ok")
                    if (cont.isActive) cont.resume(ok)
                }
            })
        }
    }

    @SuppressLint("MissingPermission")
    fun removeGroup() {
        val mgr = manager
        val ch = channel
        if (mgr != null && ch != null) {
            mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "removeGroup: success")
                }

                override fun onFailure(reason: Int) {
                    Log.w(TAG, "removeGroup: failure reason=$reason")
                }
            })
        }
        unregisterReceiver()
        _groupInfo.value = null
    }

    /** Suspends until the P2P group is formed, or returns false on timeout. */
    suspend fun awaitGroupFormed(timeoutMs: Long): Boolean {
        if (_groupInfo.value?.groupFormed == true) return true
        return withTimeoutOrNull(timeoutMs) {
            _groupInfo.first { it?.groupFormed == true }
            true
        } ?: false
    }

    fun groupOwnerAddress(): String? = _groupInfo.value?.groupOwnerAddress?.hostAddress

    /**
     * Best-effort: read the kernel ARP table for a client on the WiFi Direct
     * subnet (192.168.49.x, excluding the group owner .1). Often empty on
     * Android 10+, so this is only a fallback to the glassesControl p2pIp.
     */
    fun clientIpFromArp(): String? {
        return try {
            File("/proc/net/arp").readLines()
                .drop(1)
                .mapNotNull { line ->
                    val c = line.split(Regex("\\s+")).filter { it.isNotBlank() }
                    if (c.size >= 4) Triple(c[0], c[2], c[3]) else null
                }
                .firstOrNull { (ip, flags, mac) ->
                    ip.startsWith("192.168.49.") && ip != "192.168.49.1" &&
                        flags != "0x0" && mac != "00:00:00:00:00:00"
                }
                ?.first
                .also { Log.d(TAG, "clientIpFromArp: $it") }
        } catch (e: Exception) {
            Log.e(TAG, "clientIpFromArp failed: ${e.message}")
            null
        }
    }

    private fun registerReceiver() {
        val ctx = appContext ?: return
        if (receiver != null) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) return
                val mgr = manager ?: return
                val ch = channel ?: return
                mgr.requestConnectionInfo(ch) { info ->
                    Log.d(
                        TAG,
                        "connectionInfo groupFormed=${info.groupFormed} isGO=${info.isGroupOwner} go=${info.groupOwnerAddress?.hostAddress}"
                    )
                    _groupInfo.value = if (info.groupFormed) info else null
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(r, filter)
        }
        receiver = r
    }

    private fun unregisterReceiver() {
        val ctx = appContext ?: return
        receiver?.let {
            runCatching { ctx.unregisterReceiver(it) }
            receiver = null
        }
    }
}
