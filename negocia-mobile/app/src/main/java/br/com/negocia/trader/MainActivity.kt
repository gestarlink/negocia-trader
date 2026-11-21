package br.com.negocia.trader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener
import android.speech.tts.UtteranceProgressListener
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import org.json.JSONObject
import kotlin.concurrent.thread

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private val speechRequest = 1200
    private val permissionRequest = 3001
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var wakeMode = false
    private var foreground = false
    private var busy = false
    private var speaking = false
    private var awaitingQuestion = false
    private val conversation = ArrayDeque<Pair<String, String>>()
    private var followUpUntil = 0L
    private lateinit var wakeToggle: Button
    private val restart = Runnable { startWake() }
    private val endpoint = "https://negocia-trader-ai.gestarlink.workers.dev"
    private lateinit var status: TextView
    private val prototypeCode = "22AbdvvF00dmCNe_GQuuB-_Gb7YwM2PO"
    
    private var tts: TextToSpeech? = null
    private var speechReady = false
    private val bg = Color.rgb(16, 24, 39)
    private val blue = Color.rgb(45, 119, 235)
    private val muted = Color.rgb(169, 187, 209)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun shape(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    private fun text(value: String, size: Float, color: Int = Color.WHITE): TextView =
        TextView(this).apply { text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        tts = TextToSpeech(this, this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(bg)
            setPadding(dp(24), dp(28), dp(24), dp(46))
        }
        root.addView(text("NIA   •   NEGOCIA TRADER", 16f).apply {
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 0.35f))
        val mic = Button(this).apply {
            text = "🎙"
            textSize = 50f
            setTextColor(Color.WHITE)
            background = shape(blue, 100)
            contentDescription = "Falar com Leonan"
            setOnClickListener { listen() }
        }
        root.addView(mic, LinearLayout.LayoutParams(dp(164), dp(164)))
        root.addView(text("Olá, sou Leonan", 24f).apply {
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, dp(66)))
        root.addView(text("Toque no microfone para conversar comigo", 14f, muted),
            LinearLayout.LayoutParams(-1, dp(44)))
        status = text("Cloudflare AI • Pronto para conversar", 14f, muted)
        val responseScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(status, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
        root.addView(responseScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        wakeToggle = Button(this).apply {
            text = "Ativar escuta: Léo ou Leonan"
            setOnClickListener { if (wakeMode) stopWake() else enableWake() }
        }
        root.addView(wakeToggle, LinearLayout.LayoutParams(-1, dp(56)))
        val update = Button(this).apply { text = "Verificar atualizações" }
        val updater = UpdateHelper(this)
        update.setOnClickListener {
            updater.check({ version, install ->
                update.text = "Atualizar para versão $version"
                update.setOnClickListener { install() }
            }, true)
        }
        root.addView(update, LinearLayout.LayoutParams(-1, dp(50)))
        setContentView(root)
        updater.check({ version, install ->
            update.text = "Atualizar para versão $version"
            update.setOnClickListener { install() }
        })
    }

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Fale com Leonan")
        }
        try { startActivityForResult(intent, speechRequest) }
        catch (_: ActivityNotFoundException) { status.text = "Reconhecimento de voz indisponível." }
    }


    private fun enableWake() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), permissionRequest)
            return
        }
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            status.text = "Reconhecimento local indisponível neste aparelho. Use o microfone."
            return
        }
        wakeMode = true
        wakeToggle.text = "Desativar escuta"
        status.text = "Aguardando Léo ou Leonan..."
        startWake()
    }
    private fun stopWake() {
        wakeMode = false
        awaitingQuestion = false
        followUpUntil = 0L
        handler.removeCallbacks(restart)
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        wakeToggle.text = "Ativar escuta: Léo ou Leonan"
    }
    private fun scheduleWake() {
        handler.removeCallbacks(restart)
        if (wakeMode && foreground && !busy && !speaking) handler.postDelayed(restart, 900L)
    }
    private fun startWake() {
        if (!wakeMode || !foreground || busy || speaking) return
        recognizer?.destroy()
        recognizer = null
        try {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onResults(results: Bundle?) {
                    val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    val normalized = java.text.Normalizer.normalize(phrase.lowercase(Locale.ROOT),
                        java.text.Normalizer.Form.NFD).replace(Regex("[\\p{Mn}]"), "")
                    val match = Regex("\\b(leonan|leo)\\b").find(normalized)
                    if (match != null) {
                        val command = phrase.substring(match.range.last + 1).trim(' ', ',', ':', '?', '!')
                        if (command.isNotBlank()) {
                            awaitingQuestion = false
                            askLeonan(command)
                        } else {
                            awaitingQuestion = true
                            status.text = "Estou ouvindo. Faça sua pergunta."
                        }
                    } else if (awaitingQuestion && System.currentTimeMillis() < followUpUntil && phrase.isNotBlank()) {
                        awaitingQuestion = false
                        askLeonan(phrase)
                    }
                    if (System.currentTimeMillis() >= followUpUntil) awaitingQuestion = false
                    scheduleWake()
                }
                override fun onError(error: Int) { scheduleWake() }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            recognizer?.startListening(intent)
        } catch (_: Exception) {
            stopWake()
            status.text = "Não foi possível iniciar a escuta local."
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequest && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) enableWake()
    }
    override fun onResume() {
        super.onResume()
        foreground = true
        if (wakeMode) scheduleWake()
    }
    override fun onPause() {
        foreground = false
        handler.removeCallbacks(restart)
        recognizer?.cancel()
        super.onPause()
    }
    override fun onInit(result: Int) {
        speechReady = result == TextToSpeech.SUCCESS
        if (speechReady) {
            tts?.language = Locale.forLanguageTag("pt-BR")
            tts?.voices?.filter { it.locale.language == "pt" && it.locale.country == "BR" &&
                !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                ?.maxByOrNull { it.quality }?.let { tts?.voice = it }
            tts?.setSpeechRate(0.96f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { speaking = true }
                override fun onDone(utteranceId: String?) { handler.post { speaking = false; scheduleWake() } }
                override fun onError(utteranceId: String?) { handler.post { speaking = false; scheduleWake() } }
            })
        }
    }

    private fun speak(message: String) {
        if (speechReady) tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "nia_reply")
    }

    private fun askLeonan(message: String) {
        busy = true
        handler.removeCallbacks(restart)
        recognizer?.cancel()
        val token = prototypeCode
        val context = buildString {
            append("Você é Leonan, assistente de voz em português brasileiro. Responda de forma natural e breve. Considere o histórico recente, quando relevante.\n")
            for ((question, answer) in conversation) {
                append("Usuário: ").append(question).append("\nLeonan: ").append(answer).append("\n")
            }
            append("Usuário: ").append(message).append("\nLeonan:")
        }
        status.text = "Você: $message\n\nLeonan está pensando..."
        thread {
            var conn: HttpURLConnection? = null
            val reply = try {
                conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 30000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Authorization", "Bearer $token")
                }
                conn.outputStream.use {
                    it.write(JSONObject().put("message", context).toString().toByteArray(Charsets.UTF_8))
                }
                when (conn.responseCode) {
                    200 -> JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        .optString("reply", "Não consegui responder.")
                    401 -> "Código de acesso inválido."
                    else -> "Serviço indisponível: ${conn.responseCode}."
                }
            } catch (_: Exception) {
                "Não consegui conectar à IA. Verifique sua internet."
            } finally { conn?.disconnect() }
            runOnUiThread {
                status.text = "Você: $message\n\nLeonan: $reply"
                busy = false
                if (reply.isNotBlank()) {
                    conversation.addLast(message to reply)
                    while (conversation.size > 5) conversation.removeFirst()
                }
                if (wakeMode) {
                    awaitingQuestion = true
                    followUpUntil = System.currentTimeMillis() + 45000L
                }
                speak(reply)
                if (!speechReady) scheduleWake()
            }
        }
    }

    @Deprecated("Compatibility with Android speech prototype")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == speechRequest && resultCode == RESULT_OK) {
            val heard = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
            askLeonan(heard)
        }
    }

    override fun onDestroy() {
        wakeMode = false
        handler.removeCallbacks(restart)
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
