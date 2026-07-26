package com.github.momentum4control

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Returns true when the given device already has an active system Bluetooth connection
 * (ACL / audio profile), as shown in system Bluetooth settings.
 */
fun isSystemBluetoothConnected(context: Context, mac: String): Boolean {
    if (mac.isEmpty()) return false
    if (!hasBluetoothConnectPermission(context)) return false

    val manager = context.getSystemService(BluetoothManager::class.java) ?: return false
    val adapter = manager.adapter ?: return false
    if (!adapter.isEnabled) return false

    val device = try {
        adapter.getRemoteDevice(mac)
    } catch (_: IllegalArgumentException) {
        return false
    }

    if (device.bondState != BluetoothDevice.BOND_BONDED) return false

    // Hidden API that reflects the system "Connected" state (ACL link).
    try {
        val method = BluetoothDevice::class.java.getMethod("isConnected")
        if (method.invoke(device) as Boolean) return true
    } catch (_: Exception) {
        // Fall through to profile checks on API 31+.
    }

    // Fallback for API 31+: A2DP / HFP connection counts as system-connected.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val profiles = intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
        for (profile in profiles) {
            try {
                if (manager.getConnectionState(device, profile) == BluetoothProfile.STATE_CONNECTED) {
                    return true
                }
            } catch (_: SecurityException) {
                // Missing BLUETOOTH_CONNECT — already checked, but be safe.
            }
        }
    }
    return false
}

private fun hasBluetoothConnectPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED
}
