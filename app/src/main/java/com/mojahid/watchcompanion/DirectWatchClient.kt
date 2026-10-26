package com.mojahid.watchcompanion

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class DirectWatchStatus(
    val state: String = "Not connected",
    val deviceName: String = "",
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val battery: Int? = null,
    val manufacturer: String = "",
    val firmware: String = "",
    val hardware: String = "",
    val message: String = "Connect Watch 7 directly from Momo. MActivePro is not required.",
    val vendorServices: List<String> = emptyList(),
    val vendorCharacteristics: List<String> = emptyList()
)

/**
 * One GATT connection owned by Momo itself. Android Bluetooth settings handle
 * OS pairing; the official vendor companion app is NOT involved.
 *
 * Safe operations: discover GATT services, read standard device information
 * and battery. NEVER write a proprietary characteristic, OTA/DFU channel,
 * flash memory, custom dial, or any descriptor.
 */
class DirectWatchClient(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val prefs = context.getSharedPreferences("momo_direct_watch", Context.MODE_PRIVATE)

    private val mutable = MutableStateFlow(DirectWatchStatus())
    val state: StateFlow<DirectWatchStatus> = mutable

    private var gatt: BluetoothGatt? = null
    private var session = 0
    private var connected = false
    private var timeout: Runnable? = null
    private var retry: Runnable? = null
    private var autoReconnect = false
    private var retryCount = 0
    private var selectedWatch: BleDeviceRow? = null
    private val readQueue = ArrayDeque<BluetoothGattCharacteristic>()

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun pairedWatches(): List<BleDeviceRow> {
        if (!hasPermission()) return emptyList()
        return runCatching {
            val devices = adapter?.bondedDevices.orEmpty()
            devices.filter { device ->
                val name = device.name.orEmpty().lowercase(Locale.ROOT)
                (name.contains("watch") || name.contains("w17") ||
                    name.contains("momo") || name.contains("mactive")) &&
                    !name.contains("watch call")
            }.map { device ->
                BleDeviceRow(device.name ?: "Paired watch", device.address)
            }.sortedBy { if (it.name.contains("Watch 7", true)) 0 else 1 }
        }.getOrDefault(emptyList())
    }

    fun knownWatch(): BleDeviceRow? {
        val address = prefs.getString("address", null) ?: return null
        val name = prefs.getString("name", "Watch 7") ?: "Watch 7"
        return BleDeviceRow(name, address)
    }

    private fun emit(update: DirectWatchStatus) {
        if (Looper.myLooper() == Looper.getMainLooper()) mutable.value = update
        else handler.post { mutable.value = update }
    }

    private fun cancelTimeout() {
        timeout?.let { handler.removeCallbacks(it) }
        timeout = null
    }

    private fun cancelRetry() {
        retry?.let { handler.removeCallbacks(it) }
        retry = null
    }

    private fun scheduleRetry(message: String) {
        val watch = selectedWatch ?: return
        if (!autoReconnect || !hasPermission() || adapter?.isEnabled != true) return
        if (retryCount >= 3) {
            emit(mutable.value.copy(
                state = "Disconnected", isConnecting = false, isConnected = false,
                message = message + " Automatic reconnect paused after 3 attempts. Tap Connect."
            ))
            return
        }
        retryCount++
        val delayMillis = when (retryCount) { 1 -> 2000L; 2 -> 5000L; else -> 10000L }
        emit(mutable.value.copy(
            state = "Reconnecting", isConnecting = false, isConnected = false,
            message = "Watch link lost. Retrying " + retryCount + "/3 in " +
                (delayMillis / 1000L) + " seconds."
        ))
        val currentSession = session
        val runnable = Runnable {
            retry = null
            if (autoReconnect && currentSession == session) {
                connectInternal(watch, userInitiated = false)
            }
        }
        retry = runnable
        handler.postDelayed(runnable, delayMillis)
    }

    @SuppressLint("MissingPermission")
    private fun closeLink() {
        cancelTimeout()
        readQueue.clear()
        connected = false
        val old = gatt
        gatt = null
        if (old != null) runCatching { old.disconnect(); old.close() }
    }

    @SuppressLint("MissingPermission")
    fun connect(row: BleDeviceRow) {
        retryCount = 0
        autoReconnect = true
        selectedWatch = row
        connectInternal(row, userInitiated = true)
    }

    @SuppressLint("MissingPermission")
    private fun connectInternal(row: BleDeviceRow, userInitiated: Boolean) {
        cancelRetry()
        if (!hasPermission()) {
            emit(DirectWatchStatus(
                state = "Permission needed",
                message = "Allow Nearby devices in Android settings, then try Connect again."
            ))
            return
        }
        if (adapter?.isEnabled != true) {
            emit(DirectWatchStatus(
                state = "Bluetooth off",
                message = "Switch on Bluetooth in phone Settings first."
            ))
            return
        }
        session++
        val current = session
        closeLink()
        val device = runCatching { adapter?.getRemoteDevice(row.address) }.getOrNull()
        if (device == null) {
            emit(DirectWatchStatus(
                state = "Invalid watch",
                message = "That watch address is no longer available. Scan again."
            ))
            return
        }
        val friendlyName = row.name.removeSuffix(" [paired]").removeSuffix(" [last seen]")
        emit(DirectWatchStatus(
            state = "Connecting...", isConnecting = true, deviceName = friendlyName,
            message = (if (userInitiated) "Opening " else "Reopening ") +
                "a direct Bluetooth connection to " + friendlyName
        ))
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                connection: BluetoothGatt, status: Int, newState: Int
            ) {
                handler.post {
                    if (current != session) return@post
                    if (status == BluetoothGatt.GATT_SUCCESS &&
                        newState == BluetoothProfile.STATE_CONNECTED) {
                        connected = true
                        emit(mutable.value.copy(
                            state = "Reading watch...", isConnecting = true,
                            message = "Connected directly. Discovering standard watch services."
                        ))
                        val ok = runCatching { connection.discoverServices() }.getOrDefault(false)
                        if (!ok) fail(current, "Couldn't start service discovery.")
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        fail(current, if (status == 0) {
                            "Watch ended the Bluetooth session. Tap Reconnect to try again."
                        } else {
                            "Watch disconnected (Bluetooth code " + status +
                                "). Keep its screen awake and retry."
                        })
                    } else if (status != 0) {
                        fail(current, "Bluetooth connection rejected (code " + status + ").")
                    }
                }
            }

            override fun onServicesDiscovered(connection: BluetoothGatt, status: Int) {
                handler.post {
                    if (current != session) return@post
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        fail(current, "Could not discover services (code " + status + ").")
                        return@post
                    }
                    cancelTimeout()
                    retryCount = 0
                    val manufacturerUuid = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
                    val firmwareUuid = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
                    val hardwareUuid = UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb")
                    val batteryUuid = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
                    val knownReads = setOf(manufacturerUuid, firmwareUuid, hardwareUuid, batteryUuid)
                    readQueue.clear()
                    val vendorServices = mutableListOf<String>()
                    val vendorCharacteristics = mutableListOf<String>()
                    connection.services.forEach { service ->
                        val serviceId = service.uuid.toString()
                        val isVendor = !serviceId.startsWith("000018")
                        if (isVendor) {
                            vendorServices += serviceId
                        }
                        service.characteristics.forEach { ch ->
                            if (isVendor) {
                                val properties = ch.properties
                                val flags = buildList {
                                    if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
                                    if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
                                    if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NO_RESPONSE")
                                    if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
                                    if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
                                }.joinToString("|").ifBlank { "NONE" }
                                vendorCharacteristics += serviceId + " -> " +
                                    ch.uuid.toString() + " [" + flags + "]"
                            }

                            if (ch.uuid in knownReads &&
                                ch.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                                readQueue.addLast(ch)
                            }
                        }
                    }
                    // Direct link remains open. No OTA or custom vendor payloads sent.
                    prefs.edit().putString("address", row.address)
                        .putString("name", friendlyName).apply()
                    emit(mutable.value.copy(
                        state = "Connected", isConnected = true, isConnecting = false,
                        message = "Watch 7 is linked directly to Momo (BLE). " +
                            "Watch call audio is a separate Bluetooth profile.",
                        vendorServices = vendorServices,
                        vendorCharacteristics = vendorCharacteristics
                    ))
                    readNext(current)
                }
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(
                connection: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (android.os.Build.VERSION.SDK_INT < 33) {
                    readDone(current, characteristic, characteristic.value ?: byteArrayOf(), status)
                }
            }

            override fun onCharacteristicRead(
                connection: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                readDone(current, characteristic, value, status)
            }
        }
        val opened = runCatching {
            device.connectGatt(context.applicationContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()
        if (opened == null) {
            fail(current, "Can't establish a BLE link. Use Find Watch below.")
            return
        }
        gatt = opened
        val stop = Runnable {
            if (session == current && !mutable.value.isConnected) {
                fail(current, "Connection timed out. Unlock the watch and retry.")
            }
        }
        timeout = stop
        handler.postDelayed(stop, 15000)
    }

    private fun readDone(
        connectionId: Int,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
        status: Int
    ) {
        handler.post {
            if (connectionId != session || !connected) return@post
            if (status == BluetoothGatt.GATT_SUCCESS && data.isNotEmpty()) {
                val id = characteristic.uuid.toString().substring(4, 8).lowercase(Locale.ROOT)
                val stringValue = String(data, Charsets.UTF_8).trim().trim('\u0000').take(70)
                val next = when (id) {
                    "2a19" -> mutable.value.copy(battery = data[0].toInt() and 0xff)
                    "2a29" -> mutable.value.copy(manufacturer = stringValue)
                    "2a26" -> mutable.value.copy(firmware = stringValue)
                    "2a27" -> mutable.value.copy(hardware = stringValue)
                    else -> mutable.value
                }
                emit(next)
            }
            readNext(connectionId)
        }
    }

    @SuppressLint("MissingPermission")
    private fun readNext(connectionId: Int) {
        if (connectionId != session || !connected) return
        while (readQueue.isNotEmpty()) {
            val char = readQueue.removeFirst()
            val success = runCatching { gatt?.readCharacteristic(char) == true }.getOrDefault(false)
            if (success) return
        }
    }

    private fun fail(connectionId: Int, message: String) {
        if (connectionId != session) return
        closeLink()
        emit(mutable.value.copy(
            state = "Disconnected", isConnecting = false, isConnected = false,
            message = message
        ))
        scheduleRetry(message)
    }

    fun reconnect() {
        val saved = knownWatch()
        if (saved != null) connect(saved)
        else emit(DirectWatchStatus(
            state = "Choose a watch",
            message = "Use Find Watch or pair Watch 7 in Android Bluetooth settings."
        ))
    }

    fun disconnect() {
        autoReconnect = false
        cancelRetry()
        session++
        closeLink()
        emit(DirectWatchStatus(
            state = "Disconnected", message = "Direct Watch 7 link closed by Momo."
        ))
    }

    fun close() {
        autoReconnect = false
        cancelRetry()
        session++
        closeLink()
        handler.removeCallbacksAndMessages(null)
    }
}
