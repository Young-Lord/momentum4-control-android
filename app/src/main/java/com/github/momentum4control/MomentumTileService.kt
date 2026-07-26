package com.github.momentum4control

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
        scope.launch { refreshTileState() }
    }

    override fun onClick() {
        super.onClick()
        if (busy) return

        scope.launch {
            val settings = settingsStore.settingsFlow.first()
            if (settings.deviceMac.isEmpty()) {
                dropClient()
                updateTileDisconnected()
                return@launch
            }

            // User-initiated: allow actively establishing RFCOMM even if not yet system-connected.
            if (client == null || client?.connectedChannel ?: -1 < 0) {
                updateTileDisconnected()
                connectAndRefresh(settings.deviceMac, allowActiveConnect = true)
                return@launch
            }

            val modes = buildList {
                if (settings.modeOffEnabled) add(NoiseMode.OFF)
                if (settings.modeAncEnabled) add(NoiseMode.ANC)
                if (settings.modeAmbEnabled) add(NoiseMode.AMB)
            }
            if (modes.isEmpty()) {
                updateTileDisconnected()
                return@launch
            }

            val idx = modes.indexOf(settings.currentMode)
            val next = modes[(idx + 1) % modes.size]

            settingsStore.updateCurrentMode(next)
            updateTileConnected(next)
            applyMode(settings.deviceMac, next, allowActiveConnect = true)
        }
    }

    private suspend fun refreshTileState() {
        val settings = settingsStore.settingsFlow.first()
        // Passive refresh: only talk when the system link is already up — never initiate.
        if (settings.deviceMac.isEmpty() || !isSystemBluetoothConnected(this, settings.deviceMac)) {
            dropClient()
            updateTileDisconnected()
            return
        }

        val existing = client
        if (existing != null && existing.connectedChannel >= 0) {
            try {
                val state = withContext(Dispatchers.IO) { existing.getState() }
                val mode = modeFromState(state)
                settingsStore.updateCurrentMode(mode)
                updateTileConnected(mode)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read ANC state from existing connection", e)
                dropClient()
                updateTileDisconnected()
            }
            return
        }

        try {
            val c = ensureClient(settings.deviceMac, allowActiveConnect = false)
            if (c == null) {
                updateTileDisconnected()
                return
            }
            val state = withContext(Dispatchers.IO) { c.getState() }
            val mode = modeFromState(state)
            settingsStore.updateCurrentMode(mode)
            updateTileConnected(mode)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh tile state", e)
            dropClient()
            updateTileDisconnected()
        }
    }

    private suspend fun connectAndRefresh(mac: String, allowActiveConnect: Boolean) {
        busy = true
        ensureNotificationChannel()
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_connecting)))

        try {
            val c = ensureClient(mac, allowActiveConnect)
            if (c != null) {
                val state = withContext(Dispatchers.IO) { c.getState() }
                val detected = modeFromState(state)
                settingsStore.updateCurrentMode(detected)
                withContext(Dispatchers.Main) { updateTileConnected(detected) }
            } else {
                withContext(Dispatchers.Main) { updateTileDisconnected() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connect and refresh failed", e)
            dropClient()
            withContext(Dispatchers.Main) { updateTileDisconnected() }
        } finally {
            busy = false
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private suspend fun applyMode(mac: String, mode: NoiseMode, allowActiveConnect: Boolean) {
        busy = true
        ensureNotificationChannel()
        val notif = buildNotification(getString(R.string.notif_controlling))
        startForeground(NOTIF_ID, notif)

        try {
            val c = ensureClient(mac, allowActiveConnect)
                ?: run {
                    withContext(Dispatchers.Main) { updateTileDisconnected() }
                    return
                }

            withContext(Dispatchers.IO) {
                when (mode) {
                    NoiseMode.OFF -> c.setModeOff()
                    NoiseMode.ANC -> c.setModeAnc()
                    NoiseMode.AMB -> c.setModeAmbient()
                }
            }
            withContext(Dispatchers.Main) { updateTileConnected(mode) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply mode $mode", e)
            dropClient()
            withContext(Dispatchers.Main) { updateTileDisconnected() }
        } finally {
            busy = false
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    /**
     * @param allowActiveConnect when true (user click), may initiate RFCOMM even if the
     * system is not yet connected; when false (tile refresh), only proceed if already connected.
     */
    private suspend fun ensureClient(mac: String, allowActiveConnect: Boolean): Momentum4Client? {
        if (!allowActiveConnect && !isSystemBluetoothConnected(this, mac)) {
            dropClient()
            return null
        }

        val existing = client
        if (existing != null && existing.connectedChannel >= 0) return existing

        dropClient()
        return withContext(Dispatchers.IO) {
            try {
                if (!allowActiveConnect && !isSystemBluetoothConnected(applicationContext, mac)) {
                    return@withContext null
                }
                val bm = applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val adapter = bm.adapter ?: return@withContext null
                val device = adapter.getRemoteDevice(mac)
                val c = Momentum4Client()
                c.connect(device)
                client = c
                c
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                dropClient()
                null
            }
        }
    }

    private fun dropClient() {
        try {
            client?.close()
        } catch (_: Exception) {
        }
        client = null
    }

    private fun modeFromState(state: Momentum4Client.DeviceState): NoiseMode = when {
        !state.ancEnabled -> NoiseMode.OFF
        state.transparency >= 90 -> NoiseMode.AMB
        else -> NoiseMode.ANC
    }

    private fun updateTileDisconnected() {
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_disconnected)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_headphones_disconnected)
        tile.state = Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.stateDescription = getString(R.string.tile_disconnected)
        }
        tile.updateTile()
    }

    private fun updateTileConnected(mode: NoiseMode) {
        val tile = qsTile ?: return
        tile.label = mode.shortLabel()
        tile.icon = Icon.createWithResource(this, R.drawable.ic_headphones)
        tile.state = when (mode) {
            NoiseMode.OFF -> Tile.STATE_INACTIVE
            else -> Tile.STATE_ACTIVE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = mode.shortLabel()
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
