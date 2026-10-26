package com.mojahid.watchcompanion

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

/**
 * Experimental opt-in wake phrase service.
 * Android SpeechRecognizer is session-based and not guaranteed for continuous wake listening.
 * Foreground notification always shows while enabled. It never auto-starts on boot.
 */
class WakeWordService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val CHANNEL = "momo_english_wake"
        const val ACTION_STOP = "com.mojahid.watchcompanion.STOP_WAKE"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine by lazy { EnglishTaskEngine(this) }
    private val keys by lazy { AiKeyStore(this) }
    private val prefs by lazy { getSharedPreferences("momo_runtime", Context.MODE_PRIVATE) }

    private var recognizer: SpeechRecognizer? = null
    private lateinit var tts: TextToSpeech
    private lateinit var audio: AudioManager
    private var speechReady = false
    private var busy = false
    private var awaitingCommandUntil = 0L
    private var errorsInRow = 0
    private var conversation = emptyList<ChatTurn>()

    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Momo wake listening", NotificationManager.IMPORTANCE_LOW)
        )
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        routeToPreferredWatch()
        tts = TextToSpeech(this, this)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) {
                handler.post { if (!busy) scheduleListening(450) }
            }
            override fun onError(utteranceId: String) {
                handler.post { if (!busy) scheduleListening(650) }
            }
        })
        prefs.edit().putBoolean("wake_running", true).apply()
        startForeground(1001, notification())
        scheduleListening(500)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        prefs.edit().putBoolean("wake_running", false).apply()
        handler.removeCallbacksAndMessages(null)
        worker.cancel()
        releaseRecognizer()
        tts.stop()
        tts.shutdown()
        runCatching { audio.clearCommunicationDevice() }
        runCatching { audio.mode = AudioManager.MODE_NORMAL }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onInit(status: Int) {
        speechReady = status == TextToSpeech.SUCCESS
        if (speechReady) {
            tts.language = Locale.US
            tts.setSpeechRate(0.94f)
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }
    }

    private fun notification(): Notification {
        val stop = Intent(this, WakeWordService::class.java).apply { action = ACTION_STOP }
        val pending = PendingIntent.getService(
            this, 2, stop, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Momo: English wake listening (beta)")
            .setContentText("Say Hey Momo. Tap Stop to disable. May use battery.")
            .setOngoing(true)
            .addAction(0, "Stop", pending)
            .build()
    }

    private fun routeToPreferredWatch() {
        val preferred = prefs.getString("preferred_audio_name", null) ?: return
        runCatching {
            val device: AudioDeviceInfo? = audio.availableCommunicationDevices.firstOrNull {
                it.productName?.toString() == preferred &&
                    (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                     it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                     it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
            if (device != null && audio.setCommunicationDevice(device)) {
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
            }
        }
    }

    private fun say(text: String) {
        if (!speechReady) {
            scheduleListening(1100)
            return
        }
        tts.speak(text.take(550), TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    private fun releaseRecognizer() {
        val old = recognizer
        recognizer = null
        old?.destroy()
    }

    private fun scheduleListening(delay: Long) {
        handler.postDelayed({ beginListening() }, delay)
    }

    private fun beginListening() {
        if (busy || recognizer != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

        val sr = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                releaseRecognizer()
                errorsInRow = (errorsInRow + 1).coerceAtMost(8)
                scheduleListening((950L + errorsInRow * 650L).coerceAtMost(7000L))
            }

            override fun onResults(results: Bundle?) {
                releaseRecognizer()
                errorsInRow = 0
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                handlePhrase(text)
            }
        })

        val language = prefs.getString("recognition_locale", "en-IN") ?: "en-IN"
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        runCatching { sr.startListening(intent) }.onFailure {
            releaseRecognizer()
            scheduleListening(2400)
        }
    }

    private fun isWake(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return lower == "momo" || lower.startsWith("momo ") ||
            lower.startsWith("hey momo") || lower.startsWith("hello momo") ||
            lower.startsWith("hi momo")
    }

    private fun stripWake(text: String): String {
        return text.replace(
            Regex("""^(?:(?:hey|hello|hi)\s+)?momo[\s,.:!?]*""", RegexOption.IGNORE_CASE),
            ""
        ).trim()
    }

    private fun handlePhrase(text: String) {
        if (text.isBlank()) {
            scheduleListening(800)
            return
        }
        if (isWake(text)) {
            val command = stripWake(text)
            if (command.isBlank()) {
                awaitingCommandUntil = SystemClock.elapsedRealtime() + 12000L
                say("Yes! I'm here. What can I do for you?")
            } else {
                awaitingCommandUntil = 0
                answer(command)
            }
        } else if (awaitingCommandUntil > SystemClock.elapsedRealtime()) {
            awaitingCommandUntil = 0
            answer(text)
        } else {
            scheduleListening(550)
        }
    }

    private fun answer(text: String) {
        busy = true
        worker.launch {
            val local = engine.tryLocal(text)
            val output = if (local != null) local.message else {
                val key = keys.read()
                if (key.isNullOrBlank()) {
                    "I didn't understand that. Enable free Gemini AI in the app's AI Setup tab."
                } else {
                    runCatching {
                        val answer = GeminiClient.chat(text, conversation, key)
                        conversation = (conversation + ChatTurn("user", text) +
                            ChatTurn("model", answer)).takeLast(8)
                        answer
                    }.getOrElse { error ->
                        "Gemini error: " +
                            (error.message ?: "Check the saved API key or internet connection.").take(170)
                    }
                }
            }
            busy = false
            say(output)
        }
    }
}
