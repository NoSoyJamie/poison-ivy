package com.poisonivy.printer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * Port of print.py's connection/send logic.
 *
 * IMPORTANT DIFFERENCE FROM THE PYTHON VERSION: the Python tool opens
 * raw AF_BLUETOOTH RFCOMM sockets to hardcoded channel numbers (2 and
 * 4), which isn't something Android's public Bluetooth API exposes.
 * Instead, this connects via the STANDARD SERVICE UUIDS the printer
 * itself advertises over SDP (confirmed via `bluetoothctl info` on
 * the Linux side: "Serial Port" 0x1101 and "OBEX Object Push" 0x1105)
 * and lets Android's own Bluetooth stack resolve the correct channel.
 * This is the officially-supported path and should be at least as
 * reliable as hardcoding channel numbers -- but it's untested against
 * a real device from this codebase, since it was written without
 * access to an Android device/emulator. If createRfcommSocketToServiceRecord
 * doesn't resolve to the right channels for some reason, the
 * REFLECTION_FALLBACK constant below documents the alternative
 * (unofficial, but commonly used) approach of specifying the channel
 * number directly.
 */
object BluetoothPrinter {

    // Standard Bluetooth SDP UUIDs. Base pattern: 0000XXXX-0000-1000-8000-00805F9B34FB
    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // channel 2 (proprietary commands)
    val OBEX_OBJECT_PUSH_UUID: UUID = UUID.fromString("00001105-0000-1000-8000-00805F9B34FB") // channel 4 (image push)

    private const val SEND_CHUNK = 512

    data class Reply(val label: String, val bytes: ByteArray)

    data class PrintResult(
        val replies: List<Reply>,
        val channel2Error: String?,
        val channel4Error: String?,
    )

    fun getAdapter(context: Context): BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        return manager?.adapter
    }

    /**
     * Bonded (paired) devices only. The printer needs to already be
     * paired via Android's system Bluetooth settings, or via
     * requestPairing() below, before this will show it.
     */
    @SuppressLint("MissingPermission")
    fun bondedDevices(context: Context): List<BluetoothDevice> {
        val adapter = getAdapter(context) ?: return emptyList()
        return try {
            adapter.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            emptyList() // missing BLUETOOTH_CONNECT permission
        }
    }

    /**
     * Sends the full patched channel-2 command stream and OBEX stream
     * concurrently, mirroring print.py's two-thread approach. Returns
     * once both channels are done (sent + a brief window listening for
     * replies), or throws if the device can't be reached at all.
     */
    suspend fun sendPrintJob(
        device: BluetoothDevice,
        channel2Bytes: ByteArray,
        channel4Bytes: ByteArray,
        onLog: (String) -> Unit,
    ): PrintResult = withContext(Dispatchers.IO) {
        val replies = mutableListOf<Reply>()
        var ch2Error: String? = null
        var ch4Error: String? = null

        val ch2Job = async {
            try {
                sendOverSocket(device, SPP_UUID, channel2Bytes, "CH2/print", onLog) { r ->
                    synchronized(replies) { replies.add(r) }
                }
            } catch (e: Exception) {
                ch2Error = e.message ?: e.toString()
                onLog("[CH2/print] ERROR: ${e.message}")
            }
        }
        val ch4Job = async {
            try {
                sendOverSocket(device, OBEX_OBJECT_PUSH_UUID, channel4Bytes, "CH4/obex", onLog) { r ->
                    synchronized(replies) { replies.add(r) }
                }
            } catch (e: Exception) {
                ch4Error = e.message ?: e.toString()
                onLog("[CH4/obex] ERROR: ${e.message}")
            }
        }

        awaitAll(ch2Job, ch4Job)

        PrintResult(replies.toList(), ch2Error, ch4Error)
    }

    @SuppressLint("MissingPermission")
    private fun sendOverSocket(
        device: BluetoothDevice,
        uuid: UUID,
        data: ByteArray,
        label: String,
        onLog: (String) -> Unit,
        onReply: (Reply) -> Unit,
    ) {
        onLog("[$label] connecting...")
        var socket: BluetoothSocket? = null
        try {
            socket = device.createRfcommSocketToServiceRecord(uuid)
            socket.connect()
            onLog("[$label] connected, sending ${data.size} bytes...")

            val out = socket.outputStream
            var sent = 0
            while (sent < data.size) {
                val end = minOf(sent + SEND_CHUNK, data.size)
                out.write(data, sent, end - sent)
                sent = end
            }
            out.flush()
            onLog("[$label] done sending. listening briefly for a reply...")

            // Listen for a short window of replies, same as print.py's
            // recv-with-timeout loop.
            socket.inputStream.let { input ->
                val buf = ByteArray(4096)
                val deadline = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < deadline) {
                    if (input.available() > 0) {
                        val n = input.read(buf)
                        if (n > 0) {
                            onReply(Reply(label, buf.copyOf(n)))
                        }
                    } else {
                        Thread.sleep(50)
                    }
                }
            }
        } finally {
            try {
                socket?.close()
            } catch (e: IOException) {
                // ignore close errors
            }
            onLog("[$label] closed.")
        }
    }

    /**
     * If createRfcommSocketToServiceRecord doesn't work reliably for
     * this printer (e.g. its SDP records don't cleanly resolve to
     * channels 2 and 4 the way this project's Python/Linux testing
     * found), this is the documented unofficial fallback: Android's
     * BluetoothDevice class has a hidden createRfcommSocket(int
     * channel) method, accessible via reflection. This is NOT part
     * of the public API and could break on some devices/Android
     * versions, but has been used reliably by many community
     * Bluetooth-serial apps for years. Swap this in for
     * createRfcommSocketToServiceRecord(uuid) above if needed:
     *
     *   private fun createRfcommSocketOnChannel(device: BluetoothDevice, channel: Int): BluetoothSocket {
     *       val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
     *       return method.invoke(device, channel) as BluetoothSocket
     *   }
     *
     * Then connect with createRfcommSocketOnChannel(device, 2) and
     * createRfcommSocketOnChannel(device, 4) instead of the UUID-based
     * calls.
     */
    const val REFLECTION_FALLBACK_NOTE = "See kdoc above this constant."

    /**
     * Programmatically initiates pairing with a discovered-but-not-yet-
     * bonded device. Android will show its own system pairing dialog;
     * this just kicks that off. Requires BLUETOOTH_CONNECT permission.
     */
    @SuppressLint("MissingPermission")
    fun requestPairing(device: BluetoothDevice): Boolean {
        return try {
            device.createBond()
        } catch (e: SecurityException) {
            false
        }
    }
}
