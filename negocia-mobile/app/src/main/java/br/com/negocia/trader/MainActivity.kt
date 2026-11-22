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
import android.media.projection.MediaProjectionManager
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Path
import android.graphics.BlurMaskFilter
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
    companion object { const val NIA_ACCESS_TOKEN = "22AbdvvF00dmCNe_GQuuB-_Gb7YwM2PO" }
    private val speechRequest = 1200
    private val chartRequest = 1202
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
    private lateinit var transcriptPanel: ScrollView
    private var transcriptVisible = false
    private val prototypeCode = NIA_ACCESS_TOKEN
    
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
        root.addView(text("NIA", 16f).apply {
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 0.2f))
        orb = VoiceOrb()
        orb.contentDescription = "Conversar com Leonan"
        orb.setOnClickListener { if (wakeMode) stopWake() else enableWake() }
        root.addView(orb, LinearLayout.LayoutParams(dp(280), dp(280)))
        root.addView(text("Leonan", 25f).apply { setTypeface(null, Typeface.BOLD) },
            LinearLayout.LayoutParams(-1, dp(54)))
        root.addView(text("Sua inteligência por voz", 13f, muted),
            LinearLayout.LayoutParams(-1, dp(34)))
        status = text("Cloudflare AI • Pronto para conversar", 14f, muted)
        val responseScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(status, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
        transcriptPanel = responseScroll
        responseScroll.visibility = View.GONE
        val historyToggle = text("⌄  Ver conversa", 14f, muted).apply {
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener {
                transcriptVisible = !transcriptVisible
                responseScroll.visibility = if (transcriptVisible) View.VISIBLE else View.GONE
                text = if (transcriptVisible) "⌃  Ocultar conversa" else "⌄  Ver conversa"
            }
        }
        root.addView(historyToggle, LinearLayout.LayoutParams(-1, dp(44)))
        root.addView(responseScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        wakeToggle = Button(this).apply {
            text = "Ativar escuta: Léo ou Leonan"
            setOnClickListener { if (wakeMode) stopWake() else enableWake() }
        }
        root.addView(wakeToggle, LinearLayout.LayoutParams(-1, dp(56)))
        val chartButton = Button(this).apply {
            text = "Acompanhar gráfico no Chrome"
            setOnClickListener { startChartSharing() }
        }
        root.addView(chartButton, LinearLayout.LayoutParams(-1, dp(54)))
        val stopChart = Button(this).apply {
            text = "Parar acompanhamento"
            setOnClickListener {
                startService(Intent(this@MainActivity, ChartCaptureService::class.java)
                    .setAction(ChartCaptureService.STOP))
                status.text = "Acompanhamento encerrado."
            }
        }
        root.addView(stopChart, LinearLayout.LayoutParams(-1, dp(48)))
        val update = Button(this).apply { text = "Atualizações" }
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
                    val alternatives = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    val phrase = alternatives.firstOrNull { candidate ->
                        val clean = java.text.Normalizer.normalize(candidate.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                            .replace(Regex("[\\p{Mn}]"), "")
                        Regex("\\b(leonan|leonam|leona|leo)\\b").containsMatchIn(clean)
                    } ?: alternatives.firstOrNull().orEmpty()
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
                            status.text = "Oi Jay, o que você deseja?"
                            speak("Oi Jay, o que você deseja?")
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
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
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
            // Prefer a Brazilian Portuguese voice installed in the selected TTS engine.
            val chosen = available.firstOrNull {
                it.locale.country.equals("BR", ignoreCase = true) &&
                    it.name.contains("faber", ignoreCase = true)
            } ?: available.firstOrNull {
                it.locale.country.equals("BR", ignoreCase = true) &&
                    it.name.contains("razo", ignoreCase = true)
            } ?: available.firstOrNull { it.locale.country.equals("BR", ignoreCase = true) }
                ?: available.firstOrNull()
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
            set(value) { field = value; invalidate() }
        var level = 0f
            set(value) { field = value.coerceIn(0f, 1f); invalidate() }
        private var phase = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val animation = ValueAnimator.ofFloat(0f, 6.283185f).apply {
            duration = 2400L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val base = minOf(width, height) * 0.405f
            val pulse = when (mode) {
                1 -> level * 0.10f
                2 -> 0.025f
                3 -> maxOf(0.10f, level) * 0.14f
                else -> 0.015f
            }
            val r = base * (1f + pulse + 0.013f * kotlin.math.sin(phase).toFloat())
            val outer = RadialGradient(cx, cy, r * 1.18f,
                intArrayOf(0x003B9EFF, 0x124BBDFF, 0x004BBDFF),
                floatArrayOf(0f, 0.77f, 1f), Shader.TileMode.CLAMP)
            paint.shader = outer
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, r * 1.18f, paint)
            paint.shader = null
            val save = canvas.save()
            val sphere = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
            canvas.clipPath(sphere)
            paint.shader = LinearGradient(cx, cy - r, cx, cy + r,
                intArrayOf(0xFFE8FFFF.toInt(), 0xFFD9F7FF.toInt(),
                    0xFF7BD8FF.toInt(), 0xFF0B83F9.toInt(), 0xFF0060E6.toInt()),
                floatArrayOf(0f, 0.28f, 0.50f, 0.77f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, paint)
            paint.shader = null
            for (layer in 0..3) {
                val path = Path()
                val offset = layer * r * 0.15f
                val middle = cy + r * (0.10f + layer * 0.14f) - offset
                val amplitude = r * (0.12f + pulse * 1.6f)
                path.moveTo(cx-r*1.5f, cy+r*1.5f)
                path.lineTo(cx-r*1.5f, middle)
                for (i in 0..80) {
                    val x = cx-r*1.5f + i*r*3f/80f
                    val theta = (x-cx)/r * 2.5f + phase * (if(layer%2==0) 1f else -0.8f) + layer
                    val y = middle + kotlin.math.sin(theta).toFloat()*amplitude +
                        kotlin.math.sin(theta*1.7f+layer).toFloat()*amplitude*0.26f
                    path.lineTo(x,y)
                }
                path.lineTo(cx+r*1.5f,cy+r*1.5f)
                path.close()
                paint.color = when(layer) {
                    0 -> 0x3383EDFF
                    1 -> 0x5573CFFF
                    2 -> 0x7753B8FF
                    else -> 0x99529CFF.toInt()
                }
                canvas.drawPath(path,paint)
            }
            paint.shader = RadialGradient(cx-r*0.32f,cy-r*0.62f,r*1.25f,
                intArrayOf(0x66FFFFFF,0x12FFFFFF,0x00FFFFFF),
                floatArrayOf(0f,0.52f,1f),Shader.TileMode.CLAMP)
            canvas.drawCircle(cx,cy,r,paint)
            paint.shader = null
            canvas.restoreToCount(save)
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

    private fun tryLocalCommand(message: String): Boolean {
        val normalized = java.text.Normalizer.normalize(message.lowercase(Locale.ROOT),
            java.text.Normalizer.Form.NFD).replace(Regex("[\\p{Mn}]"), "")
        if (Regex("\\b(abra|abrir|abre|inicie|iniciar)\\b").containsMatchIn(normalized) &&
            normalized.contains("whatsapp")) {
            val launch = packageManager.getLaunchIntentForPackage("com.whatsapp")
                ?: packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")
            if (launch != null) {
                try {
                    startActivity(launch)
                    status.text = "WhatsApp aberto. Para ler conversas, será necessária uma integração autorizada."
                    speak("Abri o WhatsApp, Jay.")
                } catch (_: Exception) { speak("Não consegui abrir o WhatsApp.") }
            } else speak("Não encontrei o WhatsApp instalado.")
            return true
        }
        return false
    }

    private fun askLeonan(message: String) {
        if (tryLocalCommand(message)) { busy = false; orb.mode = 1; return }
        busy = true
        orb.mode = 2
        handler.removeCallbacks(restart)
        recognizer?.cancel()
        val token = prototypeCode
        val question = message.take(520)
        val history = conversation.lastOrNull()?.let { (q, answer) ->
            "Contexto anterior: " + q.take(65) + " / " + answer.take(95) + "\n"
        }.orEmpty()
        val context = ("Você é Leonan, criado por Jay McKenzie, CEO da GestarLink. Jay é seu criador. Não invente outras origens. Responda em português brasileiro, em até duas frases.\n" +
            history + "Usuário: " + question + "\nLeonan:").take(780)
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

    private fun startChartSharing() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), permissionRequest)
            status.text = "Permita o microfone e toque novamente em Acompanhar gráfico."
            return
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), chartRequest)
    }

    @Deprecated("Compatibility with Android speech prototype")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == chartRequest && resultCode == RESULT_OK && data != null) {
            stopWake()
            val service = Intent(this, ChartCaptureService::class.java)
                .setAction(ChartCaptureService.START)
                .putExtra(ChartCaptureService.RESULT, resultCode)
                .putExtra(ChartCaptureService.DATA, data)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
            status.text = "Compartilhamento autorizado. Abra o Chrome e diga: Léo, capture o gráfico."
            return
        }
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
