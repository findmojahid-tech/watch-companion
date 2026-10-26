package com.mojahid.watchcompanion

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max

data class WatchSpeechResult(
    val hypotheses: List<String>,
    val captureRoute: String,
    val capturedFromWatch: Boolean
)

/**
 * A user-initiated, at-most-nine-second Bluetooth watch microphone -> English
 * speech recognition experiment.
 *
 * Input is explicitly captured with AudioRecord through the Bluetooth headset
 * microphone, then forwarded as 16-bit mono PCM to the Android recognition
 * service using Android 13+'s RecognizerIntent.EXTRA_AUDIO_SOURCE pipe.
 *
 * RecognitionService implementations MAY ignore EXTRA_AUDIO_SOURCE and open
 * their own microphone. Therefore a transcript is NOT presented as proof that
 * the recognizer itself used the provided audio; the actual AudioRecord route
 * is reported separately. No PCM is saved or uploaded by this application.
 * Android speech recognition providers may process audio on their own servers.
 */
class WatchSpeechBridge(
    private val context: Context,
    private val manager: AudioManager
) {
    private val handler = Handler(Looper.getMainLooper())

    private class ActiveSession(
        val recognizer: SpeechRecognizer,
        val record: AudioRecord,
        val reader: ParcelFileDescriptor,
        val writer: ParcelFileDescriptor,
        val sampleRate: Int
    ) {
        val ended = AtomicBoolean(false)
        @Volatile var running = true
        @Volatile var captureRoute = "Not reported by Android"
        @Volatile var capturedFromWatch = false
        @Volatile var routeSeen = false
        var timeout: Runnable? = null
    }

    private var current: ActiveSession? = null

    private fun isBluetoothMicrophone(type: Int) =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            type == AudioDeviceInfo.TYPE_BLE_HEADSET

    private fun isMainThread() = Looper.myLooper() == Looper.getMainLooper()

    fun cancel() {
        if (!isMainThread()) {
            handler.post { cancel() }
            return
        }
        current?.let { finish(it) }
    }

    private fun finish(s: ActiveSession) {
        if (!s.ended.compareAndSet(false, true)) return
        if (current === s) current = null
        s.running = false
        s.timeout?.let(handler::removeCallbacks)
        runCatching { s.record.stop() }
        runCatching { s.writer.close() }
        runCatching { s.reader.close() }
        runCatching { s.recognizer.destroy() }
        // release() is also safe if recording never successfully started.
        runCatching { s.record.release() }
    }

    private fun messageFor(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH ->
            "Speech recognizer couldn't understand this attempt. Keep the phone away and retry."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            "No speech detected. Press the button and speak close to your watch."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            "Recognizer busy. Stop Momo Listening and try once more."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Speech recognizer needs microphone permission."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Android speech recognition needs network access right now."
        else -> "Android speech recognizer error " + error + ". Try again."
    }

    @SuppressLint("MissingPermission")
    fun start(
        language: String,
        onStatus: (String) -> Unit,
        onResult: (WatchSpeechResult) -> Unit,
        onFailure: (String) -> Unit
    ) {
        if (!isMainThread()) {
            onFailure("Please start the microphone test from the app screen.")
            return
        }
        if (current != null) {
            onFailure("A watch speech test is already running.")
            return
        }
        if (Build.VERSION.SDK_INT < 33) {
            onFailure("This direct speech-audio test requires Android 13 or newer.")
            return
        }
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            onFailure("Allow Microphone and Nearby devices permissions.")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onFailure("Android speech recognition isn't available on this phone.")
            return
        }

        val output = manager.communicationDevice
        if (output == null || !isBluetoothMicrophone(output.type)) {
            onFailure("First select Watch call under Watch microphone & speaker.")
            return
        }
        val bluetoothInputs = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { isBluetoothMicrophone(it.type) }
        val input = bluetoothInputs.firstOrNull {
            it.productName?.toString()?.equals(
                output.productName?.toString(), ignoreCase = true
            ) == true
        } ?: bluetoothInputs.singleOrNull()
        if (input == null) {
            onFailure(
                if (bluetoothInputs.size > 1) {
                    "Several Bluetooth microphones are present. Disconnect other headsets and retry."
                } else {
                    "Android doesn't expose the Watch call microphone. Reconnect it."
                }
            )
            return
        }

        var record: AudioRecord? = null
        var chosenRate = 16000
        for (rate in listOf(16000, 8000)) {
            val minSize = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minSize <= 0) continue
            val candidate = runCatching {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(max(minSize * 2, rate))
                    .build()
            }.getOrNull()
            if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                record = candidate
                chosenRate = rate
                break
            }
            candidate?.release()
        }

        val mic = record ?: run {
            onFailure("Couldn't initialize the Bluetooth microphone recorder.")
            return
        }

        // setPreferredDevice() is only a preference, not evidence of active routing.
        // Android may already route the default mic to Watch call even if it returns false.
        runCatching { mic.setPreferredDevice(input) }

        val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull()
        if (pipe == null) {
            mic.release()
            onFailure("Couldn't open the internal speech-audio pipe.")
            return
        }

        val recognizer = runCatching {
            SpeechRecognizer.createSpeechRecognizer(context)
        }.getOrNull()
        if (recognizer == null) {
            runCatching { pipe[0].close() }
            runCatching { pipe[1].close() }
            mic.release()
            onFailure("Couldn't start the Android speech recognition service.")
            return
        }

        val s = ActiveSession(recognizer, mic, pipe[0], pipe[1], chosenRate)
        current = s

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (current === s && !s.ended.get()) {
                    onStatus("Listening to your Watch call microphone... speak your question.")
                }
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                if (current === s && !s.ended.get()) onStatus("Processing watch speech...")
            }
            override fun onPartialResults(partialResults: Bundle?) {
                if (current !== s || s.ended.get()) return
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isNotBlank()) onStatus("Heard so far: " + text)
            }
            override fun onResults(results: Bundle?) {
                if (current !== s || s.ended.get()) return
                val hypotheses = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.toList().orEmpty()
                val route = s.captureRoute
                val watchMic = s.capturedFromWatch
                finish(s)
                if (hypotheses.isEmpty()) {
                    onFailure("No English words recognized. Try speaking nearer the watch.")
                } else {
                    onResult(WatchSpeechResult(hypotheses, route, watchMic))
                }
            }
            override fun onError(error: Int) {
                if (current !== s || s.ended.get()) return
                val route = s.captureRoute
                finish(s)
                onFailure(messageFor(error) + " Captured mic route: " + route)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, s.reader)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(
                RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,
                AudioFormat.ENCODING_PCM_16BIT
            )
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, s.sampleRate)
        }

        val started = runCatching {
            mic.startRecording()
            if (mic.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("Recorder isn't recording.")
            }
            recognizer.startListening(intent)
            true
        }.getOrElse { failure ->
            finish(s)
            onFailure("Unable to start watch English recognition: " +
                (failure.message ?: "check microphone permission."))
            false
        }
        if (!started) return

        onStatus("Speak an English sentence into Watch 7 within 9 seconds.")
        thread(name = "momo-watch-pcm", isDaemon = true) {
            val buffer = ByteArray(1280)
            val deadline = SystemClock.elapsedRealtime() + 9000L
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(s.writer).use { outputStream ->
                    while (s.running && SystemClock.elapsedRealtime() < deadline) {
                        val bytes = s.record.read(
                            buffer, 0, buffer.size, AudioRecord.READ_BLOCKING
                        )
                        if (!s.running || bytes <= 0) break

                        val actual = s.record.routedDevice
                        if (actual != null) {
                            val isWatch = isBluetoothMicrophone(actual.type) &&
                                actual.id == input.id
                            s.captureRoute = (actual.productName?.toString()
                                ?: "Android input type " + actual.type) +
                                " (input ID " + actual.id + ")"
                            s.capturedFromWatch = isWatch
                            s.routeSeen = true
                            if (!isWatch) {
                                handler.post {
                                    if (current === s && !s.ended.get()) {
                                        finish(s)
                                        onFailure("Input routed to " + s.captureRoute +
                                            ", not Watch call. No question was sent to AI.")
                                    }
                                }
                                return@use
                            }
                        }
                        outputStream.write(buffer, 0, bytes)
                    }
                    outputStream.flush()
                }
            } catch (_: Exception) {
                // Recognition result/error and the UI timeout handle abnormal pipe closure.
            } finally {
                if (current === s && !s.ended.get()) {
                    handler.post {
                        if (current === s && !s.ended.get()) {
                            onStatus(
                                "Listening finished. Waiting for Android speech recognition..."
                            )
                        }
                    }
                }
            }
        }

        val timeout = Runnable {
            if (current === s && !s.ended.get()) {
                finish(s)
                onFailure(
                    "Speech service didn't finish within 18 seconds. " +
                        "It may not support external audio input; try again."
                )
            }
        }
        s.timeout = timeout
        handler.postDelayed(timeout, 18000L)
    }
}
