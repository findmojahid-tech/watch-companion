package com.mojahid.watchcompanion

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
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

data class BleDeviceRow(val name: String, val address: String)
data class BleInspection(val device: BleDeviceRow, val services: List<String>, val note: String)

/**
 * Read-only GATT diagnostics. It is NOT a persistent watch-control connection.
 * A normal GATT disconnect after discovery does not mean Bluetooth call audio disconnected.
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
                else "Scan finished. Tap your watch to inspect read-only BLE services."
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
        closeGatt()
        generation += 1
        val currentGeneration = generation
        var finished = false
        val device = runCatching { adapter?.getRemoteDevice(row.address) }.getOrNull() ?: run {
            onStatus("That BLE address isn't available. Scan again.")
            return
        }

        fun reportFailure(message: String) {
            handler.post {
                if (currentGeneration != generation || finished) return@post
                finished = true
                closeGatt()
                onStatus(message + " Bluetooth call audio is separate from this test.")
            }
        }

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
                if (currentGeneration != generation) return
                if (status == BluetoothGatt.GATT_SUCCESS &&
                    state == BluetoothProfile.STATE_CONNECTED) {
                    val requested = runCatching { g.discoverServices() }.getOrDefault(false)
                    if (!requested) reportFailure("Service discovery couldn't start.")
                } else if (state == BluetoothProfile.STATE_DISCONNECTED && !finished) {
                    reportFailure(
                        "The watch ended this diagnostic BLE session (code " + status +
                            "). Its firmware or companion app may only allow one BLE client."
                    )
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (currentGeneration != generation || finished) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    reportFailure("BLE service discovery failed (code " + status + ").")
                    return
                }

                val names = g.services.flatMap { service ->
                    buildList {
                        add("Service: " + service.uuid.toString())
                        service.characteristics.forEach { characteristic ->
                            add("  Characteristic: " + characteristic.uuid.toString())
                        }
                    }
                }
                finished = true
                handler.post {
                    if (currentGeneration != generation) return@post
                    closeGatt()
                    onResult(
                        BleInspection(
                            row, names,
                            "BLE inspection completed. The short diagnostic connection was " +
                                "closed intentionally. Watch call audio should remain separate. " +
                                "Watch-face protocol compatibility is NOT confirmed yet."
                        )
                    )
                }
            }
        }

        onStatus("Connecting to " + row.name + " to inspect read-only BLE services...")
        val opened = runCatching {
            device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()
        if (opened == null) {
            reportFailure("Unable to open a GATT connection.")
            return
        }
        gatt = opened

        val timeout = Runnable {
            if (currentGeneration == generation && !finished) {
                finished = true
                closeGatt()
                onStatus(
                    "BLE inspection timed out. The watch may be controlled by another app. " +
                        "This does not prove watch call audio is disconnected."
                )
            }
        }
        timeoutRunnable = timeout
        handler.postDelayed(timeout, 11000)
    }

    fun close() {
        generation += 1
        stopScan()
        closeGatt()
        handler.removeCallbacksAndMessages(null)
    }
}
