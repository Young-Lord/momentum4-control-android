package com.github.momentum4control

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MomentumTileService : TileService() {

    companion object {
        private const val TAG = "M4Tile"
        private const val CHANNEL_ID = "momentum4_anc"
        private const val NOTIF_ID = 1001
        private var client: Momentum4Client? = null
        private var busy = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settingsStore by lazy { SettingsStore(applicationContext) }

    override fun onStartListening() {
        super.onStartListening()
        scope.launch {
            val settings = settingsStore.settingsFlow.first()
            updateTile(settings.currentMode, settings.deviceMac.isNotEmpty())
        }
    }

    override fun onClick() {
        super.onClick()
        if (busy) return

        scope.launch {
            val settings = settingsStore.settingsFlow.first()
            if (settings.deviceMac.isEmpty()) {
                updateTile(NoiseMode.OFF, false)
                return@launch
            }

            if (client == null || client?.connectedChannel ?: -1 < 0) {
                connectAndRefresh(settings.deviceMac, settings.currentMode)
                return@launch
            }

            val modes = buildList {
                if (settings.modeOffEnabled) add(NoiseMode.OFF)
                if (settings.modeAncEnabled) add(NoiseMode.ANC)
                if (settings.modeAmbEnabled) add(NoiseMode.AMB)
            }
            if (modes.isEmpty()) {
                updateTile(NoiseMode.OFF, false)
                return@launch
            }

            val idx = modes.indexOf(settings.currentMode)
            val next = modes[(idx + 1) % modes.size]

            settingsStore.updateCurrentMode(next)
            updateTile(next, true)
            applyMode(settings.deviceMac, next)
        }
    }

    private suspend fun connectAndRefresh(mac: String, mode: NoiseMode) {
        busy = true
        ensureNotificationChannel()
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_connecting)))

        try {
            val c = ensureClient(mac)
            if (c != null) {
                val state = withContext(Dispatchers.IO) { c.getState() }
                val detected = when {
                    !state.ancEnabled -> NoiseMode.OFF
                    state.transparency >= 90 -> NoiseMode.AMB
                    else -> NoiseMode.ANC
                }
                settingsStore.updateCurrentMode(detected)
                withContext(Dispatchers.Main) { updateTile(detected, true) }
            } else {
                withContext(Dispatchers.Main) { updateTile(mode, false) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connect and refresh failed", e)
            client = null
            withContext(Dispatchers.Main) { updateTile(mode, false) }
        } finally {
            busy = false
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private suspend fun applyMode(mac: String, mode: NoiseMode) {
        busy = true
        ensureNotificationChannel()
        val notif = buildNotification(getString(R.string.notif_controlling))
        startForeground(NOTIF_ID, notif)

        try {
            val c = ensureClient(mac)
                ?: run {
                    withContext(Dispatchers.Main) { updateTile(mode, false) }
                    return
                }

            withContext(Dispatchers.IO) {
                when (mode) {
                    NoiseMode.OFF -> c.setModeOff()
                    NoiseMode.ANC -> c.setModeAnc()
                    NoiseMode.AMB -> c.setModeAmbient()
                }
            }
            withContext(Dispatchers.Main) { updateTile(mode, true) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply mode $mode", e)
            client = null
            withContext(Dispatchers.Main) { updateTile(mode, false) }
        } finally {
            busy = false
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private suspend fun ensureClient(mac: String): Momentum4Client? {
        val existing = client
        if (existing != null && existing.connectedChannel >= 0) return existing

        client = null
        return withContext(Dispatchers.IO) {
            try {
                val bm = applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val adapter = bm.adapter ?: return@withContext null
                val device = adapter.getRemoteDevice(mac)
                val c = Momentum4Client()
                c.connect(device)
                client = c
                c
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                client = null
                null
            }
        }
    }

    private fun updateTile(mode: NoiseMode, connected: Boolean) {
        val tile = qsTile ?: return
        tile.label = mode.shortLabel()
        tile.icon = Icon.createWithResource(this, R.drawable.ic_headphones)
        tile.state = when {
            !connected -> Tile.STATE_INACTIVE
            mode == NoiseMode.OFF -> Tile.STATE_INACTIVE
            else -> Tile.STATE_ACTIVE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (connected) mode.shortLabel() else "Tap to connect"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.stateDescription = mode.shortLabel()
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.channel_description) }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_headphones)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setOngoing(true)
            .build()
    }
}
