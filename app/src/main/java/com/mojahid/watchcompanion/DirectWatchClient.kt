package com.mojahid.watchcompanion

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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

/** Ephemeral capture: never uploaded, persisted or sent to another app automatically. */
data class WatchProtocolCapture(
    val running: Boolean = false,
    val status: String = "Not started",
    val events: List<String> = emptyList(),
    val receivedPackets: Int = 0
)

/**
 * One GATT connection owned by Momo itself. Android Bluetooth settings handle
 * OS pairing; the official vendor companion app is NOT involved.
 *
 * Safe operations: discover services; read standard/device vendor info; and
 * with explicit user action subscribe to the exact 4.21 vendor NOTIFY
 * characteristics via the standard CCCD descriptor only.
 * NEVER write proprietary characteristic payloads or OTA/DFU data.
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
    private var standardReadPending = false

    private val captureMutable = MutableStateFlow(WatchProtocolCapture())
    val protocolCapture: StateFlow<WatchProtocolCapture> = captureMutable
    private val probeReads = ArrayDeque<BluetoothGattCharacteristic>()
    private val probeNotifications = ArrayDeque<BluetoothGattCharacteristic>()
    private var probeReadUuid: UUID? = null
    private var probeDescriptorUuid: UUID? = null
    private var probeTimer: Runnable? = null
    private var probeRunning = false
    private var probeIdSent = false
    private var probePendingWriteUuid: UUID? = null

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

    private fun addProbeEvent(text: String, packet: Boolean = false) {
        val previous = captureMutable.value
        captureMutable.value = previous.copy(
            events = (previous.events + text.take(260)).takeLast(Watch7PassiveProbe.MAX_EVENTS),
            receivedPackets = previous.receivedPackets + if (packet) 1 else 0
        )
    }

    /**
     * Uses the existing GATT session. Bounded 30-second read/notify
     * experiment; never guesses watch commands, edits phone settings or
     * transmits face/OTA data. The session is closed on exit so standard
     * notification subscriptions cannot linger, then normal BLE reconnects.
     */
    fun startPassiveCapture() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { startPassiveCapture() }
            return
        }
        if (probeRunning) return
        val connection = gatt
        val state = mutable.value
        if (connection == null || !connected || !state.isConnected) {
            captureMutable.value = WatchProtocolCapture(status =
                "Connect Watch 7 via Momo first; capture was not started.")
            return
        }
        val profile = Watch7GattProfile.assess(
            state.firmware, state.hardware, state.vendorServices, state.vendorCharacteristics
        )
        if (!profile.exactFirmware421Profile) {
            captureMutable.value = WatchProtocolCapture(status =
                "Capture blocked: this connection does not exactly match the verified " +
                "Watch 7 firmware 4.21 / hardware 1.0 profile.")
            return
        }

        probeReads.clear()
        probeNotifications.clear()
        connection.services.forEach { service ->
            service.characteristics.forEach { characteristic ->
                val serviceId = service.uuid.toString()
                val charId = characteristic.uuid.toString()
                if (Watch7PassiveProbe.canRead(serviceId, charId, characteristic.properties)) {
                    probeReads.addLast(characteristic)
                }
                if (Watch7PassiveProbe.canSubscribe(serviceId, charId, characteristic.properties)) {
                    probeNotifications.addLast(characteristic)
                }
            }
        }
        if (probeReads.size != 5 || probeNotifications.size != 8) {
            captureMutable.value = WatchProtocolCapture(status =
                "Expected five safe vendor reads and eight notification sources. " +
                "Observed a different profile; capture blocked.")
            probeReads.clear()
            probeNotifications.clear()
            return
        }

        probeRunning = true
        probeIdSent = false
        probePendingWriteUuid = null
        probeReadUuid = null
        probeDescriptorUuid = null
        captureMutable.value = WatchProtocolCapture(
            running = true,
            status = "Watch-only capture started. Change a face or menu on the WATCH " +
                "itself during the next 30 seconds. No dial or firmware is sent."
        )
        val timer = Runnable {
            finishPassiveCapture("30-second capture finished; Momo is reconnecting BLE.")
        }
        probeTimer = timer
        handler.postDelayed(timer, Watch7PassiveProbe.WINDOW_MILLIS)
        advanceProbe()
    }

    /**
     * A single GATT operation at a time. Only the standard CCCD 0x2902 is
     * written, strictly for a characteristic from the fixed allowlist.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun advanceProbe() {
        if (!probeRunning || !connected || standardReadPending ||
            probeReadUuid != null || probeDescriptorUuid != null) return
        val connection = gatt ?: return
        while (probeReads.isNotEmpty()) {
            val ch = probeReads.removeFirst()
            probeReadUuid = ch.uuid
            if (runCatching { connection.readCharacteristic(ch) }.getOrDefault(false)) return
            probeReadUuid = null
            addProbeEvent("READ " + ch.uuid.toString().substring(0, 8) + " rejected")
        }
        val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        while (probeNotifications.isNotEmpty()) {
            val ch = probeNotifications.removeFirst()
            val serviceId = ch.service?.uuid?.toString().orEmpty()
            if (!Watch7PassiveProbe.canSubscribe(
                    serviceId, ch.uuid.toString(), ch.properties)) continue
            val descriptor = ch.getDescriptor(cccdUuid)
            if (descriptor == null) {
                addProbeEvent("NO CCCD for " + ch.uuid.toString().substring(0, 8))
                continue
            }
            val listening = runCatching {
                connection.setCharacteristicNotification(ch, true)
            }.getOrDefault(false)
            if (!listening) {
                addProbeEvent("NOTIFY local setup rejected for " +
                    ch.uuid.toString().substring(0, 8))
                continue
            }
            probeDescriptorUuid = ch.uuid
            // Standard notification subscription only; no vendor packet/OTA.
            val sent = runCatching {
                if (Build.VERSION.SDK_INT >= 33) {
                    connection.writeDescriptor(
                        descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    connection.writeDescriptor(descriptor)
                }
            }.getOrDefault(false)
            if (sent) return
            probeDescriptorUuid = null
            addProbeEvent("CCCD subscription rejected for " +
                ch.uuid.toString().substring(0, 8))
        }
        captureMutable.value = captureMutable.value.copy(
            status = "Vendor reads finished. Watching for BLE notifications; " +
                "interact with the watch until the 30-second capture ends."
        )
    }

    /**
     * ONE explicit watch-only experiment per capture. The fixed six-byte
     * request is an NJJ product-ID QUERY from a DIFFERENT watch family, so a
     * reply is NOT guaranteed. Never send it to the Goodix service. This is
     * not a dial uploader and never touches the Android phone firmware.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun sendExperimentalProductId(endpoint: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { sendExperimentalProductId(endpoint) }
            return
        }
        if (!probeRunning || !connected || probeIdSent) {
            addProbeEvent("ID query not sent: start a new 30s capture first.")
            return
        }
        if (standardReadPending || probeReadUuid != null ||
            probeDescriptorUuid != null || probeNotifications.isNotEmpty() ||
            probeReads.isNotEmpty()) {
            addProbeEvent("ID query not sent: wait for vendor notification setup.")
            return
        }
        val uuid = when (endpoint) {
            "0b" -> "6e40000b-b5a3-f393-e0a9-e50e24dcca9e"
            "0c" -> "6e40000c-b5a3-f393-e0a9-e50e24dcca9e"
            else -> {
                addProbeEvent("ID query blocked: endpoint is not 0b or 0c.")
                return
            }
        }
        val connection = gatt ?: return
        val service = connection.getService(UUID.fromString(Watch7PassiveProbe.VENDOR_SERVICE))
        val characteristic = service?.getCharacteristic(UUID.fromString(uuid))
        if (characteristic == null || !Watch7PassiveProbe.canProbe(
                Watch7PassiveProbe.VENDOR_SERVICE, uuid, characteristic.properties)) {
            addProbeEvent("ID query blocked: write-with-response endpoint not found.")
            return
        }
        probeIdSent = true // no retry, even after a failed attempt
        probePendingWriteUuid = characteristic.uuid
        val packet = Watch7PassiveProbe.speculativeProductIdQuery
        addProbeEvent("TX ID QUERY on " + uuid.substring(0, 8) + ": " +
            Watch7PassiveProbe.briefHex(packet))
        val accepted = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                connection.writeCharacteristic(
                    characteristic, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                characteristic.value = packet
                connection.writeCharacteristic(characteristic)
            }
        }.getOrDefault(false)
        if (!accepted) {
            probePendingWriteUuid = null
            addProbeEvent("ID query was rejected by Android Bluetooth (no retry).")
        }
    }

    fun stopPassiveCapture() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { stopPassiveCapture() }
            return
        }
        if (probeRunning) finishPassiveCapture("Capture stopped; restoring direct watch BLE.")
    }

    private fun finishPassiveCapture(message: String) {
        if (!probeRunning) return
        probeRunning = false
        probeTimer?.let(handler::removeCallbacks)
        probeTimer = null
        captureMutable.value = captureMutable.value.copy(running = false, status = message)
        session++ // Discard all late GATT callbacks from the lab link.
        closeLink() // Disconnect clears temporary CCCD subscriptions on the watch.
        emit(mutable.value.copy(
            state = "Disconnected", isConnected = false, isConnecting = false,
            message = "Watch-only capture ended. Restoring Momo Bluetooth connection."
        ))
        scheduleRetry("Watch-only capture completed.")
    }

    private fun logProbeNotification(
        connectionId: Int,
        connection: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        bytes: ByteArray
    ) {
        handler.post {
            if (connectionId != session || connection !== gatt || !probeRunning) return@post
            val serviceId = ch.service?.uuid?.toString().orEmpty()
            if (!Watch7PassiveProbe.canSubscribe(
                    serviceId, ch.uuid.toString(), ch.properties)) return@post
            addProbeEvent(Watch7PassiveProbe.entry("NOTIFY", ch.uuid.toString(), bytes), true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeLink() {
        cancelTimeout()
        probeTimer?.let(handler::removeCallbacks)
        probeTimer = null
        if (probeRunning) {
            probeRunning = false
            captureMutable.value = captureMutable.value.copy(
                running = false,
                status = "Capture stopped because the watch connection closed."
            )
        }
        probeReads.clear()
        probeNotifications.clear()
        probeReadUuid = null
        probeDescriptorUuid = null
        probePendingWriteUuid = null
        probeIdSent = false
        standardReadPending = false
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
        prefs.edit().putBoolean("connect_enabled", true).apply()
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
                    if (current != session || connection !== gatt) return@post
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
                    if (current != session || connection !== gatt) return@post
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

            override fun onCharacteristicWrite(
                connection: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                handler.post {
                    if (current != session || connection !== gatt || !probeRunning ||
                        probePendingWriteUuid != characteristic.uuid) return@post
                    probePendingWriteUuid = null
                    addProbeEvent(
                        if (status == BluetoothGatt.GATT_SUCCESS)
                            "ID query GATT accepted; watch reply still unverified."
                        else "ID query GATT failed: " + status + " (no retry)."
                    )
                }
            }

            override fun onDescriptorWrite(
                connection: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                handler.post {
                    if (current != session || connection !== gatt || !probeRunning ||
                        probeDescriptorUuid != descriptor.characteristic.uuid) return@post
                    addProbeEvent("CCCD " +
                        descriptor.characteristic.uuid.toString().substring(0, 8) +
                        if (status == BluetoothGatt.GATT_SUCCESS) " subscribed"
                        else " error " + status)
                    probeDescriptorUuid = null
                    advanceProbe()
                }
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                connection: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                if (Build.VERSION.SDK_INT < 33) {
                    logProbeNotification(
                        current, connection, characteristic, characteristic.value ?: byteArrayOf()
                    )
                }
            }

            override fun onCharacteristicChanged(
                connection: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                logProbeNotification(current, connection, characteristic, value)
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
            if (probeRunning && probeReadUuid == characteristic.uuid) {
                probeReadUuid = null
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    addProbeEvent(
                        Watch7PassiveProbe.entry(
                            "READ", characteristic.uuid.toString(), data
                        )
                    )
                } else {
                    addProbeEvent("READ " +
                        characteristic.uuid.toString().substring(0, 8) +
                        " GATT error " + status)
                }
                advanceProbe()
                return@post
            }
            standardReadPending = false
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
            if (success) {
                standardReadPending = true
                return
            }
        }
        if (probeRunning) advanceProbe()
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

    fun reconnectIfEnabled() {
        if (state.value.isConnected || state.value.isConnecting) return
        if (!prefs.getBoolean("connect_enabled", false)) return
        if (!hasPermission() || adapter?.isEnabled != true) return
        knownWatch()?.let { connect(it) }
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
        prefs.edit().putBoolean("connect_enabled", false).apply()
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
