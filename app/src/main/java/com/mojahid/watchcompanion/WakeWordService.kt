package com.mojahid.watchcompanion

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.UUID

class WakeWordService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val CHANNEL = "momo_wake"
        const val ACTION_STOP = "com.mojahid.watchcompanion.STOP_WAKE"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private lateinit var tts: TextToSpeech
    private lateinit var engine: CommandEngine
    private var awaitingCommand = false
    private var ttsReady = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        tts = TextToSpeech(this, this)
        engine = CommandEngine(this)
        startForeground(1001, notification("Momo is listening"))
        startRecognizerLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startRecognizerLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        tts.stop()
        tts.shutdown()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("en", "BD")
            ttsReady = true
        }
    }

    private fun speak(text: String) {
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    private fun isWake(text: String): Boolean {
        val t = text.lowercase().trim()
        return t.contains("hey momo") || t.contains("hi momo") || t == "momo" || t.contains("মোমো")
    }

    private fun removeWake(text: String): String =
        text.replace(Regex("(?i)hey\\s+momo"), "")
            .replace(Regex("(?i)hi\\s+momo"), "")
            .replace(Regex("(?i)\\bmomo\\b"), "")
            .replace("মোমো", "")
            .trim(' ', ',', '.', '!', '?')

    private fun handleCandidates(list: List<String>) {
        if (list.isEmpty()) return
        val wakeCandidate = list.firstOrNull { isWake(it) }

        if (wakeCandidate != null) {
            val command = removeWake(wakeCandidate)
            if (command.isBlank()) {
                awaitingCommand = true
                speak("Ji bolo.")
            } else {
                awaitingCommand = false
                speak(engine.process(command).reply)
            }
            return
        }

        if (awaitingCommand) {
            awaitingCommand = false
            val result = list.asSequence().map { engine.process(it) }.firstOrNull { it.matched } ?: engine.process(list.first())
            speak(result.reply)
        }
    }

    private fun startRecognizerLoop() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (recognizer != null) return

        val sr = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { restart() }
            override fun onResults(results: Bundle?) {
                handleCandidates(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty())
                restart()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val wake = list.firstOrNull { isWake(it) }
                if (wake != null && !awaitingCommand && removeWake(wake).isBlank()) {
                    awaitingCommand = true
                    speak("Ji bolo.")
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        listen(sr)
    }

    private fun listen(sr: SpeechRecognizer) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-BD")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
        runCatching { sr.startListening(intent) }.onFailure { restart() }
    }

    private fun restart() {
        recognizer?.destroy()
        recognizer = null
        handler.postDelayed({ startRecognizerLoop() }, 700)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Momo wake listening", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(text: String): Notification {
        val stopIntent = Intent(this, WakeWordService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Watch Companion")
            .setContentText(text)
            .setOngoing(true)
            .addAction(0, "Stop", stopPending)
            .build()
    }
}
