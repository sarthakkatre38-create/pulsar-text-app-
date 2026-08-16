package com.example.pulsartext

import android.app.*
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * Foreground service that owns the BLE connection to the bike cluster.
 *
 * Rewritten from the original classic-SPP (RFCOMM/AT+CLIP) approach after
 * confirming — by decompiling the official Bajaj Ride Connect app with JADX —
 * that the cluster actually speaks BLE (BluetoothGatt), not classic SPP.
 *
 * Protocol notes (reverse-engineered from com.bajajconnect.ble.BleService,
 * com.bajajconnect.utils.CallFrame, and com.bajajconnect.variables.GlobalVar):
 *
 *  - No proprietary handshake or crypto — just a standard BLE connect ->
 *    discoverServices -> requestMtu(256) -> writeCharacteristic sequence.
 *  - The characteristic that carries the caller-name field is GENERAL_CHAR,
 *    UUID 0210676e-6972-6565-6e69-676e4543544f.
 *  - The frame is 55 bytes. Byte[1] low bits carry the call-state code
 *    (1 = INCOMING_CALL), and only when the call state is INCOMING or ACTIVE
 *    does the cluster read the name field: byte[20] = name length (max 30),
 *    bytes[21..50] = UTF-8 name bytes.
 *  - The real app re-sends this frame roughly every second as a heartbeat;
 *    we do the same so the text stays displayed rather than timing out.
 *  - 30 characters is a hard cap enforced here on the phone side — text
 *    beyond that never gets sent, regardless of how the cluster's own
 *    marquee/scroll display renders what it does receive.
 */
class BikeBluetoothService : Service() {

    companion object {
        private const val TAG = "BikeBluetoothService"
        private const val CHANNEL_ID = "bike_bt_channel"
        private const val NOTIF_ID = 1

        // GENERAL characteristic UUID, decoded from the official app.
        val GENERAL_CHAR_UUID: UUID = UUID.fromString("0210676e-6972-6565-6e69-676e4543544f")

        // Hard cap confirmed from CallFrame.phoneStatusNew(): only the first
        // 30 bytes of the name are ever copied into the frame.
        const val MAX_MESSAGE_LENGTH = 30

        private const val WRITE_INTERVAL_MS = 1000L
        private const val SCAN_TIMEOUT_MS = 10000L

        // Broadcast actions the Activity listens for (unchanged from before)
        const val ACTION_STATUS = "com.example.pulsartext.STATUS"
        const val EXTRA_STATUS = "status"
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var bluetoothGatt: BluetoothGatt? = null
    private var generalChar: BluetoothGattCharacteristic? = null
    private var isMtuIncreased = false
    private var heartbeat = 0
    private var repeatJob: Job? = null
    private var scanner: BluetoothLeScanner? = null
    private var isScanning = false

    /** Message currently being shown as the "caller name" on the cluster. */
    private var customMessage: String = "HI"

    inner class LocalBinder : android.os.Binder() {
        fun getService(): BikeBluetoothService = this@BikeBluetoothService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Bike text service running"))
    }

    // ---- Public interface (same shape as before) ----

    /** Scans for the bike over BLE and connects once found. */
    fun connect() {
        if (!hasBtConnectPermission() || !hasBtScanPermission()) {
            broadcastStatus("Missing Bluetooth permissions.")
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            broadcastStatus("Bluetooth is off.")
            return
        }
        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            broadcastStatus("BLE scanning not supported on this device.")
            return
        }
        startScan()
    }

    /**
     * Sets the text to display and (re)starts continuous sending.
     * Keeps the same method name/signature as the old SPP version so
     * MainActivity.kt does not need to change.
     */
    fun sendCustomText(rawText: String) {
        val text = truncate(rawText)
        customMessage = text
        if (generalChar == null || !isMtuIncreased) {
            broadcastStatus("Not connected — tap Connect first.")
            return
        }
        startContinuousDisplay()
        broadcastStatus("Sending: $text")
    }

    fun disconnect() {
        stopScan()
        stopContinuousDisplay()
        closeGatt()
        broadcastStatus("Disconnected")
    }

    // ---- BLE scanning ----

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!hasBtConnectPermission()) return
            val device = result.device
            val name = device.name ?: return
            val lower = name.lowercase(Locale.ROOT)
            if (lower.contains("pulsar") || lower.contains("bajaj") || lower.contains("ns160")) {
                stopScan()
                broadcastStatus("Found $name, connecting...")
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            broadcastStatus("Scan failed: $errorCode")
        }
    }

    private fun startScan() {
        if (!hasBtScanPermission() || isScanning) return
        isScanning = true
        broadcastStatus("Scanning for bike...")
        try {
            scanner?.startScan(scanCallback)
        } catch (e: SecurityException) {
            broadcastStatus("Permission denied while scanning: ${e.message}")
            isScanning = false
            return
        }
        scope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (isScanning) {
                stopScan()
                broadcastStatus("No bike found. Make sure it's on and in range.")
            }
        }
    }

    private fun stopScan() {
        if (!isScanning) return
        isScanning = false
        try {
            if (hasBtScanPermission()) scanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "Error stopping scan", e)
        }
    }

    // ---- GATT connection ----

    private fun connectToDevice(device: BluetoothDevice) {
        if (!hasBtConnectPermission()) {
            broadcastStatus("Missing BLUETOOTH_CONNECT permission.")
            return
        }
        try {
            bluetoothGatt = device.connectGatt(this, false, gattCallback)
        } catch (e: SecurityException) {
            broadcastStatus("Permission denied while connecting: ${e.message}")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                broadcastStatus("Connected, discovering services...")
                try {
                    if (hasBtConnectPermission()) gatt.discoverServices()
                } catch (e: SecurityException) {
                    broadcastStatus("Permission denied: ${e.message}")
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isMtuIncreased = false
                generalChar = null
                stopContinuousDisplay()
                broadcastStatus("Disconnected")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                broadcastStatus("Service discovery failed: $status")
                return
            }
            for (service in gatt.services) {
                for (characteristic in service.characteristics) {
                    if (characteristic.uuid == GENERAL_CHAR_UUID) {
                        generalChar = characteristic
                    }
                }
            }
            if (generalChar == null) {
                broadcastStatus("Bike connected, but expected characteristic not found.")
                return
            }
            try {
                if (hasBtConnectPermission()) gatt.requestMtu(256)
            } catch (e: SecurityException) {
                broadcastStatus("Permission denied: ${e.message}")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            isMtuIncreased = true
            broadcastStatus("Connected: $mtu")
            startContinuousDisplay()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Write failed, status=$status")
            }
        }
    }

    private fun closeGatt() {
        try {
            if (hasBtConnectPermission()) {
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Error closing gatt", e)
        } finally {
            bluetoothGatt = null
            generalChar = null
            isMtuIncreased = false
        }
    }

    // ---- Continuous frame writing ----

    private fun startContinuousDisplay() {
        repeatJob?.cancel()
        repeatJob = scope.launch {
            while (isActive) {
                writeStatusFrame()
                delay(WRITE_INTERVAL_MS)
            }
        }
    }

    private fun stopContinuousDisplay() {
        repeatJob?.cancel()
        repeatJob = null
    }

    private fun writeStatusFrame() {
        val gatt = bluetoothGatt ?: return
        val characteristic = generalChar ?: return
        if (!isMtuIncreased) return
        if (!hasBtConnectPermission()) return

        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        characteristic.value = buildPhoneStatusFrame(customMessage)
        try {
            gatt.writeCharacteristic(characteristic)
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission denied during write", e)
        }
    }

    /**
     * Rebuilds CallFrame.phoneStatusNew() from the decompiled app, with our
     * own custom message in place of the real caller name, and call state
     * forced to INCOMING_CALL (1) so the cluster displays the name field.
     */
    private fun buildPhoneStatusFrame(message: String): ByteArray {
        heartbeat++
        val frame = ByteArray(55)

        // byte[0]: headset bit | volume | base flag byte (values are cosmetic defaults)
        val currentVolume = 5
        val isHeadsetConnected = false
        frame[0] = (((if (isHeadsetConnected) 1 else 0) shl 4) or currentVolume or 0xC0).toByte()

        // byte[1]: callState.getValue() | (batteryPercentage << 3)
        val callStateIncoming = 1 // CallState.INCOMING_CALL
        val batteryPercentage = 4
        frame[1] = (callStateIncoming or (batteryPercentage shl 3)).toByte()

        frame[2] = 4 // signal strength
        frame[3] = 0 // not ACTIVE_CALL
        frame[4] = 1 // not ACTIVE_CALL

        val safeMessage = truncate(message)
        val nameBytes = safeMessage.toByteArray(StandardCharsets.UTF_8)
        val len = minOf(nameBytes.size, MAX_MESSAGE_LENGTH)
        frame[20] = len.toByte()
        System.arraycopy(nameBytes, 0, frame, 21, len)

        frame[53] = heartbeat.toByte()
        return frame
    }

    private fun truncate(msg: String): String =
        if (msg.length > MAX_MESSAGE_LENGTH) msg.substring(0, MAX_MESSAGE_LENGTH) else msg

    // ---- Permissions / notification plumbing (unchanged) ----

    private fun hasBtConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ActivityCompat.checkSelfPermission(
            this, android.Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBtScanPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ActivityCompat.checkSelfPermission(
            this, android.Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun broadcastStatus(msg: String) {
        Log.d(TAG, msg)
        val intent = Intent(ACTION_STATUS).putExtra(EXTRA_STATUS, msg)
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Bike Bluetooth", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pulsar Text")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopScan()
        stopContinuousDisplay()
        closeGatt()
        scope.cancel()
        super.onDestroy()
    }
}