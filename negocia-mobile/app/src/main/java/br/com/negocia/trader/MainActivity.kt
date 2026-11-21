package br.com.negocia.trader

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
    private lateinit var tokenInput: EditText
    private lateinit var activation: LinearLayout
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
            setPadding(dp(24), dp(28), dp(24), dp(20))
        }
        root.addView(text("NIA   •   NEGOCIA TRADER", 16f).apply {
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, dp(56)))
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
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
        root.addView(status, LinearLayout.LayoutParams(-1, dp(140)))
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        activation = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = shape(Color.rgb(24, 36, 58), 16)
        }
        activation.addView(text("Ativação inicial do aparelho", 14f))
        tokenInput = EditText(this).apply {
            hint = "Código de ativação"
            setHintTextColor(muted)
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        activation.addView(tokenInput)
        activation.addView(Button(this).apply {
            text = "Ativar e guardar neste aparelho"
            setOnClickListener {
                if (tokenInput.text.isNullOrBlank()) {
                    status.text = "Informe o código de ativação."
                } else {
                    // This is a temporary activation UI. No token is stored in plaintext.
                    status.text = "Código informado. A validação segura será concluída na próxima etapa."
                }
            }
        })
        root.addView(activation, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    private fun listen() {
        if (tokenInput.text.isNullOrBlank()) {
            status.text = "Ative primeiro o acesso à IA."
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Fale com Leonan")
        }
        try { startActivityForResult(intent, speechRequest) }
        catch (_: ActivityNotFoundException) { status.text = "Reconhecimento de voz indisponível." }
    }

    override fun onInit(result: Int) {
        speechReady = result == TextToSpeech.SUCCESS
        if (speechReady) tts?.language = Locale.forLanguageTag("pt-BR")
    }

    private fun speak(message: String) {
        if (speechReady) tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "nia_reply")
    }

    private fun askLeonan(message: String) {
        val token = tokenInput.text.toString()
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
                    it.write(JSONObject().put("message", message).toString().toByteArray(Charsets.UTF_8))
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
                speak(reply)
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
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
