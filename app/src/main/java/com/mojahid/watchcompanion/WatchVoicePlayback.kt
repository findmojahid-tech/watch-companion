package com.mojahid.watchcompanion

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Play voice through the Android app's OWN audio stream, not TTS engine playback.
 *
 * TextToSpeech.speak() may be rendered by another process, so the calling
 * Activity's AudioManager communication route does not prove its output route.
 * Instead we generate speech to a temporary WAV, then play it using an in-app
 * MediaPlayer with a preferred Bluetooth communication device.
 *
 * Playback starts MUTED and is unmuted ONLY when MediaPlayer.getRoutedDevice()
 * reports a matching Bluetooth call endpoint. It never intentionally falls
 * back to the Pixel speaker in CMF mode. Temporary WAVs stay in app cache.
 *
 * This still cannot force an OEM watch to expose an HFP audio endpoint.
 */
class WatchVoicePlayback(
    private val context: Context,
    private val manager: AudioManager,
    private val tts: TextToSpeech,
    private val onLegacySpeechFinished: (() -> Unit)? = null
) {
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("momo_runtime", Context.MODE_PRIVATE)
    private var pendingId: String? = null
    private var pendingWav: File? = null
    private var selected: AudioDeviceInfo? = null
    private var player: MediaPlayer? = null
    private var routeVerified = false
    private var status: ((String) -> Unit)? = null
    private var afterPlayback: (() -> Unit)? = null

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) {
                handler.post {
                    if (pendingId == utteranceId) startPlayer()
                    else if (!utteranceId.startsWith("tiffy-watch-")) onLegacySpeechFinished?.invoke()
                }
            }
            override fun onError(utteranceId: String) {
                handler.post {
                    if (pendingId == utteranceId) finish(
                        "Watch voice synthesis failed. Nothing sent to phone speaker."
                    ) else if (!utteranceId.startsWith("tiffy-watch-")) onLegacySpeechFinished?.invoke()
                }
            }
            override fun onStop(utteranceId: String, interrupted: Boolean) {
                handler.post {
                    if (pendingId == utteranceId) finish("Watch voice synthesis cancelled.")
                }
            }
        })
    }

    fun speak(
        text: String,
        onStatus: (String) -> Unit,
        onFinished: (() -> Unit)? = null
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { speak(text, onStatus, onFinished) }
            return
        }
        clear(true)
        status = onStatus
        afterPlayback = onFinished

        val preferred = prefs.getString("preferred_audio_name", null)?.trim().orEmpty()
        val output = manager.communicationDevice
        val allowed = output != null &&
            (output.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                output.type == AudioDeviceInfo.TYPE_BLE_HEADSET) &&
            output.productName?.toString()?.equals(preferred, ignoreCase = true) == true
        if (!allowed) {
            finish("No selected Watch call audio route. Open Tiffy > Watch profile, " +
                "choose the CMF/BT Call device, then try Test speaker.")
            return
        }

        selected = output
        routeVerified = false
        val id = "tiffy-watch-" + UUID.randomUUID()
        val wav = File(context.cacheDir, id + ".wav")
        pendingId = id
        pendingWav = wav
        onStatus("Preparing voice for " + output.productName + " ...")
        val result = tts.synthesizeToFile(text.take(1300), Bundle(), wav, id)
        if (result != TextToSpeech.SUCCESS) {
            finish("Android TTS did not synthesize watch voice. No phone fallback.")
        }
    }

    private fun startPlayer() {
        val target = selected ?: return finish("Watch audio target was lost.")
        val wav = pendingWav ?: return finish("Watch voice file was lost.")
        val current = manager.communicationDevice
        if (current?.id != target.id) {
            finish("Watch call route changed while synthesizing. Audio not played.")
            return
        }
        if (!wav.exists() || wav.length() < 44) {
            finish("Android did not generate a valid speech file.")
            return
        }
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            if (!mp.setPreferredDevice(target)) {
                finish("Android refused the Watch call playback device. Nothing sent to phone.")
                return
            }
            mp.setDataSource(wav.absolutePath)
            mp.setVolume(0f, 0f)
            mp.setOnPreparedListener { prepared ->
                if (player !== prepared) return@setOnPreparedListener
                runCatching {
                    prepared.start()
                    checkActualRoute(prepared, target, 0)
                }.onFailure {
                    finish("Watch audio playback could not start: " +
                        (it.message ?: "unknown error"))
                }
            }
            mp.setOnErrorListener { _, what, _ ->
                finish("Watch audio playback error (" + what + "). No phone fallback.")
                true
            }
            mp.setOnCompletionListener {
                if (player === it) {
                    finish(if (routeVerified) {
                        "Watch audio playback completed. Confirm sound on physical D395."
                    } else {
                        "Android never confirmed watch output; no successful playback claimed."
                    })
                }
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            finish("Watch playback setup failed: " + (e.message ?: "unknown error"))
        }
    }

    private fun checkActualRoute(mp: MediaPlayer, target: AudioDeviceInfo, attempt: Int) {
        handler.postDelayed({
            if (player !== mp) return@postDelayed
            val actual = mp.routedDevice
            val recognized = actual != null &&
                (actual.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    actual.type == AudioDeviceInfo.TYPE_BLE_HEADSET) &&
                (actual.id == target.id ||
                    actual.productName?.toString()?.lowercase(Locale.ROOT) ==
                    target.productName?.toString()?.lowercase(Locale.ROOT))
            if (recognized) {
                routeVerified = true
                mp.setVolume(1f, 1f)
                status?.invoke("Android active output: " + actual!!.productName +
                    " (Bluetooth call audio). Please confirm sound on physical D395.")
            } else if (attempt < 12) {
                checkActualRoute(mp, target, attempt + 1)
            } else {
                finish("Audio output was not routed to the selected CMF watch. " +
                    "Android may restrict this watch to actual phone calls. " +
                    "Speaker audio stayed muted; no phone playback was intended.")
            }
        }, 120L)
    }

    private fun finish(message: String) {
        val callback = status
        val done = afterPlayback
        clear(false)
        callback?.invoke(message)
        done?.invoke()
    }

    fun stop() {
        if (Looper.myLooper() == Looper.getMainLooper()) clear(true)
        else handler.post { clear(true) }
    }

    private fun clear(cancelTts: Boolean) {
        if (cancelTts) runCatching { tts.stop() }
        pendingId = null
        selected = null
        routeVerified = false
        val oldPlayer = player
        player = null
        if (oldPlayer != null) {
            runCatching { oldPlayer.setVolume(0f, 0f) }
            runCatching { oldPlayer.stop() }
            runCatching { oldPlayer.release() }
        }
        pendingWav?.let { runCatching { it.delete() } }
        pendingWav = null
        status = null
        afterPlayback = null
    }
}
