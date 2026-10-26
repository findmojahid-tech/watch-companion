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
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.ArrayDeque

data class BleDeviceRow(val name: String, val address: String)
data class BleInspection(val device: BleDeviceRow, val services: List<String>, val note: String)

/**
 * Watch protocol inspection ONLY. No GATT characteristic/descriptor writes, notification
 * subscriptions, OTA commands, watch-face uploads or firmware modifications are performed.
 *
 * In particular A6ED0401 is a Goodix OTA/DFU service, not proof that the watch's UI is
 * programmable or that the main MCU is Goodix. Do not send commands to that service.
 */
class BleDiagnostics(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var scanCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var stopScanRunnable: Runnable? = null
    private var timeoutRunnable: Runnable? = null
    private var generation = 0

    private fun allowed(permission: String): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun scan(onUpdate: (List<BleDeviceRow>) -> Unit, onStatus: (String) -> Unit) {
        if (!allowed(Manifest.permission.BLUETOOTH_SCAN) ||
            !allowed(Manifest.permission.BLUETOOTH_CONNECT)) {
            onStatus("Allow Nearby devices, then scan again.")
            return
        }
        stopScan()
        closeGatt()
        val scanner = adapter?.bluetoothLeScanner ?: run {
            onStatus("Bluetooth LE isn't available or is turned off.")
            return
        }

        val found = linkedMapOf<String, BleDeviceRow>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val row = BleDeviceRow(
                    runCatching { result.device.name }.getOrNull()
                        ?: result.scanRecord?.deviceName ?: "Unnamed BLE device",
                    result.device.address
                )
                handler.post {
                    found[row.address] = row
                    onUpdate(found.values.sortedWith(
                        compareByDescending<BleDeviceRow> {
                            it.name.contains("watch", true) || it.name.contains("w17", true)
                        }.thenBy { it.name }
                    ))
                }
            }

            override fun onScanFailed(errorCode: Int) {
                handler.post { onStatus("Bluetooth scan error " + errorCode + ". Try again.") }
            }
        }

        scanCallback = cb
        runCatching { scanner.startScan(cb) }.onFailure {
            onStatus("Unable to start BLE scan. Check Bluetooth permission.")
            stopScan()
            return
        }

        onStatus("Scanning nearby BLE devices for 12 seconds...")
        val stopper = Runnable {
            stopScan()
            onStatus(
                if (found.isEmpty()) "No BLE devices found. Try with the watch screen awake."
                else "Scan finished. Tap your watch for a read-only protocol audit."
            )
        }
        stopScanRunnable = stopper
        handler.postDelayed(stopper, 12000)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        stopScanRunnable?.let { handler.removeCallbacks(it) }
        stopScanRunnable = null
        val callback = scanCallback
        scanCallback = null
        if (callback != null && allowed(Manifest.permission.BLUETOOTH_SCAN)) {
            runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
        val old = gatt
        gatt = null
        if (old != null) runCatching { old.disconnect(); old.close() }
    }

    private fun serviceLabel(uuid: String): String {
        return when {
            uuid.startsWith("00001800") -> "Generic Access (standard)"
            uuid.startsWith("00001801") -> "Generic Attribute (standard)"
            uuid.startsWith("0000180a") -> "Device Information (standard)"
            uuid.startsWith("0000180f") -> "Battery (standard)"
            uuid.startsWith("00001812") -> "Human Interface Device / HID (standard)"
            uuid.startsWith("a6ed0401", true) ->
                "Goodix OTA/DFU firmware update channel -- DO NOT WRITE"
            uuid.startsWith("6e4000", true) ->
                "Custom 6E40 vendor channel -- protocol UNKNOWN"
            else -> "Unidentified service"
        }
    }

    private fun characteristicProperties(ch: BluetoothGattCharacteristic): String {
        val bits = ch.properties
        val flags = mutableListOf<String>()
        if (bits and BluetoothGattCharacteristic.PROPERTY_READ != 0) flags += "READ"
        if (bits and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) flags += "WRITE"
        if (bits and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) flags += "WRITE_NO_RESPONSE"
        if (bits and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) flags += "NOTIFY"
        if (bits and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) flags += "INDICATE"
        if (bits and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) flags += "SIGNED_WRITE"
        return if (flags.isEmpty()) "none" else flags.joinToString("|")
    }

    private fun standardReadLabel(uuid: String): String? = when {
        uuid.startsWith("00002a00") -> "Device name"
        uuid.startsWith("00002a26") -> "Firmware revision"
        uuid.startsWith("00002a27") -> "Hardware revision"
        uuid.startsWith("00002a28") -> "Software revision"
        uuid.startsWith("00002a29") -> "Manufacturer"
        uuid.startsWith("00002a19") -> "Battery level"
        else -> null
    }

    private fun prettyValue(ch: BluetoothGattCharacteristic, bytes: ByteArray): String {
        if (bytes.isEmpty()) return "(empty)"
        if (ch.uuid.toString().startsWith("00002a19")) {
            return (bytes[0].toInt() and 0xff).toString() + "%"
        }
        val clean = String(bytes, Charsets.UTF_8).trim().trim('\u0000')
        return if (clean.isNotBlank() && clean.length <= 160 &&
            clean.all { it.isLetterOrDigit() || it == ' ' || it in "-_.,:+/()" }) {
            clean
        } else {
            bytes.take(48).joinToString(" ") { "%02X".format(it.toInt() and 0xff) } +
                if (bytes.size > 48) " ..." else ""
        }
    }

    @SuppressLint("MissingPermission")
    fun inspect(
        row: BleDeviceRow,
        onResult: (BleInspection) -> Unit,
        onStatus: (String) -> Unit
    ) {
        if (!allowed(Manifest.permission.BLUETOOTH_CONNECT)) {
            onStatus("Bluetooth Connect permission is needed.")
            return
        }
        stopScan()
        generation += 1
        closeGatt()
        val session = generation
        var finished = false
        val details = mutableListOf<String>()
        val readQueue = ArrayDeque<BluetoothGattCharacteristic>()
        val device = runCatching { adapter?.getRemoteDevice(row.address) }.getOrNull() ?: run {
            onStatus("That BLE address isn't available. Scan again.")
            return
        }

        fun finish(message: String, successful: Boolean) {
            handler.post {
                if (session != generation || finished) return@post
                finished = true
                closeGatt()
                if (successful) {
                    onResult(BleInspection(row, details.toList(), message))
                } else {
                    onStatus(message + " Bluetooth call audio is a separate connection.")
                }
            }
        }

        fun startNextRead(g: BluetoothGatt) {
            if (session != generation || finished) return
            if (readQueue.isEmpty()) {
                finish(
                    "Read-only audit completed. The temporary BLE connection is now " +
                        "closed intentionally. A Goodix OTA UUID is not a watch-face API. " +
                        "No firmware or watch settings were changed.",
                    true
                )
                return
            }
            val ch = readQueue.removeFirst()
            val label = standardReadLabel(ch.uuid.toString()) ?: "Standard information"
            if (!runCatching { g.readCharacteristic(ch) }.getOrDefault(false)) {
                details += "  " + label + ": not readable (read request rejected)"
                startNextRead(g)
            }
        }

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
                if (session != generation || finished) return
                if (status == BluetoothGatt.GATT_SUCCESS &&
                    state == BluetoothProfile.STATE_CONNECTED) {
                    if (!runCatching { g.discoverServices() }.getOrDefault(false)) {
                        finish("BLE service discovery could not start.", false)
                    }
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    finish(
                        "Watch closed the diagnostic BLE session (code " + status + "). " +
                            "Its firmware or companion app may limit concurrent BLE connections.",
                        false
                    )
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (session != generation || finished) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finish("BLE service discovery failed, code " + status + ".", false)
                    return
                }
                handler.post {
                    if (session != generation || finished) return@post
                    g.services.forEach { service ->
                        val serviceId = service.uuid.toString()
                        details += "Service: " + serviceId + " [" + serviceLabel(serviceId) + "]"
                        service.characteristics.forEach { ch ->
                            val properties = characteristicProperties(ch)
                            details += "  Characteristic: " + ch.uuid.toString() +
                                " [" + properties + "]"
                            val label = standardReadLabel(ch.uuid.toString())
                            if (label != null &&
                                ch.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 &&
                                (serviceId.startsWith("00001800") ||
                                 serviceId.startsWith("0000180a") ||
                                 serviceId.startsWith("0000180f"))) {
                                readQueue.addLast(ch)
                            }
                        }
                    }
                    onStatus("Found services. Reading standard device info only...")
                    startNextRead(g)
                }
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(
                g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
            ) {
                handleRead(g, characteristic, characteristic.value ?: byteArrayOf(), status)
            }

            override fun onCharacteristicRead(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                handleRead(g, characteristic, value, status)
            }

            private fun handleRead(
                g: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
                bytes: ByteArray,
                status: Int
            ) {
                handler.post {
                    if (session != generation || finished) return@post
                    val label = standardReadLabel(ch.uuid.toString()) ?: "Info"
                    details += if (status == BluetoothGatt.GATT_SUCCESS) {
                        "  " + label + ": " + prettyValue(ch, bytes)
                    } else {
                        "  " + label + ": read unavailable (status " + status + ")"
                    }
                    startNextRead(g)
                }
            }
        }

        onStatus("Inspecting " + row.name + " read-only BLE profile...")
        val opened = runCatching {
            device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()
        if (opened == null) {
            finish("Could not open BLE diagnostics.", false)
            return
        }
        gatt = opened

        val timeout = Runnable {
            if (session == generation && !finished) {
                val whatRead = details.size
                finish(
                    "BLE audit timed out after reading " + whatRead + " lines. " +
                        "Try while the watch is awake; close its official companion app if necessary.",
                    false
                )
            }
        }
        timeoutRunnable = timeout
        handler.postDelayed(timeout, 18000)
    }

    fun close() {
        generation += 1
        stopScan()
        closeGatt()
        handler.removeCallbacksAndMessages(null)
    }
}
