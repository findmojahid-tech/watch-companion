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
    val directWatch = remember { DirectWatchClient(context) }
    val watch by directWatch.state.collectAsState()
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
    var showCloud by remember { mutableStateOf(false) }
    var showTasks by remember { mutableStateOf(false) }
    var tasks by remember { mutableStateOf(taskEngine.tasks()) }
    var aiKey by remember { mutableStateOf("") }
    var cloudInfo by remember { mutableStateOf("Optional: stronger cloud AI, requires a key.") }
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
        say(output.take(550))
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
                } else if (offlineStore.verified()) {
                    OfflineMomo.answer(context, text)
                } else {
                    val key = keys.read()
                    if (!key.isNullOrBlank()) {
                        GeminiClient.chat(text, emptyList(), key)
                    } else {
                        "To answer questions without an API key, tap Download offline AI " +
                            "in this app. It's a one-time 347 MB download."
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
    }

    // Refresh model download and show last recognized wake conversation without requiring
    // a debug screen. This polls only while this Activity is visible.
    LaunchedEffect(Unit) {
        var lastWakeReply = ""
        while (true) {
            model = offlineStore.status()
            wakeEnabled = prefs.getBoolean("wake_running", false)
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
            directWatch.close()
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
                    if (model.ready) "Free offline brain ready · no API key"
                    else "Offline brain not installed"
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
                modifier = Modifier.fillMaxWidth().height(62.dp),
                shape = RoundedCornerShape(21.dp)
            ) {
                Text(if (wakeEnabled) "Stop Momo Listening" else "Start Momo Listening",
                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
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
                MomoNote("A visible microphone notification stays on. Android may suspend " +
                    "continuous recognition after locking the phone; this is experimental.")
            }
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
                onClick = {
                    if (watchSpeechRunning || wakeEnabled || micTestRunning) return@Button
                    watchSpeechRunning = true
                    watchSpeechStatus = "Speak into Watch 7 for up to 9 seconds. " +
                        "Try: What is the capital of Bangladesh?"
                    val accent = prefs.getString("recognition_locale", "en-IN") ?: "en-IN"
                    recognizeWatch(
                        accent,
                        { progress ->
                            watchSpeechStatus = progress
                        },
                        { result ->
                            watchSpeechRunning = false
                            val spoken = result.hypotheses.firstOrNull().orEmpty()
                            val route = result.captureRoute
                            if (!result.capturedFromWatch) {
                                watchSpeechStatus = "Input source not confirmed as watch: " + route +
                                    ". No AI command sent. Reconnect Watch call and retry."
                                face = FaceState.CONFUSED
                            } else if (spoken.isBlank()) {
                                watchSpeechStatus = "Watch microphone was selected, " +
                                    "but no English text was recognized. Please retry."
                                face = FaceState.CONFUSED
                            } else {
                                watchSpeechStatus = "Heard through test: " + spoken +
                                    "\nAudioRecord route: " + route +
                                    "\nAndroid recognition may ignore injected input " +
                                    "on some phones. Check that the phone was far away."
                                val clean = spoken.replace(
                                    Regex("^(?:(?:hey|hi|hello)\\s+)?momo[\\s,.:!?]*",
                                        RegexOption.IGNORE_CASE), ""
                                ).trim().ifBlank { spoken }
                                ask(clean)
                            }
                        },
                        { error ->
                            watchSpeechRunning = false
                            watchSpeechStatus = "Watch speech test: " + error
                            face = FaceState.CONFUSED
                        }
                    )
                },
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
                Text("Watch face: direct transfer pending protocol verification",
                    color = Color.White, fontWeight = FontWeight.SemiBold)
                MomoNote("Your Watch 7 exposes custom 6E40 services and a Goodix OTA channel. " +
                    "A safe dial-upload format has NOT been identified. No file will be " +
                    "sent to the watch until its exact protocol is confirmed.")
                OutlinedButton(onClick = {
                    dialInfo = if (MomoFaceExport.saveToGallery(context)) {
                        "Momo PNG generated locally in Pictures/Momo. " +
                            "It has not been transferred to the watch."
                    } else "Could not create the PNG."
                }, modifier = Modifier.fillMaxWidth()) { Text("Generate Momo face PNG") }
                if (dialInfo.isNotBlank()) MomoNote(dialInfo)
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

                OutlinedButton(onClick = { showCloud = !showCloud },
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (showCloud) "Hide optional cloud AI" else "Optional: stronger cloud AI")
                }
                if (showCloud) {
                    MomoNote("Gemini cloud uses your own API key and internet. " +
                        "Offline AI is the default if it is installed.")
                    OutlinedTextField(
                        value = aiKey,
                        onValueChange = { aiKey = it.take(250) },
                        label = { Text("Gemini API key (optional)") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = {
                        validatingKey = true
                        scope.launch {
                            cloudInfo = try {
                                val check = GeminiClient.verifyKey(aiKey.trim())
                                if (keys.save(aiKey.trim())) {
                                    aiKey = ""
                                    "Connected to " + check.model
                                } else "Google accepted the key but the phone could not save it."
                            } catch (e: Exception) {
                                e.message ?: "Cloud AI setup failed."
                            }
                            validatingKey = false
                        }
                    }, enabled = !validatingKey && aiKey.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()) {
                        Text(if (validatingKey) "Verifying..." else "Verify and save cloud key")
                    }
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://aistudio.google.com/apikey")))
                    }, modifier = Modifier.fillMaxWidth()) { Text("Get Gemini API key") }
                    MomoNote(cloudInfo)
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
