package de.lukasrunge.verweil.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.LifecycleOwner
import de.lukasrunge.verweil.core.model.CarConnection
import de.lukasrunge.verweil.hasPermission
import androidx.car.app.connection.CarConnection as AndroidAutoConnection

/**
 * Tells the engine when the phone is in a car: Android Auto runs, or a Bluetooth device the user marked as their car
 * is connected. Reports once at start and then on every change. Call everything on the main thread.
 */
class CarDetector(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val onChange: (CarConnection) -> Unit,
) {
    private val bluetooth = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var carDevices: Set<String> = emptySet()
    private val connected = mutableSetOf<String>()
    private var projecting = false
    private var listening = false
    private var reported: Boolean? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> connected += device.address
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> connected -= device.address
            }
            report()
        }
    }

    fun start() {
        AndroidAutoConnection(context).type.observe(owner) { type ->
            projecting = type != AndroidAutoConnection.CONNECTION_TYPE_NOT_CONNECTED
            report()
        }
    }

    fun stop() {
        if (listening) context.unregisterReceiver(receiver)
        listening = false
    }

    /** The Bluetooth addresses of the user's cars; listening starts with the first one, once allowed. */
    fun setCarDevices(addresses: Set<String>) {
        carDevices = addresses
        if (addresses.isNotEmpty() && !listening && context.canUseBluetooth() && bluetooth != null) {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                    addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            listening = true
            readConnectedDevices()
        }
        report()
    }

    /** A car connected before tracking started sends no broadcast; its audio and phone links show it. */
    @SuppressLint("MissingPermission") // Checked in setCarDevices.
    private fun readConnectedDevices() {
        listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET).forEach { profile ->
            bluetooth?.getProfileProxy(
                context,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        connected += proxy.connectedDevices.map { it.address }
                        bluetooth.closeProfileProxy(profile, proxy)
                        report()
                    }

                    override fun onServiceDisconnected(profile: Int) = Unit
                },
                profile,
            )
        }
    }

    private fun report() {
        val inCar = projecting || connected.any { it in carDevices }
        if (inCar == reported) return
        reported = inCar
        onChange(CarConnection(System.currentTimeMillis(), inCar))
    }
}

/** Bluetooth devices may be listed and watched: always below Android 12, after asking from then on. */
fun Context.canUseBluetooth(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasPermission(Manifest.permission.BLUETOOTH_CONNECT)

/** The phone's paired Bluetooth devices as address to name, for choosing the car. */
@SuppressLint("MissingPermission") // Checked first.
fun Context.pairedBluetoothDevices(): Map<String, String> {
    if (!canUseBluetooth()) return emptyMap()
    val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyMap()
    return adapter.bondedDevices.orEmpty().associate { it.address to (it.name ?: it.address) }
}
