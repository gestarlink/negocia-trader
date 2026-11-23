package br.com.negocia.trader

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import android.speech.*
import android.speech.tts.TextToSpeech
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale
import kotlin.concurrent.thread
import org.json.JSONObject

/** User-activated foreground microphone service. No recording is stored. */
class VoiceAssistantService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val START = "br.com.negocia.trader.VOICE_START"
        const val STOP = "br.com.negocia.trader.VOICE_STOP"
        const val CHANNEL = "nia_voice"
        const val STATE = "br.com.negocia.trader.VOICE_STATE"
        var enabled = false
            private set
    }
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var speaking = false
    private var listening = false
    private var processing = false
    private var waiting = false
    private var followUpDeadline = 0L
    private var errors = 0
    private var lastSearchQuery: String? = null
    private var lastConversation: String? = null
    private val loop = Runnable { listen() }
    private fun normalize(value: String) = Normalizer.normalize(value.lowercase(Locale.ROOT),
        Normalizer.Form.NFD).replace(Regex("[\\p{Mn}]"), "")
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, "Leonan • escuta ativa", NotificationManager.IMPORTANCE_LOW))
        val engines = packageManager.queryIntentServices(Intent("android.intent.action.TTS_SERVICE"), 0)
        val hayai = engines.firstOrNull { it.serviceInfo.packageName.contains("hayai", true) }
        tts = if (hayai != null) TextToSpeech(this, this, hayai.serviceInfo.packageName)
              else TextToSpeech(this, this)
    }
    private fun notification(message: String): Notification {
        val stop = PendingIntent.getService(this, 12,
            Intent(this, VoiceAssistantService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 13,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Leonan • escuta em segundo plano")
            .setContentText(message).setContentIntent(open).setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "Desativar escuta", stop).build()
    }
    private fun update(message: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(111, notification(message))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == START) {
            if (!enabled) {
                try {
                    if (Build.VERSION.SDK_INT >= 29)
                        startForeground(111, notification("Aguardando Léo ou Leonan"),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                    else startForeground(111, notification("Aguardando Léo ou Leonan"))
                    enabled = true
                    broadcastState()
                    listen()
                } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
            }
        }
        return START_STICKY
    }
    private fun broadcastState() {
        sendBroadcast(Intent(STATE).setPackage(packageName).putExtra("enabled", enabled))
    }
    private fun retry(delay: Long = 850L) {
        main.removeCallbacks(loop)
        if (enabled && !speaking && !processing) main.postDelayed(loop, delay)
    }
    private fun listen() {
        if (!enabled || speaking || processing || listening) return
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            update("Reconhecimento local indisponível")
            return
        }
        try {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                recognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() { errors = 0 }
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                    override fun onResults(results: Bundle?) {
                        listening = false
                        errors = 0
                        val alternatives = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        handle(alternatives)
                        retry()
                    }
                    override fun onError(error: Int) {
                        listening = false
                        errors = (errors + 1).coerceAtMost(5)
                        retry(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 2500L
                              else (1000L * errors).coerceAtMost(5000L))
                    }
                })
            }
            listening = true
            recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            })
        } catch (_: Exception) { listening = false; retry(2500L) }
    }
    private fun handle(alternatives: List<String>) {
        val regex = Regex("\\b(leonan|leonam|leona|leo)\\b")
        val phrase = alternatives.firstOrNull { regex.containsMatchIn(normalize(it)) }
            ?: alternatives.firstOrNull().orEmpty()
        val match = regex.find(normalize(phrase))
        if (match != null) {
            val command = phrase.substring(match.range.last + 1).trim(' ', ',', ':', '?', '!')
            if (command.isBlank()) {
                waiting = true
                followUpDeadline = System.currentTimeMillis() + 20000L
                say("Oi Jay, o que você deseja?")
            } else { waiting = false; execute(command) }
        } else if (waiting && System.currentTimeMillis() < followUpDeadline && phrase.isNotBlank()) {
            waiting = false
            execute(phrase)
        }
    }
    private fun say(value: String) {
        val spoken = value.replace(Regex("\\bJay\\b", RegexOption.IGNORE_CASE), "Djei")
            .replace(Regex("\\btrader\\b", RegexOption.IGNORE_CASE), "trêider")
        if (tts == null) { retry(); return }
        speaking = true
        recognizer?.cancel()
        listening = false
        tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "nia_voice")
    }
    /** Wikipedia's public API provides attributed factual snippets, not Google search results. */
    private fun fetchSearchSummary(query: String) {
        processing = true
        recognizer?.cancel()
        listening = false
        update("Pesquisa aberta. Consultando fonte pública para leitura...")
        thread {
            var conn: HttpURLConnection? = null
            val summary = try {
                val url = "https://pt.wikipedia.org/w/api.php?action=query&generator=search" +
                    "&gsrsearch=" + java.net.URLEncoder.encode(query, "UTF-8") +
                    "&gsrlimit=2&prop=extracts&exintro=1&explaintext=1&exchars=700&format=json"
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 12000
                    setRequestProperty("User-Agent", "JAY-Leonan/1.1 (Android voice assistant; informational lookup)")
                }
                if (conn!!.responseCode != 200) null else {
                    val pages = JSONObject(conn!!.inputStream.bufferedReader().use { it.readText() })
                        .optJSONObject("query")?.optJSONObject("pages")
                    val keys = pages?.keys()
                    val found = mutableListOf<String>()
                    while (keys != null && keys.hasNext() && found.size < 2) {
                        val page = pages?.optJSONObject(keys.next()) ?: continue
                        val title = page.optString("title")
                        val excerpt = page.optString("extract").replace(Regex("\\s+"), " ").trim()
                        if (excerpt.isNotBlank()) found.add(title + ": " + excerpt.take(440))
                    }
                    found.takeIf { it.isNotEmpty() }?.joinToString(". ")
                }
            } catch (_: Exception) { null }
            finally { conn?.disconnect() }
            main.post {
                processing = false
                if (summary != null) {
                    lastConversation = "Pesquisa sobre " + query.take(70) + ": " + summary.take(320)
                    say("Abri o Google. Também encontrei uma referência na Wikipédia: " +
                        summary.take(520) + ". Quer que eu explique melhor?")
                } else {
                    say("Abri a pesquisa no Google sobre " + query.take(70) +
                        ". Ainda não consegui obter um resumo verificável para ler. Você pode ver os resultados no navegador.")
                }
                update("Aguardando Léo ou Leonan")
            }
        }
    }

    private fun execute(command: String) {
        val normalized = normalize(command)
        if (Regex("\\b(abra|abrir|abre|inicie|iniciar)\\b").containsMatchIn(normalized) &&
            normalized.contains("whatsapp")) {
            val app = packageManager.getLaunchIntentForPackage("com.whatsapp")
                ?: packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")
            if (app != null) {
                try {
                    app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(app)
                    say("Abri o WhatsApp, Jay.")
                } catch (_: Exception) { say("Abra o WhatsApp pela tela. Estou escutando.") }
            } else say("Não encontrei o WhatsApp.")
            return
        }
        val opening = Regex("\\b(abra|abr|abrir|abre|inicie|iniciar|execute|executar)\\b").containsMatchIn(normalized)
        if (opening && (normalized.contains("chrome") || normalized.contains("navegador") ||
                normalized.contains("chatgpt"))) {
            val isChat = normalized.contains("chatgpt")
            val pkg = if (isChat) "com.openai.chatgpt" else "com.android.chrome"
            val app = packageManager.getLaunchIntentForPackage(pkg)
            if (app != null) {
                try {
                    app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(app)
                    say(if (isChat) "Abri o ChatGPT." else "Abri o Chrome.")
                } catch (_: Exception) { say("Não consegui abrir o aplicativo.") }
            } else if (isChat) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://chatgpt.com"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    say("Abri o ChatGPT no navegador.")
                } catch (_: Exception) { say("Não consegui abrir o ChatGPT.") }
            } else say("Não encontrei o Chrome instalado.")
            return
        }
        val searchRequest = Regex("\\b(pesquise|pesquisar|procure|buscar|busque|pesquisa)\\b")
            .containsMatchIn(normalized)
        if (searchRequest && (normalized.contains("google") || normalized.contains("internet"))) {
            val query = command.replace(
                Regex("(?i)^(pesquise|pesquisar|procure|buscar|busque|pesquisa)\\s+(no |na |pelo )?(google|internet)\\s*(por |sobre )?"), ""
            ).trim()
            if (query.isNotBlank()) {
                try {
                    val url = "https://www.google.com/search?q=" +
                        java.net.URLEncoder.encode(query, "UTF-8")
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    lastSearchQuery = query
                    fetchSearchSummary(query)
                } catch (_: Exception) { say("Não consegui iniciar a pesquisa.") }
            } else say("O que você deseja pesquisar?")
            return
        }
        if (lastSearchQuery != null &&
            (normalized.contains("leia") || normalized.contains("ler os resultados") ||
                normalized.contains("leitura dos resultados"))) {
            say("Ainda não tenho acesso ao conteúdo da página do Google. Posso explicar o tema, mas não vou fingir que li os resultados.")
            return
        }
        if (normalized.contains("tela inicial") || normalized.contains("voltar para inicio")) {
            try {
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                say("Voltei para a tela inicial.")
            } catch (_: Exception) { say("Não consegui voltar à tela inicial.") }
            return
        }
        if (Regex("\\b(feche|fechar|encerre)\\b").containsMatchIn(normalized) &&
            (normalized.contains("whatsapp") || normalized.contains("chrome") || normalized.contains("chatgpt"))) {
            say("O Android não permite encerrar outro aplicativo diretamente. Posso voltar à tela inicial.")
            return
        }
        processing = true
        recognizer?.cancel()
        listening = false
        update("Processando comando de voz")
        thread {
            var connection: HttpURLConnection? = null
            val reply = try {
                connection = (URL("https://negocia-trader-ai.gestarlink.workers.dev").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 12000; readTimeout = 25000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Authorization", "Bearer " + MainActivity.NIA_ACCESS_TOKEN)
                }
                val prompt = "Você é Leonan, assistente de voz. Responda em português brasileiro, naturalmente e brevemente. " +
                    "Não afirme ter pesquisado ou lido páginas da internet se não recebeu resultados. " +
                    (lastSearchQuery?.let { "Tema recente de pesquisa aberta no navegador: " + it.take(110) + ". " } ?: "") +
                    (lastConversation?.let { "Conversa anterior: " + it.take(180) + ". " } ?: "") +
                    "Usuário: " + command.take(520)
                connection!!.outputStream.use { it.write(JSONObject().put("message", prompt).toString().toByteArray()) }
                if (connection!!.responseCode == 200)
                    JSONObject(connection!!.inputStream.bufferedReader().use { it.readText() })
                        .optString("reply", "Não consegui responder.")
                else "Não consegui acessar a inteligência agora."
            } catch (_: Exception) { "Não consegui conectar à inteligência. Confira a internet." }
            finally { connection?.disconnect() }
            main.post {
                processing = false
                lastConversation = "Usuário: " + command.take(80) + " Leonan: " + reply.take(100)
                waiting = true
                followUpDeadline = System.currentTimeMillis() + 45000L
                update("Aguardando Léo ou Leonan")
                say(reply)
            }
        }
    }
    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val voices = tts?.voices.orEmpty().filter { it.locale.language == "pt" }
        val selected = voices.firstOrNull { it.name.contains("faber", true) }
            ?: voices.firstOrNull { it.locale.country.equals("BR", true) }
        selected?.let { tts?.voice = it }
        tts?.setSpeechRate(0.96f)
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { main.post {
                speaking = false
                if (waiting) followUpDeadline = System.currentTimeMillis() + 20000L
                retry(400L)
            } }
            override fun onError(utteranceId: String?) { main.post { speaking = false; retry() } }
        })
    }
    override fun onDestroy() {
        enabled = false
        broadcastState()
        main.removeCallbacks(loop)
        recognizer?.cancel()
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
