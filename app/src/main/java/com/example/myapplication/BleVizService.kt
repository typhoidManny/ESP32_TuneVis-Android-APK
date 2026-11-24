package com.example.bleaudioviz

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaMetadata
import android.media.MediaRecorder
import android.media.audiofx.Visualizer
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private const val TARGET_DEVICE_NAME = "ESP32-AudioViz"
private val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
private val CHAR_RX_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
private val CHAR_META_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

private const val CHANNEL_ID = "ble_audio_viz_channel"
private const val NOTIFICATION_ID = 1001
private const val MIN_FRAME_INTERVAL_MS = 40L // ~25 Hz steady stream without congesting BLE
private const val WRITE_WATCHDOG_MS = 250L
private const val META_POLL_INTERVAL_MS = 1_500L

enum class AudioSourceMode {
    OUTPUT_MIX,
    MICROPHONE;

    companion object {
        fun fromNameOrNull(name: String?): AudioSourceMode? =
            entries.firstOrNull { it.name == name }
    }
}

data class VizUiState(
    val statusText: String = "Disconnected",
    val isScanning: Boolean = false,
    val isConnected: Boolean = false,
    val isStreaming: Boolean = false,
    val sourceMode: AudioSourceMode = AudioSourceMode.OUTPUT_MIX,
    val sensitivity: Float = 1.0f,
    val currentTrack: String = "",
    val bands: List<Int> = List(NUM_BANDS) { 0 },
)

class BleVizService : Service() {

    companion object {
        const val ACTION_SCAN_AND_CONNECT = "com.example.bleaudioviz.ACTION_SCAN_AND_CONNECT"
        const val ACTION_DISCONNECT = "com.example.bleaudioviz.ACTION_DISCONNECT"
        const val ACTION_START_VIZ = "com.example.bleaudioviz.ACTION_START_VIZ"
        const val ACTION_STOP_VIZ = "com.example.bleaudioviz.ACTION_STOP_VIZ"
        const val ACTION_UPDATE_CONFIG = "com.example.bleaudioviz.ACTION_UPDATE_CONFIG"

        const val EXTRA_AUDIO_SESSION_ID = "extra_audio_session_id"
        const val EXTRA_SOURCE_MODE = "extra_source_mode"
        const val EXTRA_SENSITIVITY = "extra_sensitivity"

        private val _uiState = MutableStateFlow(VizUiState())
        val uiState: StateFlow<VizUiState> = _uiState.asStateFlow()

        @Volatile
        private var sharedSensitivity: Float = 1.0f

        fun updateConfig(context: Context, sourceMode: AudioSourceMode, sensitivity: Float) {
            val prev = _uiState.value
            sharedSensitivity = sensitivity
            _uiState.update { it.copy(sourceMode = sourceMode, sensitivity = sensitivity) }

            if (prev.isStreaming && prev.sourceMode != sourceMode) {
                sendCommand(context, ACTION_UPDATE_CONFIG) {
                    putExtra(EXTRA_SOURCE_MODE, sourceMode.name)
                    putExtra(EXTRA_SENSITIVITY, sensitivity)
                }
            }
        }

        fun sendCommand(context: Context, action: String, extras: Intent.() -> Unit = {}) {
            val intent = Intent(context, BleVizService::class.java).apply {
                this.action = action
                extras()
            }
            if (action == ACTION_SCAN_AND_CONNECT || action == ACTION_START_VIZ) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val spectrumProcessor = SpectrumProcessor(NUM_BANDS)

    private val bluetoothAdapter: BluetoothAdapter?
        get() = getSystemService(BluetoothManager::class.java)?.adapter

    private var gattClient: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var metaCharacteristic: BluetoothGattCharacteristic? = null
    private var lastSentTrack: String = ""
    @Volatile
    private var pendingMetaTrack: String? = null
    private var lastConnectedDevice: BluetoothDevice? = null
    private var userRequestedDisconnect = false
    private var resumeStreamingOnReconnect = false

    private var currentSessionId = 0
    private var currentSourceMode = AudioSourceMode.OUTPUT_MIX

    private var visualizer: Visualizer? = null
    private var audioRecord: AudioRecord? = null
    private var micThread: Thread? = null
    private val micRunning = AtomicBoolean(false)

    private var scanTimeoutRunnable: Runnable? = null
    private var reconnectRunnable: Runnable? = null
    private var metaPollRunnable: Runnable? = null

    private val writeInFlight = AtomicBoolean(false)
    @Volatile
    private var lastWriteStartMs = 0L
    @Volatile
    private var lastSendMs = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SCAN_AND_CONNECT -> {
                promoteToForeground(includeMic = false)
                userRequestedDisconnect = false
                cancelPendingReconnect()
                startBleScan()
            }

            ACTION_DISCONNECT -> {
                userRequestedDisconnect = true
                resumeStreamingOnReconnect = false
                cancelPendingReconnect()
                stopBleScan()
                stopMetadataPolling()
                stopAudioCapture()
                closeGatt()
                updateState {
                    copy(
                        isScanning = false,
                        isConnected = false,
                        isStreaming = false,
                        statusText = "Disconnected",
                        currentTrack = "",
                        bands = List(NUM_BANDS) { 0 }
                    )
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_START_VIZ -> {
                currentSessionId = intent.getIntExtra(EXTRA_AUDIO_SESSION_ID, currentSessionId)
                AudioSourceMode.fromNameOrNull(intent.getStringExtra(EXTRA_SOURCE_MODE))?.let {
                    currentSourceMode = it
                }
                sharedSensitivity = intent.getFloatExtra(EXTRA_SENSITIVITY, sharedSensitivity)
                promoteToForeground(includeMic = true)
                startAudioCapture()
            }

            ACTION_STOP_VIZ -> {
                resumeStreamingOnReconnect = false
                stopAudioCapture()
                updateState {
                    copy(
                        isStreaming = false,
                        statusText = if (isConnected) "Stopped streaming (connected)" else statusText,
                        bands = List(NUM_BANDS) { 0 }
                    )
                }
                if (_uiState.value.isConnected) {
                    promoteToForeground(includeMic = false)
                }
            }

            ACTION_UPDATE_CONFIG -> {
                val newMode = AudioSourceMode.fromNameOrNull(
                    intent.getStringExtra(EXTRA_SOURCE_MODE)
                ) ?: currentSourceMode
                sharedSensitivity = intent.getFloatExtra(EXTRA_SENSITIVITY, sharedSensitivity)
                val modeChanged = newMode != currentSourceMode
                currentSourceMode = newMode
                updateState { copy(sourceMode = currentSourceMode, sensitivity = sharedSensitivity) }

                if (modeChanged && _uiState.value.isStreaming) {
                    promoteToForeground(includeMic = true)
                    startAudioCapture()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelPendingReconnect()
        stopBleScan()
        stopMetadataPolling()
        stopAudioCapture()
        closeGatt()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Dynamic Permission Checks
    // -------------------------------------------------------------------------

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    // -------------------------------------------------------------------------
    // Foreground Notification
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Old School BLE Visualizer",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the BLE connection and audio visualizer active in the background"
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Old School BLE Visualizer")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun promoteToForeground(includeMic: Boolean) {
        val notification = buildNotification(_uiState.value.statusText)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                var fgsType = 0
                if (hasConnectPermission()) {
                    fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                }
                if (includeMic && hasAudioPermission()) {
                    fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                if (fgsType != 0) {
                    startForeground(NOTIFICATION_ID, notification, fgsType)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val fgsType = if (includeMic && hasAudioPermission()) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                }
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: SecurityException) {
            updateState { copy(statusText = "Missing permission for background service") }
        }
    }

    private fun updateNotificationText(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun updateState(transform: VizUiState.() -> VizUiState) {
        val updated = _uiState.updateAndGet(transform)
        updateNotificationText(updated.statusText)
    }

    // -------------------------------------------------------------------------
    // BLE Scanning & Connection
    // -------------------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!_uiState.value.isScanning) return

            val device = result.device
            val foundName = try {
                result.scanRecord?.deviceName ?: if (hasConnectPermission()) device.name ?: "" else ""
            } catch (_: SecurityException) {
                result.scanRecord?.deviceName ?: ""
            }
            val serviceUuids = result.scanRecord?.serviceUuids.orEmpty()
            val matchesServiceUuid = serviceUuids.any { it.uuid == SERVICE_UUID }

            if (foundName == TARGET_DEVICE_NAME || matchesServiceUuid) {
                stopBleScan()
                val displayName = foundName.ifBlank { TARGET_DEVICE_NAME }
                mainHandler.post {
                    updateState {
                        copy(
                            isScanning = false,
                            statusText = "Found $displayName, connecting..."
                        )
                    }
                    connectToDevice(device)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            stopBleScan()
            mainHandler.post {
                updateState {
                    copy(
                        isScanning = false,
                        statusText = "BLE scan failed (code $errorCode)"
                    )
                }
            }
        }
    }

    private fun startBleScan() {
        if (_uiState.value.isScanning) return
        if (!hasScanPermission() || !hasConnectPermission()) {
            updateState { copy(statusText = "Bluetooth permission required") }
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            updateState { copy(statusText = "Turn on Bluetooth first") }
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            updateState { copy(statusText = "BLE scanner unavailable") }
            return
        }

        stopBleScan()
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            updateState {
                copy(
                    isScanning = true,
                    statusText = "Scanning for $TARGET_DEVICE_NAME..."
                )
            }
            scanner.startScan(null, scanSettings, scanCallback)
        } catch (_: SecurityException) {
            updateState {
                copy(
                    isScanning = false,
                    statusText = "Bluetooth scan permission denied"
                )
            }
            return
        }

        val timeout = Runnable {
            if (_uiState.value.isScanning) {
                stopBleScan()
                updateState {
                    copy(
                        isScanning = false,
                        statusText = "$TARGET_DEVICE_NAME not found (scan timed out)"
                    )
                }
            }
        }
        scanTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, 10_000L)
    }

    private fun stopBleScan() {
        scanTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        scanTimeoutRunnable = null
        if (!hasScanPermission()) return
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (!hasConnectPermission()) {
            updateState { copy(statusText = "Bluetooth connect permission required") }
            return
        }
        lastConnectedDevice = device
        closeGatt()
        writeInFlight.set(false)
        try {
            gattClient = device.connectGatt(
                this,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        } catch (_: SecurityException) {
            updateState { copy(statusText = "Bluetooth connect permission denied") }
        }
    }

    private fun closeGatt() {
        stopMetadataPolling()
        rxCharacteristic = null
        metaCharacteristic = null
        lastSentTrack = ""
        pendingMetaTrack = null
        writeInFlight.set(false)
        try {
            if (hasConnectPermission()) {
                gattClient?.disconnect()
                gattClient?.close()
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
        gattClient = null
    }

    private fun cancelPendingReconnect() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    private fun scheduleReconnect(reasonStatus: Int) {
        val device = lastConnectedDevice ?: return
        if (userRequestedDisconnect) return
        cancelPendingReconnect()

        val msg = if (reasonStatus != 0) {
            "Connection lost (status $reasonStatus), reconnecting..."
        } else {
            "Reconnecting to $TARGET_DEVICE_NAME..."
        }
        updateState { copy(isConnected = false, statusText = msg) }

        val task = Runnable {
            if (!userRequestedDisconnect && bluetoothAdapter?.isEnabled == true) {
                connectToDevice(device)
            }
        }
        reconnectRunnable = task
        mainHandler.postDelayed(task, 1_500L)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            mainHandler.post {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    cancelPendingReconnect()
                    try {
                        if (hasConnectPermission()) {
                            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                            updateState { copy(statusText = "Connected, negotiating MTU...") }
                            if (!gatt.requestMtu(128)) {
                                gatt.discoverServices()
                            }
                        } else {
                            updateState { copy(statusText = "Bluetooth permission revoked") }
                        }
                    } catch (_: SecurityException) {
                        updateState { copy(statusText = "Bluetooth permission revoked") }
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val wasStreaming = _uiState.value.isStreaming
                    if (wasStreaming && !userRequestedDisconnect) {
                        resumeStreamingOnReconnect = true
                    }
                    stopMetadataPolling()
                    stopAudioCapture()
                    rxCharacteristic = null
                    metaCharacteristic = null
                    lastSentTrack = ""
                    pendingMetaTrack = null
                    writeInFlight.set(false)
                    try {
                        if (hasConnectPermission()) {
                            gatt.close()
                        }
                    } catch (_: SecurityException) {
                    } catch (_: Exception) {
                    }
                    if (gattClient == gatt) gattClient = null

                    if (!userRequestedDisconnect && lastConnectedDevice != null) {
                        scheduleReconnect(status)
                    } else {
                        val text = if (status != 0) "Disconnected (GATT status $status)" else "Disconnected"
                        updateState {
                            copy(
                                isConnected = false,
                                isStreaming = false,
                                statusText = text,
                                currentTrack = "",
                                bands = List(NUM_BANDS) { 0 }
                            )
                        }
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            mainHandler.post {
                try {
                    if (hasConnectPermission()) {
                        updateState { copy(statusText = "MTU=$mtu, discovering services...") }
                        if (rxCharacteristic == null) {
                            gatt.discoverServices()
                        }
                    } else {
                        updateState { copy(statusText = "Bluetooth permission revoked") }
                    }
                } catch (_: SecurityException) {
                    updateState { copy(statusText = "Bluetooth permission revoked") }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            mainHandler.post {
                val service = gatt.getService(SERVICE_UUID)
                val charRx = service?.getCharacteristic(CHAR_RX_UUID)
                if (charRx != null) {
                    rxCharacteristic = charRx
                    metaCharacteristic = service.getCharacteristic(CHAR_META_UUID)
                    lastSentTrack = ""
                    pendingMetaTrack = null
                    try {
                        if (hasConnectPermission()) {
                            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        }
                    } catch (_: SecurityException) {
                    }
                    updateState {
                        copy(
                            isConnected = true,
                            statusText = "Ready: $TARGET_DEVICE_NAME"
                        )
                    }
                    sendCurrentTrackMetadata(this@BleVizService)
                    startMetadataPolling()
                    if (resumeStreamingOnReconnect) {
                        resumeStreamingOnReconnect = false
                        startAudioCapture()
                    }
                } else {
                    updateState { copy(statusText = "RX characteristic not found") }
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            writeInFlight.set(false)
            if (pendingMetaTrack != null) {
                flushPendingMetadata()
            }
        }
    }

    private fun startMetadataPolling() {
        stopMetadataPolling()
        val pollTask = object : Runnable {
            override fun run() {
                if (_uiState.value.isConnected) {
                    sendCurrentTrackMetadata(this@BleVizService)
                    mainHandler.postDelayed(this, META_POLL_INTERVAL_MS)
                }
            }
        }
        metaPollRunnable = pollTask
        mainHandler.postDelayed(pollTask, META_POLL_INTERVAL_MS)
    }

    private fun stopMetadataPolling() {
        metaPollRunnable?.let { mainHandler.removeCallbacks(it) }
        metaPollRunnable = null
    }

    fun sendCurrentTrackMetadata(context: Context = this) {
        if (gattClient == null || metaCharacteristic == null) return

        val msm = context.getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        val listener = ComponentName(context, MediaNotificationListener::class.java)

        val controllers = try {
            msm.getActiveSessions(listener)
        } catch (_: SecurityException) {
            return // User hasn't granted Notification Access yet
        }

        val activeController = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull()

        val meta = activeController?.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()

        val raw = when {
            title.isNotEmpty() && artist.isNotEmpty() -> "$title - $artist"
            title.isNotEmpty() -> title
            else -> "No Media"
        }

        // Strip non-ASCII chars so Adafruit_GFX doesn't print garbled symbols
        val clean = raw.replace(Regex("[^\\x20-\\x7E]"), "").take(120)
        _uiState.update { it.copy(currentTrack = clean) }
        if (clean == lastSentTrack && pendingMetaTrack == null) return

        pendingMetaTrack = clean
        flushPendingMetadata()
    }

    private fun flushPendingMetadata(): Boolean {
        val track = pendingMetaTrack ?: return false
        val gatt = gattClient ?: return false
        val charMeta = metaCharacteristic ?: return false
        if (!_uiState.value.isConnected || !hasConnectPermission()) return false

        val now = System.currentTimeMillis()
        if (writeInFlight.get() && (now - lastWriteStartMs) < WRITE_WATCHDOG_MS) {
            return false
        }

        lastWriteStartMs = now
        writeInFlight.set(true)

        val bytes = track.toByteArray(Charsets.UTF_8)
        val queuedOk = writeCharacteristicNoResponse(gatt, charMeta, bytes)
        if (queuedOk) {
            lastSentTrack = track
            if (pendingMetaTrack == track) {
                pendingMetaTrack = null
            }
        } else {
            writeInFlight.set(false)
        }
        return queuedOk
    }

    private fun writeCharacteristicNoResponse(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val res = gatt.writeCharacteristic(
                    characteristic,
                    bytes,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                )
                res == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = bytes
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        } catch (_: SecurityException) {
            false
        }
    }

    private fun sendBands(bands: ByteArray) {
        val now = System.currentTimeMillis()
        if ((now - lastSendMs) < MIN_FRAME_INTERVAL_MS) return

        val previewList = List(bands.size) { i -> bands[i].toInt() and 0xFF }
        _uiState.update { it.copy(bands = previewList) }

        val gatt = gattClient ?: return
        val charRx = rxCharacteristic ?: return
        if (!_uiState.value.isConnected || !hasConnectPermission()) return

        // Prioritize pending track metadata write over a single spectrum frame
        if (pendingMetaTrack != null && flushPendingMetadata()) {
            return
        }

        if (writeInFlight.get() && (now - lastWriteStartMs) < WRITE_WATCHDOG_MS) {
            return
        }

        lastSendMs = now
        lastWriteStartMs = now
        writeInFlight.set(true)

        val queuedOk = writeCharacteristicNoResponse(gatt, charRx, bands)
        if (!queuedOk) {
            writeInFlight.set(false)
        }
    }

    // -------------------------------------------------------------------------
    // Audio Capture: Output Mix (Visualizer) & Microphone (DRM-compatible FFT)
    // -------------------------------------------------------------------------

    private fun startAudioCapture() {
        stopAudioCapture()
        if (!hasAudioPermission()) {
            updateState {
                copy(
                    isStreaming = false,
                    statusText = "Microphone permission required to visualize audio"
                )
            }
            return
        }
        spectrumProcessor.reset()
        when (currentSourceMode) {
            AudioSourceMode.OUTPUT_MIX -> startVisualizerCapture(currentSessionId)
            AudioSourceMode.MICROPHONE -> startMicrophoneCapture()
        }
    }

    private fun stopAudioCapture() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (_: Exception) {
        }
        visualizer = null

        micRunning.set(false)
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        micThread?.interrupt()
        micThread = null
    }

    private fun startVisualizerCapture(audioSessionId: Int) {
        try {
            val viz = Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                scalingMode = Visualizer.SCALING_MODE_NORMALIZED
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            v: Visualizer?,
                            waveform: ByteArray?,
                            samplingRate: Int
                        ) {
                        }

                        override fun onFftDataCapture(
                            v: Visualizer?,
                            fft: ByteArray?,
                            samplingRate: Int
                        ) {
                            if (fft == null) return
                            val bands = spectrumProcessor.computeFromVisualizerFft(
                                fft = fft,
                                samplingRateRaw = samplingRate,
                                sensitivity = sharedSensitivity
                            )
                            sendBands(bands)
                        }
                    },
                    Visualizer.getMaxCaptureRate(),
                    false,
                    true
                )
                enabled = true
            }
            visualizer = viz
            updateState {
                copy(
                    isStreaming = true,
                    sourceMode = AudioSourceMode.OUTPUT_MIX,
                    statusText = "Streaming Output Mix (session $audioSessionId)"
                )
            }
        } catch (_: SecurityException) {
            updateState {
                copy(
                    isStreaming = false,
                    statusText = "Audio permission denied for Visualizer"
                )
            }
        } catch (e: Exception) {
            updateState {
                copy(
                    isStreaming = false,
                    statusText = "Visualizer error: ${e.message}"
                )
            }
        }
    }

    private fun startMicrophoneCapture() {
        val sampleRate = 44_100
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            updateState {
                copy(
                    isStreaming = false,
                    statusText = "Microphone unavailable (buffer error $minBuf)"
                )
            }
            return
        }

        val bufferSizeBytes = maxOf(minBuf * 2, 1024 * 2 * 2)
        val recorder = try {
            if (!hasAudioPermission()) {
                updateState {
                    copy(
                        isStreaming = false,
                        statusText = "Microphone permission required"
                    )
                }
                return
            }
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeBytes
            )
        } catch (_: SecurityException) {
            updateState {
                copy(
                    isStreaming = false,
                    statusText = "Microphone permission denied"
                )
            }
            return
        } catch (e: Exception) {
            updateState {
                copy(
                    isStreaming = false,
                    statusText = "Mic init error: ${e.message}"
                )
            }
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            updateState {
                copy(
                    isStreaming = false,
                    statusText = "Microphone failed to initialize"
                )
            }
            return
        }

        audioRecord = recorder
        micRunning.set(true)
        try {
            recorder.startRecording()
        } catch (_: SecurityException) {
            recorder.release()
            audioRecord = null
            micRunning.set(false)
            updateState {
                copy(
                    isStreaming = false,
                    statusText = "Microphone permission revoked"
                )
            }
            return
        }

        val worker = Thread({
            val pcm = ShortArray(1024)
            while (micRunning.get() && !Thread.currentThread().isInterrupted) {
                val read = recorder.read(pcm, 0, pcm.size)
                if (read > 0) {
                    val bands = spectrumProcessor.computeFromPcm(
                        pcm = pcm,
                        sampleCount = read,
                        sampleRateHz = sampleRate.toDouble(),
                        sensitivity = sharedSensitivity
                    )
                    sendBands(bands)
                }
            }
        }, "MicFftThread")
        micThread = worker
        worker.start()

        updateState {
            copy(
                isStreaming = true,
                sourceMode = AudioSourceMode.MICROPHONE,
                statusText = "Streaming Microphone FFT (works with DRM)"
            )
        }
    }
}
