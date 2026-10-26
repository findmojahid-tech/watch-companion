package com.mojahid.watchcompanion

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private lateinit var audioManager: AudioManager
    private lateinit var tts: TextToSpeech
    private var speechRecognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        tts = TextToSpeech(this, this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompanionApp(
                    audioManager = audioManager,
                    onSpeak = { speak(it) },
                    createRecognizer = { createRecognizer() }
                )
            }
        }
    }

    private fun createRecognizer(): SpeechRecognizer {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        return speechRecognizer!!
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts.language = Locale("en", "BD")
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }
}

enum class FaceState { IDLE, LISTENING, THINKING, HAPPY, CONFUSED }

@Composable
fun CompanionApp(
    audioManager: AudioManager,
    onSpeak: (String) -> Unit,
    createRecognizer: () -> SpeechRecognizer
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val taskStore = remember { TaskStore(context) }
    val engine = remember { CommandEngine(context) }
    val ble = remember { BleDiagnostics(context) }

    var tasks by remember { mutableStateOf(taskStore.load()) }
    var face by remember { mutableStateOf(FaceState.IDLE) }
    var transcript by remember { mutableStateOf("Ready") }
    var lastHeard by remember { mutableStateOf("") }
    var watchDevices by remember { mutableStateOf<List<AudioDeviceInfo>>(emptyList()) }
    var selectedDevice by remember { mutableStateOf<AudioDeviceInfo?>(null) }
    var wakeEnabled by remember { mutableStateOf(false) }

    var bleDevices by remember { mutableStateOf<List<BleDeviceRow>>(emptyList()) }
    var bleStatus by remember { mutableStateOf("Not scanned yet") }
    var inspection by remember { mutableStateOf<BleInspection?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    fun refreshAudioDevices() {
        watchDevices = audioManager.availableCommunicationDevices.filter {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
        }
        selectedDevice = audioManager.communicationDevice
    }

    fun reply(text: String, state: FaceState = FaceState.HAPPY) {
        transcript = text
        face = state
        onSpeak(text)
    }

    fun processCandidates(candidates: List<String>) {
        if (candidates.isEmpty()) {
            reply("Kichu shunte paini.", FaceState.CONFUSED)
            return
        }
        lastHeard = candidates.first()
        var best: CommandEngine.Result? = null
        for (candidate in candidates) {
            val result = engine.process(candidate)
            if (result.matched) {
                best = result
                lastHeard = candidate
                break
            }
        }
        val result = best ?: engine.process(candidates.first())
        tasks = result.tasks
        reply(result.reply, if (result.matched) FaceState.HAPPY else FaceState.CONFUSED)
    }

    fun startWakeMode() {
        ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
        wakeEnabled = true
        reply("Wake mode on. Momo bole dakba.")
    }

    fun stopWakeMode() {
        val intent = Intent(context, WakeWordService::class.java).apply { action = WakeWordService.ACTION_STOP }
        context.startService(intent)
        wakeEnabled = false
        reply("Wake mode off.", FaceState.IDLE)
    }

    LaunchedEffect(Unit) {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) {
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(perms.toTypedArray())
        refreshAudioDevices()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0B0F))
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompanionFace(face, Modifier.size(120.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Momo Companion", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(transcript, color = Color(0xFFB8B8C4))
                if (lastHeard.isNotBlank()) {
                    Text("Heard: $lastHeard", color = Color(0xFF7D7D8A), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF17171D))) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("1. Watch audio", fontWeight = FontWeight.SemiBold)
                if (watchDevices.isEmpty()) {
                    Text("No Bluetooth call audio device detected.", color = Color(0xFFB8B8C4))
                } else {
                    watchDevices.forEach { device ->
                        Button(
                            onClick = {
                                val allowed = Build.VERSION.SDK_INT < 31 ||
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                                if (allowed) {
                                    audioManager.setCommunicationDevice(device)
                                    selectedDevice = device
                                    reply("Watch audio select korechi.")
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val label = device.productName?.toString() ?: "Bluetooth device"
                            Text(label + if (selectedDevice?.id == device.id) "  ✓" else "")
                        }
                    }
                }
                OutlinedButton(onClick = { refreshAudioDevices() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Refresh audio devices")
                }
                OutlinedButton(onClick = { onSpeak("Momo watch speaker test. Shunte paccho?") }, modifier = Modifier.fillMaxWidth()) {
                    Text("Test watch speaker")
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF17171D))) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("2. Wake Mode", fontWeight = FontWeight.SemiBold)
                Text(
                    if (wakeEnabled) "Listening for: Momo / Hey Momo" else "Off — enable kore Momo bole dakba",
                    color = Color(0xFFB8B8C4)
                )
                Button(
                    onClick = { if (wakeEnabled) stopWakeMode() else startWakeMode() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (wakeEnabled) "Turn Wake Mode Off" else "Turn Wake Mode On")
                }
                Text(
                    "Android foreground microphone service use kore, tai notification visible thakbe.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF7D7D8A)
                )
            }
        }

        PushToTalkButton(
            onResult = { candidates ->
                if (candidates.isNotEmpty()) {
                    lastHeard = candidates.first()
                    transcript = candidates.first()
                }
                processCandidates(candidates)
            },
            onState = { face = it },
            createRecognizer = createRecognizer
        )

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF17171D))) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("3. Tasks", fontWeight = FontWeight.SemiBold)
                if (tasks.isEmpty()) {
                    Text("Kono task nai.", color = Color(0xFFB8B8C4))
                } else {
                    tasks.forEach { task ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = task.done,
                                onCheckedChange = { checked ->
                                    tasks = tasks.map { if (it.id == task.id) it.copy(done = checked) else it }
                                    taskStore.save(tasks)
                                }
                            )
                            Text(
                                task.title,
                                modifier = Modifier.weight(1f),
                                color = if (task.done) Color(0xFF777783) else Color.White
                            )
                        }
                    }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF17171D))) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("4. Watch Lab — face compatibility", fontWeight = FontWeight.SemiBold)
                Text(bleStatus, color = Color(0xFFB8B8C4))
                Button(
                    onClick = {
                        inspection = null
                        bleStatus = "Scanning BLE devices..."
                        ble.scan(
                            onUpdate = {
                                bleDevices = it
                                bleStatus = "Found ${it.size} BLE device(s). Watch 7 / watch-like device select koro."
                            },
                            onError = { bleStatus = it }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Scan Watch BLE") }

                bleDevices.take(12).forEach { row ->
                    OutlinedButton(
                        onClick = {
                            bleStatus = "Inspecting ${row.name}..."
                            ble.inspect(
                                row,
                                onResult = {
                                    inspection = it
                                    bleStatus = it.note
                                },
                                onError = { bleStatus = it }
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("${row.name} • ${row.address}")
                    }
                }

                inspection?.let { info ->
                    Text("Connected: ${info.device.name}", fontWeight = FontWeight.Medium)
                    Text("Services (${info.services.size})", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB8B8C4))
                    info.services.take(18).forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF8E8E99))
                    }
                    Text(
                        "Ei scan RCSP/Jieli protocol identify korar data. Compatible hole direct face push enable kora jabe.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB8B8C4)
                    )
                }
            }
        }

        Spacer(Modifier.height(30.dp))
    }
}

@Composable
fun PushToTalkButton(
    onResult: (List<String>) -> Unit,
    onState: (FaceState) -> Unit,
    createRecognizer: () -> SpeechRecognizer
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var listening by remember { mutableStateOf(false) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    Button(
        onClick = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return@Button
            if (!listening) {
                val sr = createRecognizer()
                recognizer = sr
                sr.setRecognitionListener(object : android.speech.RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { onState(FaceState.LISTENING) }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        listening = false
                        onState(FaceState.THINKING)
                    }
                    override fun onError(error: Int) {
                        listening = false
                        onState(FaceState.CONFUSED)
                    }
                    override fun onResults(results: Bundle?) {
                        listening = false
                        onResult(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty())
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-BD")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                }
                sr.startListening(intent)
                listening = true
                onState(FaceState.LISTENING)
            } else {
                recognizer?.stopListening()
                listening = false
            }
        },
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(18.dp)
    ) {
        Text(if (listening) "Stop & Process" else "Talk to Momo")
    }
}

@Composable
fun CompanionFace(state: FaceState, modifier: Modifier = Modifier) {
    val openness by animateFloatAsState(
        targetValue = when (state) {
            FaceState.HAPPY -> 0.35f
            FaceState.CONFUSED -> 0.65f
            FaceState.LISTENING -> 1f
            FaceState.THINKING -> 0.75f
            FaceState.IDLE -> 0.85f
        },
        label = "eye"
    )

    Canvas(modifier = modifier.background(Color(0xFF15151B), RoundedCornerShape(32.dp))) {
        val eyeW = size.width * 0.22f
        val eyeH = size.height * 0.20f * openness
        val y = size.height * 0.42f

        drawRoundRect(Color(0xFF9DE7FF), Offset(size.width * 0.20f, y), Size(eyeW, eyeH), cornerRadius = CornerRadius(eyeH / 2, eyeH / 2))
        drawRoundRect(Color(0xFF9DE7FF), Offset(size.width * 0.58f, y), Size(eyeW, if (state == FaceState.CONFUSED) eyeH * 0.55f else eyeH), cornerRadius = CornerRadius(eyeH / 2, eyeH / 2))
        if (state == FaceState.HAPPY) {
            drawArc(
                Color(0xFF9DE7FF),
                15f,
                150f,
                false,
                Offset(size.width * 0.38f, size.height * 0.63f),
                Size(size.width * 0.24f, size.height * 0.12f),
                style = Stroke(width = 6f)
            )
        }
    }
}
