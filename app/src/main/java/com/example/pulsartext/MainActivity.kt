package com.example.pulsartext

import android.Manifest
import android.app.AlertDialog
import android.content.*
import android.content.ClipData
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var service: BikeBluetoothService? = null
    private var bound = false
    private var pendingAction: String? = null

    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var statusDotPulse: View
    private lateinit var inputText: EditText
    private lateinit var charCountText: TextView
    private lateinit var connectButton: Button
    private lateinit var sendButton: Button
    private lateinit var selectDeviceButton: Button
    private lateinit var selectedDeviceText: TextView
    private lateinit var logText: TextView
    private lateinit var logScrollView: ScrollView
    private lateinit var copyLogButton: Button
    private lateinit var callStateValueText: TextView

    private val logBuilder = StringBuilder()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(BikeBluetoothService.EXTRA_STATUS) ?: return
            updateStatusUi(msg, msg.startsWith("Connected"))
            appendLog(msg)
        }
    }

    /** Updates the status text, the colored dot, and starts/stops its pulse animation. */
    private fun updateStatusUi(message: String, connected: Boolean) {
        statusText.text = message
        sendButton.isEnabled = connected
        val color = if (connected) {
            android.graphics.Color.parseColor("#00E5C7")
        } else {
            android.graphics.Color.parseColor("#7A8A94")
        }
        statusDot.background.setTint(color)
        statusDotPulse.background.setTint(color)
        if (connected) {
            if (statusDotPulse.animation == null) {
                statusDotPulse.startAnimation(AnimationUtils.loadAnimation(this, R.anim.pulse))
            }
        } else {
            statusDotPulse.clearAnimation()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as BikeBluetoothService.LocalBinder
            service = localBinder.getService()
            bound = true
            appendLog("Service bound.")
            syncStatusFromService()
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
        try {
            setupUi()
        } catch (t: Throwable) {
            showCrashScreen(t)
        }
    }

    /** All the normal startup logic, isolated so we can catch anything that throws. */
    private fun setupUi() {
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        statusDot = findViewById(R.id.statusDot)
        statusDotPulse = findViewById(R.id.statusDotPulse)
        inputText = findViewById(R.id.inputText)
        charCountText = findViewById(R.id.charCountText)
        connectButton = findViewById(R.id.connectButton)
        sendButton = findViewById(R.id.sendButton)
        selectDeviceButton = findViewById(R.id.selectDeviceButton)
        selectedDeviceText = findViewById(R.id.selectedDeviceText)
        logText = findViewById(R.id.logText)
        logScrollView = findViewById(R.id.logScrollView)
        copyLogButton = findViewById(R.id.copyLogButton)

        // --- Experimental: live "notification state" tester ---
        // Added so we can try different byte values for the call-state field
        // without rebuilding the app each time. Once we find the value that
        // shows text without the incoming-call UI, this can be removed and
        // the value hardcoded back into the service.
        setupCallStateTester()

        updateCharCount(inputText.text?.length ?: 0)
        inputText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateCharCount(s?.length ?: 0)
            }
        })

        connectButton.setOnClickListener {
            try {
                appendLog("Connect button tapped")
                runWithPermissions("connect")
            } catch (t: Throwable) {
                showCrashScreen(t)
            }
        }

        sendButton.setOnClickListener {
            try {
                val text = inputText.text.toString().trim()
                if (text.isEmpty()) {
                    statusText.text = "Type some text first."
                    return@setOnClickListener
                }
                appendLog("Send tapped: \"$text\"")
                service?.sendCustomText(text)
            } catch (t: Throwable) {
                showCrashScreen(t)
            }
        }

        selectDeviceButton.setOnClickListener {
            try {
                appendLog("Select device button tapped")
                runWithPermissions("picker")
            } catch (t: Throwable) {
                showCrashScreen(t)
            }
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
     * Replaces the whole screen with a plain error view showing the full
     * exception, plus a Copy button. Used any time something throws instead
     * of letting the app crash with no information.
     */
    private fun showCrashScreen(t: Throwable) {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val trace = sw.toString()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = "Something crashed"
            textSize = 18f
            setPadding(0, 0, 0, 16)
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val traceView = TextView(this).apply {
            text = trace
            textSize = 11f
            setTextColor(Color.parseColor("#B00020"))
        }
        scroll.addView(traceView)

        val copyBtn = Button(this).apply {
            text = "Copy error"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Crash", trace)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@MainActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        }

        root.addView(title)
        root.addView(scroll)
        root.addView(copyBtn)
        setContentView(root)
    }

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

    /**
     * Pulls the real current state directly from the service, instead of
     * relying only on broadcasts (which can be missed while the app is
     * briefly backgrounded, e.g. during the permission popup). Call this
     * whenever the app becomes visible or (re)binds to the service.
     */
    private fun syncStatusFromService() {
        val svc = service ?: return
        val status = svc.getLastStatus()
        updateStatusUi(status, svc.isCurrentlyConnected())
        appendLog("Synced status from service: $status")
    }

    /**
     * Adds a small floating +/- control (built in code, no layout changes
     * needed) so we can try different notification "call state" byte values
     * live on the bike, instantly, without rebuilding the app each time.
     * Tap Send first to start the write loop, then use +/- to cycle values
     * and watch the cluster for a change in behavior.
     */
    private fun setupCallStateTester() {
        val root = findViewById<LinearLayout>(android.R.id.content).let {
            // content is a FrameLayout; find the actual root LinearLayout inside the ScrollView
            (it.getChildAt(0) as? ScrollView)?.getChildAt(0) as? LinearLayout
        } ?: return

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 24, 0, 24)
        }

        val label = TextView(this).apply {
            text = "Test notify state:"
            setTextColor(Color.parseColor("#7A8A94"))
            textSize = 12f
        }

        val minusBtn = Button(this).apply {
            text = "-"
            setOnClickListener {
                val svc = service ?: return@setOnClickListener
                val newVal = (svc.getCallState() - 1).coerceAtLeast(0)
                svc.setCallState(newVal)
                callStateValueText.text = newVal.toString()
                appendLog("Testing call state: $newVal")
            }
        }

        callStateValueText = TextView(this).apply {
            text = "1"
            setTextColor(Color.parseColor("#F2F2F5"))
            textSize = 16f
            setPadding(24, 0, 24, 0)
        }

        val plusBtn = Button(this).apply {
            text = "+"
            setOnClickListener {
                val svc = service ?: return@setOnClickListener
                val newVal = (svc.getCallState() + 1).coerceAtMost(7)
                svc.setCallState(newVal)
                callStateValueText.text = newVal.toString()
                appendLog("Testing call state: $newVal")
            }
        }

        row.addView(label)
        row.addView(minusBtn)
        row.addView(callStateValueText)
        row.addView(plusBtn)

        // Insert this row right after the send button for visibility
        val sendIndex = root.indexOfChild(sendButton)
        root.addView(row, sendIndex + 1)
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
        try {
            registerReceiver(
                statusReceiver,
                IntentFilter(BikeBluetoothService.ACTION_STATUS),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RECEIVER_NOT_EXPORTED else 0
            )
            // Catch up on any status change that happened while we were
            // backgrounded and the receiver above was unregistered.
            if (bound) syncStatusFromService()
        } catch (t: Throwable) {
            // If setupUi() itself failed, statusReceiver/etc may already be
            // in a broken state — the crash screen from onCreate is enough.
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(statusReceiver)
        } catch (t: Throwable) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) {
            try {
                unbindService(serviceConnection)
            } catch (t: Throwable) {
            }
            bound = false
        }
    }
}