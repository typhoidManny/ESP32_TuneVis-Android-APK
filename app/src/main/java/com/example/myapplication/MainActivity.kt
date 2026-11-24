package com.example.bleaudioviz

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.bleaudioviz.ui.theme.BleVisualizerTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var hasNotificationAccess by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasNotificationAccess = isNotificationListenerEnabled()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        setContent {
            BleVisualizerTheme {
                BleVisualizerScreen(hasNotificationAccess = hasNotificationAccess)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasNotificationAccess = isNotificationListenerEnabled()
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val listenerComponent = ComponentName(this, MediaNotificationListener::class.java).flattenToString()
        return enabled.split(':').any { it.equals(listenerComponent, ignoreCase = true) }
    }
}

@Composable
fun BleVisualizerScreen(hasNotificationAccess: Boolean) {
    val context = LocalContext.current
    val activity = context as? Activity
    val uiState by BleVizService.uiState.collectAsState()

    var sessionIdInput by remember { mutableStateOf("0") }
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    var permanentlyDenied by remember { mutableStateOf(false) }
    var rationalePermissions by remember { mutableStateOf<List<String>?>(null) }
    var pendingRequired by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var askedNotificationsOnce by remember { mutableStateOf(false) }

    val blePermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }
    }

    val audioPermissions = remember {
        listOf(Manifest.permission.RECORD_AUDIO)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val deniedCore = pendingRequired.filter { perm ->
            ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
        }
        if (deniedCore.isEmpty()) {
            permissionMessage = null
            permanentlyDenied = false
            pendingAction?.invoke()
        } else {
            val isPermanent = activity != null && deniedCore.any { perm ->
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)
            }
            permanentlyDenied = isPermanent
            permissionMessage = if (isPermanent) {
                "Permission permanently denied. Enable Bluetooth and Microphone in App Settings."
            } else {
                "Required permission denied. Please allow access to continue."
            }
        }
        pendingAction = null
        pendingRequired = emptyList()
    }

    fun requestPermissionsFor(required: List<String>, includeNotifications: Boolean, action: () -> Unit) {
        val missingCore = required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        val toRequest = buildList {
            addAll(missingCore)
            if (
                includeNotifications &&
                !askedNotificationsOnce &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (missingCore.isEmpty() && toRequest.isEmpty()) {
            permissionMessage = null
            permanentlyDenied = false
            action()
            return
        }

        pendingRequired = required
        pendingAction = action
        if (includeNotifications) {
            askedNotificationsOnce = true
        }

        val needsRationale = activity != null && missingCore.any { perm ->
            ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)
        }
        if (needsRationale) {
            rationalePermissions = toRequest
        } else {
            permissionLauncher.launch(toRequest.toTypedArray())
        }
    }

    if (rationalePermissions != null) {
        AlertDialog(
            onDismissRequest = {
                rationalePermissions = null
                pendingAction = null
                pendingRequired = emptyList()
            },
            title = { Text("Permissions needed") },
            text = {
                Text(
                    "Old School BLE Visualizer needs Bluetooth access to connect to your ESP32 " +
                        "and Microphone access to analyze audio frequencies."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val perms = rationalePermissions.orEmpty()
                        rationalePermissions = null
                        permissionLauncher.launch(perms.toTypedArray())
                    }
                ) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        rationalePermissions = null
                        pendingAction = null
                        pendingRequired = emptyList()
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "Old School BLE Visualizer", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "Status: ${permissionMessage ?: uiState.statusText}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (uiState.currentTrack.isNotEmpty()) {
                Text(
                    text = "Track: ${uiState.currentTrack}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (!hasNotificationAccess) {
                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Enable Notification Access (for Track Info)")
                }
            }

            if (permanentlyDenied) {
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open App Settings")
                }
            }

            SpectrumPreview(
                bands = uiState.bands,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        requestPermissionsFor(
                            required = blePermissions,
                            includeNotifications = true
                        ) {
                            BleVizService.sendCommand(context, BleVizService.ACTION_SCAN_AND_CONNECT)
                        }
                    },
                    enabled = !uiState.isScanning,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (uiState.isScanning) "Scanning..." else "Scan & Connect")
                }
                Button(
                    onClick = {
                        BleVizService.sendCommand(context, BleVizService.ACTION_DISCONNECT)
                    },
                    enabled = uiState.isConnected || uiState.isStreaming || uiState.isScanning,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Disconnect")
                }
            }

            Text(text = "Audio Source", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilterChip(
                    selected = uiState.sourceMode == AudioSourceMode.OUTPUT_MIX,
                    onClick = {
                        BleVizService.updateConfig(
                            context = context,
                            sourceMode = AudioSourceMode.OUTPUT_MIX,
                            sensitivity = uiState.sensitivity
                        )
                    },
                    label = { Text("Output Mix") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = uiState.sourceMode == AudioSourceMode.MICROPHONE,
                    onClick = {
                        BleVizService.updateConfig(
                            context = context,
                            sourceMode = AudioSourceMode.MICROPHONE,
                            sensitivity = uiState.sensitivity
                        )
                    },
                    label = { Text("Mic (DRM)") },
                    modifier = Modifier.weight(1f)
                )
            }

            Column {
                Text(
                    text = String.format(Locale.US, "Sensitivity: %.2fx", uiState.sensitivity),
                    style = MaterialTheme.typography.labelLarge
                )
                Slider(
                    value = uiState.sensitivity,
                    onValueChange = { value ->
                        BleVizService.updateConfig(
                            context = context,
                            sourceMode = uiState.sourceMode,
                            sensitivity = value
                        )
                    },
                    valueRange = 0.5f..2.5f
                )
            }

            if (uiState.sourceMode == AudioSourceMode.OUTPUT_MIX) {
                OutlinedTextField(
                    value = sessionIdInput,
                    onValueChange = { sessionIdInput = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Audio Session ID (0 = output mix)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        requestPermissionsFor(
                            required = audioPermissions,
                            includeNotifications = false
                        ) {
                            val sid = sessionIdInput.toIntOrNull() ?: 0
                            BleVizService.sendCommand(context, BleVizService.ACTION_START_VIZ) {
                                putExtra(BleVizService.EXTRA_AUDIO_SESSION_ID, sid)
                                putExtra(BleVizService.EXTRA_SOURCE_MODE, uiState.sourceMode.name)
                                putExtra(BleVizService.EXTRA_SENSITIVITY, uiState.sensitivity)
                            }
                        }
                    },
                    enabled = uiState.isConnected && !uiState.isStreaming,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Start Viz")
                }
                Button(
                    onClick = {
                        BleVizService.sendCommand(context, BleVizService.ACTION_STOP_VIZ)
                    },
                    enabled = uiState.isStreaming,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Stop Viz")
                }
            }
        }
    }
}

@Composable
private fun SpectrumPreview(bands: List<Int>, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF070A0D))
            .padding(horizontal = 8.dp, vertical = 10.dp)
    ) {
        val count = bands.size.coerceAtLeast(1)
        val gap = if (count > 32) 2f else 6f
        val barWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1.5f)

        bands.forEachIndexed { index, raw ->
            val fraction = (raw.coerceIn(0, 255)) / 255f
            val barHeight = (size.height * fraction).coerceAtLeast(2f)
            val left = index * (barWidth + gap)
            val top = size.height - barHeight
            drawRoundRect(
                color = Color(0xFF00E5FF),
                topLeft = Offset(left, top),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(3f, 3f)
            )
        }
    }
}
