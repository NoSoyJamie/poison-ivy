package com.poisonivy.printer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
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
    private lateinit var previewContainer: FrameLayout
    private lateinit var imagePreview: InteractivePreviewView
    private lateinit var resetPreviewButton: Button
    private lateinit var undoPreviewButton: Button
    private lateinit var redoPreviewButton: Button
    private lateinit var rotateButton: Button
    private lateinit var rotationLabel: TextView
    private lateinit var pickImageButton: Button
    private lateinit var padCheckbox: CheckBox
    private lateinit var padColorGroup: RadioGroup
    private lateinit var maxSizeInput: EditText
    private lateinit var printButton: Button
    private lateinit var statusLog: TextView

    private var selectedDevice: BluetoothDevice? = null
    private var selectedImageUri: Uri? = null
    private var rotationDegrees: Int = 0

    // The oriented (EXIF + coarse rotationDegrees) and optionally padded
    // bitmap that the interactive view frames via pinch/rotate/pan. This
    // is what actually gets baked into the final print, using whatever
    // transform the view currently holds.
    private var sourceBitmap: Bitmap? = null

    // Undo/redo history for the interactive transform: a simple list +
    // pointer. Resets to a single default entry whenever the source
    // bitmap changes (new image, coarse rotation, or pad option change),
    // since those are structural changes the old fine-adjustment history
    // doesn't meaningfully apply to anymore.
    private val transformHistory = mutableListOf(ImagePrep.TransformState())
    private var historyIndex = 0

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
            rotationDegrees = 0
            updateRotationLabel()
            regenerateSourceBitmap()
            updatePrintButtonEnabled()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        selectedDeviceText = findViewById(R.id.selectedDeviceText)
        selectDeviceButton = findViewById(R.id.selectDeviceButton)
        previewContainer = findViewById(R.id.previewContainer)
        imagePreview = findViewById(R.id.imagePreview)
        resetPreviewButton = findViewById(R.id.resetPreviewButton)
        undoPreviewButton = findViewById(R.id.undoPreviewButton)
        redoPreviewButton = findViewById(R.id.redoPreviewButton)
        rotateButton = findViewById(R.id.rotateButton)
        rotationLabel = findViewById(R.id.rotationLabel)
        pickImageButton = findViewById(R.id.pickImageButton)
        padCheckbox = findViewById(R.id.padCheckbox)
        padColorGroup = findViewById(R.id.padColorGroup)
        maxSizeInput = findViewById(R.id.maxSizeInput)
        printButton = findViewById(R.id.printButton)
        statusLog = findViewById(R.id.statusLog)

        selectDeviceButton.setOnClickListener { requestPermissionsThenShowDevices() }
        pickImageButton.setOnClickListener { pickImageLauncher.launch("image/*") }
        printButton.setOnClickListener { startPrint() }

        rotateButton.setOnClickListener {
            rotationDegrees = (rotationDegrees + 90) % 360
            updateRotationLabel()
            regenerateSourceBitmap()
        }
        padCheckbox.setOnCheckedChangeListener { _, _ -> regenerateSourceBitmap() }
        padColorGroup.setOnCheckedChangeListener { _, _ -> regenerateSourceBitmap() }

        imagePreview.onTransformCommitted = { newState -> pushHistory(newState) }
        resetPreviewButton.setOnClickListener {
            imagePreview.resetTransform()
            pushHistory(ImagePrep.TransformState())
        }
        undoPreviewButton.setOnClickListener { undoTransform() }
        redoPreviewButton.setOnClickListener { redoTransform() }
        updateHistoryButtonsEnabled()

        lockPreviewContainerTo2x3()
    }

    /**
     * Forces the preview container's shape to exactly 2:3 (width:height),
     * matching the final print canvas's own proportions. This isn't just
     * cosmetic: InteractivePreviewView computes its "cover" auto-scale
     * from its own measured width/height, and the final print bake uses
     * a fixed 1280x1920 (also 2:3) canvas -- if the on-screen container
     * were a different aspect ratio, the same zoom/pan/rotation values
     * would frame the image differently on screen than in the final
     * print, breaking the "what you see is what prints" guarantee this
     * whole preview exists for. Computed from the container's actual
     * measured width once layout has happened, rather than an XML
     * aspect-ratio constraint, so this is easy to verify is correct
     * (plain arithmetic) without needing to compile-test constraint-ratio
     * syntax.
     */
    private fun lockPreviewContainerTo2x3() {
        previewContainer.post {
            val width = previewContainer.width
            if (width > 0) {
                val height = width * 3 / 2 // width:height = 2:3
                val params = previewContainer.layoutParams
                if (params.height != height) {
                    params.height = height
                    previewContainer.layoutParams = params
                }
            }
        }
    }

    private fun updateRotationLabel() {
        rotationLabel.text = "Rotation: ${rotationDegrees}°"
    }

    /**
     * Rebuilds the oriented/padded source bitmap (EXIF correction +
     * coarse rotationDegrees + optional 2:3 padding) whenever any of
     * those inputs change, assigns it to the interactive view (which
     * resets its own zoom/pan/rotate transform back to default whenever
     * the source changes -- a new source invalidates old fine-tuned
     * framing anyway), and resets the undo/redo history to match.
     */
    private fun regenerateSourceBitmap() {
        val uri = selectedImageUri ?: return
        val padTo2x3 = padCheckbox.isChecked
        val padColor = if (padColorGroup.checkedRadioButtonId == R.id.padColorBlack) Color.BLACK else Color.WHITE
        val rotation = rotationDegrees

        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    ImagePrep.prepareSourceBitmap(
                        context = this@MainActivity,
                        uri = uri,
                        rotationDegrees = rotation,
                        padTo2x3 = padTo2x3,
                        padFillColor = padColor,
                    )
                }
                sourceBitmap = bitmap
                imagePreview.backgroundFillColor = padColor
                imagePreview.sourceBitmap = bitmap // resets the view's own transform to default
                transformHistory.clear()
                transformHistory.add(ImagePrep.TransformState())
                historyIndex = 0
                updateHistoryButtonsEnabled()
            } catch (e: Exception) {
                log("Could not load image: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------------
    // Undo / redo history for the interactive transform
    // ---------------------------------------------------------------

    private fun pushHistory(state: ImagePrep.TransformState) {
        // Discard any "redo" entries beyond the current point, then append.
        while (transformHistory.size > historyIndex + 1) {
            transformHistory.removeAt(transformHistory.size - 1)
        }
        transformHistory.add(state)
        historyIndex = transformHistory.size - 1
        updateHistoryButtonsEnabled()
    }

    private fun undoTransform() {
        if (historyIndex <= 0) return
        historyIndex--
        imagePreview.transform = transformHistory[historyIndex]
        updateHistoryButtonsEnabled()
    }

    private fun redoTransform() {
        if (historyIndex >= transformHistory.size - 1) return
        historyIndex++
        imagePreview.transform = transformHistory[historyIndex]
        updateHistoryButtonsEnabled()
    }

    private fun updateHistoryButtonsEnabled() {
        undoPreviewButton.isEnabled = historyIndex > 0
        redoPreviewButton.isEnabled = historyIndex < transformHistory.size - 1
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
        val source = sourceBitmap ?: return
        val transform = imagePreview.transform
        val padColor = if (padColorGroup.checkedRadioButtonId == R.id.padColorBlack) Color.BLACK else Color.WHITE
        val maxSize = maxSizeInput.text.toString().toIntOrNull() ?: ImagePrep.DEFAULT_MAX_PREVIEW_BYTES

        printButton.isEnabled = false
        statusLog.text = ""

        lifecycleScope.launch {
            try {
                log("Preparing image...")
                val prepared = withContext(Dispatchers.IO) {
                    ImagePrep.prepareImage(
                        source = source,
                        transform = transform,
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
