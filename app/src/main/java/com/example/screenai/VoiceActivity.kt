package com.example.screenai

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.util.Locale

class VoiceActivity : Activity() {

    private lateinit var status: TextView
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var completed = false
    private val handler = Handler(Looper.getMainLooper())
    private var rootView: View? = null
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScreenService.paused = true
        val d = resources.displayMetrics.density
        val pad = (18 * d).toInt()

        status = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.rgb(125, 235, 150))
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.rgb(12, 17, 13))
                cornerRadius = 20 * d
                setStroke((1.5f * d).toInt(), Color.rgb(65, 210, 105))
            }
            text = "К.А.П.Р.И слушает…"
        }
        val root = FrameLayout(this).apply { setBackgroundColor(0xE9080B09.toInt()) }
        rootView = root
        root.addView(
            status,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = (90 * d).toInt()
                leftMargin = (24 * d).toInt()
                rightMargin = (24 * d).toInt()
            }
        )
        root.setOnClickListener { finishAnimated() }
        setContentView(root)
        root.alpha = 0f
        root.scaleX = 0.94f
        root.scaleY = 0.94f
        root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(300).start()
        status.alpha = 0f
        status.translationY = 18f
        status.animate().alpha(1f).translationY(0f).setStartDelay(90).setDuration(360).start()

        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("ru", "RU"))
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            status.text = "Нужен доступ к микрофону. Разреши его в настройках приложения."
            closeLater(3000)
        }
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "На телефоне нет службы распознавания речи."
            closeLater(3000)
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { status.text = "Слушаю…" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrBlank()) status.text = t
            }
            override fun onError(error: Int) {
                status.text = "Не расслышала. Зажми меня ещё раз."
                closeLater(1800)
            }
            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t.isNullOrBlank()) {
                    status.text = "Не расслышала. Зажми меня ещё раз."
                    closeLater(1800)
                } else {
                    handle(t)
                }
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        r.startListening(i)
    }

    private fun handle(text: String) {
        status.text = "Ты: $text\nДумаю…"
        val key = getSharedPreferences("cfg", MODE_PRIVATE).getString("key", "") ?: ""
        Thread {
            val act = try {
                Brain.process(key, text)
            } catch (e: Exception) {
                BrainAction("chat", "Ошибка: ${e.message}")
            }
            runOnUiThread { respond(act) }
        }.start()
    }

    private fun respond(a: BrainAction) {
        status.text = a.reply.ifBlank { "…" }
        if (ttsReady && a.reply.isNotBlank()) {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { runOnUiThread { complete(a) } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { runOnUiThread { complete(a) } }
            })
            tts?.speak(a.reply, TextToSpeech.QUEUE_FLUSH, null, "kapri")
            handler.postDelayed({ complete(a) }, 15000)
        } else {
            complete(a)
        }
    }

    private fun complete(a: BrainAction) {
        if (completed) return
        completed = true
        val note = Brain.execute(this, a)
        if (note != null) {
            status.text = note
            closeLater(2500)
        } else {
            closeLater(if (a.action == "chat") 1500 else 300)
        }
    }

    private fun closeLater(ms: Long) {
        handler.postDelayed({ finishAnimated() }, ms)
    }

    private fun finishAnimated() {
        if (closing || isFinishing) return
        closing = true
        val root = rootView
        if (root == null) {
            finishNow()
            return
        }
        root.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f)
            .setDuration(220)
            .withEndAction { finishNow() }
            .start()
    }

    private fun finishNow() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finishAnimated()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { recognizer?.destroy() } catch (_: Exception) {}
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        ScreenService.paused = false
        super.onDestroy()
    }
}
