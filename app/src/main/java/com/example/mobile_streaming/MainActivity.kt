package com.example.mobile_streaming

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
import androidx.lifecycle.lifecycleScope
import com.example.mobile_streaming.ui.theme.Mobile_streamingTheme
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.NoAudioSource
import com.pedro.encoder.input.sources.video.BufferVideoSource
import com.pedro.library.generic.GenericStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

internal const val TAG = "UsbCamStream"
internal const val VIDEO_WIDTH = 1280
internal const val VIDEO_HEIGHT = 720
private const val ACTION_USB_PERMISSION = "com.example.mobile_streaming.USB_PERMISSION"
private const val VIDEO_FPS = 30
private const val VIDEO_BITRATE = 2_500_000
private const val AUDIO_BITRATE = 128 * 1024
private const val AUDIO_SAMPLE_RATE = 32_000
private const val PREVIEW_FRAGMENT_TAG = "usb_camera_fragment"
private const val STREAM_LOOKUP_URL =
    "https://staging.api.cipher.interactivelife.me/api/camera/get/stream"
private const val API_BASE_URL = "https://staging.api.cipher.interactivelife.me/api"
private const val PREFS_NAME = "camera_stream_prefs"
private const val PREF_CAMERA_ID = "camera_id"
private const val PREF_STREAM_URL = "stream_url"
private const val PREF_TRACKING_TYPE = "tracking_type"
private const val PREF_APP_LANGUAGE = "app_language"
private const val POSITION_UPDATE_INTERVAL_MS = 10_000L
private val ScreenBackground = Color(0xFF232323)
private val PreviewBackground = Color(0xFF000000)
private val StartButtonColor = Color(0xFF2E7D32)
private val StopButtonColor = Color(0xFFC62828)
private const val SECRET_LANGUAGE_PRESS_MS = 3_000L

private data class AudioPreparationResult(
    val encoderPrepared: Boolean,
    val isSilent: Boolean
)

private enum class AppLanguage {
    JA,
    EN;

    fun text(ja: String, en: String): String = if (this == JA) ja else en
}

private enum class TrackingType(
    val apiValue: String,
    private val japaneseLabel: String,
    private val englishLabel: String
) {
    CAR("car", "車", "Car"),
    VAN("van", "バン", "Van"),
    TRUCK("truck", "トラック", "Truck"),
    MOTORCYCLE("motorcycle", "バイク", "Motorcycle"),
    HORSE("horse", "馬", "Horse"),
    BICYCLE("bicycle", "自転車", "Bicycle"),
    ON_FOOT("on-foot", "ウォーキング", "On Foot");

    fun label(language: AppLanguage): String = language.text(japaneseLabel, englishLabel)

    companion object {
        fun fromApiValue(value: String?): TrackingType {
            return entries.firstOrNull { it.apiValue == value } ?: CAR
        }
    }
}

class MainActivity : FragmentActivity(), ConnectChecker, UsbCameraFragment.Host {

    private var uiState by mutableStateOf(StreamUiState(statusMessage = ""))
    private var showLanguageDialog by mutableStateOf(false)

    private var isStarted = false
    private var frameLogCount = 0
    private var receiverRegistered = false
    private var usbDetachInProgress = false
    private var previewContainerId: Int? = null
    private var latestLocation: Location? = null
    private var locationUpdateJob: Job? = null
    private var locationListener: LocationListener? = null
    private var requestedLocationPermission = false

    private val videoSource = BufferVideoSource(BufferVideoSource.Format.NV21, VIDEO_BITRATE)
    private val microphoneSource = MicrophoneSource()
    private val noAudioSource = NoAudioSource()
    private val genericStream by lazy {
        GenericStream(this, this, videoSource, microphoneSource)
    }

    private val cameraPrefs by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val locationManager by lazy {
        getSystemService(LocationManager::class.java)
    }

    private val cameraIdHeaders = mapOf(
        "accept" to "*/*",
        "productid" to "40095093-5ee8-44eb-b92a-68cb5ae9d04c",
        "organizationid" to BuildConfig.ORGANIZATION_ID,
        "integratedid" to BuildConfig.INTEGRATED_ID,
        "project" to "cipher",
        "Content-Type" to "application/json"
    )

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            uiState = uiState.copy(
                hasCameraPermission = hasCameraPermission(),
                hasMicPermission = hasMicrophonePermission()
            )
            when {
                uiState.hasCameraPermission && uiState.isStreamConfigured -> {
                    beginConfiguredCameraFlow()
                }

                uiState.hasCameraPermission -> {
                    updateStatus(tr("カメラへのアクセスが許可されました。", "Camera access granted."))
                }

                else -> {
                    updateStatus(tr("続行するにはカメラへのアクセスが必要です。", "Camera access is required to continue."))
                }
            }
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            requestedLocationPermission = !hasLocationPermission()
            if (hasLocationPermission()) {
                maybeStartLocationTracking()
            } else {
                Log.w(TAG, "Location permission denied; position updates disabled")
            }
        }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val device = intent?.getParcelableExtraCompat<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (!isUvcDevice(device)) return
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    logUsbEvent("System USB attached: ${device.toLogSummary()}")
                    usbDetachInProgress = false
                    uiState = uiState.copy(usbCameraDetected = true)
                    if (!uiState.isStreamConfigured) {
                        updateStatus(tr("カメラが検出されました。先に設定を完了してください。", "Camera detected. Complete setup first."))
                        return
                    }
                    if (!uiState.hasCameraPermission) {
                        updateStatus(tr("カメラが検出されました。続行するにはカメラへのアクセスを許可してください。", "Camera detected. Allow camera access to continue."))
                        requestPermissions()
                    } else {
                        device?.let(::requestSystemUsbPermission)
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    logUsbEvent("System USB detached: ${device.toLogSummary()}")
                    usbDetachInProgress = true
                    handleUsbCameraRemoved(tr("カメラが切断されました。", "Camera disconnected."))
                }

                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    logUsbEvent(
                        "System USB permission result: granted=$granted device=${device.toLogSummary()}"
                    )
                    if (granted && device != null) {
                        handleUsbPermissionGranted(device)
                    } else {
                        uiState = uiState.copy(usbPermissionGranted = false)
                        updateStatus(tr("カメラへのアクセスが拒否されました。", "Camera access was denied."))
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        microphoneSource.audioSource = MediaRecorder.AudioSource.MIC
        microphoneSource.mute()
        uiState = initialUiState().copy(
            hasCameraPermission = hasCameraPermission(),
            hasMicPermission = hasMicrophonePermission()
        )

        setContent {
            Mobile_streamingTheme {
                StreamApp(
                    activity = this,
                    uiState = uiState,
                    showLanguageDialog = showLanguageDialog,
                    onCameraIdChanged = ::updateCameraId,
                    onTrackingTypeChanged = ::updateTrackingType,
                    onFetchStreamUrl = ::fetchStreamUrl,
                    onToggleStream = ::toggleStream,
                    onAudioMutedChanged = ::setAudioMuted,
                    onShowLanguageDialog = { showLanguageDialog = true },
                    onDismissLanguageDialog = { showLanguageDialog = false },
                    onLanguageSelected = ::setLanguage,
                    onLogout = ::logout
                )
            }
        }

        registerUsbReceiver()
        if (!hasAllStartupPermissions()) {
            updateStatus(tr("続行するにはカメラへのアクセスを許可してください。", "Allow camera access to continue."))
            window.decorView.post { requestPermissions() }
        } else if (uiState.isStreamConfigured) {
            beginConfiguredCameraFlow()
        }
    }

    override fun onStart() {
        super.onStart()
        isStarted = true
    }

    override fun onStop() {
        val wasStreaming = genericStream.isStreaming
        stopLocationTracking()
        stopStream()
        if (wasStreaming) {
            updateStatus(tr("アプリがバックグラウンドに移動したため配信を停止しました。", "Streaming stopped because the app moved to the background."))
        }
        isStarted = false
        super.onStop()
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(usbReceiver)
            receiverRegistered = false
        }
        stopLocationTracking()
        genericStream.release()
        super.onDestroy()
    }

    override fun onUsbCameraLog(message: String) {
        logUsbEvent(message)
    }

    override fun onUsbCameraOpened() {
        runOnUiThread {
            usbDetachInProgress = false
            uiState = uiState.copy(
                usbCameraDetected = true,
                usbPermissionGranted = true
            )
            updateStatus(tr("カメラが接続されました。", "Camera connected."))
        }
    }

    override fun onUsbPreviewReady(width: Int, height: Int, format: IPreviewDataCallBack.DataFormat) {
        Log.d(TAG, "USB preview ready: ${width}x$height format=$format")
        runOnUiThread {
            usbDetachInProgress = false
            uiState = uiState.copy(
                usbCameraDetected = true,
                usbPermissionGranted = true,
                previewReady = true
            )
            updateStatus(tr("カメラの準備ができました。", "Camera ready."))
        }
    }

    override fun onUsbPreviewFrame(
        data: ByteArray,
        width: Int,
        height: Int,
        format: IPreviewDataCallBack.DataFormat
    ) {
        if (format != IPreviewDataCallBack.DataFormat.NV21) {
            if (frameLogCount < 3) {
                Log.w(TAG, "Ignoring preview frame with unsupported format=$format")
                frameLogCount += 1
            }
            return
        }
        if (videoSource.isRunning()) {
            videoSource.setBuffer(data.copyOf())
            if (frameLogCount < 5) {
                Log.d(TAG, "Forwarding preview frame to RTMP encoder: bytes=${data.size}")
                frameLogCount += 1
            }
        }
    }

    override fun onUsbCameraClosed(reason: String) {
        runOnUiThread {
            logUsbEvent("UsbCameraFragment closed: $reason")
            if (usbDetachInProgress || findUsbCameraDevice() == null) {
                handleUsbCameraRemoved(reason)
                return@runOnUiThread
            }
            uiState = uiState.copy(
                usbPermissionGranted = false,
                previewReady = false,
                isStreaming = false
            )
            if (isStarted) {
                updateStatus(tr("カメラが閉じられました。", "Camera closed."))
            }
        }
    }

    override fun onUsbCameraError(message: String) {
        Log.e(TAG, "USB camera error: $message")
        runOnUiThread {
            handleUsbCameraRemoved(tr("カメラエラー: $message", "Camera error: $message"))
        }
    }

    override fun onConnectionStarted(url: String) {
        Log.d(TAG, "RTMP connection started: $url")
        updateStatus(tr("接続中...", "Connecting..."))
    }

    override fun onConnectionSuccess() {
        Log.d(TAG, "RTMP connection success")
        runOnUiThread {
            uiState = uiState.copy(isStreaming = true)
            maybeStartLocationTracking()
            updateStatus(tr("配信中", "Live"))
        }
    }

    override fun onConnectionFailed(reason: String) {
        Log.e(TAG, "RTMP connection failed: $reason")
        runOnUiThread {
            stopStream(tr("配信に失敗しました: $reason", "Streaming failed: $reason"))
        }
    }

    override fun onNewBitrate(bitrate: Long) = Unit

    override fun onDisconnect() {
        Log.d(TAG, "RTMP disconnected")
        runOnUiThread {
            stopStream(tr("配信が切断されました。", "Stream disconnected."))
        }
    }

    override fun onAuthError() {
        Log.e(TAG, "RTMP auth error")
        runOnUiThread {
            stopStream(tr("配信へのアクセスが拒否されました。", "Stream access was denied."))
        }
    }

    override fun onAuthSuccess() {
        Log.d(TAG, "RTMP auth success")
        updateStatus(tr("接続が承認されました。", "Connection approved."))
    }

    internal fun ensureUsbCameraFragment(containerId: Int) {
        previewContainerId = containerId
        if (supportFragmentManager.isStateSaved) return
        val existing = supportFragmentManager.findFragmentByTag(PREVIEW_FRAGMENT_TAG)
        if (existing is UsbCameraFragment && existing.id == containerId) {
            if (uiState.usbPermissionGranted) {
                existing.requestFirstAvailableUsbCamera()
            }
            return
        }
        supportFragmentManager.commitNow {
            replace(containerId, UsbCameraFragment(), PREVIEW_FRAGMENT_TAG)
        }
        if (uiState.usbPermissionGranted) {
            (supportFragmentManager.findFragmentByTag(PREVIEW_FRAGMENT_TAG) as? UsbCameraFragment)
                ?.requestFirstAvailableUsbCamera()
        }
    }

    private fun restartUsbCameraFragment(reason: String) {
        val containerId = previewContainerId ?: return
        if (supportFragmentManager.isStateSaved) return
        logUsbEvent("Restarting USB camera fragment: $reason")
        supportFragmentManager.commitNow {
            replace(containerId, UsbCameraFragment(), PREVIEW_FRAGMENT_TAG)
        }
        (supportFragmentManager.findFragmentByTag(PREVIEW_FRAGMENT_TAG) as? UsbCameraFragment)
            ?.requestFirstAvailableUsbCamera()
    }

    private fun updateCameraId(cameraId: String) {
        uiState = uiState.copy(cameraId = cameraId)
    }

    private fun updateTrackingType(trackingType: TrackingType) {
        uiState = uiState.copy(trackingType = trackingType)
    }

    private fun fetchStreamUrl() {
        val cameraId = uiState.cameraId.trim()
        if (cameraId.isBlank()) {
            updateStatus(tr("続行するにはカメラIDを入力してください。", "Enter a camera ID to continue."))
            return
        }
        if (uiState.isFetchingStreamUrl) return

        uiState = uiState.copy(isFetchingStreamUrl = true)
        updateStatus(tr("配信情報を読み込み中...", "Loading stream details..."))

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { fetchStreamUrlFromApi(cameraId) }
            }
            result.onSuccess { streamUrl ->
                Log.d(TAG, "Resolved RTMP target for cameraId=$cameraId url=$streamUrl")
                saveCameraConfig(cameraId, streamUrl, uiState.trackingType)
                uiState = uiState.copy(
                    cameraId = cameraId,
                    streamUrl = streamUrl,
                    isFetchingStreamUrl = false,
                    isStreamConfigured = true
                )
                updateStatus(tr("配信の準備ができました。続行するにはカメラを接続してください。", "Stream is ready. Connect your camera to continue."))
                beginConfiguredCameraFlow()
            }.onFailure { error ->
                Log.e(TAG, "Unable to fetch RTMP target", error)
                uiState = uiState.copy(isFetchingStreamUrl = false)
                updateStatus(
                    tr(
                        "配信情報を読み込めませんでした: ${error.message ?: "不明なエラー"}",
                        "Unable to load stream details: ${error.message ?: "Unknown error"}"
                    )
                )
            }
        }
    }

    private fun fetchStreamUrlFromApi(cameraId: String): String {
        val connection = (URL(STREAM_LOOKUP_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doInput = true
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 15_000
            cameraIdHeaders.forEach { (key, value) ->
                setRequestProperty(key, value)
            }
        }

        return try {
            val requestBody = JSONObject().put("id", cameraId).toString()
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(requestBody)
            }

            val responseCode = connection.responseCode
            val responseText = (if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (responseCode !in 200..299) {
                throw IOException("HTTP $responseCode ${responseText.ifBlank { "empty response" }}")
            }

            val root = JSONObject(responseText)
            if (!root.optBoolean("status")) {
                throw IOException(root.optString("message", "API returned status=false"))
            }

            val streamUrl = root.optJSONObject("results")?.optString("stream_url").orEmpty()
            if (streamUrl.isBlank()) {
                throw IOException("API response did not include stream_url")
            }
            streamUrl
        } finally {
            connection.disconnect()
        }
    }

    private fun beginConfiguredCameraFlow() {
        if (!uiState.isStreamConfigured) return
        if (!uiState.hasCameraPermission) {
            updateStatus(tr("続行するにはカメラへのアクセスを許可してください。", "Allow camera access to continue."))
            requestPermissions()
            return
        }
        findUsbCameraDevice()?.let(::requestSystemUsbPermission)
    }

    private fun logout() {
        logUsbEvent("Clearing saved camera configuration")
        stopStream(tr("カメラ設定をクリアしました。", "Camera session cleared."))
        clearSavedCameraConfig()
        usbDetachInProgress = false
        stopLocationTracking()
        removeUsbCameraFragment()
        uiState = initialUiState().copy(
            hasCameraPermission = hasCameraPermission(),
            hasMicPermission = hasMicrophonePermission()
        )
        microphoneSource.unMute()
    }

    private fun toggleStream() {
        if (genericStream.isStreaming) {
            stopStream(tr("配信を停止しました。", "Streaming stopped."))
            return
        }

        if (uiState.streamUrl.isBlank()) {
            updateStatus(tr("開始する前に設定を完了してください。", "Complete setup before starting."))
            return
        }

        if (!uiState.previewReady) {
            updateStatus(tr("開始する前にカメラを接続してください。", "Connect your camera before starting."))
            return
        }

        frameLogCount = 0
        Log.d(TAG, "Preparing video source for RTMP stream")
        val videoPrepared = genericStream.prepareVideo(
            VIDEO_WIDTH,
            VIDEO_HEIGHT,
            VIDEO_BITRATE,
            VIDEO_FPS,
            2,
            0
        )
        if (!videoPrepared) {
            Log.e(TAG, "prepareVideo returned false")
            updateStatus(tr("配信の準備に失敗しました。", "Unable to prepare the stream."))
            return
        }

        val audioPreparation = prepareAudioForStreaming()
        if (audioPreparation.isSilent) {
            updateStatus(tr("音声なしで開始します。", "Starting without audio."))
        }

        runCatching {
            Log.d(TAG, "Starting RTMP stream to ${uiState.streamUrl}")
            genericStream.startStream(uiState.streamUrl)
            updateStatus(tr("配信を開始しています...", "Starting stream..."))
        }.onFailure { error ->
            if (!audioPreparation.encoderPrepared || !error.isMicrophoneStartFailure()) {
                Log.e(TAG, "Unable to start RTMP stream", error)
                updateStatus(
                    tr(
                        "配信を開始できませんでした: ${error.message ?: "不明なエラー"}",
                        "Unable to start streaming: ${error.message ?: "Unknown error"}"
                    )
                )
                return@onFailure
            }

            Log.w(TAG, "Microphone start failed, retrying video-only stream", error)
            updateStatus(tr("音声なしで開始します。", "Starting without audio."))
            runCatching {
                switchToNoAudioSource()
                prepareNoAudioEncoder()
                Log.d(TAG, "Retrying RTMP stream without audio")
                genericStream.startStream(uiState.streamUrl)
                updateStatus(tr("配信を開始しています...", "Starting stream..."))
            }.onFailure { fallbackError ->
                Log.e(TAG, "Unable to start RTMP stream without audio", fallbackError)
                updateStatus(
                    tr(
                        "配信を開始できませんでした: ${fallbackError.message ?: "不明なエラー"}",
                        "Unable to start streaming: ${fallbackError.message ?: "Unknown error"}"
                    )
                )
            }
        }
    }

    private fun prepareAudioForStreaming(): AudioPreparationResult {
        uiState = uiState.copy(hasMicPermission = hasMicrophonePermission())
        if (!uiState.hasMicPermission) {
            Log.w(TAG, "Audio permission missing; streaming video only")
            switchToNoAudioSource()
            return AudioPreparationResult(
                encoderPrepared = prepareNoAudioEncoder(),
                isSilent = true
            )
        }

        val preferredUsbMic = findUsbAudioInput()
        if (preferredUsbMic == null) {
            Log.w(TAG, "No USB microphone input found; streaming video only")
            switchToNoAudioSource()
            return AudioPreparationResult(
                encoderPrepared = prepareNoAudioEncoder(),
                isSilent = true
            )
        }

        switchToMicrophoneSource()

        Log.d(TAG, "Preparing audio sampleRate=$AUDIO_SAMPLE_RATE bitrate=$AUDIO_BITRATE")
        val audioPrepared = genericStream.prepareAudio(
            AUDIO_SAMPLE_RATE,
            true,
            AUDIO_BITRATE,
            true,
            true
        )
        if (!audioPrepared) {
            Log.w(TAG, "prepareAudio returned false")
            return AudioPreparationResult(
                encoderPrepared = false,
                isSilent = true
            )
        }

        val preferredSet = microphoneSource.setPreferredDevice(preferredUsbMic)
        Log.d(TAG, "Preferred USB audio device set=$preferredSet deviceId=${preferredUsbMic.id}")
        applyAudioMuteState()
        return AudioPreparationResult(
            encoderPrepared = true,
            isSilent = false
        )
    }

    private fun prepareNoAudioEncoder(): Boolean {
        Log.d(TAG, "Preparing no-audio encoder sampleRate=$AUDIO_SAMPLE_RATE bitrate=$AUDIO_BITRATE")
        val prepared = genericStream.prepareAudio(
            AUDIO_SAMPLE_RATE,
            true,
            AUDIO_BITRATE,
            false,
            false
        )
        if (!prepared) {
            Log.w(TAG, "prepareAudio returned false for no-audio source")
        }
        return prepared
    }

    private fun findUsbAudioInput(): AudioDeviceInfo? {
        val audioManager = getSystemService(AudioManager::class.java) ?: return null
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        inputs.forEach { device ->
            Log.d(
                TAG,
                "Audio input device id=${device.id} type=${device.type} product=${device.productName}"
            )
        }
        return inputs.firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }

    private fun stopStream(statusMessage: String? = null) {
        logUsbEvent("stopStream called statusMessage=$statusMessage")
        val wasStreaming = uiState.isStreaming || genericStream.isStreaming
        if (genericStream.isStreaming) {
            logUsbEvent("Stopping RTMP stream")
            genericStream.stopStream()
        }
        stopLocationTracking()
        uiState = uiState.copy(isStreaming = false)
        if (wasStreaming) {
            val cameraIdSnapshot = uiState.cameraId
            val trackingTypeSnapshot = uiState.trackingType.apiValue
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    sendCameraPosition(
                        cameraId = cameraIdSnapshot,
                        lat = 0.0,
                        lng = 0.0,
                        type = trackingTypeSnapshot,
                        active = false
                    )
                }.onFailure { error ->
                    Log.w(TAG, "Unable to send inactive camera position", error)
                }
            }
        }
        if (statusMessage != null) {
            updateStatus(statusMessage)
        }
    }

    private fun handleUsbCameraRemoved(statusMessage: String) {
        val wasStreaming = genericStream.isStreaming
        stopStream()
        uiState = uiState.copy(
            usbCameraDetected = false,
            usbPermissionGranted = false,
            previewReady = false,
            isStreaming = false
        )
        updateStatus(
            if (wasStreaming) {
                "$statusMessage ${tr("配信は自動的に停止されました。", "Streaming stopped automatically.")}"
            } else {
                statusMessage
            }
        )
    }

    private fun requestPermissions() {
        permissionLauncher.launch(buildPermissionList())
    }

    private fun registerUsbReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        ContextCompat.registerReceiver(
            this,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    private fun saveCameraConfig(cameraId: String, streamUrl: String, trackingType: TrackingType) {
        cameraPrefs.edit()
            .putString(PREF_CAMERA_ID, cameraId)
            .putString(PREF_STREAM_URL, streamUrl)
            .putString(PREF_TRACKING_TYPE, trackingType.apiValue)
            .apply()
    }

    private fun clearSavedCameraConfig() {
        cameraPrefs.edit()
            .remove(PREF_CAMERA_ID)
            .remove(PREF_STREAM_URL)
            .remove(PREF_TRACKING_TYPE)
            .apply()
    }

    private fun maybeStartLocationTracking() {
        if (!isStarted || !uiState.isStreaming || !uiState.isStreamConfigured) return
        if (!hasLocationPermission()) {
            if (!requestedLocationPermission) {
                requestedLocationPermission = true
                requestLocationPermission()
            }
            return
        }
        requestedLocationPermission = false
        startLocationTracking()
    }

    private fun startLocationTracking() {
        if (locationUpdateJob != null) return
        val manager = locationManager ?: run {
            Log.w(TAG, "Location manager unavailable; position updates disabled")
            return
        }
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        ).filter { provider ->
            runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
        }
        if (providers.isEmpty()) {
            Log.w(TAG, "No enabled location providers; position updates disabled")
            return
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                latestLocation = location
            }
        }
        locationListener = listener

        providers.forEach { provider ->
            runCatching {
                manager.requestLocationUpdates(provider, 5_000L, 0f, listener, Looper.getMainLooper())
            }.onFailure { error ->
                Log.w(TAG, "Unable to request location updates for provider=$provider", error)
            }
            runCatching { manager.getLastKnownLocation(provider) }
                .getOrNull()
                ?.let { location ->
                    if (latestLocation == null || location.time > latestLocation?.time ?: 0L) {
                        latestLocation = location
                    }
                }
        }

        val trackedCameraId = uiState.cameraId
        val trackedType = uiState.trackingType.apiValue
        locationUpdateJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && isStarted && uiState.isStreaming && uiState.isStreamConfigured) {
                latestLocation?.let { location ->
                    runCatching {
                        sendCameraPosition(
                            cameraId = trackedCameraId,
                            lat = location.latitude,
                            lng = location.longitude,
                            type = trackedType,
                            active = true
                        )
                    }.onFailure { error ->
                        Log.w(TAG, "Unable to send camera position", error)
                    }
                }
                delay(POSITION_UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun stopLocationTracking() {
        locationUpdateJob?.cancel()
        locationUpdateJob = null
        locationListener?.let { listener ->
            runCatching { locationManager?.removeUpdates(listener) }
        }
        locationListener = null
        latestLocation = null
    }

    private fun sendCameraPosition(
        cameraId: String,
        lat: Double?,
        lng: Double?,
        type: String,
        active: Boolean
    ) {
        val endpoint = "$API_BASE_URL/camera/position"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doInput = true
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 15_000
            cameraIdHeaders.forEach { (key, value) ->
                setRequestProperty(key, value)
            }
        }

        try {
            val requestPayload = JSONObject()
                .put("cameraId", cameraId)
                .put("type", "camera")
                .put("camera_type", type)
                .put("active", active)
            if (lat != null) requestPayload.put("lat", lat)
            if (lng != null) requestPayload.put("lng", lng)
            val requestBody = requestPayload.toString()
            val requestBodyPretty = requestPayload.toString(2)
            Log.d(
                TAG,
                "POST /position request endpoint=$endpoint cameraId=$cameraId payload=$requestBody"
            )
            Log.d(
                TAG,
                "POST /position request.pretty endpoint=$endpoint cameraId=$cameraId\n$requestBodyPretty"
            )
            Log.d(
                TAG,
                "POST /position start endpoint=$endpoint cameraId=$cameraId lat=$lat lng=$lng type=$type active=$active"
            )
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(requestBody)
            }

            val responseCode = connection.responseCode
            val responseText = (if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val responsePretty = prettyJsonForLog(responseText)

            if (responseCode !in 200..299) {
                Log.w(
                    TAG,
                    "POST /position failed endpoint=$endpoint cameraId=$cameraId code=$responseCode body=${responseText.ifBlank { "empty response" }}"
                )
                Log.w(
                    TAG,
                    "POST /position failed.pretty endpoint=$endpoint cameraId=$cameraId code=$responseCode\n$responsePretty"
                )
                throw IOException("HTTP $responseCode ${responseText.ifBlank { "empty response" }}")
            }
            Log.d(
                TAG,
                "POST /position success endpoint=$endpoint cameraId=$cameraId code=$responseCode body=${responseText.ifBlank { "empty response" }}"
            )
            Log.d(
                TAG,
                "POST /position success.pretty endpoint=$endpoint cameraId=$cameraId code=$responseCode\n$responsePretty"
            )
        } catch (error: Exception) {
            Log.w(TAG, "POST /position exception endpoint=$endpoint cameraId=$cameraId", error)
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun prettyJsonForLog(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return "empty response"
        return runCatching {
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed).toString(2)
                trimmed.startsWith("[") -> JSONArray(trimmed).toString(2)
                else -> raw
            }
        }.getOrElse { raw }
    }

    private fun initialUiState(): StreamUiState {
        val savedCameraId = cameraPrefs.getString(PREF_CAMERA_ID, "").orEmpty()
        val savedStreamUrl = cameraPrefs.getString(PREF_STREAM_URL, "").orEmpty()
        val savedTrackingType = TrackingType.fromApiValue(
            cameraPrefs.getString(PREF_TRACKING_TYPE, TrackingType.CAR.apiValue)
        )
        val defaultLanguage = runCatching {
            AppLanguage.valueOf(getString(R.string.default_language))
        }.getOrDefault(AppLanguage.JA)
        val language = cameraPrefs.getString(PREF_APP_LANGUAGE, defaultLanguage.name)
            ?.let { runCatching { AppLanguage.valueOf(it) }.getOrDefault(defaultLanguage) }
            ?: defaultLanguage
        val hasSavedConfig = savedCameraId.isNotBlank() && savedStreamUrl.isNotBlank()
        return StreamUiState(
            statusMessage = if (hasSavedConfig) {
                language.text(
                    "保存済みのカメラ設定を読み込みました。続行するにはカメラを接続してください。",
                    "Saved camera loaded. Connect your camera to continue."
                )
            } else {
                language.text(
                    "続行するにはカメラIDを入力してください。",
                    "Enter a camera ID to continue."
                )
            },
            cameraId = savedCameraId,
            streamUrl = savedStreamUrl,
            trackingType = savedTrackingType,
            isStreamConfigured = hasSavedConfig,
            language = language,
            isAudioMuted = false

        )
    }

    private fun removeUsbCameraFragment() {
        if (supportFragmentManager.isStateSaved) return
        val fragment = supportFragmentManager.findFragmentByTag(PREVIEW_FRAGMENT_TAG) ?: return
        supportFragmentManager.commitNow {
            remove(fragment)
        }
    }

    private fun logUsbEvent(message: String) {
        Log.d(TAG, "$message | ${buildStateSnapshot()}")
    }

    private fun buildStateSnapshot(): String {
        return buildString {
            append("thread=")
            append(Thread.currentThread().name)
            append(",isStarted=")
            append(isStarted)
            append(",streaming=")
            append(genericStream.isStreaming)
            append(",previewReady=")
            append(uiState.previewReady)
            append(",usbDetected=")
            append(uiState.usbCameraDetected)
            append(",usbPermissionGranted=")
            append(uiState.usbPermissionGranted)
            append(",hasCameraPermission=")
            append(uiState.hasCameraPermission)
            append(",hasMicPermission=")
            append(uiState.hasMicPermission)
            append(",isStreamConfigured=")
            append(uiState.isStreamConfigured)
            append(",isFetchingStreamUrl=")
            append(uiState.isFetchingStreamUrl)
            append(",usbDetachInProgress=")
            append(usbDetachInProgress)
        }
    }

    private fun findUsbCameraDevice(): UsbDevice? {
        val usbManager = getSystemService(UsbManager::class.java) ?: return null
        return usbManager.deviceList.values.firstOrNull(::isUvcDevice)
    }

    private fun isUvcDevice(device: UsbDevice?): Boolean {
        if (device == null) return false
        if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
        return (0 until device.interfaceCount).any { index ->
            device.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_VIDEO
        }
    }

    private fun requestSystemUsbPermission(device: UsbDevice) {
        val usbManager = getSystemService(UsbManager::class.java) ?: return
        if (usbManager.hasPermission(device)) {
            handleUsbPermissionGranted(device)
            return
        }
        updateStatus(tr("カメラを開くにはUSBアクセスのポップアップを許可してください。", "Allow the USB access popup to open the camera."))
        val permissionIntent = PendingIntent.getBroadcast(
            this,
            device.deviceId,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentMutabilityFlag()
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun handleUsbPermissionGranted(device: UsbDevice) {
        usbDetachInProgress = false
        uiState = uiState.copy(
            usbCameraDetected = true,
            usbPermissionGranted = true
        )
        updateStatus(tr("USBアクセスが許可されました。カメラを開いています...", "USB access granted. Opening camera..."))
        window.decorView.postDelayed({
            restartUsbCameraFragment("USB permission granted for ${device.deviceId}")
        }, 350L)
    }

    private fun pendingIntentMutabilityFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
    }

    private fun buildPermissionList(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions.toTypedArray()
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestLocationPermission() {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasAllStartupPermissions(): Boolean {
        return hasCameraPermission() && hasMicrophonePermission() && hasNotificationPermission()
    }

    private fun switchToMicrophoneSource() {
        if (genericStream.audioSource !== microphoneSource) {
            genericStream.changeAudioSource(microphoneSource)
        }
        microphoneSource.audioSource = MediaRecorder.AudioSource.MIC
    }

    private fun switchToNoAudioSource() {
        if (genericStream.audioSource !== noAudioSource) {
            genericStream.changeAudioSource(noAudioSource)
        }
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            uiState = uiState.copy(statusMessage = message)
        }
    }

    private fun setLanguage(language: AppLanguage) {
        cameraPrefs.edit().putString(PREF_APP_LANGUAGE, language.name).apply()
        showLanguageDialog = false
        uiState = uiState.copy(language = language)
        refreshLocalizedStatus()
    }

    private fun setAudioMuted(isMuted: Boolean) {
        uiState = uiState.copy(isAudioMuted = isMuted)
        applyAudioMuteState()
    }

    private fun applyAudioMuteState() {
        if (uiState.isAudioMuted) {
            microphoneSource.mute()
            Log.d(TAG, "Stream audio muted")
        } else {
            microphoneSource.unMute()
            Log.d(TAG, "Stream audio unmuted")
        }
    }

    private fun refreshLocalizedStatus() {
        val language = uiState.language
        val message = when {
            uiState.isFetchingStreamUrl -> language.text("配信情報を読み込み中...", "Loading stream details...")
            uiState.isStreaming -> language.text("配信中", "Live")
            !uiState.isStreamConfigured -> language.text("続行するにはカメラIDを入力してください。", "Enter a camera ID to continue.")
            !uiState.hasCameraPermission -> language.text("続行するにはカメラへのアクセスを許可してください。", "Allow camera access to continue.")
            uiState.previewReady -> language.text("カメラの準備ができました。", "Camera ready.")
            uiState.usbCameraDetected -> language.text("カメラが検出されました。", "Camera detected.")
            else -> language.text("続行するにはカメラを接続してください。", "Connect your camera to continue.")
        }
        uiState = uiState.copy(statusMessage = message)
    }

    private fun tr(ja: String, en: String): String = uiState.language.text(ja, en)

    private fun Throwable.isMicrophoneStartFailure(): Boolean {
        return this is IllegalArgumentException &&
            message?.contains("microphone audio source", ignoreCase = true) == true
    }
}

private inline fun <reified T> Intent.getParcelableExtraCompat(key: String): T? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }
}

@Composable
private fun StreamApp(
    activity: MainActivity,
    uiState: StreamUiState,
    showLanguageDialog: Boolean,
    onCameraIdChanged: (String) -> Unit,
    onTrackingTypeChanged: (TrackingType) -> Unit,
    onFetchStreamUrl: () -> Unit,
    onToggleStream: () -> Unit,
    onAudioMutedChanged: (Boolean) -> Unit,
    onShowLanguageDialog: () -> Unit,
    onDismissLanguageDialog: () -> Unit,
    onLanguageSelected: (AppLanguage) -> Unit,
    onLogout: () -> Unit
) {
    Scaffold(
        containerColor = ScreenBackground,
        topBar = {
            AppTopBar(
                language = uiState.language,
                showLogout = uiState.isStreamConfigured,
                onShowLanguageDialog = onShowLanguageDialog,
                onLogout = onLogout
            )
        }
    ) { innerPadding ->
        if (uiState.isStreamConfigured) {
            StreamingScreen(
                activity = activity,
                uiState = uiState,
                innerPadding = innerPadding,
                onToggleStream = onToggleStream,
                onAudioMutedChanged = onAudioMutedChanged
            )
        } else {
            SetupScreen(
                uiState = uiState,
                innerPadding = innerPadding,
                onCameraIdChanged = onCameraIdChanged,
                onTrackingTypeChanged = onTrackingTypeChanged,
                onFetchStreamUrl = onFetchStreamUrl,
                onShowLanguageDialog = onShowLanguageDialog
            )
        }

        if (showLanguageDialog) {
            LanguageDialog(
                language = uiState.language,
                onDismiss = onDismissLanguageDialog,
                onLanguageSelected = onLanguageSelected
            )
        }
    }
}

@Composable
private fun SetupScreen(
    uiState: StreamUiState,
    innerPadding: PaddingValues,
    onCameraIdChanged: (String) -> Unit,
    onTrackingTypeChanged: (TrackingType) -> Unit,
    onFetchStreamUrl: () -> Unit,
    onShowLanguageDialog: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    var showTypeDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBackground)
            .padding(innerPadding)
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .padding(bottom = 16.dp)
            .offset(y = (-30).dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 52.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.app_logo),
                contentDescription = uiState.language.text("CIVIX Stream ロゴ", "CIVIX Stream logo"),
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .secretLanguagePress(onShowLanguageDialog),
                contentScale = ContentScale.Fit
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                OutlinedTextField(
                    value = uiState.cameraId,
                    onValueChange = onCameraIdChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(uiState.language.text("カメラID", "Camera ID")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus(force = true)
                        }
                    ),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF9A9A9A),
                        unfocusedBorderColor = Color(0xFF9A9A9A),
                        disabledBorderColor = Color(0xFF9A9A9A),
                        focusedLabelColor = Color(0xFF6F6A6A),
                        unfocusedLabelColor = Color(0xFF6F6A6A),
                        cursorColor = Color(0xFF1E3B54)
                    )
                )
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = uiState.trackingType.label(uiState.language),
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(uiState.language.text("タイプ", "Type")) },
                        readOnly = true,
                        singleLine = true,
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Filled.ArrowDropDown,
                                contentDescription = uiState.language.text("タイプを選択", "Select type")
                            )
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF9A9A9A),
                            unfocusedBorderColor = Color(0xFF9A9A9A),
                            disabledBorderColor = Color(0xFF9A9A9A),
                            focusedLabelColor = Color(0xFF6F6A6A),
                            unfocusedLabelColor = Color(0xFF6F6A6A),
                            cursorColor = Color(0xFF1E3B54)
                        )
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable { showTypeDialog = true }
                    )
                }
                Button(
                    onClick = onFetchStreamUrl,
                    enabled = uiState.cameraId.isNotBlank() && !uiState.isFetchingStreamUrl,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF6A6A6A),
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFF4A4A4A),
                        disabledContentColor = Color(0xFFB1B1B1)
                    )
                ) {
                    if (uiState.isFetchingStreamUrl) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    Text(if (uiState.isFetchingStreamUrl) uiState.language.text("接続中...", "Connecting...") else uiState.language.text("続行", "Continue"))
                }
            }
        }

        if (uiState.shouldShowSetupMessage) {
            Spacer(modifier = Modifier.size(18.dp))
            SetupStatusCard(uiState = uiState)
        }

        if (showTypeDialog) {
            TrackingTypeDialog(
                language = uiState.language,
                selectedType = uiState.trackingType,
                onDismiss = { showTypeDialog = false },
                onTypeSelected = { trackingType ->
                    onTrackingTypeChanged(trackingType)
                    showTypeDialog = false
                }
            )
        }
    }
}

@Composable
private fun StreamingScreen(
    activity: MainActivity,
    uiState: StreamUiState,
    innerPadding: PaddingValues,
    onToggleStream: () -> Unit,
    onAudioMutedChanged: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBackground)
            .padding(innerPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = uiState.language.text("カメラID", "Camera ID"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = uiState.cameraId,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB0B0B0)
                    )
                    Text(
                        text = uiState.language.text("ストリームID", "Stream ID"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = uiState.streamUrl.streamIdFromUrl().maskedStreamId(),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB0B0B0)
                    )
                }
            }

            PreviewCard(
                activity = activity,
                showPlaceholder = !uiState.previewReady,
                language = uiState.language
            )

            StatusCard(uiState = uiState)

            AudioControlCard(
                isMuted = uiState.isAudioMuted,
                onMutedChanged = onAudioMutedChanged
            )
        }

        Button(
            onClick = onToggleStream,
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState.previewReady && uiState.streamUrl.isNotBlank(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (uiState.isStreaming) StopButtonColor else StartButtonColor,
                contentColor = Color.White
            )
        ) {
            Text(if (uiState.isStreaming) uiState.language.text("停止", "Stop") else uiState.language.text("開始", "Start"))
        }
    }
}

@Composable
private fun AudioControlCard(
    isMuted: Boolean,
    onMutedChanged: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onMutedChanged(!isMuted) }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                contentDescription = if (isMuted) "Unmute stream audio" else "Mute stream audio",
                tint = if (isMuted) Color(0xFFC62828) else Color(0xFF2E7D32),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun PreviewCard(
    activity: MainActivity,
    showPlaceholder: Boolean,
    language: AppLanguage
) {
    val containerId = remember { View.generateViewId() }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(PreviewBackground),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { context ->
                    FragmentContainerView(context).apply {
                        id = containerId
                        setBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { container ->
                    container.post {
                        activity.ensureUsbCameraFragment(container.id)
                    }
                }
            )
            if (showPlaceholder) {
                Icon(
                    imageVector = Icons.Filled.VideocamOff,
                    contentDescription = language.text("カメラ未接続", "No camera"),
                    tint = Color(0xFF9A9A9A),
                    modifier = Modifier.size(56.dp)
                )
            }
        }
    }
}

@Composable
private fun SetupStatusCard(uiState: StreamUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = uiState.statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFFFB4AB)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(
    language: AppLanguage,
    showLogout: Boolean,
    onShowLanguageDialog: () -> Unit,
    onLogout: () -> Unit
) {
    TopAppBar(
        title = {
            if (showLogout) {
                Image(
                    painter = painterResource(id = R.drawable.app_logo),
                    contentDescription = language.text("CIVIX Stream ロゴ", "CIVIX Stream logo"),
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .size(108.dp)
                        .secretLanguagePress(onShowLanguageDialog),
                    contentScale = ContentScale.Fit
                )
            } else {
                Spacer(modifier = Modifier.size(1.dp))
            }
        },
        actions = {
            if (showLogout) {
                Box(
                    modifier = Modifier
                        .padding(top = 10.dp, end = 16.dp)
                        .size(40.dp)
                        .clickable(onClick = onLogout),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { context ->
                            ImageView(context).apply {
                                setImageResource(R.drawable.logout)
                                setColorFilter(android.graphics.Color.WHITE)
                                scaleType = ImageView.ScaleType.CENTER_INSIDE
                            }
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = ScreenBackground,
            titleContentColor = Color.White,
            actionIconContentColor = Color.White
        )
    )
}

@Composable
private fun StatusCard(uiState: StreamUiState) {
    val indicatorColor = when {
        uiState.isStreaming -> Color(0xFFD32F2F)
        uiState.previewReady -> Color(0xFF2E7D32)
        uiState.usbCameraDetected -> Color(0xFFF9A825)
        else -> Color(0xFF616161)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    modifier = Modifier.size(14.dp),
                    color = indicatorColor,
                    shape = RoundedCornerShape(50)
                ) {}
                Text(
                    text = when {
                        uiState.isStreaming -> uiState.language.text("配信中", "Streaming")
                        uiState.previewReady -> uiState.language.text("カメラ準備完了", "Preview Ready")
                        uiState.usbCameraDetected -> uiState.language.text("カメラ検出", "Camera Detected")
                        else -> uiState.language.text("カメラ未検出", "No Cam Detected")
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Text(
                text = uiState.statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB0B0B0)
            )
        }
    }
}

private data class StreamUiState(
    val statusMessage: String,
    val cameraId: String = "",
    val streamUrl: String = "",
    val trackingType: TrackingType = TrackingType.CAR,
    val isFetchingStreamUrl: Boolean = false,
    val isStreamConfigured: Boolean = false,
    val hasCameraPermission: Boolean = false,
    val hasMicPermission: Boolean = false,
    val usbCameraDetected: Boolean = false,
    val usbPermissionGranted: Boolean = false,
    val previewReady: Boolean = false,
    val isStreaming: Boolean = false,
    val isAudioMuted: Boolean = false,
    val language: AppLanguage = AppLanguage.JA
) {
    val shouldShowSetupMessage: Boolean
        get() = statusMessage.startsWith("配信情報を読み込めませんでした") ||
            statusMessage.startsWith("Unable to") ||
            statusMessage.contains("拒否") ||
            statusMessage.contains("denied", ignoreCase = true) ||
            statusMessage.contains("エラー") ||
            statusMessage.contains("error", ignoreCase = true)
}

@Composable
private fun LanguageDialog(
    language: AppLanguage,
    onDismiss: () -> Unit,
    onLanguageSelected: (AppLanguage) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(language.text("言語を選択", "Select Language"))
        },
        text = {
            Text(language.text("ロゴを3秒間長押しすると、いつでも切り替えられます。", "Press and hold the logo for 3 seconds anytime to switch again."))
        },
        confirmButton = {
            TextButton(onClick = { onLanguageSelected(AppLanguage.JA) }) {
                Text("日本語")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onLanguageSelected(AppLanguage.EN) }) {
                    Text("English")
                }
                TextButton(onClick = onDismiss) {
                    Text(language.text("閉じる", "Close"))
                }
            }
        }
    )
}

private fun Modifier.secretLanguagePress(onTrigger: () -> Unit): Modifier {
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            val released = withTimeoutOrNull(SECRET_LANGUAGE_PRESS_MS) {
                waitForUpOrCancellation()
            }
            if (released == null) {
                onTrigger()
                waitForUpOrCancellation()
            }
        }
    }
}

private fun UsbDevice?.toLogSummary(): String {
    if (this == null) return "null"
    val interfaces = buildString {
        append("[")
        for (index in 0 until interfaceCount) {
            if (index > 0) append(", ")
            val usbInterface = getInterface(index)
            append(
                "i$index(class=${usbInterface.interfaceClass},sub=${usbInterface.interfaceSubclass},proto=${usbInterface.interfaceProtocol})"
            )
        }
        append("]")
    }
    return "name=$deviceName,id=$deviceId,vendor=$vendorId,product=$productId,class=$deviceClass,interfaces=$interfaces"
}

private fun String.streamIdFromUrl(): String {
    return substringAfterLast('/').ifBlank { this }
}

private fun String.maskedStreamId(): String {
    if (isBlank()) return ""
    return "******${takeLast(4)}"
}

@Composable
private fun TrackingTypeDialog(
    language: AppLanguage,
    selectedType: TrackingType,
    onDismiss: () -> Unit,
    onTypeSelected: (TrackingType) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = language.text("タイプを選択", "Select Type"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = language.text(
                            "移動手段を選択してください。",
                            "Choose how this camera is moving."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB0B0B0)
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TrackingType.entries.forEach { trackingType ->
                        val isSelected = trackingType == selectedType
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onTypeSelected(trackingType) },
                            shape = RoundedCornerShape(18.dp),
                            color = if (isSelected) Color(0xFF3A3D46) else Color(0xFF2E2F36)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = trackingType.label(language),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (isSelected) Color.White else Color(0xFFD8D8D8)
                                )
                                Surface(
                                    modifier = Modifier.size(12.dp),
                                    shape = RoundedCornerShape(50),
                                    color = if (isSelected) Color(0xFF2E7D32) else Color(0xFF6C6F78)
                                ) {}
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(language.text("閉じる", "Close"))
                    }
                }
            }
        }
    }
}
