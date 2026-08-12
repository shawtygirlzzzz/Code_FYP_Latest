package com.malacca.guide.ble

import android.bluetooth.BluetoothDevice
import android.util.Log
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.QCBluetoothCallbackCloneReceiver
import com.oudmon.ble.base.communication.LargeDataHandler

/**
 * SDK BLE callback receiver. Registered via BleAction.getIntentFilter() in
 * HeyCyanApplication.
 *
 * onServiceDiscovered() is the SDK's "ready" signal — we MUST call initEnable()
 * there to turn on the large-data channel that carries command responses and
 * device-notify events (e.g. the 0x02 capture event). Without it the glasses
 * receive our BLE writes but never reply.
 */
class MyBluetoothReceiver : QCBluetoothCallbackCloneReceiver() {

    override fun onServiceDiscovered() {
        Log.d("ImagePipeline", "onServiceDiscovered: initEnable() — SDK large-data channel ready")
        // REQUIRED before any feature command will get a response.
        LargeDataHandler.getInstance().initEnable()
        BleOperateManager.getInstance().isReady = true
        GlassesManager.onSdkReady()
    }

    override fun connectStatue(device: BluetoothDevice?, connected: Boolean) {
        Log.d("ImagePipeline", "connectStatue connected=$connected addr=${device?.address}")
        if (!connected) {
            GlassesManager.markDisconnected(device?.address)
        }
    }
}
