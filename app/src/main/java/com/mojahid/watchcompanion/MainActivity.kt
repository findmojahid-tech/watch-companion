package com.mojahid.watchcompanion

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

private val Ink = Color(0xFF0A0C14)
private val Panel = Color(0xFF181D2A)
private val Cyan = Color(0xFF91E6FF)
private val Muted = Color(0xFFABB9CE)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private lateinit var audio: AudioManager
    private lateinit var tts: TextToSpeech
    private var recognizer: SpeechRecognizer? = null
    private var watchSpeech: WatchSpeechBridge? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        watchSpeech = WatchSpeechBridge(this, audio)
        tts = TextToSpeech(this, this)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Cyan,
                    onPrimary = Ink,
                    background = Ink,
                    onBackground = Color.White,
                    surface = Panel,
                    onSurface = Color.White,
                    surfaceVariant = Panel,
                    onSurfaceVariant = Muted
                )
            ) {
                MomoHome(
                    audio = audio,
                    say = { speak(it) },
                    recognize = { locale, onText, onError -> startRecognizing(locale, onText, onError) },
                    recognizeWatch = { locale, onStatus, onText, onError ->
                        watchSpeech?.start(locale, onStatus, onText, onError)
                            ?: onError("Watch speech service is unavailable.")
                    }
                )
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
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

    private fun speak(message: String) {
        if (::tts.isInitialized) tts.speak(
            message.take(550), TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString()
        )
    }

    private fun startRecognizing(
        locale: String,
        onText: (List<String>) -> Unit,
        onError: (String) -> Unit
    ) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            onError("Microphone permission is missing. Allow it in Android Settings.")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            onError("Speech Recognition isn't available on this phone.")
            return
        }
        recognizer?.destroy()
        recognizer = null

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

            override fun onResults(results: Bundle?) {
                val alternatives = results?.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION
                ).orEmpty()
                if (recognizer === sr) {
                    recognizer = null
                    sr.destroy()
                }
                onText(alternatives)
            }

            override fun onError(error: Int) {
                if (recognizer === sr) {
                    recognizer = null
                    sr.destroy()
                }
                onError(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "I couldn't hear clearly. Speak closer to the mic."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected. Tap and try again."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            "Speech recognition needs network access right now."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                            "Microphone permission is missing."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                            "The microphone is busy. Turn Wake Mode off and retry."
                        else -> "Speech recognition failed (code " + error + "). Try again."
                    }
                )
            }
        })

        val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        runCatching { sr.startListening(speechIntent) }.onFailure {
            if (recognizer === sr) {
                recognizer = null
                sr.destroy()
            }
            onError("Couldn't start the microphone. Try again.")
        }
    }

    override fun onDestroy() {
        watchSpeech?.cancel()
        watchSpeech = null
        recognizer?.destroy()
        recognizer = null
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        // Keep Watch call routed if wake listening still owns communication audio.
        if (!getSharedPreferences("momo_runtime", Context.MODE_PRIVATE)
                .getBoolean("wake_running", false)) {
            runCatching { audio.clearCommunicationDevice() }
            runCatching { audio.mode = AudioManager.MODE_NORMAL }
        }
        super.onDestroy()
    }
}

enum class FaceState { IDLE, LISTENING, THINKING, HAPPY, CONFUSED }

@Composable
private fun SectionTitle(value: String) {
    Text(value, color = Color.White, fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun Hint(value: String) {
    Text(value, color = Muted, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun DarkCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun MomoApp(
    audio: AudioManager,
    say: (String) -> Unit,
    recognize: (String, (List<String>) -> Unit, (String) -> Unit) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("momo_runtime", Context.MODE_PRIVATE) }
    val engine = remember { EnglishTaskEngine(context) }
    val keys = remember { AiKeyStore(context) }
    val diagnostics = remember { BleDiagnostics(context) }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableIntStateOf(0) }
    var tasks by remember { mutableStateOf(engine.tasks()) }
    var expression by remember { mutableStateOf(FaceState.IDLE) }
    var status by remember { mutableStateOf("Hi! I'm Momo. Say hello, or ask me about your tasks.") }
    var heard by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    var history by remember { mutableStateOf<List<ChatTurn>>(emptyList()) }
    var isBusy by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var wakeOn by remember { mutableStateOf(prefs.getBoolean("wake_running", false)) }
    var locale by remember { mutableStateOf(prefs.getString("recognition_locale", "en-IN") ?: "en-IN") }

    var watchAudio by remember { mutableStateOf<List<AudioDeviceInfo>>(emptyList()) }
    var selectedAudio by remember { mutableStateOf<AudioDeviceInfo?>(null) }
    var audioStatus by remember { mutableStateOf("Choose Watch call to route compatible speech audio.") }

    var aiKeyDraft by remember { mutableStateOf("") }
    var hasAiKey by remember { mutableStateOf(!keys.read().isNullOrBlank()) }
    var aiStatus by remember { mutableStateOf(if (hasAiKey) "A key is saved. Tap Test saved key to verify it." else "No Gemini API key yet. Verify & save a key.") }
    var testingAi by remember { mutableStateOf(false) }

    var bleRows by remember { mutableStateOf<List<BleDeviceRow>>(emptyList()) }
    var bleStatus by remember { mutableStateOf("No BLE scan yet.") }
    var bleResult by remember { mutableStateOf<BleInspection?>(null) }
    var faceStatus by remember { mutableStateOf("") }

    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == false) {
            status = "Microphone permission denied. Voice won't work until you allow it."
            expression = FaceState.CONFUSED
        }
    }

    fun refreshAudio() {
        val canConnect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (!canConnect) {
            audioStatus = "Allow Nearby devices permission, then refresh."
            return
        }
        watchAudio = audio.availableCommunicationDevices.filter {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
        }
        selectedAudio = audio.communicationDevice
    }

    fun respond(message: String, understood: Boolean = true) {
        status = message
        expression = if (understood) FaceState.HAPPY else FaceState.CONFUSED
        say(message)
    }

    fun processMessage(input: String) {
        val text = input.trim()
        if (isBusy || text.isBlank()) return
        heard = text
        isBusy = true
        expression = FaceState.THINKING
        status = "Understanding your English..."

        scope.launch {
            val local = engine.tryLocal(text)
            val output: TaskReply = if (local != null) local else {
                val key = keys.read()
                if (key.isNullOrBlank()) {
                    TaskReply("AI isn't connected. Open AI Setup, paste your Gemini key, and press Verify & save.",
                        engine.tasks(), false)
                } else {
                    try {
                        val answer = GeminiClient.chat(text, history, key)
                        history = (history + ChatTurn("user", text) + ChatTurn("model", answer)).takeLast(8)
                        TaskReply(answer, engine.tasks())
                    } catch (error: Exception) {
                        TaskReply("AI error: " + (error.message ?: "Check internet or Gemini quota."),
                            engine.tasks(), false)
                    }
                }
            }
            tasks = output.tasks
            isBusy = false
            respond(output.message, output.understood)
        }
    }

    fun enableWake() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            status = "Allow microphone permission before enabling Wake Mode."
            return
        }
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
        }.onSuccess {
            wakeOn = true
            status = "Wake Mode beta enabled. Try: Hey Momo."
        }.onFailure {
            status = "Couldn't start Wake Mode: " + (it.message ?: "Android restriction.")
            wakeOn = false
        }
    }

    fun disableWake() {
        context.stopService(Intent(context, WakeWordService::class.java))
        wakeOn = false
        status = "Wake Mode stopped. Tap Speak to talk."
    }

    LaunchedEffect(Unit) {
        val all = mutableListOf(Manifest.permission.RECORD_AUDIO)
        all += Manifest.permission.BLUETOOTH_CONNECT
        all += Manifest.permission.BLUETOOTH_SCAN
        if (android.os.Build.VERSION.SDK_INT >= 33) all += Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(all.toTypedArray())
        refreshAudio()
    }

    DisposableEffect(Unit) {
        onDispose { diagnostics.close() }
    }

    Column(Modifier.fillMaxSize().background(Ink)) {
        TabRow(
            selectedTabIndex = tab, containerColor = Ink, contentColor = Cyan
        ) {
            listOf("Momo", "Watch", "AI Setup").forEachIndexed { index, name ->
                Tab(selected = tab == index, onClick = {
                    tab = index
                    if (index == 1) refreshAudio()
                    tasks = engine.tasks()
                }, text = { Text(name, color = if (tab == index) Cyan else Muted) })
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (tab == 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CompanionFace(expression, Modifier.size(132.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("MOMO", color = Color.White, style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Black)
                        Text(status, color = Muted, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                DarkCard {
                    SectionTitle("English voice")
                    Hint("Speech language: " + if (locale == "en-IN") "English (India)" else "English (US)")
                    Button(
                        enabled = !isBusy && !wakeOn && !listening,
                        onClick = {
                            listening = true
                            expression = FaceState.LISTENING
                            status = "Listening... speak in English."
                            recognize(locale, { alternatives ->
                                listening = false
                                if (alternatives.isEmpty()) {
                                    respond("I couldn't understand what I heard. Please try again.", false)
                                } else {
                                    // Prefer a recognized alternative that can be handled locally.
                                    val best = alternatives.firstOrNull { engine.tryLocalDryRun(it) }
                                        ?: alternatives.first()
                                    processMessage(best)
                                }
                            }, { reason ->
                                listening = false
                                respond(reason, false)
                            })
                        },
                        modifier = Modifier.fillMaxWidth().height(58.dp),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(if (listening) "Listening..." else "Tap to Speak", fontWeight = FontWeight.Bold)
                    }
                    if (wakeOn) Hint("Wake Mode is running. Turn it off before using Tap to Speak.")
                    if (heard.isNotBlank()) {
                        Text("Heard: " + heard, color = Cyan, style = MaterialTheme.typography.bodyMedium)
                    }
                    Hint("Try: Add a task to finish my portfolio. Or: What tasks are left?")
                }

                DarkCard {
                    SectionTitle("Type to test English / AI")
                    OutlinedTextField(
                        value = typed, onValueChange = { typed = it },
                        label = { Text("Ask Momo anything") },
                        modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 3
                    )
                    Button(
                        onClick = { val message = typed; typed = ""; processMessage(message) },
                        enabled = typed.isNotBlank() && !isBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isBusy) "Thinking..." else "Send message")
                    }
                }

                DarkCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Your tasks", color = Color.White, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { tasks = engine.tasks() }) { Text("Refresh") }
                    }
                    val pending = tasks.count { !it.done }
                    Hint(pending.toString() + " pending • " + tasks.count { it.done } + " completed")
                    if (tasks.isEmpty()) {
                        Hint("No tasks saved yet. Try saying: Add a task to edit my video.")
                    }
                    tasks.forEach { task ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = task.done, onCheckedChange = { checked ->
                                val updated = tasks.map {
                                    if (it.id == task.id) it.copy(done = checked) else it
                                }
                                if (TaskStore(context).save(updated)) tasks = updated
                                else status = "Couldn't save your task. Try again."
                            })
                            Text(task.title, modifier = Modifier.weight(1f),
                                color = if (task.done) Muted else Color.White)
                        }
                    }
                }

                DarkCard {
                    SectionTitle("Experimental wake listening")
                    Hint("Say 'Hey Momo' to get an English voice response. The app keeps a visible " +
                        "microphone notification and can use battery. Android may stop continuous speech listening.")
                    Button(
                        onClick = { if (wakeOn) disableWake() else enableWake() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (wakeOn) "Stop Wake Mode" else "Enable Wake Mode (beta)")
                    }
                }
            }

            if (tab == 1) {
                SectionTitle("Watch connection")
                DarkCard {
                    Text("Bluetooth call audio", color = Color.White, fontWeight = FontWeight.Bold)
                    Hint(audioStatus)
                    if (watchAudio.isEmpty()) Hint("No watch call audio found. Pair 'Watch call' in Android Bluetooth settings.")
                    watchAudio.forEach { device ->
                        Button(
                            onClick = {
                                runCatching {
                                    audio.mode = AudioManager.MODE_IN_COMMUNICATION
                                    val ok = audio.setCommunicationDevice(device)
                                    if (ok) {
                                        selectedAudio = device
                                        val name = device.productName?.toString().orEmpty()
                                        prefs.edit().putString("preferred_audio_name", name).apply()
                                        audioStatus = name + " selected for compatible communication audio."
                                    } else audioStatus = "Android couldn't route audio to this device."
                                }.onFailure {
                                    audioStatus = "Audio route error. Check Bluetooth call audio permissions."
                                }
                            }, modifier = Modifier.fillMaxWidth()
                        ) {
                            val name = device.productName?.toString() ?: "Bluetooth audio"
                            Text(name + if (selectedAudio?.id == device.id) " ✓" else "")
                        }
                    }
                    OutlinedButton(onClick = { refreshAudio() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Refresh call audio")
                    }
                    OutlinedButton(onClick = { say("Hello! This is Momo speaking in English.") },
                        modifier = Modifier.fillMaxWidth()) {
                        Text("Test watch speaker")
                    }
                    Hint("Important: Android may still use your phone microphone for speech recognition. " +
                        "The Heard line on the Momo tab shows what recognition actually captured.")
                }

                DarkCard {
                    SectionTitle("Momo face for the watch")
                    Hint("Step 1: Save the original 320 x 390 Momo photo face. " +
                        "Step 2: Open MActivePro, connect Watch 7, then choose the Custom/Album " +
                        "watch face and import the saved image. UI names may vary by watch.")
                    Button(onClick = {
                        faceStatus = if (MomoFaceExport.saveToGallery(context))
                            "Saved to Pictures/Momo. Now use MActivePro > Watch faces > Custom/Album."
                        else "Couldn't save the PNG. Check storage and try again."
                    }, modifier = Modifier.fillMaxWidth()) { Text("1. Save Momo face for Watch 7") }
                    OutlinedButton(onClick = {
                        runCatching {
                            val appIntent = context.packageManager
                                .getLaunchIntentForPackage("com.njj.mactivepro")
                            context.startActivity(appIntent ?: Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://play.google.com/store/apps/details?id=com.njj.mactivepro")
                            ))
                        }.onFailure {
                            faceStatus = "Open MActivePro manually, then look for Custom watch faces."
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("2. Open MActivePro watch-face app") }
                    Hint("This makes a real STATIC face visible on supported W17 watches. " +
                        "Live expressions and standalone AI require unverified firmware capabilities.")
                    if (faceStatus.isNotBlank()) Hint(faceStatus)
                }
                DarkCard {
                    SectionTitle("BLE Watch Lab")
                    Hint("Read-only protocol audit: reads standard manufacturer / firmware details " +
                        "and characteristic capabilities. It never sends vendor commands or updates firmware.")
                    Hint("The Goodix A6ED OTA/DFU service is NOT a watch-face upload interface. " +
                        "Never use it for experimenting with uploads.")
                    Text(bleStatus, color = Muted, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = {
                        bleResult = null
                        bleRows = emptyList()
                        diagnostics.scan(onUpdate = { bleRows = it }, onStatus = { bleStatus = it })
                    }, modifier = Modifier.fillMaxWidth()) { Text("Scan BLE devices") }
                    bleRows.take(14).forEach { row ->
                        OutlinedButton(onClick = {
                            bleStatus = "Inspecting " + row.name + "..."
                            diagnostics.inspect(row,
                                onResult = { result ->
                                    bleResult = result
                                    bleStatus = result.note
                                }, onStatus = { bleStatus = it })
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(row.name + " (" + row.address.takeLast(5) + ")")
                        }
                    }
                    bleResult?.let { result ->
                        Text("Services from: " + result.device.name, color = Color.White,
                            fontWeight = FontWeight.Bold)
                        val details = result.services.filter { line ->
                            line.contains("Firmware revision:") ||
                                line.contains("Hardware revision:") ||
                                line.contains("Software revision:") ||
                                line.contains("Manufacturer:") ||
                                line.contains("Device name:") ||
                                line.contains("Battery level:")
                        }
                        if (details.isNotEmpty()) {
                            Text("Device identity (read-only)", color = Cyan, fontWeight = FontWeight.Bold)
                            details.forEach { line ->
                                Text(line, color = Color.White, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text("GATT services and properties", color = Cyan, fontWeight = FontWeight.SemiBold)
                        result.services.filterNot { details.contains(it) }.take(48).forEach { line ->
                            Text(line, color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Hint("A custom WRITE characteristic alone does NOT prove watch-face support. " +
                            "Use Copy full BLE report for all services and read results.")
                        OutlinedButton(onClick = {
                            val report = "Device: " + result.device.name + "\n" +
                                result.note + "\n" + result.services.joinToString("\n")
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Momo BLE report", report))
                            bleStatus = "BLE report copied. Paste it into ChatGPT for analysis."
                        }, modifier = Modifier.fillMaxWidth()) { Text("Copy full BLE report") }
                    }
                }

            }

            if (tab == 2) {
                SectionTitle("Free English AI — ask anything")
                DarkCard {
                    Text("Google Gemini 3.5 Flash-Lite", color = Color.White, fontWeight = FontWeight.Bold)
                    Hint("Answers English questions, explanations, creative prompts, and tasks. " +
                        "Limited free-tier Gemini access requires internet and a verified personal key.")
                    Text(if (hasAiKey) "A Gemini key is saved" else "No saved Gemini key",
                        color = if (hasAiKey) Cyan else Muted)
                    OutlinedTextField(
                        value = aiKeyDraft, onValueChange = { aiKeyDraft = it.trim().take(250) },
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("Paste Gemini API key here") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            val candidate = aiKeyDraft.trim()
                            testingAi = true
                            aiStatus = "Checking free Gemini access with Google..."
                            scope.launch {
                                aiStatus = try {
                                    val check = GeminiClient.verifyKey(candidate)
                                    if (keys.save(candidate)) {
                                        aiKeyDraft = ""
                                        hasAiKey = true
                                        "Verified using " + check.model + "! " + check.testReply
                                    } else {
                                        "Google verified the key, but Android couldn't save it."
                                    }
                                } catch (error: Exception) {
                                    "Key NOT saved: " + (error.message ?: "Unknown verification error.")
                                }
                                testingAi = false
                            }
                        },
                        enabled = aiKeyDraft.isNotBlank() && !testingAi,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (testingAi) "Verifying..." else "Verify & save new API key") }
                    OutlinedButton(
                        onClick = {
                            val stored = keys.read()
                            if (stored.isNullOrBlank()) {
                                aiStatus = "No saved key found. Paste a new key above."
                            } else {
                                testingAi = true
                                aiStatus = "Testing saved key with Google..."
                                scope.launch {
                                    aiStatus = try {
                                        val check = GeminiClient.verifyKey(stored)
                                        "Gemini connected: " + check.model + "! " + check.testReply
                                    } catch (error: Exception) {
                                        "Saved key test failed: " + (error.message ?: "Unknown error.")
                                    }
                                    testingAi = false
                                }
                            }
                        },
                        enabled = !testingAi,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (testingAi) "Testing..." else "Test saved key") }
                    OutlinedButton(onClick = {
                        keys.clear()
                        hasAiKey = false
                        aiKeyDraft = ""
                        aiStatus = "API key removed from this phone."
                    }, modifier = Modifier.fillMaxWidth()) { Text("Remove API key") }
                    Text(aiStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
                    Hint("Privacy: AI requests send recognized text and relevant task titles to Google. " +
                        "Free-tier input/output may be used to improve Google's products. " +
                        "Your microphone audio is not sent directly by this Gemini integration, " +
                        "but Android speech recognition may use its own cloud service.")
                }

                DarkCard {
                    SectionTitle("English speech accent")
                    Hint("English (India) often works better for South Asian English; " +
                        "try English (US) if it mishears you.")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = {
                            locale = "en-IN"
                            prefs.edit().putString("recognition_locale", locale).apply()
                        }, modifier = Modifier.weight(1f)) { Text("India" + if (locale == "en-IN") " ✓" else "") }
                        Button(onClick = {
                            locale = "en-US"
                            prefs.edit().putString("recognition_locale", locale).apply()
                        }, modifier = Modifier.weight(1f)) { Text("US" + if (locale == "en-US") " ✓" else "") }
                    }
                }

                DarkCard {
                    SectionTitle("How to get a free key")
                    Hint("Get a free-tier eligible Google AI Studio key, paste it above, and press " +
                        "Verify & save. Don't share your API key in screenshots or ChatGPT.")
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://aistudio.google.com/apikey")))
                    }, modifier = Modifier.fillMaxWidth()) { Text("Open Google AI Studio") }
                }
            }

            Spacer(Modifier.height(26.dp))
        }
    }
}

@Composable
fun CompanionFace(state: FaceState, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "momo-blink")
    val blink by transition.animateFloat(
        initialValue = 1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 5000
                1f at 0
                1f at 3850
                0.10f at 3980
                1f at 4160
                1f at 5000
            },
            repeatMode = RepeatMode.Restart
        ), label = "blink"
    )
    val openness by animateFloatAsState(
        targetValue = when (state) {
            FaceState.IDLE -> 0.88f
            FaceState.LISTENING -> 1f
            FaceState.THINKING -> 0.55f
            FaceState.HAPPY -> 0.40f
            FaceState.CONFUSED -> 0.75f
        }, label = "momo-expression"
    )

    Canvas(modifier = modifier.background(Color(0xFF111827), RoundedCornerShape(35.dp))) {
        val eyeH = size.height * 0.23f * blink * openness
        val eyeW = size.width * 0.23f
        val y = size.height * 0.38f
        drawRoundRect(Cyan, Offset(size.width * 0.18f, y),
            Size(eyeW, eyeH), CornerRadius(eyeW * 0.48f, eyeW * 0.48f))
        drawRoundRect(Cyan, Offset(size.width * 0.59f, y),
            Size(eyeW, if (state == FaceState.CONFUSED) eyeH * 0.56f else eyeH),
            CornerRadius(eyeW * 0.48f, eyeW * 0.48f))

        if (state == FaceState.HAPPY || state == FaceState.LISTENING) {
            drawArc(
                color = Cyan, startAngle = 18f, sweepAngle = 145f, useCenter = false,
                topLeft = Offset(size.width * 0.39f, size.height * 0.65f),
                size = Size(size.width * 0.22f, size.height * 0.12f),
                style = Stroke(width = size.width * 0.032f)
            )
        }
    }
}
