package com.mojahid.watchcompanion

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private val MomoBg = Color(0xFF080C14)
private val MomoCard = Color(0xFF131C2B)
private val MomoBlue = Color(0xFF8FDEFC)
private val MomoSoft = Color(0xFFB2C3D5)

@Composable
private fun MomoPanel(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(23.dp),
        colors = CardDefaults.cardColors(containerColor = MomoCard)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
            content = content
        )
    }
}

@Composable
private fun MomoNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MomoSoft)
}

@Composable
fun MomoHome(
    audio: AudioManager,
    say: (String) -> Unit,
    recognize: (String, (List<String>) -> Unit, (String) -> Unit) -> Unit,
    recognizeWatch: (String, (String) -> Unit, (WatchSpeechResult) -> Unit, (String) -> Unit) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("momo_runtime", Context.MODE_PRIVATE) }
    val taskEngine = remember { EnglishTaskEngine(context) }
    val keys = remember { AiKeyStore(context) }
    val offlineStore = remember { OfflineModelStore(context) }
    val diagnostics = remember { BleDiagnostics(context) }
    val directWatch = remember { DirectWatchSession.get(context.applicationContext) }
    val watch by directWatch.state.collectAsState()
    val watchProbe by directWatch.protocolCapture.collectAsState()
    val scope = rememberCoroutineScope()

    var model by remember { mutableStateOf(ModelDownloadStatus("Checking offline AI...")) }
    var status by remember { mutableStateOf("Hi! I'm Momo. Let's get your watch ready.") }
    var face by remember { mutableStateOf(FaceState.IDLE) }
    var heard by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    var voiceBusy by remember { mutableStateOf(false) }
    var wakeEnabled by remember { mutableStateOf(prefs.getBoolean("wake_running", false)) }
    var wakeInputStatus by remember { mutableStateOf("") }
    var audioChoices by remember { mutableStateOf<List<AudioDeviceInfo>>(emptyList()) }
    var chosenAudio by remember { mutableStateOf<AudioDeviceInfo?>(null) }
    var audioInfo by remember { mutableStateOf("Connect the Watch call Bluetooth profile.") }
    var micTestRunning by remember { mutableStateOf(false) }
    var watchSpeechRunning by remember { mutableStateOf(false) }
    var watchSpeechStatus by remember {
        mutableStateOf("English recognition from the watch microphone isn't tested yet.")
    }
    var micTestStatus by remember {
        mutableStateOf("Speaker confirmed by user. Microphone source still needs testing.")
    }
    var showAdvanced by remember { mutableStateOf(false) }
    var watchProbeAccepted by remember { mutableStateOf(false) }
    var watchAutoIdentify by remember { mutableStateOf(false) }
    var showTasks by remember { mutableStateOf(false) }
    var tasks by remember { mutableStateOf(taskEngine.tasks()) }
    var aiKey by remember { mutableStateOf("") }
    var cloudInfo by remember {
        mutableStateOf(if (keys.read().isNullOrBlank())
            "No cloud AI connected. Paste a key once to activate fast English answers."
        else "A Gemini key is saved. Use Test saved key to confirm it.")
    }
    var cloudConfigured by remember { mutableStateOf(!keys.read().isNullOrBlank()) }
    var chatHistory by remember { mutableStateOf<List<ChatTurn>>(emptyList()) }
    var validatingKey by remember { mutableStateOf(false) }
    var bleList by remember { mutableStateOf<List<BleDeviceRow>>(emptyList()) }
    var bleInfo by remember { mutableStateOf("No watch inspection yet.") }
    var bleResult by remember { mutableStateOf<BleInspection?>(null) }
    var dialInfo by remember { mutableStateOf("") }
    var pairedWatches by remember { mutableStateOf<List<BleDeviceRow>>(emptyList()) }
    var scanningWatches by remember { mutableStateOf(false) }

    val requestPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == false) {
            status = "Microphone permission is required to listen for Momo."
        }
        pairedWatches = directWatch.pairedWatches()
        if (result[Manifest.permission.BLUETOOTH_CONNECT] == true) {
            directWatch.reconnectIfEnabled()
        }
    }

    fun audioRefresh() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            audioInfo = "Allow Nearby devices permission, then connect your watch."
            return
        }
        runCatching {
            audioChoices = audio.availableCommunicationDevices.filter {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
            }
            chosenAudio = audio.communicationDevice
            audioInfo = if (audioChoices.isEmpty()) {
                "No Bluetooth call-audio device. Pair Watch call in Android Bluetooth settings."
            } else "Choose your Watch call audio device below."
        }.onFailure { audioInfo = "Couldn't read Bluetooth audio devices." }
    }

    fun reply(output: String) {
        answer = output
        status = output
        face = FaceState.HAPPY
        say(output.take(1600))
    }

    fun ask(raw: String) {
        val text = raw.trim()
        if (text.isBlank() || isBusy) return
        heard = text
        status = "Momo is thinking..."
        answer = ""
        face = FaceState.THINKING
        isBusy = true
        scope.launch {
            val result = try {
                val local = taskEngine.tryLocal(text)
                if (local != null) {
                    tasks = local.tasks
                    local.message
                } else {
                    val key = keys.read()
                    if (!key.isNullOrBlank()) {
                        try {
                            val result = GeminiClient.chat(text, chatHistory, key)
                            chatHistory = (chatHistory + ChatTurn("user", text) +
                                ChatTurn("model", result)).takeLast(8)
                            result
                        } catch (cloudError: Exception) {
                            if (offlineStore.verified()) {
                                OfflineMomo.answer(context, text)
                            } else {
                                "Gemini connection issue: " +
                                    (cloudError.message ?: "Try the AI key test below.")
                            }
                        }
                    } else if (offlineStore.verified()) {
                        OfflineMomo.answer(context, text)
                    } else {
                        "Connect Gemini under AI Brain below, or download the offline AI."
                    }
                }
            } catch (error: Exception) {
                "I couldn't answer yet: " + (error.message ?: "try again.")
            }
            isBusy = false
            reply(result)
        }
    }

    fun startWake() {
        prefs.edit().remove("wake_last_status").apply()
        wakeInputStatus = ""
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            status = "Please allow the microphone, then tap Start Momo again."
            return
        }
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
        }.onSuccess {
            wakeEnabled = true
            status = "Momo is listening. Say 'Momo' or 'Hey Momo'."
            face = FaceState.LISTENING
        }.onFailure {
            status = "Couldn't start wake listening. Open Momo and retry: " +
                (it.message ?: "Android restriction.")
        }
    }

    fun stopWake() {
        context.stopService(Intent(context, WakeWordService::class.java))
        prefs.edit().putBoolean("wake_running", false).apply()
        wakeEnabled = false
        face = FaceState.IDLE
        status = "Momo is resting. Tap Start Momo when you want to talk."
    }

    /**
     * One tap: use only a specifically named Watch call endpoint. Do not hijack
     * Pixel Buds/other headsets; no phone OS setting, root or firmware changes.
     * This is a per-app communication audio route, not permanent pairing.
     */
    fun startWatchVoice() {
        if (watchSpeechRunning || wakeEnabled || micTestRunning || voiceBusy || isBusy) return
        if (!watch.isConnected) {
            watchSpeechStatus = "Connect Watch 7 below before talking through the watch."
            status = watchSpeechStatus
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            watchSpeechStatus = "Allow Nearby devices to use Watch call."
            requestPermissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
            return
        }
        audioRefresh()
        val preferred = prefs.getString("preferred_audio_name", null)
        val chosenId = WatchAudioRouting.choose(
            audioChoices.map {
                WatchAudioRouting.Candidate(it.id, it.productName?.toString().orEmpty())
            },
            preferred
        )
        val device = audioChoices.firstOrNull { it.id == chosenId }
        if (device == null) {
            watchSpeechStatus = if (audioChoices.isEmpty()) {
                "No Watch call audio profile is available. Pair Watch call in Android Bluetooth settings."
            } else {
                "Cannot safely auto-pick a watch microphone. Choose your Watch call device below."
            }
            status = watchSpeechStatus
            face = FaceState.CONFUSED
            return
        }
        val routed = runCatching {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            audio.setCommunicationDevice(device)
        }.getOrDefault(false)
        if (!routed) {
            runCatching { audio.clearCommunicationDevice() }
            runCatching { audio.mode = AudioManager.MODE_NORMAL }
            watchSpeechStatus = "Android couldn't route audio to Watch call. Reconnect the watch and retry."
            status = watchSpeechStatus
            face = FaceState.CONFUSED
            return
        }

        chosenAudio = device
        val name = device.productName?.toString().orEmpty()
        prefs.edit().putString("preferred_audio_name", name).apply()
        audioInfo = name + " selected for Momo speech; use Release watch audio to restore phone."
        watchSpeechRunning = true
        face = FaceState.LISTENING
        status = "Listening for your question on Watch 7..."
        watchSpeechStatus = "Watch call selected. Speak in English within 9 seconds."

        scope.launch {
            // HFP/BLE Audio needs a short route-settle period on Pixel 7.
            delay(650)
            recognizeWatch(
                prefs.getString("recognition_locale", "en-IN") ?: "en-IN",
                { progress -> watchSpeechStatus = progress },
                { result ->
                    watchSpeechRunning = false
                    val spoken = result.hypotheses.firstOrNull().orEmpty()
                    val route = result.captureRoute
                    when {
                        !result.capturedFromWatch -> {
                            watchSpeechStatus = "Watch microphone route NOT confirmed: " +
                                route + ". No AI command was sent."
                            face = FaceState.CONFUSED
                        }
                        spoken.isBlank() -> {
                            watchSpeechStatus = "Watch microphone selected, but no English words recognized."
                            face = FaceState.CONFUSED
                        }
                        else -> {
                            watchSpeechStatus = "Watch mic AudioRecord route: " + route +
                                ". Recognition service may still use a different input."
                            val clean = spoken.replace(
                                Regex("^(?:(?:hey|hi|hello)\\s+)?momo[\\s,.:!?]*",
                                    RegexOption.IGNORE_CASE), ""
                            ).trim().ifBlank { spoken }
                            ask(clean)
                        }
                    }
                },
                { error ->
                    watchSpeechRunning = false
                    watchSpeechStatus = "Watch microphone: " + error
                    face = FaceState.CONFUSED
                }
            )
        }
    }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        )
        if (Build.VERSION.SDK_INT >= 33) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        requestPermissions.launch(permissions.toTypedArray())
        audioRefresh()
        pairedWatches = directWatch.pairedWatches()
        directWatch.reconnectIfEnabled()
    }

    // Refresh model download and show last recognized wake conversation without requiring
    // a debug screen. This polls only while this Activity is visible.
    LaunchedEffect(Unit) {
        var lastWakeReply = ""
        while (true) {
            model = offlineStore.status()
            wakeEnabled = prefs.getBoolean("wake_running", false)
            wakeInputStatus = prefs.getString("wake_last_status", "").orEmpty()
            val wakeReply = prefs.getString("wake_last_answer", "").orEmpty()
            if (wakeReply.isNotBlank() && wakeReply != lastWakeReply) {
                lastWakeReply = wakeReply
                answer = wakeReply
                status = wakeReply
                heard = prefs.getString("wake_last_heard", "").orEmpty()
                face = FaceState.HAPPY
                tasks = taskEngine.tasks()
            }
            delay(if (model.downloading) 1100 else 2200)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            diagnostics.close()
            // DirectWatchSession is app-scoped: changing pages does not disconnect watch.
        }
    }

    // SAFE DRAWING PADDING is essential: Android 15/16 enforces edge-to-edge for
    // targetSdk 35, and V5's top TabRow was hidden under the portrait status bar.
    Column(
        Modifier.fillMaxSize()
            .background(MomoBg)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompanionFace(
                when {
                    isBusy -> FaceState.THINKING
                    voiceBusy || wakeEnabled -> FaceState.LISTENING
                    else -> face
                },
                Modifier.size(118.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("MOMO", style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Black, color = Color.White)
                Text(
                    if (wakeEnabled) "Listening for Momo" else "Your tiny AI companion",
                    color = MomoBlue, style = MaterialTheme.typography.titleSmall
                )
                MomoNote(
                    if (cloudConfigured) "Gemini AI key saved · cloud answers first"
                    else if (model.ready) "Free offline AI ready"
                    else "AI not connected — setup below"
                )
            }
        }

        MomoPanel {
            Text(
                if (wakeEnabled) "Say 'Momo' anytime while listening is active"
                else "One name. One assistant.",
                color = Color.White, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            MomoNote(
                "Say 'Momo' or 'Hey Momo'. I'll answer 'Yes?' — then ask your question in English."
            )
            Button(
                onClick = { if (wakeEnabled) stopWake() else startWake() },
                enabled = !watchSpeechRunning && !micTestRunning,
                modifier = Modifier.fillMaxWidth().height(62.dp),
                shape = RoundedCornerShape(21.dp)
            ) {
                Text(if (wakeEnabled) "Stop Momo Listening" else "Start Momo Listening",
                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
            Button(
                onClick = { startWatchVoice() },
                enabled = !wakeEnabled && !watchSpeechRunning && !micTestRunning &&
                    !voiceBusy && !isBusy,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(17.dp)
            ) {
                Text(
                    if (watchSpeechRunning) "Listening through Watch 7..."
                    else "Talk through Watch 7",
                    fontWeight = FontWeight.Bold
                )
            }
            if (!watch.isConnected) {
                MomoNote("Connect Watch 7 below to activate the watch microphone.")
            } else if (watchSpeechStatus.isNotBlank()) {
                MomoNote(watchSpeechStatus)
            }
            if (!wakeEnabled) {
                OutlinedButton(
                    onClick = {
                        if (voiceBusy || isBusy) return@OutlinedButton
                        voiceBusy = true
                        face = FaceState.LISTENING
                        status = "Listening for your English question..."
                        recognize(prefs.getString("recognition_locale", "en-IN") ?: "en-IN",
                            { alternatives ->
                                voiceBusy = false
                                val best = alternatives.firstOrNull { taskEngine.tryLocalDryRun(it) }
                                    ?: alternatives.firstOrNull().orEmpty()
                                if (best.isBlank()) {
                                    status = "I didn't catch that. Tap and try again."
                                } else {
                                    ask(best.removePrefix("Hey Momo").removePrefix("Momo").trim())
                                }
                            },
                            { issue ->
                                voiceBusy = false
                                status = issue
                                face = FaceState.CONFUSED
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Or tap to ask once") }
            }
            if (isBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                answer.ifBlank { status },
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )
            if (heard.isNotBlank()) MomoNote("Heard: " + heard)
            if (wakeEnabled) {
                if (wakeInputStatus.isNotBlank()) {
                    MomoNote("Watch listening: " + wakeInputStatus)
                }
                MomoNote("A visible microphone notification stays on. Android may suspend " +
                    "continuous recognition after locking the phone; this is experimental.")
            }
        }

        MomoPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("AI Brain", color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                Text(
                    if (cloudConfigured) "KEY SAVED" else "SETUP",
                    color = MomoBlue,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            MomoNote("Cloud Gemini answers first when your personal key is saved. " +
                "You can also use the free offline model without a key.")
            if (!cloudConfigured || aiKey.isNotBlank()) {
                OutlinedTextField(
                    value = aiKey,
                    onValueChange = { aiKey = it.trim().take(250) },
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("Gemini key — paste once") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
                            val clip = clipboard.primaryClip
                            val copied = clip?.getItemAt(0)?.coerceToText(context)
                                ?.toString()?.trim().orEmpty()
                            if (copied.isBlank() || copied.length > 250) {
                                cloudInfo = "Copy your API key first, then tap Paste key."
                            } else {
                                aiKey = copied
                                cloudInfo = "Key pasted privately. Tap Connect Gemini " +
                                    "to verify it against Google."
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Paste key") }
                    Button(
                        enabled = aiKey.isNotBlank() && !validatingKey,
                        onClick = {
                            validatingKey = true
                            cloudInfo = "Connecting to Gemini and testing an English answer..."
                            val candidate = aiKey.trim()
                            scope.launch {
                                try {
                                    val tested = GeminiClient.verifyKey(candidate)
                                    if (keys.save(candidate)) {
                                        cloudConfigured = true
                                        aiKey = ""
                                        cloudInfo = "Gemini connected: " + tested.model +
                                            ". You can now ask questions in English."
                                    } else {
                                        cloudInfo = "Google accepted the key, but the " +
                                            "phone could not save it."
                                    }
                                } catch (error: Exception) {
                                    cloudInfo = "Connection failed: " +
                                        (error.message ?: "Check network, key and quota.")
                                } finally {
                                    validatingKey = false
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (validatingKey) "Connecting..." else "Connect Gemini")
                    }
                }
            }

            if (cloudConfigured) {
                OutlinedButton(
                    onClick = {
                        val stored = keys.read()
                        if (stored.isNullOrBlank()) {
                            cloudInfo = "Saved key couldn't be unlocked. Reconnect Gemini."
                            cloudConfigured = false
                        } else {
                            validatingKey = true
                            cloudInfo = "Testing saved key..."
                            scope.launch {
                                try {
                                    val tested = GeminiClient.verifyKey(stored)
                                    cloudInfo = "Live AI OK: " + tested.model +
                                        ". " + tested.testReply
                                } catch (error: Exception) {
                                    cloudInfo = "Saved-key test failed: " +
                                        (error.message ?: "Please reconnect Gemini.")
                                } finally {
                                    validatingKey = false
                                }
                            }
                        }
                    },
                    enabled = !validatingKey,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (validatingKey) "Testing..." else "Test saved Gemini key") }
                TextButton(onClick = {
                    keys.clear()
                    aiKey = ""
                    cloudConfigured = false
                    chatHistory = emptyList()
                    cloudInfo = "Gemini key removed from this phone."
                }, modifier = Modifier.fillMaxWidth()) { Text("Remove saved key") }
            }

            MomoNote(cloudInfo)
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))
                )
            }, modifier = Modifier.fillMaxWidth()) { Text("Open Google AI Studio") }
            MomoNote("Never send your API key in a chat or put it in GitHub. " +
                "This personal prototype encrypts the key on your phone. " +
                "Google's free quota and internet availability still apply.")
        }

        MomoPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Free offline AI brain", color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if (model.ready) "READY" else if (model.downloading) "GETTING" else "SETUP",
                    color = MomoBlue, style = MaterialTheme.typography.labelMedium)
            }
            MomoNote(model.label)
            if (model.downloading) {
                LinearProgressIndicator(
                    progress = { model.progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (!model.ready && !model.downloading) {
                Button(
                    onClick = {
                        status = offlineStore.requestDownload()
                        scope.launch { model = offlineStore.status() }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Download free AI — no API key") }
                MomoNote("One-time ~347 MB download (Wi-Fi recommended). " +
                    "After that, basic questions work offline. Advanced knowledge may be limited.")
            }
            if (model.ready) {
                MomoNote("Qwen3 0.6B runs locally on your Pixel 7. " +
                    "The first answer may take longer while the model loads.")
            }
        }

        MomoPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Connect your Watch 7", color = Color.White,
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                Text(
                    if (watch.isConnected) "CONNECTED" else watch.state.uppercase(),
                    color = if (watch.isConnected) MomoBlue else MomoSoft,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            MomoNote("No vendor app required. Connect Watch 7 directly from Momo.")
            MomoNote(watch.message)
            if (watch.isConnected) {
                if (watch.battery != null) Text(
                    "Battery: " + watch.battery + "%", color = MomoBlue,
                    fontWeight = FontWeight.Bold
                )
                if (watch.manufacturer.isNotBlank()) MomoNote(
                    "Manufacturer: " + watch.manufacturer)
                if (watch.firmware.isNotBlank()) MomoNote(
                    "Firmware: " + watch.firmware)
                if (watch.hardware.isNotBlank()) MomoNote(
                    "Hardware: " + watch.hardware)
            }

            if (!watch.isConnected) {
                Button(
                    onClick = {
                        pairedWatches = directWatch.pairedWatches()
                        val candidate = directWatch.knownWatch()
                            ?: pairedWatches.firstOrNull {
                                it.name.contains("Watch 7", ignoreCase = true)
                            }
                            ?: pairedWatches.firstOrNull()
                        if (candidate == null) {
                            scanningWatches = true
                            bleList = emptyList()
                            diagnostics.scan(
                                onUpdate = { rows -> bleList = rows },
                                onStatus = { bleInfo = it }
                            )
                        } else {
                            scanningWatches = false
                            diagnostics.stopScan()
                            directWatch.connect(candidate)
                        }
                        audioRefresh()
                    },
                    enabled = !watch.isConnecting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (watch.isConnecting) "Connecting..."
                        else "Connect Watch 7"
                    )
                }
            } else {
                OutlinedButton(onClick = { directWatch.disconnect() },
                    modifier = Modifier.fillMaxWidth()) {
                    Text("Disconnect watch")
                }
            }

            if (!watch.isConnected) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            pairedWatches = directWatch.pairedWatches()
                            bleList = emptyList()
                            scanningWatches = true
                            bleInfo = "Finding watches..."
                            diagnostics.scan(
                                onUpdate = { rows -> bleList = rows },
                                onStatus = { bleInfo = it }
                            )
                        }, modifier = Modifier.weight(1f)
                    ) { Text("Find Watch") }
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                            )
                        }, modifier = Modifier.weight(1f)
                    ) { Text("Android Pairing") }
                }
                if (scanningWatches) MomoNote(bleInfo)
                (pairedWatches + bleList)
                    .distinctBy { it.address }
                    .filter {
                        (it.name.contains("watch", true) ||
                            it.name.contains("W17", true) ||
                            it.name.contains("paired", true)) &&
                            !it.name.contains("Watch call", true)
                    }
                    .take(10).forEach { row ->
                        OutlinedButton(
                            onClick = {
                                diagnostics.stopScan()
                                scanningWatches = false
                                directWatch.connect(row)
                                audioRefresh()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Connect " + row.name) }
                    }
            }

            HorizontalDivider(color = Color(0xFF344155))
            Text("Watch microphone & speaker", color = Color.White,
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            MomoNote("Select the separate 'Watch call' audio profile below. " +
                "The watch data link and voice audio are different Bluetooth connections.")
            MomoNote(audioInfo)
            audioChoices.forEach { device ->
                Button(
                    onClick = {
                        runCatching {
                            audio.mode = AudioManager.MODE_IN_COMMUNICATION
                            val routed = audio.setCommunicationDevice(device)
                            if (routed) {
                                chosenAudio = device
                                val deviceName = device.productName?.toString().orEmpty()
                                prefs.edit().putString("preferred_audio_name", deviceName).apply()
                                audioInfo = deviceName + " selected for Android voice communication."
                            } else audioInfo = "Android couldn't route communication audio."
                        }.onFailure { audioInfo = "Audio route not available." }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text((device.productName?.toString() ?: "Bluetooth call device") +
                        if (chosenAudio?.id == device.id) " ✓" else "")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    audioRefresh()
                    pairedWatches = directWatch.pairedWatches()
                }, modifier = Modifier.weight(1f)) { Text("Refresh audio") }
                OutlinedButton(onClick = {
                    say("Hello! I am Momo. Can you hear me through your watch?")
                }, modifier = Modifier.weight(1f)) { Text("Test speaker") }
            }
            OutlinedButton(
                onClick = {
                    runCatching { audio.clearCommunicationDevice() }
                    runCatching { audio.mode = AudioManager.MODE_NORMAL }
                    chosenAudio = null
                    audioInfo = "Watch call released. Phone audio is restored for this app."
                    watchSpeechStatus = "Watch call audio route released."
                },
                enabled = !watchSpeechRunning && !micTestRunning && !wakeEnabled,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Release watch audio · restore phone") }
            MomoNote("A voice response can use Watch call without keeping BLE diagnostics open.")
            HorizontalDivider(color = Color(0xFF344155))
            Text("Watch microphone verification", color = Color.White,
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            MomoNote("Test is 3 seconds. Keep the Pixel 7 away, speak close to your watch, " +
                "and look for WATCH MICROPHONE CONFIRMED. No voice audio is saved or uploaded.")
            Button(
                onClick = {
                    micTestRunning = true
                    micTestStatus = "Checking watch microphone for 3 seconds. " +
                        "Speak near your watch, not your phone..."
                    scope.launch {
                        try {
                            val result = WatchMicrophoneProbe.test(context, audio)
                            micTestStatus = result.title + "\n" + result.detail
                        } catch (error: Exception) {
                            micTestStatus = "Microphone diagnostic failed: " +
                                (error.message ?: "Try once more.")
                        } finally {
                            micTestRunning = false
                        }
                    }
                },
                enabled = !wakeEnabled && !voiceBusy && !isBusy && !micTestRunning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (micTestRunning) "Testing watch mic..." else "Test watch microphone (3 sec)")
            }
            MomoNote(micTestStatus)

            Button(
                onClick = { startWatchVoice() },
                enabled = !wakeEnabled && !watchSpeechRunning && !micTestRunning && !voiceBusy && !isBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (watchSpeechRunning) "Listening through watch..." else
                    "Ask Momo using watch microphone")
            }
            MomoNote(watchSpeechStatus)
            MomoNote("The watch mic is explicitly captured for this test. " +
                "Android speech services may ignore injected audio; successful " +
                "English recognition with the phone kept away is the next check.")
            if (wakeEnabled) {
                MomoNote("Turn off Momo Listening before the mic test so the " +
                    "two microphone sessions don't compete.")
            }
        }

        MomoPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompanionFace(FaceState.IDLE, Modifier.size(90.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Momo face on Watch 7", color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium)
                    MomoNote("Custom watch-face transfer: NOT VERIFIED")
                }
            }
            MomoNote("This watch's display runs proprietary firmware. " +
                "Momo can connect by BLE, but the firmware 4.21 face-upload " +
                "packet format is still unknown. Exporting a PNG does NOT " +
                "change the watch screen.")
            Button(
                onClick = {
                    dialInfo = if (MomoFaceExport.saveToGallery(context)) {
                        "Momo image generated at Pictures/Momo (320 x 390 PNG). " +
                            "NOT uploaded to the watch."
                    } else {
                        "Unable to create the Momo image."
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Prepare Momo watch-face image") }

            if (watch.isConnected) {
                MomoNote("Connected watch firmware: " +
                    watch.firmware.ifBlank { "unknown" } +
                    " / hardware: " + watch.hardware.ifBlank { "unknown" })
                MomoNote("Discovered " + watch.vendorServices.size +
                    " vendor BLE services, " + watch.vendorCharacteristics.size +
                    " proprietary characteristics.")
                val profile = Watch7GattProfile.assess(
                    watch.firmware, watch.hardware, watch.vendorServices,
                    watch.vendorCharacteristics
                )
                MomoNote(profile.explanation)
                OutlinedButton(onClick = {
                    val report = buildString {
                        append("Watch: ").append(watch.deviceName)
                        append("\nFirmware: ").append(watch.firmware)
                        append("\nHardware: ").append(watch.hardware)
                        append("\nVendor services:\n")
                        append(watch.vendorServices.joinToString("\n"))
                        append("\nCharacteristic properties:\n")
                        append(watch.vendorCharacteristics.joinToString("\n"))
                        append("\nWatch 7 firmware 4.21 profile match: ")
                            .append(profile.exactFirmware421Profile)
                        append("\nVerified watch-face transfer: ")
                            .append(profile.dialTransferVerified)
                        append("\nNotes: Read-only. No dial upload attempted.")
                    }
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Momo dial protocol", report))
                    dialInfo = "Read-only watchface compatibility report copied. " +
                        "It contains no AI key and no microphone audio."
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Copy watch-face protocol report")
                }
            } else {
                MomoNote("Connect Watch 7 above to inspect its dial communication capabilities.")
            }

            HorizontalDivider(color = Color(0xFF35495C))
            Text("Watch-only BLE experiment", color = Color.White,
                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            MomoNote("For Watch 7 firmware 4.21 only: read five vendor values " +
                "and monitor eight notification sources for 30 seconds. " +
                "Passive mode only changes temporary BLE notification subscriptions. " +
                "Optional JieLi identification query on 0b OR 0c requires consent. " +
                "No dial/OTA file or Android phone firmware is written.")
            if (!watchProbe.running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = watchAutoIdentify,
                        onCheckedChange = { watchAutoIdentify = it }
                    )
                    Text("One-tap identify: also send ONE experimental JieLi query " +
                        "to my watch (watch risk accepted; never phone firmware).",
                        color = MomoSoft, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f))
                }
            }
            OutlinedButton(
                onClick = {
                    watchProbeAccepted = false
                    val auto = watchAutoIdentify
                    watchAutoIdentify = false // fresh consent next session
                    directWatch.startPassiveCapture(auto)
                },
                enabled = watch.isConnected && !watchProbe.running,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (watchAutoIdentify) "Diagnose Watch 7 and identify JieLi"
                     else "Capture 30 seconds of watch BLE traffic")
            }
            val queryEvidence = Watch7PacketAnalysis.evaluate(watchProbe.events)
            val faceEvidence = Watch7PacketAnalysis.evaluateFaceChange(watchProbe.events)
            if (watchProbe.running) {
                OutlinedButton(
                    onClick = { directWatch.markWatchFaceChange() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Mark: changing face on physical watch")
                }
                MomoNote("Tap Mark immediately before switching the face on the watch. " +
                    "Momo records a timestamp only, so later notifications can " +
                    "be compared with that action. No command is sent.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = watchProbeAccepted,
                        onCheckedChange = { watchProbeAccepted = it }
                    )
                    Text("I accept the risk of ONE unknown watch-side command. " +
                        "Pixel 7 firmware and settings will not be modified.",
                        color = MomoSoft, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { directWatch.sendExperimentalJieliFeature("0b") },
                        enabled = watchProbeAccepted && !queryEvidence.queryRecorded,
                        modifier = Modifier.weight(1f)
                    ) { Text("JieLi identify 0b") }
                    OutlinedButton(
                        onClick = { directWatch.sendExperimentalJieliFeature("0c") },
                        enabled = watchProbeAccepted && !queryEvidence.queryRecorded,
                        modifier = Modifier.weight(1f)
                    ) { Text("JieLi identify 0c") }
                }
                MomoNote("Only one query per capture, on 0b OR 0c. " +
                    "The 8-byte JieLi RCSP GET_TARGET_FEATURE query is experimental: " +
                    "AC6954 is listed for some W17 Pros, but this exact firmware " +
                    "has not been validated. No Goodix OTA or face file is sent.")
                TextButton(onClick = { directWatch.stopPassiveCapture() },
                    modifier = Modifier.fillMaxWidth()) {
                    Text("Stop capture and reconnect watch")
                }
            }
            MomoNote(watchProbe.status)
            if (queryEvidence.queryRecorded) {
                MomoNote(queryEvidence.explanation)
            }
            if (faceEvidence.markerRecorded) {
                MomoNote(faceEvidence.explanation)
            }
            if (watchProbe.events.isNotEmpty()) {
                MomoNote("Capture records: " + watchProbe.events.size +
                    " (notifications: " + watchProbe.receivedPackets + "). " +
                    "Data remains in memory; no automatic uploads.")
                Text(
                    watchProbe.events.takeLast(15).joinToString("\n"),
                    color = MomoSoft,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(onClick = {
                    val summary = buildString {
                        append("MOMO Watch 7 passive BLE capture\n")
                        append("Firmware 4.21 / hardware 1.0. No OTA, no face upload.\n")
                        append("Optional owner-approved identification query: ")
                            .append(queryEvidence.queryRecorded).append("\n")
                        append("GATT write accepted (not proof of watch response): ")
                            .append(queryEvidence.gattWriteAccepted).append("\n")
                        append("Notifications AFTER query: ")
                            .append(queryEvidence.postQueryNotifications).append("\n")
                        append("Command-group notifications AFTER query: ")
                            .append(queryEvidence.postQueryCommandGroupNotifications)
                            .append("\n")
                        append("Analysis: ").append(queryEvidence.explanation).append("\n")
                        append("Physical face-change marker: ")
                            .append(faceEvidence.markerRecorded).append("\n")
                        append("Notifications AFTER face marker: ")
                            .append(faceEvidence.notificationsAfterMarker).append("\n")
                        append("Face action correlation: ").append(faceEvidence.explanation)
                            .append("\n")
                        append("Total notifications: ").append(watchProbe.receivedPackets)
                        append("\n").append(watchProbe.events.joinToString("\n"))
                    }
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("Momo watch protocol evidence", summary)
                    )
                    dialInfo = "Watch-only capture copied. Review the hex for " +
                        "private health/device data before sharing publicly."
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Copy captured BLE observations")
                }
            }
            if (dialInfo.isNotBlank()) MomoNote(dialInfo)
            MomoNote("Goodix OTA, firmware flashing and arbitrary vendor commands " +
                "remain blocked. Only one optional six-byte watch identification " +
                "query can be sent with explicit watch-risk consent.")
        }

        MomoPanel {
            Text("Ask in English", color = Color.White,
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Ask Momo a question...") },
                modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 3
            )
            Button(
                onClick = {
                    val text = input
                    input = ""
                    ask(text)
                },
                enabled = input.isNotBlank() && !isBusy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Ask Momo") }
            MomoNote("Examples: 'Why is the sky blue?', 'Add a task to design a logo', " +
                "'What tasks are left?'")
        }

        MomoPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tasks", color = Color.White, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { showTasks = !showTasks; tasks = taskEngine.tasks() }) {
                    Text(if (showTasks) "Hide" else "Show " + tasks.count { !it.done })
                }
            }
            if (showTasks) {
                if (tasks.isEmpty()) MomoNote("No tasks yet. Ask Momo to add one.")
                tasks.forEach { task ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = task.done, onCheckedChange = { checked ->
                            val updated = tasks.map {
                                if (it.id == task.id) it.copy(done = checked) else it
                            }
                            if (TaskStore(context).save(updated)) tasks = updated
                        })
                        Text(task.title, modifier = Modifier.weight(1f),
                            color = if (task.done) MomoSoft else Color.White)
                    }
                }
            }
        }

        MomoPanel {
            TextButton(onClick = { showAdvanced = !showAdvanced },
                modifier = Modifier.fillMaxWidth()) {
                Text(if (showAdvanced) "Hide watch tools & settings"
                    else "Advanced watch tools & settings")
            }
            if (showAdvanced) {
                MomoNote("Read-only watch tools. No vendor application, firmware flashing, " +
                    "root, bootloader, unknown Bluetooth writes or phone system changes.")
                OutlinedButton(onClick = {
                    bleResult = null
                    bleList = emptyList()
                    diagnostics.scan(onUpdate = { bleList = it }, onStatus = { bleInfo = it })
                }, enabled = !watch.isConnected,
                    modifier = Modifier.fillMaxWidth()) { Text("Inspect nearby BLE services") }
                if (watch.isConnected) {
                    MomoNote("Disconnect direct Watch 7 link before running the separate diagnostic session.")
                }
                MomoNote(bleInfo)
                bleList.take(12).forEach { row ->
                    OutlinedButton(enabled = !watch.isConnected, onClick = {
                        bleInfo = "Reading services from " + row.name + "..."
                        diagnostics.inspect(
                            row,
                            onResult = { bleResult = it; bleInfo = it.note },
                            onStatus = { bleInfo = it }
                        )
                    }, modifier = Modifier.fillMaxWidth()) { Text(row.name) }
                }
                bleResult?.let { inspection ->
                    MomoNote("Inspected " + inspection.device.name + ". Read-only.")
                    OutlinedButton(onClick = {
                        val report = inspection.note + "\n" + inspection.services.joinToString("\n")
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Momo GATT report", report))
                        bleInfo = "Copied watch report. Paste it in ChatGPT."
                    }, modifier = Modifier.fillMaxWidth()) { Text("Copy BLE report") }
                }

                OutlinedButton(onClick = {
                    prefs.edit().putString("recognition_locale",
                        if (prefs.getString("recognition_locale", "en-IN") == "en-IN")
                            "en-US" else "en-IN").apply()
                    status = "English speech accent changed for next voice session."
                }, modifier = Modifier.fillMaxWidth()) { Text("Switch English recognition accent") }
                if (model.ready) {
                    TextButton(onClick = {
                        scope.launch {
                            OfflineMomo.unload()
                            if (offlineStore.removeModel()) {
                                model = offlineStore.status()
                                status = "Offline model removed from the app's own storage."
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Remove downloaded offline model") }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}
