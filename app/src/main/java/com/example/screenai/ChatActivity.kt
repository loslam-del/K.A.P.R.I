package com.example.screenai

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
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
            setBackgroundColor(Color.rgb(7, 10, 8))
        }
        root.addView(TextView(this).apply {
            text = "К.А.П.Р.И"
            textSize = 25f
            setTextColor(Color.rgb(255, 75, 82))
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.08f
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
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            background = GradientDrawable().apply {
                setColor(Color.rgb(20, 25, 21))
                cornerRadius = 14 * d
                setStroke((1 * d).toInt(), Color.rgb(50, 105, 65))
            }
        }
        val send = Button(this).apply {
            text = "→"
            setTextColor(Color.rgb(5, 18, 9))
            background = GradientDrawable().apply {
                setColor(Color.rgb(65, 220, 112))
                cornerRadius = 14 * d
            }
        }
        bar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(send, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(bar)
        setContentView(root)
        root.alpha = 0f
        root.translationY = 18f
        root.animate().alpha(1f).translationY(0f).setDuration(380).start()

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
            setTextColor(if (mine) Color.rgb(5, 18, 9) else Color.rgb(225, 235, 226))
            setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
            maxWidth = (resources.displayMetrics.widthPixels * 0.8f).toInt()
            background = GradientDrawable().apply {
                setColor(if (mine) Color.rgb(65, 220, 112) else Color.rgb(20, 25, 21))
                cornerRadius = 16 * d
                if (!mine) setStroke((1 * d).toInt(), Color.rgb(50, 105, 65))
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
                pending.alpha = 0f
                pending.animate().alpha(1f).setDuration(220).start()
                val note = Brain.execute(this, act)
                if (note != null) addBubble(note, false)
                scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }.start()
    }
}
