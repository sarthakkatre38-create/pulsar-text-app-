package com.example.pulsartext

import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.IOException
import java.io.OutputStream
import java.util.*

/**
 * Foreground service that owns the RFCOMM connection to the bike cluster.
 *
 * IMPORTANT: the exact payload format sent in sendCustomText() is a GUESS
 * based on the common AT+CLIP caller-ID convention. You must verify the real
 * format by sniffing the official Bajaj app's Bluetooth traffic (HCI snoop
 * log + Wireshark) before this will reliably work. See README.md.
 */
class BikeBluetoothService : Service() {

    companion object {
        private const val TAG = "BikeBluetoothService"
        private const val CHANNEL_ID = "bike_bt_channel"
        private const val NOTIF_ID = 1

        // Standard SPP UUID
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        // Broadcast actions the Activity can listen for
        const val ACTION_STATUS = "com.example.pulsartext.STATUS"
        const val EXTRA_STATUS = "status"
    }

    private val binder = LocalBinder()
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    inner class LocalBinder : android.os.Binder() {
        fun getService(): BikeBluetoothService = this@BikeBluetoothService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Bike text service running"))
    }

    /** Scans bonded (paired) devices for one whose name contains "pulsar" or "bajaj". */
    private fun findBikeDevice(): BluetoothDevice? {
        if (!hasBtConnectPermission()) return null
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
        val bonded = adapter.bondedDevices ?: return null
        return bonded.firstOrNull {
            val name = it.name?.lowercase(Locale.ROOT) ?: ""
            name.contains("pulsar") || name.contains("bajaj") || name.contains("ns160")
        }
    }

    fun connect() {
        scope.launch {
            try {
                val device = findBikeDevice()
                if (device == null) {
                    broadcastStatus("No paired Pulsar/Bajaj device found. Pair it first in system Bluetooth settings.")
                    return@launch
                }
                if (!hasBtConnectPermission()) {
                    broadcastStatus("Missing BLUETOOTH_CONNECT permission.")
                    return@launch
                }

                broadcastStatus("Connecting to ${device.name}...")

                // Cancel discovery — it slows down the connection attempt.
                BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()

                val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                s.connect() // blocking call, safe here since we're on Dispatchers.IO
                socket = s
                outputStream = s.outputStream

                broadcastStatus("Connected to ${device.name}")
            } catch (e: IOException) {
                Log.e(TAG, "Connection failed", e)
                broadcastStatus("Connection failed: ${e.message}")
                closeSocket()
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied", e)
                broadcastStatus("Permission denied: ${e.message}")
            }
        }
    }

    /**
     * Sends the custom text to the cluster.
     *
     * ---- PAYLOAD FORMAT: VERIFY BEFORE RELYING ON THIS ----
     * This default implementation sends a standard AT+CLIP unsolicited result
     * code, which is what a phone normally sends to a car kit / cluster to
     * announce an incoming caller's number and (optionally) name:
     *
     *   AT+CLIP: "<text>",128
     *
     * Many cheap BT clusters instead expect a raw HFP "Ring" + CLIP pair,
     * or a completely different proprietary binary frame. Replace the body
     * of this function with whatever you capture from the HCI snoop log.
     */
    fun sendCustomText(rawText: String) {
        val text = rawText.uppercase(Locale.ROOT).take(10)
        val out = outputStream
        if (out == null) {
            broadcastStatus("Not connected — tap Connect first.")
            return
        }
        scope.launch {
            try {
                val payload = "AT+CLIP: \"$text\",128\r\n"
                out.write(payload.toByteArray(Charsets.US_ASCII))
                out.flush()
                broadcastStatus("Sent: $text")
            } catch (e: IOException) {
                Log.e(TAG, "Write failed", e)
                broadcastStatus("Send failed: ${e.message}")
            }
        }
    }

    fun disconnect() {
        closeSocket()
        broadcastStatus("Disconnected")
    }

    private fun closeSocket() {
        try {
            outputStream?.close()
            socket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing socket", e)
        } finally {
            outputStream = null
            socket = null
        }
    }

    private fun hasBtConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ActivityCompat.checkSelfPermission(
            this, android.Manifest.permission.BLUETOOTH_CONNECT
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
        closeSocket()
        scope.cancel()
        super.onDestroy()
    }
}
