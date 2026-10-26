package com.mojahid.watchcompanion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("en", "BD")
        }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }
}

data class TaskItem(val id: Long, val title: String, val done: Boolean = false)
enum class FaceState { IDLE, LISTENING, THINKING, HAPPY, CONFUSED }

@Composable
fun CompanionApp(
    audioManager: AudioManager,
    onSpeak: (String) -> Unit,
    createRecognizer: () -> SpeechRecognizer
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var tasks by remember { mutableStateOf(listOf<TaskItem>()) }
    var face by remember { mutableStateOf(FaceState.IDLE) }
    var transcript by remember { mutableStateOf("Ready") }
    var watchDevices by remember { mutableStateOf<List<AudioDeviceInfo>>(emptyList()) }
    var selectedDevice by remember { mutableStateOf<AudioDeviceInfo?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    fun refreshDevices() {
        watchDevices = audioManager.availableCommunicationDevices.filter {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
        }
        selectedDevice = audioManager.communicationDevice
    }

    LaunchedEffect(Unit) {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= 31) perms += Manifest.permission.BLUETOOTH_CONNECT
        permissionLauncher.launch(perms.toTypedArray())
        refreshDevices()
    }

    fun reply(text: String, state: FaceState = FaceState.HAPPY) {
        transcript = text
        face = state
        onSpeak(text)
    }

    fun processCommand(raw: String) {
        val text = raw.trim()
        if (text.isBlank()) return
        face = FaceState.THINKING
        val lower = text.lowercase()

        when {
            lower.contains("ki ki baki") || lower.contains("what is pending") || lower == "pending" -> {
                val pending = tasks.filterNot { it.done }
                if (pending.isEmpty()) reply("Kono task baki nei.")
                else reply("Baki ache: " + pending.joinToString(", ") { it.title })
            }
            lower.contains("abar pending") || lower.contains("reopen") -> {
                val name = text.substringBefore("abar pending", text).substringBefore("reopen", text).trim()
                val match = tasks.lastOrNull { it.title.contains(name, true) || name.contains(it.title, true) }
                if (match == null) reply("Task ta khuje pai ni.", FaceState.CONFUSED)
                else {
                    tasks = tasks.map { if (it.id == match.id) it.copy(done = false) else it }
                    reply("${match.title} abar pending korechi.")
                }
            }
            lower.endsWith("done") || lower.contains(" done") -> {
                val name = text.replace(Regex("(?i)\\bdone\\b"), "").trim()
                val match = tasks.lastOrNull { it.title.contains(name, true) || name.contains(it.title, true) }
                if (match == null) reply("Kon task ta done bujhte parini.", FaceState.CONFUSED)
                else {
                    tasks = tasks.map { if (it.id == match.id) it.copy(done = true) else it }
                    reply("${match.title} done mark korechi.")
                }
            }
            lower.contains("add koro") || lower.contains("task add") || lower.contains("add task") -> {
                val title = text
                    .replace(Regex("(?i)task add koro"), "")
                    .replace(Regex("(?i)add task"), "")
                    .replace(Regex("(?i)add koro"), "")
                    .trim(' ', '.', ',')
                if (title.isBlank()) reply("Task-er nam bolo.", FaceState.CONFUSED)
                else {
                    tasks = tasks + TaskItem(System.currentTimeMillis(), title)
                    reply("$title task add korechi.")
                }
            }
            else -> reply("Command ta bujhte parini.", FaceState.CONFUSED)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0B0B0F)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompanionFace(face, Modifier.size(120.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Watch Companion", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(transcript, color = Color(0xFFB8B8C4))
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF17171D))) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Watch audio", fontWeight = FontWeight.SemiBold)
                if (watchDevices.isEmpty()) {
                    Text("No Bluetooth call audio device detected.", color = Color(0xFFB8B8C4))
                } else {
                    watchDevices.forEach { device ->
                        Button(
                            onClick = {
                                val allowed = android.os.Build.VERSION.SDK_INT < 31 ||
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
                OutlinedButton(onClick = { refreshDevices() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Refresh devices")
                }
                OutlinedButton(
                    onClick = { onSpeak("Watch speaker test. Shunte paccho?") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Test watch speaker")
                }
            }
        }

        PushToTalkButton(
            onResult = {
                transcript = it
                processCommand(it)
            },
            onState = { face = it },
            createRecognizer = createRecognizer
        )

        Text("Tasks", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            items(tasks, key = { it.id }) { task ->
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF17171D))) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = task.done,
                            onCheckedChange = { checked ->
                                tasks = tasks.map { if (it.id == task.id) it.copy(done = checked) else it }
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
}

@Composable
fun PushToTalkButton(
    onResult: (String) -> Unit,
    onState: (FaceState) -> Unit,
    createRecognizer: () -> SpeechRecognizer
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var listening by remember { mutableStateOf(false) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    Button(
        onClick = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return@Button
            }
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
                        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        onResult(list?.firstOrNull().orEmpty())
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-BD")
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
        Text(if (listening) "Stop & Process" else "Talk to Companion")
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

        drawRoundRect(
            Color(0xFF9DE7FF),
            Offset(size.width * 0.20f, y),
            Size(eyeW, eyeH),
            cornerRadius = CornerRadius(eyeH / 2, eyeH / 2)
        )
        drawRoundRect(
            Color(0xFF9DE7FF),
            Offset(size.width * 0.58f, y),
            Size(eyeW, if (state == FaceState.CONFUSED) eyeH * 0.55f else eyeH),
            cornerRadius = CornerRadius(eyeH / 2, eyeH / 2)
        )
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
