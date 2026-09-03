package com.isro.itantra.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.*
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth Classic RFCOMM transport between two iTantra phones.
 *
 * Chosen over Wi-Fi Direct for the first working version because:
 *   - Android's classic Bluetooth RFCOMM socket API is stable, works
 *     without the WifiP2p peer-discovery/group-negotiation state machine,
 *     and needs no extra runtime permissions beyond BLUETOOTH_CONNECT/SCAN.
 *   - Range/bandwidth are more than sufficient for text-only payloads
 *     (this is the whole point of the STT->text->TTS pipeline).
 * Wi-Fi Direct remains a documented option (docs/ARCHITECTURE.md) if a
 * future revision needs longer range - the transport is abstracted behind
 * this class specifically so that swap is a single-file change.
 */
class BluetoothTransportManager(private val adapter: BluetoothAdapter) {

    companion object {
        // App-specific UUID - must match on both phones (it does, since both
        // run this same app).
        val APP_UUID: UUID = UUID.fromString("7e9a3c10-6b1a-4f7e-9a3e-2f7c1d5b9a01")
        const val SDP_NAME = "iTantraTransceiver"
    }

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Listening : ConnectionState()
        object Connecting : ConnectionState()
        data class Connected(val remoteDeviceName: String) : ConnectionState()
        data class Failed(val reason: String) : ConnectionState()
    }

    private var serverSocket: BluetoothServerSocket? = null
    private var activeSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var readerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var state: ConnectionState = ConnectionState.Disconnected
        private set

    private var onStateChanged: ((ConnectionState) -> Unit)? = null
    private var onMessage: ((ItantraMessage) -> Unit)? = null

    fun setListeners(
        onStateChanged: (ConnectionState) -> Unit,
        onMessage: (ItantraMessage) -> Unit
    ) {
        this.onStateChanged = onStateChanged
        this.onMessage = onMessage
    }

    /** Call on the phone acting as the "host" - it waits for the other phone to connect. */
    @SuppressLint("MissingPermission")
    fun startListening() {
        scope.launch {
            try {
                setState(ConnectionState.Listening)
                val server = adapter.listenUsingRfcommWithServiceRecord(SDP_NAME, APP_UUID)
                serverSocket = server
                val socket = server.accept() // blocks until the other phone connects
                serverSocket = null
                server.close()
                attachSocket(socket)
            } catch (e: IOException) {
                setState(ConnectionState.Failed(e.message ?: "listen() failed"))
            }
        }
    }

    /** Call on the phone acting as the "joiner" - connects to an already-paired [device]. */
    @SuppressLint("MissingPermission")
    fun connectTo(device: BluetoothDevice) {
        scope.launch {
            try {
                setState(ConnectionState.Connecting)
                adapter.cancelDiscovery()
                val socket = device.createRfcommSocketToServiceRecord(APP_UUID)
                socket.connect() // blocking
                attachSocket(socket)
            } catch (e: IOException) {
                setState(ConnectionState.Failed(e.message ?: "connect() failed"))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun attachSocket(socket: BluetoothSocket) {
        activeSocket = socket
        outputStream = socket.outputStream
        setState(ConnectionState.Connected(socket.remoteDevice.name ?: socket.remoteDevice.address))

        readerJob = scope.launch {
            val input = socket.inputStream
            try {
                while (isActive) {
                    val payload = FrameCodec.decodeOne(input) ?: break
                    val msg = try { ItantraMessage.fromBytes(payload) } catch (e: Exception) { null }
                    msg?.let { withContext(Dispatchers.Main) { onMessage?.invoke(it) } }
                }
            } catch (e: IOException) {
                // stream closed
            } finally {
                withContext(Dispatchers.Main) { setState(ConnectionState.Disconnected) }
            }
        }
    }

    /** Sends one message. Safe to call from any thread; the write itself happens on IO. */
    fun send(message: ItantraMessage) {
        val out = outputStream ?: return
        scope.launch {
            try {
                out.write(FrameCodec.encode(message.toBytes()))
                out.flush()
            } catch (e: IOException) {
                withContext(Dispatchers.Main) { setState(ConnectionState.Disconnected) }
            }
        }
    }

    fun disconnect() {
        readerJob?.cancel()
        try { serverSocket?.close() } catch (_: IOException) {}
        try { activeSocket?.close() } catch (_: IOException) {}
        serverSocket = null
        activeSocket = null
        outputStream = null
        setState(ConnectionState.Disconnected)
    }

    private fun setState(s: ConnectionState) {
        state = s
        onStateChanged?.invoke(s)
    }
}
