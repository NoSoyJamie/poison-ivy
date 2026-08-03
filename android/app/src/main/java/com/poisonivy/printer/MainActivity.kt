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
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
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
    private lateinit var backgroundColorButton: Button
    private lateinit var backgroundColorSwatch: View
    private lateinit var maxSizeInput: EditText
    private lateinit var printButton: Button
    private lateinit var statusLog: TextView

    private var selectedDevice: BluetoothDevice? = null
    private var nextImageId = 0L

    // Undo/redo history for the whole image layout: a simple list of
    // full-list snapshots plus a pointer, same pattern as the old
    // single-image version's transform history, just snapshotting the
    // whole List<PlacedImage> now instead of one TransformState.
    private val layoutHistory = mutableListOf<List<ImagePrep.PlacedImage>>(emptyList())
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

    private val pickImagesLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            addImages(uris)
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
        backgroundColorButton = findViewById(R.id.backgroundColorButton)
        backgroundColorSwatch = findViewById(R.id.backgroundColorSwatch)
        maxSizeInput = findViewById(R.id.maxSizeInput)
        printButton = findViewById(R.id.printButton)
        statusLog = findViewById(R.id.statusLog)

        selectDeviceButton.setOnClickListener { requestPermissionsThenShowDevices() }
        pickImageButton.setOnClickListener { pickImagesLauncher.launch("image/*") }
        printButton.setOnClickListener { startPrint() }

        rotateButton.setOnClickListener { imagePreview.rotateSelectedImage90() }
        resetPreviewButton.setOnClickListener { imagePreview.resetSelectedImage() }
        undoPreviewButton.setOnClickListener { undoLayout() }
        redoPreviewButton.setOnClickListener { redoLayout() }
        backgroundColorButton.setOnClickListener { showBackgroundColorPicker() }

        imagePreview.onImagesChanged = { newImages -> pushHistory(newImages) }
        imagePreview.onSelectionChanged = { updateSelectionUi(it) }
        imagePreview.canvasBackgroundColor = Color.WHITE

        updateHistoryButtonsEnabled()
        updateSelectionUi(null)
        lockPreviewContainerTo2x3()
    }

    /**
     * Forces the preview container's shape to exactly 2:3 (width:height),
     * matching the final print canvas's own proportions -- required for
     * "what you see is what prints" to actually hold, since
     * InteractivePreviewView computes each placed image's default size
     * and position relative to its OWN measured width/height. Computed
     * from the container's actual measured width once layout has
     * happened, rather than an XML aspect-ratio constraint, so this is
     * easy to verify is correct (plain arithmetic) without needing to
     * compile-test constraint-ratio syntax.
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

    private fun addImages(uris: List<Uri>) {
        lifecycleScope.launch {
            try {
                val newImages = withContext(Dispatchers.IO) {
                    uris.mapIndexed { index, uri ->
                        val bitmap = ImagePrep.loadOrientedBitmap(this@MainActivity, uri)
                        // Cascade each new image's default position slightly so a
                        // multi-image add doesn't stack everything in one spot.
                        val step = 0.06f
                        val slot = index % 5
                        ImagePrep.PlacedImage(
                            id = nextImageId++,
                            bitmap = bitmap,
                            centerXFraction = 0.5f + slot * step - 0.12f,
                            centerYFraction = 0.5f + slot * step - 0.12f,
                        )
                    }
                }
                val updated = imagePreview.placedImages + newImages
                imagePreview.placedImages = updated
                imagePreview.selectImage(newImages.lastOrNull()?.id)
                pushHistory(updated)
                updatePrintButtonEnabled()
            } catch (e: Exception) {
                log("Could not load image: ${e.message}")
            }
        }
    }

    private fun updateSelectionUi(selectedId: Long?) {
        val selected = imagePreview.placedImages.firstOrNull { it.id == selectedId }
        if (selected != null) {
            rotationLabel.text = "Selected: ${Math.round(((selected.rotationAngle % 360) + 360) % 360)}°"
            rotateButton.isEnabled = true
            resetPreviewButton.isEnabled = true
        } else {
            rotationLabel.text = "No image selected"
            rotateButton.isEnabled = false
            resetPreviewButton.isEnabled = false
        }
    }

    private fun showBackgroundColorPicker() {
        val initial = imagePreview.canvasBackgroundColor

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val preview = View(this)
        preview.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (48 * resources.displayMetrics.density).toInt()
        )
        preview.setBackgroundColor(initial)
        container.addView(preview)

        var r = Color.red(initial)
        var g = Color.green(initial)
        var b = Color.blue(initial)

        fun updatePreviewSwatch() {
            preview.setBackgroundColor(Color.rgb(r, g, b))
        }

        fun addChannelRow(label: String, initialValue: Int, onChange: (Int) -> Unit) {
            val labelView = TextView(this)
            labelView.text = "$label: $initialValue"
            labelView.setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
            container.addView(labelView)

            val seekBar = SeekBar(this)
            seekBar.max = 255
            seekBar.progress = initialValue
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    labelView.text = "$label: $value"
                    onChange(value)
                    updatePreviewSwatch()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            container.addView(seekBar)
        }

        addChannelRow("Red", r) { r = it }
        addChannelRow("Green", g) { g = it }
        addChannelRow("Blue", b) { b = it }

        AlertDialog.Builder(this)
            .setTitle("Background color")
            .setView(container)
            .setPositiveButton("OK") { _, _ ->
                val chosen = Color.rgb(r, g, b)
                imagePreview.canvasBackgroundColor = chosen
                backgroundColorSwatch.setBackgroundColor(chosen)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------
    // Undo / redo history for the whole image layout
    // ---------------------------------------------------------------

    private fun pushHistory(images: List<ImagePrep.PlacedImage>) {
        while (layoutHistory.size > historyIndex + 1) {
            layoutHistory.removeAt(layoutHistory.size - 1)
        }
        layoutHistory.add(images)
        historyIndex = layoutHistory.size - 1
        updateHistoryButtonsEnabled()
    }

    private fun undoLayout() {
        if (historyIndex <= 0) return
        historyIndex--
        applyHistoryState(layoutHistory[historyIndex])
    }

    private fun redoLayout() {
        if (historyIndex >= layoutHistory.size - 1) return
        historyIndex++
        applyHistoryState(layoutHistory[historyIndex])
    }

    private fun applyHistoryState(images: List<ImagePrep.PlacedImage>) {
        imagePreview.placedImages = images
        val stillSelected = images.any { it.id == imagePreview.selectedImageId }
        if (!stillSelected) {
            imagePreview.selectImage(images.lastOrNull()?.id)
        } else {
            updateSelectionUi(imagePreview.selectedImageId)
        }
        updateHistoryButtonsEnabled()
    }

    private fun updateHistoryButtonsEnabled() {
        undoPreviewButton.isEnabled = historyIndex > 0
        redoPreviewButton.isEnabled = historyIndex < layoutHistory.size - 1
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
        printButton.isEnabled = selectedDevice != null && imagePreview.placedImages.isNotEmpty()
    }

    private fun startPrint() {
        val device = selectedDevice ?: return
        val images = imagePreview.placedImages
        if (images.isEmpty()) return
        val backgroundColor = imagePreview.canvasBackgroundColor
        val maxSize = maxSizeInput.text.toString().toIntOrNull() ?: ImagePrep.DEFAULT_MAX_PREVIEW_BYTES

        printButton.isEnabled = false
        statusLog.text = ""

        lifecycleScope.launch {
            try {
                log("Preparing ${images.size} image(s)...")
                val prepared = withContext(Dispatchers.IO) {
                    ImagePrep.prepareImage(
                        images = images,
                        backgroundColor = backgroundColor,
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
