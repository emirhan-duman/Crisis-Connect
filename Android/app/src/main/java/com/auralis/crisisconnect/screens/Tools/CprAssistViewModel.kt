package com.auralis.crisisconnect.screens.Tools

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.crisisconnect.R
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CprAssistViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val _uiState = MutableStateFlow(CprAssistUiState())
    val uiState: StateFlow<CprAssistUiState> = _uiState.asStateFlow()

    private val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 92)
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { }
    private var audioFocusRequest: AudioFocusRequest? = null
    private var textToSpeech: TextToSpeech? = null
    private var tickerJob: Job? = null
    private var beatAccumulatorMillis = 0.0
    private var lastTickRealtime = 0L

    init {
        textToSpeech = TextToSpeech(appContext) { status ->
            val engine = textToSpeech
            val available = status == TextToSpeech.SUCCESS && engine != null
            if (available) {
                val locale = currentSpeechLocale()
                val voices = engine?.voices.orEmpty()
                val offlineVoice = voices
                    .asSequence()
                    .filter { voice ->
                        !voice.isNetworkConnectionRequired && voice.locale.language == locale.language
                    }
                    .sortedByDescending { voice -> voice.locale.country == locale.country }
                    .firstOrNull()
                if (offlineVoice != null) {
                    engine?.voice = offlineVoice
                    _uiState.value = _uiState.value.copy(speechAvailable = true)
                } else {
                    val languageResult = engine?.setLanguage(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
                    val languageAvailable = voices.isEmpty() &&
                        languageResult != TextToSpeech.LANG_MISSING_DATA &&
                        languageResult != TextToSpeech.LANG_NOT_SUPPORTED
                    _uiState.value = _uiState.value.copy(speechAvailable = languageAvailable)
                }
            } else {
                _uiState.value = _uiState.value.copy(speechAvailable = false)
            }
        }
    }

    fun selectMode(mode: CprAssistMode) {
        if (_uiState.value.phase == CprAssistPhase.READY) {
            _uiState.value = _uiState.value.copy(mode = mode)
        }
    }

    fun setSoundEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(soundEnabled = enabled)
    }

    fun setVoiceEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(voiceEnabled = enabled)
        if (!enabled) textToSpeech?.stop()
    }

    fun setHapticsEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(hapticsEnabled = enabled)
    }

    fun startSession() {
        val settings = _uiState.value
        tickerJob?.cancel()
        beatAccumulatorMillis = 0.0
        lastTickRealtime = SystemClock.elapsedRealtime()
        _uiState.value = CprAssistUiState(
            mode = settings.mode,
            phase = CprAssistPhase.COMPRESSIONS,
            soundEnabled = settings.soundEnabled,
            voiceEnabled = settings.voiceEnabled,
            hapticsEnabled = settings.hapticsEnabled,
            speechAvailable = settings.speechAvailable
        )
        requestAudioFocus()
        speak(R.string.cpr_voice_start)
        emitCompressionBeat()
        startTicker()
    }

    fun togglePause() {
        val state = _uiState.value
        if (!state.isSessionRunning || state.pauseReason == CprPauseReason.AED_ANALYSIS) return
        if (state.pauseReason == CprPauseReason.MANUAL) {
            lastTickRealtime = SystemClock.elapsedRealtime()
            _uiState.value = state.copy(pauseReason = null)
            speak(R.string.cpr_voice_resume)
        } else {
            _uiState.value = state.copy(pauseReason = CprPauseReason.MANUAL)
            textToSpeech?.stop()
        }
    }

    fun resumeCompressionsEarly() {
        val state = _uiState.value
        if (state.phase != CprAssistPhase.BREATHS) return
        beatAccumulatorMillis = 0.0
        _uiState.value = state.copy(
            phase = CprAssistPhase.COMPRESSIONS,
            breathRemainingMillis = 0L,
            compressionInSet = 0
        )
        speak(R.string.cpr_voice_resume)
    }

    fun endSession() {
        tickerJob?.cancel()
        tickerJob = null
        textToSpeech?.stop()
        abandonAudioFocus()
        _uiState.value = _uiState.value.copy(
            phase = CprAssistPhase.ENDED,
            pauseReason = null,
            isAedGuideOpen = false
        )
    }

    fun resetSession() {
        val state = _uiState.value
        _uiState.value = CprAssistUiState(
            mode = state.mode,
            soundEnabled = state.soundEnabled,
            voiceEnabled = state.voiceEnabled,
            hapticsEnabled = state.hapticsEnabled,
            speechAvailable = state.speechAvailable
        )
    }

    fun openAedGuide() {
        val state = _uiState.value
        if (!state.isSessionRunning) return
        _uiState.value = state.copy(
            isAedGuideOpen = true,
            aedStep = CprAedStep.POWER_ON
        )
        speak(R.string.cpr_voice_aed_arrived)
    }

    fun closeAedGuideBeforeAnalysis() {
        val state = _uiState.value
        if (state.aedStep == CprAedStep.POWER_ON || state.aedStep == CprAedStep.ATTACH_PADS) {
            _uiState.value = state.copy(isAedGuideOpen = false)
        }
    }

    fun advanceAedGuide() {
        val state = _uiState.value
        when (state.aedStep) {
            CprAedStep.POWER_ON -> {
                _uiState.value = state.copy(aedStep = CprAedStep.ATTACH_PADS)
            }
            CprAedStep.ATTACH_PADS -> {
                _uiState.value = state.copy(
                    aedStep = CprAedStep.ANALYZE,
                    pauseReason = CprPauseReason.AED_ANALYSIS
                )
                speak(R.string.cpr_voice_clear_analysis)
            }
            CprAedStep.ANALYZE -> {
                _uiState.value = state.copy(aedStep = CprAedStep.SHOCK_DECISION)
            }
            CprAedStep.SHOCK_DECISION,
            CprAedStep.RESUME_CPR -> Unit
        }
    }

    fun recordAedDecision() {
        val state = _uiState.value
        if (state.aedStep != CprAedStep.SHOCK_DECISION) return
        beatAccumulatorMillis = 0.0
        lastTickRealtime = SystemClock.elapsedRealtime()
        _uiState.value = state.copy(
            aedStep = CprAedStep.RESUME_CPR,
            pauseReason = null,
            phase = CprAssistPhase.COMPRESSIONS,
            compressionInSet = 0,
            breathRemainingMillis = 0L,
            roundElapsedMillis = 0L
        )
        speak(R.string.cpr_voice_resume_after_aed)
        emitCompressionBeat()
    }

    fun resumeAfterAed() {
        val state = _uiState.value
        if (state.aedStep != CprAedStep.RESUME_CPR) return
        _uiState.value = state.copy(
            isAedGuideOpen = false,
            aedStep = CprAedStep.POWER_ON
        )
    }

    private fun startTicker() {
        tickerJob = viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                delay(CprAssistTiming.TICK_RESOLUTION_MILLIS)
                val now = SystemClock.elapsedRealtime()
                val delta = (now - lastTickRealtime).coerceIn(0L, 250L)
                lastTickRealtime = now
                updateClock(delta)
            }
        }
    }

    private suspend fun updateClock(deltaMillis: Long) {
        val state = _uiState.value
        if (!state.isSessionRunning || state.isPaused) return

        var elapsed = state.elapsedMillis + deltaMillis
        var roundElapsed = state.roundElapsedMillis + deltaMillis
        var completedRounds = state.completedRounds
        if (roundElapsed >= CprAssistTiming.ROUND_DURATION_MILLIS) {
            roundElapsed %= CprAssistTiming.ROUND_DURATION_MILLIS
            completedRounds += 1
            speak(R.string.cpr_voice_two_minutes)
        }

        if (state.phase == CprAssistPhase.BREATHS) {
            val remaining = (state.breathRemainingMillis - deltaMillis).coerceAtLeast(0L)
            _uiState.value = state.copy(
                elapsedMillis = elapsed,
                roundElapsedMillis = roundElapsed,
                completedRounds = completedRounds,
                breathRemainingMillis = remaining
            )
            if (remaining == 0L) {
                withContext(Dispatchers.Main.immediate) { resumeCompressionsEarly() }
            }
            return
        }

        _uiState.value = state.copy(
            elapsedMillis = elapsed,
            roundElapsedMillis = roundElapsed,
            completedRounds = completedRounds
        )
        beatAccumulatorMillis += deltaMillis.toDouble()
        while (beatAccumulatorMillis >= CprAssistTiming.BEAT_INTERVAL_MILLIS) {
            beatAccumulatorMillis -= CprAssistTiming.BEAT_INTERVAL_MILLIS
            withContext(Dispatchers.Main.immediate) { emitCompressionBeat() }
            if (_uiState.value.phase != CprAssistPhase.COMPRESSIONS) break
        }
    }

    private fun emitCompressionBeat() {
        val state = _uiState.value
        if (state.phase != CprAssistPhase.COMPRESSIONS || state.isPaused) return
        val next = CprAssistTiming.nextCompressionInSet(state.compressionInSet)
        val completedSet = CprAssistTiming.completedSetAfterBeat(next)
        _uiState.value = state.copy(
            compressionInSet = next,
            totalCompressions = state.totalCompressions + 1,
            completedSets = state.completedSets + if (completedSet) 1 else 0,
            beatSequence = state.beatSequence + 1
        )
        playBeat()

        if (completedSet && state.mode == CprAssistMode.THIRTY_TO_TWO) {
            beatAccumulatorMillis = 0.0
            _uiState.value = _uiState.value.copy(
                phase = CprAssistPhase.BREATHS,
                breathRemainingMillis = CprAssistTiming.BREATH_PAUSE_MILLIS
            )
            speak(R.string.cpr_voice_two_breaths)
        }
    }

    private fun playBeat() {
        val state = _uiState.value
        if (state.soundEnabled) {
            runCatching { toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, 72) }
        }
        val beatVibrator = vibrator
        if (state.hapticsEnabled && beatVibrator?.hasVibrator() == true) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    beatVibrator.vibrate(VibrationEffect.createOneShot(28L, 105))
                } else {
                    @Suppress("DEPRECATION")
                    beatVibrator.vibrate(28L)
                }
            }
        }
    }

    private fun speak(resId: Int) {
        val state = _uiState.value
        if (!state.voiceEnabled || !state.speechAvailable) return
        val text = appContext.getString(resId)
        viewModelScope.launch(Dispatchers.Main) {
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cpr-$resId")
        }
    }

    private fun currentSpeechLocale(): Locale {
        val locales = appContext.resources.configuration.locales
        return if (!locales.isEmpty) locales[0] else Locale.getDefault()
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusListener)
        }
    }

    override fun onCleared() {
        tickerJob?.cancel()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        toneGenerator.release()
        abandonAudioFocus()
        super.onCleared()
    }
}
