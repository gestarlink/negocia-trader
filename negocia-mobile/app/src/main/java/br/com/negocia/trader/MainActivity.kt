package br.com.negocia.trader

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private val speechRequest = 1200
    private lateinit var status: TextView
    private var tts: TextToSpeech? = null
    private var speechReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 64, 36, 36)
        }
        status = TextView(this).apply {
            text = "NegocIA Trader — teste de comandos de voz"
        }
        val listen = Button(this).apply {
            text = "Falar com Leonan"
            setOnClickListener { listen() }
        }
        layout.addView(status)
        layout.addView(listen)
        setContentView(layout)
    }

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Diga seu comando ao Leonan")
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

    @Deprecated("Compatibility with initial Android activity prototype")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == speechRequest && resultCode == RESULT_OK) {
            val heard = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
            val reply = if (heard.contains("analise", true) || heard.contains("análise", true))
                "Ouvi: $heard. A análise do gráfico será integrada em uma próxima versão."
            else "Ouvi: $heard."
            status.text = reply
            speak(reply)
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
