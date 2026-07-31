package com.poisonivy.printer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var selectedDeviceText: TextView
    private lateinit var selectDeviceButton: Button
    private lateinit var imagePreview: ImageView
    private lateinit var pickImageButton: Button
    private lateinit var padCheckbox: CheckBox
    private lateinit var padColorGroup: RadioGroup
    private lateinit var maxSizeInput: EditText
    private lateinit var printButton: Button
    private lateinit var statusLog: TextView

    private var selectedDevice: BluetoothDevice? = null
    private var selectedImageUri: Uri? = null

    private var discoveryReceiver: BroadcastReceiver? = null
    private var discoveryDialog: AlertDialog? = null
    private var discoveryListAdapter: ArrayAdapter<String>? = null
    private val discoveredDevices = mutableListOf<BluetoothDevice>()

    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            openDeviceDialog()
        } else {
            Toast.makeText(this, "Bluetooth permissions are required to find the printer", Toast.LENGTH_LONG).show()
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            selectedImageUri = uri
            imagePreview.setImageURI(uri)
            updatePrintButtonEnabled()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        selectedDeviceText = findViewById(R.id.selectedDeviceText)
        selectDeviceButton = findViewById(R.id.selectDeviceButton)
        imagePreview = findViewById(R.id.imagePreview)
        pickImageButton = findViewById(R.id.pickImageButton)
        padCheckbox = findViewById(R.id.padCheckbox)
        padColorGroup = findViewById(R.id.padColorGroup)
        maxSizeInput = findViewById(R.id.maxSizeInput)
        printButton = findViewById(R.id.printButton)
        statusLog = findViewById(R.id.statusLog)

        selectDeviceButton.setOnClickListener { requestPermissionsThenShowDevices() }
        pickImageButton.setOnClickListener { pickImageLauncher.launch("image/*") }
        printButton.setOnClickListener { startPrint() }
    }

    // ---------------------------------------------------------------
    // Permissions + device selection
    // ---------------------------------------------------------------

    private fun hasAllPermissions(): Boolean =
        requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestPermissionsThenShowDevices() {
        if (hasAllPermissions()) {
            openDeviceDialog()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    @SuppressLint("MissingPermission") // permission checked by hasAllPermissions() before this is reachable
    private fun openDeviceDialog() {
        val adapter = BluetoothPrinter.getAdapter(this)
        if (adapter == null) {
            log("This device has no Bluetooth adapter.")
            return
        }
        if (!adapter.isEnabled) {
            Toast.makeText(this, "Please turn on Bluetooth", Toast.LENGTH_LONG).show()
            return
        }

        discoveredDevices.clear()
        discoveredDevices.addAll(BluetoothPrinter.bondedDevices(this))

        val labels = discoveredDevices.map { deviceLabel(it, bonded = true) }.toMutableList()
        discoveryListAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)

        discoveryDialog = AlertDialog.Builder(this)
            .setTitle("Select printer")
            .setAdapter(discoveryListAdapter) { _, which ->
                onDeviceChosen(discoveredDevices[which])
            }
            .setNeutralButton("Scan for more") { _, _ -> startDiscovery() }
            .setPositiveButton("Bluetooth settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { stopDiscovery() }
            .create()

        discoveryDialog?.show()
    }

    @SuppressLint("MissingPermission")
    private fun deviceLabel(device: BluetoothDevice, bonded: Boolean): String {
        val name = try { device.name ?: "(unknown)" } catch (e: SecurityException) { "(unknown)" }
        val suffix = if (bonded) "" else "  [tap to pair]"
        return "$name\n${device.address}$suffix"
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        val adapter = BluetoothPrinter.getAdapter(this) ?: return

        if (discoveryReceiver == null) {
            discoveryReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == BluetoothDevice.ACTION_FOUND) {
                        @Suppress("DEPRECATION")
                        val device: BluetoothDevice? =
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        if (device != null && discoveredDevices.none { it.address == device.address }) {
                            discoveredDevices.add(device)
                            discoveryListAdapter?.add(deviceLabel(device, bonded = false))
                            discoveryListAdapter?.notifyDataSetChanged()
                        }
                    }
                }
            }
            // ACTION_FOUND is a system broadcast, not one we send ourselves, so it
            // must be registered as RECEIVER_EXPORTED on Android 13+ (API 33+).
            ContextCompat.registerReceiver(
                this,
                discoveryReceiver,
                IntentFilter(BluetoothDevice.ACTION_FOUND),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }

        try {
            adapter.startDiscovery()
            Toast.makeText(this, "Scanning...", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            log("Missing permission to scan for devices.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopDiscovery() {
        val adapter = BluetoothPrinter.getAdapter(this)
        try {
            adapter?.cancelDiscovery()
        } catch (e: SecurityException) {
            // ignore
        }
        discoveryReceiver?.let {
            try { unregisterReceiver(it) } catch (e: IllegalArgumentException) { /* not registered */ }
        }
        discoveryReceiver = null
    }

    @SuppressLint("MissingPermission")
    private fun onDeviceChosen(device: BluetoothDevice) {
        val bonded = device.bondState == BluetoothDevice.BOND_BONDED
        if (!bonded) {
            log("Pairing with ${device.name ?: device.address}...")
            BluetoothPrinter.requestPairing(device)
            Toast.makeText(this, "Confirm pairing in the system dialog, then select the printer again.", Toast.LENGTH_LONG).show()
            discoveryDialog?.dismiss()
            return
        }
        selectedDevice = device
        selectedDeviceText.text = try { "${device.name}\n${device.address}" } catch (e: SecurityException) { device.address }
        updatePrintButtonEnabled()
        discoveryDialog?.dismiss()
    }

    // ---------------------------------------------------------------
    // Print flow
    // ---------------------------------------------------------------

    private fun updatePrintButtonEnabled() {
        printButton.isEnabled = selectedDevice != null && selectedImageUri != null
    }

    private fun startPrint() {
        val device = selectedDevice ?: return
        val uri = selectedImageUri ?: return

        val padTo2x3 = padCheckbox.isChecked
        val padColor = if (padColorGroup.checkedRadioButtonId == R.id.padColorBlack) Color.BLACK else Color.WHITE
        val maxSize = maxSizeInput.text.toString().toIntOrNull() ?: ImagePrep.DEFAULT_MAX_PREVIEW_BYTES

        printButton.isEnabled = false
        statusLog.text = ""

        lifecycleScope.launch {
            try {
                log("Preparing image...")
                val prepared = withContext(Dispatchers.IO) {
                    ImagePrep.prepareImage(
                        context = this@MainActivity,
                        uri = uri,
                        padTo2x3 = padTo2x3,
                        padFillColor = padColor,
                        maxPreviewBytes = maxSize,
                    )
                }
                log("Preview: ${prepared.previewJpeg.size} bytes (quality ${prepared.usedQuality})")

                log("Building OBEX stream...")
                val ch4Stream = withContext(Dispatchers.Default) {
                    ObexPush.buildObexPutStream("img.jpg", "image/jpeg", prepared.previewJpeg)
                }

                log("Loading and patching channel-2 template...")
                val (ch2Stream, oldLength) = withContext(Dispatchers.IO) {
                    val template = Channel2Template.loadTemplate(this@MainActivity)
                    Channel2Template.patchTemplate(template, prepared.previewJpeg.size)
                }
                log("Patched size field: $oldLength -> ${prepared.previewJpeg.size}")

                log("Connecting to ${device.address}...")
                val result = BluetoothPrinter.sendPrintJob(device, ch2Stream, ch4Stream) { line ->
                    runOnUiThread { log(line) }
                }

                log("Done. Collected ${result.replies.size} reply packet(s).")
                if (result.channel2Error != null || result.channel4Error != null) {
                    log("If nothing printed, this is a real error -- check the errors above.")
                } else {
                    log("If nothing printed with no errors shown, see the Python project's " +
                        "PROTOCOL.md -- printer state issues (paper/cover/battery) don't " +
                        "always show up as a Bluetooth-level error.")
                }
            } catch (e: Exception) {
                log("ERROR: ${e.message}")
            } finally {
                printButton.isEnabled = true
            }
        }
    }

    private fun log(line: String) {
        statusLog.text = "${statusLog.text}\n$line".trim()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDiscovery()
    }
}
