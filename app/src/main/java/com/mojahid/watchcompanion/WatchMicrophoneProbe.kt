package com.mojahid.watchcompanion

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A one-shot, privacy-safe Watch call MIC routing diagnostic.
 *
 * Reads raw sound levels for three seconds into a short-lived in-memory PCM
 * buffer. It does NOT save audio, upload audio, or start a background service.
 * Only the AudioRecord's ACTUAL routed input device is used to identify
 * whether the watch or Pixel 7 microphone captured the test audio.
 */
object WatchMicrophoneProbe {
    private fun isWatchType(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }

    data class Result(val title: String, val detail: String, val isWatchMic: Boolean)

    @SuppressLint("MissingPermission")
    suspend fun test(context: Context, manager: AudioManager): Result =
        withContext(Dispatchers.IO) {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED) {
                return@withContext Result(
                    "Microphone permission missing",
                    "Allow Microphone permission in Android settings, then try again.",
                    false
                )
            }
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED) {
                return@withContext Result(
                    "Bluetooth permission missing",
                    "Allow Nearby devices permission, then try again.",
                    false
                )
            }

            val output = manager.communicationDevice
            if (output == null || !isWatchType(output.type)) {
                return@withContext Result(
                    "Choose Watch call first",
                    "Tap the Watch call button above, then test the microphone.",
                    false
                )
            }

            val inputs = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val preferred = inputs.firstOrNull { isWatchType(it.type) }
            val sampleRates = listOf(16000, 8000)
            var record: AudioRecord? = null
            var lastError = "No compatible recorder could be initialized."

            try {
                for (rate in sampleRates) {
                    val minBytes = AudioRecord.getMinBufferSize(
                        rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    if (minBytes <= 0) {
                        lastError = "Audio input format is not available."
                        continue
                    }
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
                            .setBufferSizeInBytes(max(minBytes * 2, rate))
                            .build()
                    }.getOrNull()
                    if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                        record = candidate
                        break
                    }
                    candidate?.release()
                    lastError = "The audio recorder was not initialized."
                }

                val recorder = record
                    ?: return@withContext Result("Microphone test unavailable", lastError, false)

                // Request the paired watch's microphone, where Android exposes it.
                // Actual route is checked separately after recording begins.
                val preferredAccepted = preferred?.let {
                    runCatching { recorder.setPreferredDevice(it) }.getOrDefault(false)
                } ?: false

                try {
                    recorder.startRecording()
                } catch (error: Exception) {
                    return@withContext Result(
                        "Microphone already in use",
                        "Stop Momo listening or another recording app first. " +
                            (error.message ?: ""),
                        false
                    )
                }

                val start = SystemClock.elapsedRealtime()
                val samples = ShortArray(1280)
                var total = 0L
                var sumSquares = 0.0
                var peak = 0
                var actualDevice: AudioDeviceInfo? = null

                while (SystemClock.elapsedRealtime() - start < 3200L) {
                    val read = recorder.read(
                        samples, 0, samples.size, AudioRecord.READ_BLOCKING
                    )
                    if (read <= 0) {
                        lastError = "The audio recorder returned " + read + "."
                        break
                    }
                    total += read.toLong()
                    for (i in 0 until read) {
                        val magnitude = kotlin.math.abs(samples[i].toInt())
                        if (magnitude > peak) peak = magnitude
                        sumSquares += samples[i].toDouble() * samples[i].toDouble()
                    }
                    actualDevice = recorder.routedDevice ?: actualDevice
                }
                actualDevice = recorder.routedDevice ?: actualDevice
                if (total < 100L) {
                    return@withContext Result(
                        "No microphone data captured",
                        lastError + " Confirm audio permissions and try again.",
                        false
                    )
                }

                val rootMeanSquare = sqrt(sumSquares / total.toDouble())
                val dbfs = 20.0 * log10((rootMeanSquare / 32768.0).coerceAtLeast(0.000001))
                val noiseLabel = if (peak < 200) "very quiet" else "sound detected"
                val level = String.format(Locale.US, "%.1f", dbfs) + " dBFS"

                when {
                    actualDevice != null && isWatchType(actualDevice.type) -> Result(
                        "WATCH MICROPHONE CONFIRMED",
                        "Android recorded " + noiseLabel +
                            " through the Bluetooth headset microphone (" +
                            (actualDevice.productName?.toString() ?: "Watch call") +
                            "). Input: " + level +
                            ". No sound was saved or uploaded. " +
                            "Speech recognition routing still needs a separate test.",
                        true
                    )
                    actualDevice != null -> Result(
                        "Phone or other microphone used",
                        "Input was routed to " +
                            (actualDevice.productName?.toString() ?: "Android microphone") +
                            " (type " + actualDevice.type + "). Level: " + level +
                            ". Watch microphone was NOT confirmed; selecting Watch call " +
                            "as output alone does not prove the input route.",
                        false
                    )
                    else -> Result(
                        "Microphone route unknown",
                        "Audio was captured (" + noiseLabel + ", " + level +
                            "), but Android did not report the active input. " +
                            if (preferredAccepted) {
                                "Watch microphone was requested but is not confirmed."
                            } else {
                                "No compatible watch microphone input was reported."
                            },
                        false
                    )
                }
            } finally {
                record?.let {
                    runCatching {
                        if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
                    }
                    it.release()
                }
            }
        }
}
