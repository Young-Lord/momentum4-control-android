package com.github.momentum4control

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.TileService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        requestBtPermission()
        enableEdgeToEdge()
        setContent {
            Momentum4Theme {
                SettingsScreen(SettingsStore(applicationContext))
            }
        }
    }

    private fun requestBtPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                    .launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settingsStore: SettingsStore) {
    val settings by settingsStore.settingsFlow.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var offChecked by rememberSaveable { mutableStateOf(settings.modeOffEnabled) }
    var ancChecked by rememberSaveable { mutableStateOf(settings.modeAncEnabled) }
    var ambChecked by rememberSaveable { mutableStateOf(settings.modeAmbEnabled) }
    var showDevicePicker by rememberSaveable { mutableStateOf(false) }

    fun validateAndUpdate(off: Boolean, anc: Boolean, amb: Boolean): Boolean {
        if (!off && !anc && !amb) return false
        offChecked = off
        ancChecked = anc
        ambChecked = amb
        scope.launch { settingsStore.updateModes(off, anc, amb) }
        return true
    }

    if (showDevicePicker) {
        DevicePickerDialog(
            settingsStore = settingsStore,
            onDismiss = { showDevicePicker = false },
        )
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Momentum 4 Control") },
                scrollBehavior = scrollBehavior,
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Quick Tile",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Add the tile to your Quick Settings panel for one-tap noise control switching",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                OutlinedButton(onClick = { requestAddTile(context) }) {
                    Text("Add Quick Settings tile")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Quick Tile Modes",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Select which modes the tile cycles through",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))

            ModeCheckbox(
                label = "Off — Noise control off",
                checked = offChecked,
                onCheckedChange = { validateAndUpdate(it, ancChecked, ambChecked) },
            )
            ModeCheckbox(
                label = "ANC — Active Noise Cancellation 100%",
                checked = ancChecked,
                onCheckedChange = { validateAndUpdate(offChecked, it, ambChecked) },
            )
            ModeCheckbox(
                label = "AMB — Transparency 100%",
                checked = ambChecked,
                onCheckedChange = { validateAndUpdate(offChecked, ancChecked, it) },
            )

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Device",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))

            if (settings.deviceMac.isNotEmpty()) {
                Text(
                    text = "${settings.deviceName}\n${settings.deviceMac}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    text = "No device selected — pair your Momentum 4 in Bluetooth settings first",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = { showDevicePicker = true }) {
                Text(if (settings.deviceMac.isNotEmpty()) "Change device" else "Select device")
            }
        }
    }
}

private fun requestAddTile(context: android.content.Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val sm = context.getSystemService(android.app.StatusBarManager::class.java)
        val componentName = android.content.ComponentName(context, MomentumTileService::class.java)
        sm?.requestAddTileService(
            componentName,
            context.getString(R.string.tile_label),
            android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_headphones_disconnected),
            { _ -> },
            { _ -> },
        )
    }
}

@Composable
fun DevicePickerDialog(settingsStore: SettingsStore, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val bm = context.getSystemService(BluetoothManager::class.java)
    val adapter = bm?.adapter
    val paired = adapter?.bondedDevices?.toList() ?: emptyList()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Momentum 4") },
        text = {
            if (paired.isEmpty()) {
                Text("No paired Bluetooth devices found")
            } else {
                Column {
                    paired.forEach { device ->
                        val name = device.name ?: device.address
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        settingsStore.updateDevice(device.address, name)
                                    }
                                    onDismiss()
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
fun ModeCheckbox(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}
