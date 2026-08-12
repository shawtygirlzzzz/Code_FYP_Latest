package com.malacca.guide

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.IntentFilter
import android.os.Build
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.malacca.guide.ble.BluetoothReceiver
import com.malacca.guide.ble.GlassesManager
import com.malacca.guide.ble.GlassesWifiManager
import com.malacca.guide.ble.MyBluetoothReceiver
import com.malacca.guide.voice.GlassesAudioRouter
import com.oudmon.ble.base.bluetooth.BleAction
import com.oudmon.ble.base.bluetooth.BleBaseControl
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler

class HeyCyanApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        initBle()
        GlassesManager.init(this)
        GlassesWifiManager.init(this)
        GlassesAudioRouter.init(this)
    }

    private fun initBle() {
        LargeDataHandler.getInstance()
        BleOperateManager.getInstance(this)
        BleOperateManager.getInstance().setApplication(this)
        BleOperateManager.getInstance().init()
        BleBaseControl.getInstance(this).setmContext(this)

        LocalBroadcastManager.getInstance(this)
            .registerReceiver(MyBluetoothReceiver(), BleAction.getIntentFilter())

        val systemFilter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(BluetoothReceiver(), systemFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(BluetoothReceiver(), systemFilter)
        }
    }
}
