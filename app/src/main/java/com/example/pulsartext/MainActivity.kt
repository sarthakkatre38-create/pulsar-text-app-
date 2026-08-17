package com.example.pulsartext

import android.Manifest
import android.app.AlertDialog
import android.content.*
import android.content.ClipData
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var service: BikeBluetoothService? = null
    private var bound = false

    // What to do once the service finishes binding: "connect", "picker", or null
    private var pendingAction: String? = null

    private lateinit var statusText: TextView
    private lateinit var inputText: EditText
    private lateinit var charCountText: TextView
    private lateinit var connectButton: Button
    private lateinit var sendButton: Button
    private lateinit var selectDeviceButton: Button
    private lateinit var selectedDeviceText: TextView
    private lateinit var logText: TextView
    private lateinit var logScrollView: ScrollView
    private lateinit var copyLogButton: Button

    private val logBuilder = StringBuilder()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(BikeBluetoothService.EXTRA_STATUS) ?: return
            statusText.text = msg
            sendButton.isEnabled = msg.startsWith("Connected")
            appendLog(msg)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as BikeBluetoothService.LocalBinder
            service = localBinder.getService()
            bound = true
            appendLog("Service bound.")
            when (pendingAction) {
                "connect" -> service?.connect()
                "picker" -> showDevicePicker()
            }
            pendingAction = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        appendLog("Permission result: granted=$allGranted")
        if (allGranted) {
            if (bound) {
                when (pendingAction) {
                    "connect" -> service?.connect()
                    "picker" -> showDevicePicker()
                }
                pendingAction = null
            } else {
                startAndBindService()
            }
        } else {
            statusText.text = "Bluetooth permissions are required."
            appendLog("Permissions denied by user.")
            pendingAction = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        inputText = findViewById(R.id.inputText)
        charCountText = findViewById(R.id.charCountText)
        connectButton = findViewById(R.id.connectButton)
        sendButton = findViewById(R.id.sendButton)
        selectDeviceButton = findViewById(R.id.selectDeviceButton)
        selectedDeviceText = findViewById(R.id.selectedDeviceText)
        logText = findViewById(R.id.logText)
        logScrollView = findViewById(R.id.logScrollView)
        copyLogButton = findViewById(R.id.copyLogButton)

        updateCharCount(inputText.text?.length ?: 0)
        inputText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateCharCount(s?.length ?: 0)
            }
        })

        connectButton.setOnClickListener {
            appendLog("Connect button tapped")
            runWithPermissions("connect")
        }

        sendButton.setOnClickListener {
            val text = inputText.text.toString().trim()
            if (text.isEmpty()) {
                statusText.text = "Type some text first."
                return@setOnClickListener
            }
            appendLog("Send tapped: \"$text\"")
            service?.sendCustomText(text)
        }

        selectDeviceButton.setOnClickListener {
            appendLog("Select device button tapped")
            runWithPermissions("picker")
        }

        copyLogButton.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Pulsar Text Log", logBuilder.toString())
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        appendLog("App started.")
    }

    /**
     * Requests Bluetooth permissions (if not already granted), binds/starts
     * the service (if not already bound), and then performs the requested
     * action once both are ready. This avoids ever starting the foreground
     * BLE service before permissions are actually granted, which crashes on
     * Android 14+ for a "connectedDevice" foreground service type.
     */
    private fun runWithPermissions(action: String) {
        if (!hasAllBtPermissions()) {
            pendingAction = action
            requestPermissions()
            return
        }
        if (!bound) {
            pendingAction = action
            startAndBindService()
            return
        }
        when (action) {
            "connect" -> service?.connect()
            "picker" -> showDevicePicker()
        }
    }

    private fun hasAllBtPermissions(): Boolean {
        val needed = requiredPermissions()
        return needed.all {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requiredPermissions(): List<String> {
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
        return needed
    }

    private fun requestPermissions() {
        permissionLauncher.launch(requiredPermissions().toTypedArray())
    }

    private fun showDevicePicker() {
        val svc = service
        if (svc == null) {
            appendLog("Service not bound yet — try again in a moment.")
            return
        }
        val devices = svc.getBondedDeviceList()
        if (devices.isEmpty()) {
            appendLog("No bonded devices found. Pair the bike in system Bluetooth settings first.")
            AlertDialog.Builder(this)
                .setTitle("No paired devices")
                .setMessage("No bonded Bluetooth devices found. Pair the bike in your phone's system Bluetooth settings first, then try again.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val displayNames = devices.map { it.substringBefore("|") + "  (" + it.substringAfter("|") + ")" }.toTypedArray()
        val addresses = devices.map { it.substringAfter("|") }

        appendLog("Bonded devices found: ${devices.joinToString(", ")}")

        AlertDialog.Builder(this)
            .setTitle("Select the bike")
            .setItems(displayNames) { _, which ->
                val address = addresses[which]
                val name = displayNames[which]
                svc.setTargetDevice(address)
                selectedDeviceText.text = "Selected: $name"
                appendLog("Manually selected device: $name")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun appendLog(msg: String) {
        val time = timeFormat.format(Date())
        logBuilder.append("[$time] $msg\n")
        logText.text = logBuilder.toString()
        logScrollView.post { logScrollView.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun updateCharCount(length: Int) {
        val max = BikeBluetoothService.MAX_MESSAGE_LENGTH
        charCountText.text = "$length/$max"
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