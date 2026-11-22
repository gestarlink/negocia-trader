package br.com.negocia.trader

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import kotlin.concurrent.thread
import java.io.ByteArrayOutputStream
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.speech.*
import android.speech.tts.TextToSpeech
import java.io.File
import java.io.FileOutputStream
import java.text.Normalizer
import java.util.Locale

/** User-consented screen capture and foreground voice prototype. No image is uploaded. */
class ChartCaptureService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val START = "br.com.negocia.trader.START_CHART"
        const val STOP = "br.com.negocia.trader.STOP_CHART"
        const val CAPTURE = "br.com.negocia.trader.CAPTURE_CHART"
        const val RESULT = "projection_result"
        const val DATA = "projection_data"
        const val CHANNEL = "nia_chart"
    }
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var speaking = false
    private var awaiting = false
    private var listening = false
    private var active = false
    private var analyzing = false
    private var retryCount = 0
    private val loop = Runnable { listen() }
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { main.post { stopSelf() } }
    }

    override fun onCreate() {
        super.onCreate()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, "Leonan acompanhando gráfico", NotificationManager.IMPORTANCE_LOW))
        val engines = packageManager.queryIntentServices(Intent("android.intent.action.TTS_SERVICE"), 0)
        val hayai = engines.firstOrNull { it.serviceInfo.packageName.contains("hayai", true) }
        tts = if (hayai != null) TextToSpeech(this, this, hayai.serviceInfo.packageName)
              else TextToSpeech(this, this)
    }

    private fun notification(message: String): Notification {
        val stop = PendingIntent.getService(this, 8,
            Intent(this, ChartCaptureService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val capture = PendingIntent.getService(this, 9,
            Intent(this, ChartCaptureService::class.java).setAction(CAPTURE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("NIA • Gráfico compartilhado")
            .setContentText(message)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_camera, "Analisar gráfico", capture)
            .addAction(android.R.drawable.ic_delete, "Parar", stop)
            .build()
    }

    private fun update(message: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(110, notification(message))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> { stopSelf(); return START_NOT_STICKY }
            CAPTURE -> { if (active) capture(); return START_NOT_STICKY }
            START -> {
                if (active) return START_NOT_STICKY
                val result = intent.getIntExtra(RESULT, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val data = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(DATA, Intent::class.java)
                else intent.getParcelableExtra(DATA) as? Intent
                if (result != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY }
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        startForeground(110, notification("Preparando compartilhamento autorizado"), types)
                    } else startForeground(110, notification("Preparando compartilhamento autorizado"))
                    val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    projection = manager.getMediaProjection(result, data)
                    projection?.registerCallback(projectionCallback, main)
                    val metrics = resources.displayMetrics
                    reader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels,
                        PixelFormat.RGBA_8888, 2)
                    display = projection?.createVirtualDisplay("NIA chart", metrics.widthPixels,
                        metrics.heightPixels, metrics.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, main)
                    active = true
                    update("Chrome pode ser aberto. Diga: Léo, capture o gráfico")
                    listen()
                } catch (e: Exception) {
                    update("Não foi possível iniciar: " + e.javaClass.simpleName)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun listen() {
        if (!active || speaking || analyzing || listening || Build.VERSION.SDK_INT < 31 ||
            !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) return
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onResults(results: Bundle?) {
                    listening = false
                    retryCount = 0
                    val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    val norm = Normalizer.normalize(phrase.lowercase(Locale.ROOT), Normalizer.Form.NFD)
                        .replace(Regex("[\\p{Mn}]"), "")
                    val wake = Regex("\\b(leo|leona|leonan|leonam)\\b").containsMatchIn(norm)
                    if (wake && (norm.contains("grafico") || norm.contains("captur") ||
                            norm.contains("tela") || norm.contains("analis"))) {
                        awaiting = false
                        capture()
                    } else if (wake) {
                        awaiting = true
                        say("Oi Jay, o que você deseja?")
                    } else if (awaiting && (norm.contains("grafico") || norm.contains("captur") ||
                            norm.contains("tela") || norm.contains("analis"))) {
                        awaiting = false
                        capture()
                    }
                    retry()
                }
                override fun onError(error: Int) {
                    listening = false
                    retryCount = (retryCount + 1).coerceAtMost(5)
                    if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                        error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
                        update("Escuta indisponível. Use Analisar gráfico na notificação.")
                    retry()
                }
            })
            listening = true
            recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            })
        } catch (_: Exception) {
            listening = false
            retryCount = (retryCount + 1).coerceAtMost(5)
            retry()
        }
    }

    private fun retry() {
        main.removeCallbacks(loop)
        if (active && !speaking && !analyzing) {
            val delay = if (retryCount == 0) 1800L else (3000L * retryCount).coerceAtMost(15000L)
            main.postDelayed(loop, delay)
        }
    }

    private fun say(text: String) {
        speaking = true
        recognizer?.cancel()
        listening = false
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nia_chart")
    }

    private fun capture() {
        if (analyzing) { update("Aguarde: análise em andamento"); return }
        recognizer?.cancel()
        listening = false
        main.removeCallbacks(loop)
        val image = reader?.acquireLatestImage()
        if (image == null) {
            update("Aguardando próximo quadro da tela")
            say("Ainda não recebi a imagem. Tente novamente.")
            return
        }
        try {
            val plane = image.planes[0]
            val width = image.width
            val height = image.height
            val row = plane.rowStride / plane.pixelStride
            val padded = Bitmap.createBitmap(row, height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            val bitmap = Bitmap.createBitmap(padded, 0, 0, width, height)
            val targetWidth = minOf(1024, width)
            val scaledHeight = (height.toDouble() * targetWidth / width).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, scaledHeight, true)
            val bytes = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 76, it) }.toByteArray()
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            padded.recycle()
            analyze(bytes)
        } catch (_: Exception) {
            update("Falha ao capturar a imagem")
            say("Não consegui capturar o gráfico.")
        } finally { image.close() }
    }

    private fun analyze(jpeg: ByteArray) {
        analyzing = true
        main.removeCallbacks(loop)
        update("Leonan está interpretando o gráfico...")
        thread {
            var connection: HttpURLConnection? = null
            val reply = try {
                val image = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
                connection = (URL("https://nia-chart-vision.gestarlink.workers.dev/analyze")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 60000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Authorization", "Bearer " + MainActivity.NIA_ACCESS_TOKEN)
                }
                connection!!.outputStream.use {
                    it.write(JSONObject().put("image", image)
                        .put("question", "Descreva o gráfico atual, cenários condicionais e riscos.")
                        .toString().toByteArray(Charsets.UTF_8))
                }
                if (connection!!.responseCode == 200) {
                    JSONObject(connection!!.inputStream.bufferedReader().use { it.readText() })
                        .optString("reply", "Não consegui interpretar o gráfico.")
                } else {
                    val errorText = connection!!.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    val detail = try {
                        val json = JSONObject(errorText)
                        json.optString("error", "Falha") + " " + json.optString("detail", "")
                    } catch (_: Exception) { "Resposta do serviço indisponível" }
                    "A análise falhou. Código " + connection!!.responseCode + ". " + detail.take(160)
                }
            } catch (_: Exception) {
                "Não consegui consultar a análise visual. Verifique a conexão e tente novamente."
            } finally { connection?.disconnect() }
            main.post {
                analyzing = false
                if (active) {
                    update("Análise concluída • diga Léo, analise o gráfico novamente")
                    say(reply.take(1100))
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.forLanguageTag("pt-BR")
            tts?.voices?.firstOrNull { it.name.contains("faber", true) }?.let { tts?.voice = it }
            tts?.setSpeechRate(0.96f)
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { main.post { speaking = false; retry() } }
                override fun onError(utteranceId: String?) { main.post { speaking = false; retry() } }
            })
        }
    }

    override fun onDestroy() {
        active = false
        main.removeCallbacks(loop)
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        display?.release()
        reader?.close()
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
