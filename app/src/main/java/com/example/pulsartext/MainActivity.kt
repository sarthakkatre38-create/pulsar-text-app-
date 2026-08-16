package com.example.pulsartext

import android.Manifest
import android.content.*
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var service: BikeBluetoothService? = null
    private var bound = false

    private lateinit var statusText: TextView
    private lateinit var inputText: EditText
    private lateinit var connectButton: Button
    private lateinit var sendButton: Button

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(BikeBluetoothService.EXTRA_STATUS) ?: return
            statusText.text = msg
            sendButton.isEnabled = msg.startsWith("Connected")
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as BikeBluetoothService.LocalBinder
            service = localBinder.getService()
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    // Request all needed runtime permissions in one go
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            startAndBindService()
            service?.connect()
        } else {
            statusText.text = "Bluetooth permissions are required to connect to the bike."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        inputText = findViewById(R.id.inputText)
        connectButton = findViewById(R.id.connectButton)
        sendButton = findViewById(R.id.sendButton)

        connectButton.setOnClickListener { requestPermissionsAndConnect() }

        sendButton.setOnClickListener {
            val text = inputText.text.toString().trim()
            if (text.isEmpty()) {
                statusText.text = "Type some text first."
                return@setOnClickListener
            }
            service?.sendCustomText(text)
        }
    }

    private fun requestPermissionsAndConnect() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_CONNECT
            needed += Manifest.permission.BLUETOOTH_SCAN
        } else {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startAndBindService() {
        val intent = Intent(this, BikeBluetoothService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(
            statusReceiver,
            IntentFilter(BikeBluetoothService.ACTION_STATUS),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RECEIVER_NOT_EXPORTED else 0
        )
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(statusReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
    }
}
