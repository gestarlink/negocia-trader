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
import android.graphics.Canvas
import android.graphics.Paint
import android.animation.ValueAnimator
import android.media.audiofx.Visualizer
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
    private lateinit var orb: VoiceOrb
    private var meter: Visualizer? = null
    private val restart = Runnable { startWake() }
    private val endpoint = "https://negocia-trader-ai.gestarlink.workers.dev"
    private lateinit var status: TextView
    private val prototypeCode = "22AbdvvF00dmCNe_GQuuB-_Gb7YwM2PO"
    
    private var tts: TextToSpeech? = null
    private var speechReady = false
    private var voiceNotice = ""
    private var engineName = "inicializando"
    private var requestedEngine: String? = null
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
        initializeVoiceEngine()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(bg)
            setPadding(dp(24), dp(28), dp(24), dp(46))
        }
        root.addView(text("NIA   •   NEGOCIA TRADER", 16f).apply {
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 0.2f))
        orb = VoiceOrb()
        orb.contentDescription = "Conversar com Leonan"
        orb.setOnClickListener { if (wakeMode) stopWake() else enableWake() }
        root.addView(orb, LinearLayout.LayoutParams(dp(225), dp(225)))
        root.addView(text("LEONAN", 28f).apply { setTypeface(null, Typeface.BOLD) },
            LinearLayout.LayoutParams(-1, dp(65)))
        root.addView(text("Seu assistente inteligente", 14f, muted),
            LinearLayout.LayoutParams(-1, dp(42)))
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

    private fun initializeVoiceEngine() {
        tts?.stop()
        tts?.shutdown()
        speechReady = false
        val installed = packageManager.queryIntentServices(
            Intent("android.intent.action.TTS_SERVICE"), 0
        )
        val hayai = installed.firstOrNull {
            it.serviceInfo.packageName.contains("hayai", ignoreCase = true) ||
                it.loadLabel(packageManager).toString().contains("hayai", ignoreCase = true)
        }
        requestedEngine = hayai?.serviceInfo?.packageName
        tts = if (requestedEngine != null)
            TextToSpeech(this, this, requestedEngine)
        else TextToSpeech(this, this)
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
        wakeToggle.text = "Desativar conversa"
        orb.mode = 1
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
        wakeToggle.text = "Ativar conversa por voz"
        orb.mode = 0
    }
    private fun scheduleWake() {
        handler.removeCallbacks(restart)
        if (wakeMode && foreground && !busy && !speaking) handler.postDelayed(restart, 200L)
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
                override fun onRmsChanged(rmsdB: Float) { orb.level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onResults(results: Bundle?) {
                    val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    val normalized = java.text.Normalizer.normalize(phrase.lowercase(Locale.ROOT),
                        java.text.Normalizer.Form.NFD).replace(Regex("[\\p{Mn}]"), "")
                    val match = Regex("\\b(leonan|leonam|leona|leo)\\b").find(normalized)
                    if (match != null) {
                        val command = phrase.substring(match.range.last + 1).trim(' ', ',', ':', '?', '!')
                        if (command.isNotBlank()) {
                            awaitingQuestion = false
                            askLeonan(command)
                        } else {
                            awaitingQuestion = true
                            followUpUntil = System.currentTimeMillis() + 45000L
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
        if (speechReady) onInit(TextToSpeech.SUCCESS)
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
            engineName = tts?.defaultEngine ?: "desconhecido"
            val available = tts?.voices?.filter {
                it.locale.language == "pt" &&
                    !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }.orEmpty()
            val chosen = available.firstOrNull { it.name.contains("faber", ignoreCase = true) }
                ?: available.firstOrNull { it.name.contains("razo", ignoreCase = true) }
                ?: available.firstOrNull { it.locale.country == "BR" }
            chosen?.let { tts?.voice = it }
            voiceNotice = "Motor: $engineName | Voz: ${tts?.voice?.name ?: "não encontrada"}"
            if (::status.isInitialized) status.text = voiceNotice
            tts?.setSpeechRate(0.96f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { handler.post { speaking = true; orb.mode = 3; startMeter() } }
                override fun onDone(utteranceId: String?) { handler.post { stopMeter(); speaking = false; orb.mode = if (wakeMode) 1 else 0; if (awaitingQuestion) followUpUntil = System.currentTimeMillis() + 45000L; scheduleWake() } }
                override fun onError(utteranceId: String?) { handler.post { stopMeter(); speaking = false; orb.mode = if (wakeMode) 1 else 0; scheduleWake() } }
            })
        }
    }

    private fun startMeter() {
        stopMeter()
        try {
            meter = Visualizer(0).apply {
                captureSize = Visualizer.getCaptureSizeRange()[0]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, data: ByteArray?, rate: Int) {
                        if (data != null && data.isNotEmpty()) {
                            val value = data.map { kotlin.math.abs(it.toInt()) }.average().toFloat() / 80f
                            handler.post { if (speaking) orb.level = value.coerceIn(0f, 1f) }
                        }
                    }
                    override fun onFftDataCapture(v: Visualizer?, data: ByteArray?, rate: Int) {}
                }, Visualizer.getMaxCaptureRate() / 2, true, false)
                enabled = true
            }
        } catch (_: Exception) { stopMeter() }
    }
    private fun stopMeter() {
        try { meter?.enabled = false; meter?.release() } catch (_: Exception) {}
        meter = null
    }

    private inner class VoiceOrb : View(this@MainActivity) {
        var mode = 0
        var level = 0f
        private var phase = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val animation = ValueAnimator.ofFloat(0f, 6.28318f).apply {
            duration = 1600L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val base = minOf(width, height) * 0.28f
            val energy = if (mode == 3) maxOf(level, 0.15f) else if (mode == 1) level else 0.06f
            for (ring in 3 downTo 0) {
                val radius = base + ring * dp(12) + energy * dp(24) +
                    kotlin.math.sin(phase + ring).toFloat() * dp(3)
                paint.color = Color.argb(if (ring == 0) 240 else 45, 48, 145, 245)
                paint.style = if (ring == 0) Paint.Style.FILL else Paint.Style.STROKE
                paint.strokeWidth = dp(3).toFloat()
                canvas.drawCircle(cx, cy, radius, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = dp(13).toFloat()
            paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText(when (mode) {
                1 -> "OUVINDO"
                2 -> "PENSANDO"
                3 -> "FALANDO"
                else -> "TOQUE PARA FALAR"
            }, cx, cy + dp(4), paint)
        }
        override fun onDetachedFromWindow() {
            animation.cancel()
            super.onDetachedFromWindow()
        }
    }

    private fun speak(message: String) {
        if (speechReady) {
            val spoken = message.replace(Regex("\\btrader\\b", RegexOption.IGNORE_CASE), "trêider")
            tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "nia_reply")
        }
    }

    private fun askLeonan(message: String) {
        busy = true
        orb.mode = 2
        handler.removeCallbacks(restart)
        recognizer?.cancel()
        val token = prototypeCode
        val context = buildString {
            append("Você é Leonan. Responda em português brasileiro com até 2 frases curtas, salvo se o usuário pedir detalhes. Use o histórico apenas quando relevante.\n")
            for ((question, answer) in conversation) {
                append("Usuário: ").append(question).append("\nLeonan: ").append(answer).append("\n")
            }
            append("Usuário: ").append(message).append("\nLeonan:")
        }
        status.text = "Você: $message\n\nLeonan está pensando..."
        thread {
            val startedAt = System.currentTimeMillis()
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
                val elapsed = (System.currentTimeMillis() - startedAt) / 1000f
                status.text = "Você: $message\n\nLeonan: $reply\n\nResposta da IA: %.1f s".format(Locale.US, elapsed)
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
        stopMeter()
        tts?.shutdown()
        super.onDestroy()
    }
}
