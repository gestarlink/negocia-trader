package br.com.negocia.trader

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import org.json.JSONObject
import kotlin.concurrent.thread

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private val speechRequest = 1200
    private val endpoint = "https://negocia-trader-ai.gestarlink.workers.dev"
    private lateinit var status: TextView
    private lateinit var accessToken: EditText
    private var tts: TextToSpeech? = null
    private var speechReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 64, 36, 36)
        }
        status = TextView(this).apply { text = "NegocIA Trader — Leonan (Cloudflare AI)" }
        accessToken = EditText(this).apply {
            hint = "Código de acesso (configuração inicial)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val listen = Button(this).apply {
            text = "Falar com Leonan"
            setOnClickListener { listen() }
        }
        layout.addView(status)
        layout.addView(accessToken)
        layout.addView(listen)
        setContentView(layout)
    }

    private fun listen() {
        if (accessToken.text.toString().isBlank()) {
            status.text = "Configure primeiro o código de acesso ao serviço."
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Fale com Leonan")
        }
        try {
            startActivityForResult(intent, speechRequest)
        } catch (_: ActivityNotFoundException) {
            status.text = "Reconhecimento de fala indisponível neste aparelho."
        }
    }

    override fun onInit(result: Int) {
        speechReady = result == TextToSpeech.SUCCESS
        if (speechReady) tts?.language = Locale.forLanguageTag("pt-BR")
    }

    private fun speak(message: String) {
        if (speechReady) tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "negocia_reply")
    }

    private fun askLeonan(message: String) {
        val token = accessToken.text.toString()
        status.text = "Você: $message\nLeonan está pensando..."
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
                val bytes = JSONObject().put("message", message).toString().toByteArray(Charsets.UTF_8)
                conn.outputStream.use { it.write(bytes) }
                if (conn.responseCode == 200) {
                    JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        .optString("reply", "Não consegui responder agora.")
                } else if (conn.responseCode == 401) {
                    "Código de acesso inválido."
                } else {
                    "O serviço de IA está indisponível (código ${conn.responseCode})."
                }
            } catch (_: Exception) {
                "Não consegui conectar ao Leonan. Verifique sua internet."
            } finally {
                conn?.disconnect()
            }
            runOnUiThread {
                status.text = "Você: $message\n\nLeonan: $reply"
                speak(reply)
            }
        }
    }

    @Deprecated("Compatibility with initial Android activity prototype")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == speechRequest && resultCode == RESULT_OK) {
            val heard = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
            askLeonan(heard)
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
