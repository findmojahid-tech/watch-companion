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
    var lastHeard by remember { mutableStateOf("") }
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

    fun normalizeCommand(value: String): String =
        value.lowercase()
            .replace("কাজ", " task ")
            .replace("টাস্ক", " task ")
            .replace("যোগ", " add ")
            .replace("করো", " koro ")
            .replace("কর", " koro ")
            .replace("বাকি", " baki ")
            .replace("শেষ", " done ")
            .replace("হয়ে গেছে", " done ")
            .replace("হয়ে গেছে", " done ")
            .replace("পেন্ডিং", " pending ")
            .replace(Regex("[^a-z0-9\\u0980-\\u09FF ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun processCommand(raw: String): Boolean {
        val text = raw.trim()
        if (text.isBlank()) return false
        face = FaceState.THINKING
        val lower = normalizeCommand(text)

        val asksPending =
            lower.contains("ki ki baki") ||
            lower.contains("ki baki") ||
            lower.contains("what is pending") ||
            lower.contains("pending task") ||
            lower == "pending" ||
            lower == "baki" ||
            (lower.contains("baki") && (lower.contains("task") || lower.contains("kaj")))

        val asksReopen =
            lower.contains("abar pending") ||
            lower.contains("reopen") ||
            lower.contains("back to pending")

        val asksDone =
            lower.contains(" done") ||
            lower.endsWith("done") ||
            lower.contains("complete") ||
            lower.contains("finished") ||
            lower.endsWith(" dan") ||
            lower.contains(" mark done")

        val asksAdd =
            lower.contains("add koro") ||
            lower.contains("ad koro") ||
            lower.contains("task add") ||
            lower.contains("add task") ||
            lower.contains("task dao") ||
            lower.contains("task rakho") ||
            lower.contains("list e add") ||
            lower.contains("liste add") ||
            lower.startsWith("add ")

        when {
            asksPending -> {
                val pending = tasks.filterNot { it.done }
                if (pending.isEmpty()) reply("Kono task baki nei.")
                else reply("Baki ache: " + pending.joinToString(", ") { it.title })
                return true
            }

            asksReopen -> {
                val name = text
                    .replace(Regex("(?i)abar\\s+pending"), "")
                    .replace(Regex("(?i)reopen"), "")
                    .replace(Regex("(?i)back\\s+to\\s+pending"), "")
                    .trim(' ', '.', ',')
                val match = tasks.lastOrNull {
                    it.title.contains(name, true) || name.contains(it.title, true)
                }
                if (match == null) reply("Task ta khuje pai ni.", FaceState.CONFUSED)
                else {
                    tasks = tasks.map { if (it.id == match.id) it.copy(done = false) else it }
                    reply("${match.title} abar pending korechi.")
                }
                return true
            }

            asksDone -> {
                val name = text
                    .replace(Regex("(?i)\\bdone\\b"), "")
                    .replace(Regex("(?i)\\bcomplete(d)?\\b"), "")
                    .replace(Regex("(?i)\\bfinished\\b"), "")
                    .replace(Regex("(?i)\\bdan\\b"), "")
                    .replace(Regex("(?i)mark\\s+done"), "")
                    .trim(' ', '.', ',')
                val match = tasks.lastOrNull {
                    it.title.contains(name, true) || name.contains(it.title, true)
                }
                if (match == null) reply("Kon task ta done bujhte parini.", FaceState.CONFUSED)
                else {
                    tasks = tasks.map { if (it.id == match.id) it.copy(done = true) else it }
                    reply("${match.title} done mark korechi.")
                }
                return true
            }

            asksAdd -> {
                val title = text
                    .replace(Regex("(?i)task\\s+add\\s+koro"), "")
                    .replace(Regex("(?i)add\\s+task"), "")
                    .replace(Regex("(?i)add\\s+koro"), "")
                    .replace(Regex("(?i)ad\\s+koro"), "")
                    .replace(Regex("(?i)task\\s+dao"), "")
                    .replace(Regex("(?i)task\\s+rakho"), "")
                    .replace(Regex("(?i)list(e)?\\s+add"), "")
                    .replace(Regex("(?i)^add\\s+"), "")
                    .replace("যোগ", "")
                    .replace("করো", "")
                    .replace("কর", "")
                    .replace("টাস্ক", "")
                    .replace("কাজ", "")
                    .trim(' ', '.', ',')

                if (title.isBlank()) reply("Task-er nam bolo.", FaceState.CONFUSED)
                else {
                    tasks = tasks + TaskItem(System.currentTimeMillis(), title)
                    reply("$title task add korechi.")
                }
                return true
            }

            else -> return false
        }
    }

    fun processSpeechCandidates(candidates: List<String>) {
        if (candidates.isEmpty()) {
            reply("Kichu shunte paini.", FaceState.CONFUSED)
            return
        }

        lastHeard = candidates.first()
        val matched = candidates.firstOrNull { processCommand(it) != false }
        if (matched == null) {
            transcript = "Command ta bujhte parini."
            face = FaceState.CONFUSED
            onSpeak("Command ta bujhte parini.")
        } else {
            lastHeard = matched
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
                if (lastHeard.isNotBlank()) {
                    Text("Heard: $lastHeard", color = Color(0xFF7D7D8A), style = MaterialTheme.typography.bodySmall)
                }
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
            onResult = { candidates ->
                if (candidates.isNotEmpty()) {
                    lastHeard = candidates.first()
                    transcript = candidates.first()
                }
                processSpeechCandidates(candidates)
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
    onResult: (List<String>) -> Unit,
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
                        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        onResult(list)
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
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
