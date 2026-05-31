package com.github.momentum4control

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Method
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class Momentum4Client {
    companion object {
        private const val TAG = "M4Client"
        private const val EXCHANGE_TIMEOUT_MS = 3000L
    }

    data class DeviceState(val ancEnabled: Boolean, val transparency: Int)

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var buffer = ByteArray(0)
    private val lock = ReentrantLock()
    var connectedChannel: Int = -1
        private set

    fun close() {
        try { socket?.close() } catch (_: IOException) {}
        try { inputStream?.close() } catch (_: IOException) {}
        try { outputStream?.close() } catch (_: IOException) {}
        socket = null
        inputStream = null
        outputStream = null
        connectedChannel = -1
        buffer = ByteArray(0)
    }

    fun connect(device: BluetoothDevice): Int {
        close()
        var lastError: Exception? = null

        for (channel in GaiaProtocol.COMMON_CHANNELS) {
            try {
                val sock = createRfcommSocket(device, channel)
                sock.connect()
                socket = sock
                inputStream = sock.getInputStream()
                outputStream = sock.getOutputStream()

                val resp = exchange(GaiaProtocol.CMD_GET_ANC_STATUS, ByteArray(0), 2500)
                if (resp != null
                    && resp.vendor == GaiaProtocol.VENDOR_SENNHEISER
                    && resp.command == GaiaProtocol.expectedResponse(GaiaProtocol.CMD_GET_ANC_STATUS)
                    && resp.payload.isNotEmpty()
                ) {
                    connectedChannel = channel
                    registerNotifications()
                    return channel
                }
                close()
                lastError = IOException("Unexpected GAIA response on channel $channel")
            } catch (e: Exception) {
                close()
                lastError = e
            }
        }

        throw IOException("Unable to find working GAIA RFCOMM channel", lastError)
    }

    private fun registerNotifications() {
        for (feature in intArrayOf(GaiaProtocol.FEATURE_ANC, GaiaProtocol.FEATURE_TRANSPARENCY)) {
            try {
                exchange(GaiaProtocol.CMD_REGISTER_NOTIFICATION, byteArrayOf(feature.toByte()), 1500)
            } catch (_: Exception) {}
        }
    }

    fun getState(): DeviceState {
        val ancResp = exchange(GaiaProtocol.CMD_GET_ANC_STATUS, ByteArray(0))
            ?: throw IOException("No ANC response")
        val trResp = exchange(GaiaProtocol.CMD_GET_TRANSPARENCY, ByteArray(0))
            ?: throw IOException("No transparency response")

        if (ancResp.vendor != GaiaProtocol.VENDOR_SENNHEISER
            || ancResp.command != GaiaProtocol.expectedResponse(GaiaProtocol.CMD_GET_ANC_STATUS))
            throw IOException("Invalid ANC status response")
        if (trResp.vendor != GaiaProtocol.VENDOR_SENNHEISER
            || trResp.command != GaiaProtocol.expectedResponse(GaiaProtocol.CMD_GET_TRANSPARENCY))
            throw IOException("Invalid transparency response")

        val ancEnabled = ancResp.payload.isNotEmpty() && ancResp.payload[0] != 0.toByte()
        val transparency = if (trResp.payload.isNotEmpty()) trResp.payload[0].toInt() and 0xFF else 50
        return DeviceState(ancEnabled, transparency)
    }

    fun setAncEnabled(enabled: Boolean) {
        exchange(GaiaProtocol.CMD_SET_ANC_STATUS, byteArrayOf(if (enabled) 1 else 0))
    }

    fun setTransparency(level: Int) {
        val clamped = level.coerceIn(0, 100)
        exchange(GaiaProtocol.CMD_SET_TRANSPARENCY, byteArrayOf(clamped.toByte()))
    }

    fun setModeOff() {
        setAncEnabled(false)
    }

    fun setModeAnc() {
        setAncEnabled(true)
        setTransparency(0)
    }

    fun setModeAmbient() {
        setAncEnabled(true)
        setTransparency(100)
    }

    private fun exchange(command: Int, payload: ByteArray, timeoutMs: Long = EXCHANGE_TIMEOUT_MS): GaiaProtocol.Packet? {
        lock.withLock {
            val out = outputStream ?: throw IOException("Not connected")
            val inp = inputStream ?: throw IOException("Not connected")

            out.write(GaiaProtocol.build(command, payload))
            out.flush()

            val expected = GaiaProtocol.expectedResponse(command)
            val error = GaiaProtocol.errorResponse(command)
            val wanted = setOf(expected, error)

            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val result = GaiaProtocol.parseMany(buffer)
                for (p in result.packets) {
                    if (p.command in wanted) {
                        buffer = result.leftover
                        return p
                    }
                }

                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break

                val chunk = ByteArray(4096)
                val available = inp.available()
                if (available <= 0) {
                    Thread.sleep(minOf(50, remaining))
                    continue
                }

                val read = inp.read(chunk, 0, minOf(chunk.size, available))
                if (read <= 0) throw IOException("RFCOMM channel closed")

                val newBuf = ByteArray(buffer.size + read)
                System.arraycopy(buffer, 0, newBuf, 0, buffer.size)
                System.arraycopy(chunk, 0, newBuf, buffer.size, read)
                buffer = newBuf
            }

            throw IOException("Timeout waiting for response to 0x${command.toString(16)}")
        }
    }

    private fun createRfcommSocket(device: BluetoothDevice, channel: Int): BluetoothSocket {
        val method: Method = BluetoothDevice::class.java.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
        return method.invoke(device, channel) as BluetoothSocket
    }
}
