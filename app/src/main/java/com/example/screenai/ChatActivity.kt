package com.example.screenai

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class ChatActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private var requestInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (12 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF2F4F8.toInt())
        }
        root.addView(TextView(this).apply {
            text = "К.А.П.Р.И"
            textSize = 22f
            setTextColor(0xFF1B1F2A.toInt())
            setPadding(pad * 2, pad * 3, pad * 2, pad)
        })

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad / 2, pad, pad)
        }
        input = EditText(this).apply {
            hint = "Напиши К.А.П.Р.И…"
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val send = Button(this).apply { text = "→" }
        bar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(send, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(bar)
        setContentView(root)

        addBubble("Привет! Я К.А.П.Р.И. Спроси что угодно или скажи, например: «включи alex g - black hair».", false)
        send.setOnClickListener { sendMessage() }
    }

    override fun onResume() {
        super.onResume()
        ScreenService.paused = true
    }

    override fun onPause() {
        ScreenService.paused = false
        super.onPause()
    }

    private fun addBubble(text: String, mine: Boolean): TextView {
        val d = resources.displayMetrics.density
        val tv = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(if (mine) 0xFFFFFFFF.toInt() else 0xFF1B1F2A.toInt())
            setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
            maxWidth = (resources.displayMetrics.widthPixels * 0.8f).toInt()
            background = GradientDrawable().apply {
                setColor(if (mine) 0xFF4C6EF5.toInt() else 0xFFFFFFFF.toInt())
                cornerRadius = 16 * d
            }
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (mine) Gravity.END else Gravity.START
            topMargin = (6 * d).toInt()
        }
        list.addView(tv, lp)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        return tv
    }

    private fun sendMessage() {
        val t = input.text.toString().trim()
        if (t.isEmpty() || requestInFlight) return
        requestInFlight = true
        input.setText("")
        addBubble(t, true)
        val pending = addBubble("…", false)
        val key = getSharedPreferences("cfg", MODE_PRIVATE).getString("key", "") ?: ""
        Thread {
            val act = try {
                Brain.process(key, t)
            } catch (e: Exception) {
                BrainAction("chat", "Ошибка: ${e.message}")
            }
            runOnUiThread {
                requestInFlight = false
                pending.text = act.reply.ifBlank { "…" }
                val note = Brain.execute(this, act)
                if (note != null) addBubble(note, false)
                scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }.start()
    }
}
