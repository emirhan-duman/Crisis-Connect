package com.auralis.crisisconnect.screens.Tools

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.crisisconnect.R
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class WhistleMode { CONTINUOUS, PULSE, SOS, SWEEP, RESCUE }

data class WhistleUiState(
    val isPlaying: Boolean = false,
    val frequencyHz: Float = 3200f,
    val intensity: Float = 0.88f,
    val mode: WhistleMode = WhistleMode.CONTINUOUS,
    val showLoudnessDialog: Boolean = false,
    val errorMessage: Int? = null,
    val routeSummary: String? = null,
    val routeNeedsAttention: Boolean = false,
    val alarmVolume: Int = 0,
    val alarmVolumeMax: Int = 0,
    val boostGainDb: Int = 0
)

class WhistleViewModel(application: Application) : AndroidViewModel(application) {
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Coroutine failed", throwable)
        runCatching { FirebaseCrashlytics.getInstance().recordException(throwable) }
    }

    private val appContext = application.applicationContext
    private val _uiState = MutableStateFlow(WhistleUiState())
    val uiState: StateFlow<WhistleUiState> = _uiState.asStateFlow()

    private var audioTrack: AudioTrack? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var routingListener: AudioRouting.OnRoutingChangedListener? = null
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { }
    private var focusRequest: AudioFocusRequest? = null

    private var hasAcknowledgedWarning = false
    private var previousVolume: Int? = null
    private var volumeAdjusted = false
    private var currentBoostGainMb = 0
    private val streamType = AudioManager.STREAM_ALARM

    init {
        publishVolumeState()
    }

    private companion object {
        private const val TAG = "WhistleViewModel"
        private const val PRESET_STANDARD_INTENSITY = 0.72f
        private const val PRESET_HIGH_INTENSITY = 0.88f
        private const val PRESET_MAX_INTENSITY = 1.0f
        private const val LOW_BOOST_GAIN_MB = 200
        private const val MEDIUM_BOOST_GAIN_MB = 450
        private const val HIGH_BOOST_GAIN_MB = 650
        private const val RESCUE_MODE_EXTRA_BOOST_MB = 50
        private const val MAX_BOOST_GAIN_MB = 700
    }

    fun onToggleWhistleRequested() {
        if (_uiState.value.isPlaying) {
            stopWhistle()
        } else if (!hasAcknowledgedWarning || !isStreamAtMax()) {
            _uiState.value = _uiState.value.copy(showLoudnessDialog = true)
        } else {
            startWhistle()
        }
    }

    fun setFrequency(newValue: Float) {
        val clamped = newValue.coerceIn(
            WhistleToneGenerator.MIN_FREQUENCY_HZ,
            WhistleToneGenerator.MAX_FREQUENCY_HZ
        )
        val wasPlaying = _uiState.value.isPlaying
        _uiState.value = _uiState.value.copy(frequencyHz = clamped)
        if (wasPlaying) {
            startWhistle()
        }
    }

    fun setMode(mode: WhistleMode) {
        val wasPlaying = _uiState.value.isPlaying
        _uiState.value = _uiState.value.copy(mode = mode)
        if (wasPlaying) {
            startWhistle()
        }
    }

    fun setIntensity(newValue: Float) {
        val clamped = newValue.coerceIn(
            WhistleToneGenerator.MIN_INTENSITY,
            WhistleToneGenerator.MAX_INTENSITY
        )
        val wasPlaying = _uiState.value.isPlaying
        _uiState.value = _uiState.value.copy(intensity = clamped)
        if (wasPlaying) {
            startWhistle()
        }
    }

    fun setIntensityPresetStandard() = setIntensity(PRESET_STANDARD_INTENSITY)

    fun setIntensityPresetHigh() = setIntensity(PRESET_HIGH_INTENSITY)

    fun setIntensityPresetMax() = setIntensity(PRESET_MAX_INTENSITY)

    fun confirmLoudnessDialog() {
        hasAcknowledgedWarning = true
        _uiState.value = _uiState.value.copy(showLoudnessDialog = false)
        maybeBoostVolume()
        startWhistle()
    }

    fun dismissLoudnessDialog() {
        _uiState.value = _uiState.value.copy(showLoudnessDialog = false)
    }

    private fun startWhistle() {
        viewModelScope.launch(Dispatchers.Default + exceptionHandler) {
            runCatching {
                if (!requestAudioFocus()) {
                    throw IllegalStateException("Audio focus not granted")
                }
                val currentState = _uiState.value
                val buffer = WhistleToneGenerator.generate(
                    WhistleToneRequest(
                        frequencyHz = currentState.frequencyHz,
                        intensity = currentState.intensity,
                        mode = currentState.mode
                    )
                )
                replaceAudioTrack(buffer, currentState)
                _uiState.value = _uiState.value.copy(isPlaying = true, errorMessage = null)
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    isPlaying = false,
                    errorMessage = R.string.whistle_error_playback
                )
                stopWhistle()
            }
        }
    }

    fun stopWhistle() {
        viewModelScope.launch(Dispatchers.Default + exceptionHandler) {
            releaseAudioTrack()
            abandonAudioFocus()
            restoreVolumeIfNeeded()
            _uiState.value = _uiState.value.copy(
                isPlaying = false,
                routeSummary = null,
                routeNeedsAttention = false,
                boostGainDb = 0
            )
            publishVolumeState()
        }
    }

    private fun replaceAudioTrack(buffer: ShortArray, state: WhistleUiState) {
        releaseAudioTrack()

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(WhistleToneGenerator.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val track = AudioTrack(
            attributes,
            format,
            buffer.size * java.lang.Short.BYTES,
            AudioTrack.MODE_STATIC,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        track.write(buffer, 0, buffer.size)
        preferBuiltInSpeaker(track)
        val playbackGain = runCatching { AudioTrack.getMaxVolume() }.getOrDefault(1f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            track.setVolume(playbackGain)
        } else {
            @Suppress("DEPRECATION")
            track.setStereoVolume(playbackGain, playbackGain)
        }

        registerRoutingListener(track)
        val boostGainMb = computeBoostGainMb(state)
        attachLoudnessEnhancer(track, boostGainMb)
        track.setLoopPoints(0, buffer.size, -1)
        track.play()
        publishRouteState(track)
        publishVolumeState()

        audioTrack = track
    }

    private fun releaseAudioTrack() {
        audioTrack?.let { track ->
            routingListener?.let { listener ->
                runCatching { track.removeOnRoutingChangedListener(listener) }
            }
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
        }
        audioTrack = null
        routingListener = null

        loudnessEnhancer?.let { enhancer ->
            runCatching {
                enhancer.enabled = false
                enhancer.release()
            }
        }
        loudnessEnhancer = null
        currentBoostGainMb = 0
    }

    private fun registerRoutingListener(track: AudioTrack) {
        routingListener = AudioRouting.OnRoutingChangedListener { router ->
            val routedTrack = router as? AudioTrack ?: return@OnRoutingChangedListener
            publishRouteState(routedTrack)
        }
        routingListener?.let { listener ->
            runCatching { track.addOnRoutingChangedListener(listener, null) }
        }
    }

    private fun attachLoudnessEnhancer(track: AudioTrack, gainMb: Int) {
        loudnessEnhancer = null
        currentBoostGainMb = 0
        if (gainMb <= 0) {
            return
        }

        loudnessEnhancer = runCatching {
            LoudnessEnhancer(track.audioSessionId).apply {
                setTargetGain(gainMb)
                enabled = true
            }
        }.getOrNull()
        currentBoostGainMb = if (loudnessEnhancer != null) gainMb else 0
    }

    private fun computeBoostGainMb(state: WhistleUiState): Int {
        val base = when {
            state.intensity >= 0.97f -> HIGH_BOOST_GAIN_MB
            state.intensity >= 0.84f -> MEDIUM_BOOST_GAIN_MB
            state.intensity >= 0.68f -> LOW_BOOST_GAIN_MB
            else -> 0
        }
        val rescueBonus = if (state.mode == WhistleMode.RESCUE) {
            RESCUE_MODE_EXTRA_BOOST_MB
        } else {
            0
        }
        return (base + rescueBonus).coerceAtMost(MAX_BOOST_GAIN_MB)
    }

    private fun publishVolumeState() {
        _uiState.value = _uiState.value.copy(
            alarmVolume = audioManager.getStreamVolume(streamType),
            alarmVolumeMax = audioManager.getStreamMaxVolume(streamType),
            boostGainDb = currentBoostGainMb / 100
        )
    }

    private fun publishRouteState(track: AudioTrack) {
        val routedDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            track.routedDevice ?: track.preferredDevice
        } else {
            null
        }
        val routeSummary = routedDevice?.let(::deviceLabel)
        val routeNeedsAttention = routedDevice?.let(::isExternalOutputRoute) ?: false
        _uiState.value = _uiState.value.copy(
            routeSummary = routeSummary,
            routeNeedsAttention = routeNeedsAttention
        )
    }

    private fun deviceLabel(device: AudioDeviceInfo): String {
        val resId = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> R.string.whistle_route_builtin_speaker
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> R.string.whistle_route_earpiece
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> R.string.whistle_route_bluetooth

            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> R.string.whistle_route_wired

            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> R.string.whistle_route_usb

            else -> R.string.whistle_route_unknown
        }
        return appContext.getString(resId)
    }

    private fun isExternalOutputRoute(device: AudioDeviceInfo): Boolean {
        return when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> false
            else -> true
        }
    }

    private fun preferBuiltInSpeaker(track: AudioTrack) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }
        val builtInSpeaker = audioManager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        if (builtInSpeaker != null) {
            runCatching { track.preferredDevice = builtInSpeaker }
        }
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            focusRequest = audioFocusRequest
            audioManager.requestAudioFocus(audioFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener,
                streamType,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
    }

    private fun maybeBoostVolume() {
        val maxVolume = audioManager.getStreamMaxVolume(streamType)
        val currentVolume = audioManager.getStreamVolume(streamType)
        previousVolume = currentVolume
        if (currentVolume < maxVolume) {
            runCatching { audioManager.setStreamVolume(streamType, maxVolume, 0) }
            volumeAdjusted = true
        }
        publishVolumeState()
    }

    private fun restoreVolumeIfNeeded() {
        if (volumeAdjusted) {
            previousVolume?.let { volume ->
                runCatching { audioManager.setStreamVolume(streamType, volume, 0) }
            }
            volumeAdjusted = false
            previousVolume = null
        }
        publishVolumeState()
    }

    private fun isStreamAtMax(): Boolean {
        val maxVolume = audioManager.getStreamMaxVolume(streamType)
        val currentVolume = audioManager.getStreamVolume(streamType)
        return currentVolume >= maxVolume
    }

    override fun onCleared() {
        stopWhistle()
        super.onCleared()
    }
}
