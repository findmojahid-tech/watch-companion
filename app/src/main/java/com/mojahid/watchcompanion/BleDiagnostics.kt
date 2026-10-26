package com.mojahid.watchcompanion

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

data class BleDeviceRow(val name: String, val address: String)
data class BleInspection(val device: BleDeviceRow, val services: List<String>, val note: String)

class BleDiagnostics(private val context: Context) {
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var callback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null

    fun scan(onUpdate: (List<BleDeviceRow>) -> Unit, onError: (String) -> Unit) {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            onError("Nearby devices permission lagbe.")
            return
        }
        val scanner = adapter?.bluetoothLeScanner ?: run {
            onError("BLE scanner available na.")
            return
        }
        val found = linkedMapOf<String, BleDeviceRow>()
        callback?.let { runCatching { scanner.stopScan(it) } }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = runCatching { device.name }.getOrNull() ?: result.scanRecord?.deviceName ?: "Unnamed BLE"
                found[device.address] = BleDeviceRow(name, device.address)
                onUpdate(found.values.sortedByDescending { it.name.contains("watch", true) })
            }
            override fun onScanFailed(errorCode: Int) = onError("BLE scan error: $errorCode")
        }
        callback = cb
        scanner.startScan(cb)
    }

    fun stopScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        callback?.let { runCatching { scanner.stopScan(it) } }
        callback = null
    }

    fun inspect(row: BleDeviceRow, onResult: (BleInspection) -> Unit, onError: (String) -> Unit) {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            onError("Bluetooth connect permission lagbe.")
            return
        }
        stopScan()
        gatt?.close()
        val dev = runCatching { adapter?.getRemoteDevice(row.address) }.getOrNull() ?: run {
            onError("Device open kora gelo na.")
            return
        }
        gatt = dev.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    onError("BLE disconnected (status " + status + ").")
                }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    onError("Service discovery fail: " + status)
                    return
                }
                val services = g.services.flatMap { svc ->
                    buildList {
                        add("Service " + svc.uuid.toString())
                        svc.characteristics.forEach { ch -> add("  Characteristic " + ch.uuid.toString()) }
                    }
                }
                val note = if (services.isEmpty()) {
                    "BLE connected, but kono service paoa jayni."
                } else {
                    "BLE services found. Ei data diye Jieli/RCSP compatibility verify kora jabe."
                }
                onResult(BleInspection(row, services, note))
            }
        })
    }
}
