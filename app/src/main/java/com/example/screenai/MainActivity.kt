package com.example.screenai

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var keyField: EditText
    private lateinit var intervalField: EditText
    private lateinit var promptField: EditText
    private lateinit var modes: RadioGroup
    private lateinit var assist: RadioGroup
    private val reqCapture = 1
    private val reqNotif = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(Color.rgb(7, 10, 8))
        }

        fun label(t: String) = TextView(this).apply {
            text = t
            textSize = 13f
            setTextColor(Color.rgb(105, 220, 135))
            setPadding(0, pad, 0, 4)
        }

        root.addView(TextView(this).apply {
            text = "К.А.П.Р.И"
            textSize = 30f
            setTextColor(Color.rgb(255, 75, 82))
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.08f
        })
        root.addView(TextView(this).apply {
            text = "Кибер-Ассистент Принятия Решений Игрока\nПодсказки появляются поверх любого приложения, в том числе Geometry Dash."
            setTextColor(Color.rgb(190, 205, 192))
            setPadding(0, 4, 0, 0)
        })

        root.addView(label("API-ключ Gemini"))
        keyField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("key", ""))
            hint = "AIza..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            background = fieldBackground()
        }
        root.addView(keyField)

        root.addView(label("Как часто смотреть (секунды)"))
        intervalField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getString("interval", "5"))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            background = fieldBackground()
        }
        root.addView(intervalField)

        root.addView(label("Режим"))
        modes = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val names = listOf(
            "Лучший ход (пик, бан, ход в игре на логику)",
            "Советы (несколько вариантов, что делать)",
            "Помощь с кодом"
        )
        names.forEachIndexed { i, n ->
            modes.addView(RadioButton(this).apply {
                id = 100 + i
                text = n
                setTextColor(Color.rgb(225, 235, 226))
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.rgb(70, 205, 110))
            })
        }
        modes.check(100 + prefs.getInt("mode", 0))
        root.addView(modes)

        root.addView(label("При зажатии на К.А.П.Р.И"))
        assist = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        listOf("Голосовой помощник", "Чат-помощник", "Ничего не открывать").forEachIndexed { i, n ->
            assist.addView(RadioButton(this).apply {
                id = 200 + i
                text = n
                setTextColor(Color.rgb(225, 235, 226))
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.rgb(70, 205, 110))
            })
        }
        assist.check(200 + prefs.getInt("assistant", 0))
        root.addView(assist)

        root.addView(label("Свои пожелания (необязательно)"))
        promptField = EditText(this).apply {
            setText(prefs.getString("extra", ""))
            hint = "Например: я играю за Радиант, ранг Бог"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            background = fieldBackground()
        }
        root.addView(promptField)

        val accessibility = Button(this).apply {
            text = "Настроить автопилот 2048"
            setTextColor(Color.rgb(110, 230, 145))
            background = buttonBackground(Color.rgb(18, 25, 20), Color.rgb(55, 190, 95))
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        root.addView(accessibility, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = pad })

        root.addView(TextView(this).apply {
            text = "Для автопилота отдельно включи службу «К.А.П.Р.И — управление экраном» в настройках специальных возможностей. Автопилот запускается только голосовой командой и останавливается командой «Капри, останови автопилот»."
            textSize = 12f
            setTextColor(Color.rgb(165, 180, 168))
            setPadding(0, 4, 0, pad)
        })

        val start = Button(this).apply {
            text = "НАЧАТЬ"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(5, 18, 9))
            background = buttonBackground(Color.rgb(65, 220, 112), Color.rgb(65, 220, 112))
        }
        root.addView(start, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = pad })

        root.addView(TextView(this).apply {
            text = "Захват экрана работает в активном сеансе после твоего подтверждения Android. Он не постоянный навсегда: останови его кнопкой «Стоп» в уведомлении; Android также может отозвать доступ. Для Geometry Dash выбери режим подсказок и интервал 2–3 секунды."
            textSize = 13f
            setTextColor(Color.rgb(165, 180, 168))
            setPadding(0, pad, 0, 0)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(7, 10, 8))
            addView(root)
        })
        root.alpha = 0f
        root.translationY = 22f
        root.animate().alpha(1f).translationY(0f).setDuration(420).start()

        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO)
        }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), reqNotif)

        start.setOnClickListener {
            if (keyField.text.isBlank()) {
                Toast.makeText(this, "Вставь API-ключ", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Разреши показ поверх других приложений и нажми «Начать» снова", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                return@setOnClickListener
            }
            prefs.edit()
                .putString("key", keyField.text.toString().trim())
                .putString("interval", intervalField.text.toString())
                .putString("extra", promptField.text.toString())
                .putInt("mode", modeIndex())
                .putInt("assistant", (assist.checkedRadioButtonId - 200).coerceIn(0, 2))
                .apply()
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
        }
    }

    private fun fieldBackground() = GradientDrawable().apply {
        setColor(Color.rgb(20, 25, 21))
        cornerRadius = 14 * resources.displayMetrics.density
        setStroke((1 * resources.displayMetrics.density).toInt(), Color.rgb(50, 105, 65))
    }

    private fun buttonBackground(fill: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = 16 * resources.displayMetrics.density
        setStroke((1.5f * resources.displayMetrics.density).toInt(), stroke)
    }

    private fun modeIndex() = (modes.checkedRadioButtonId - 100).coerceIn(0, 2)

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqCapture) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Доступ к экрану не выдан", Toast.LENGTH_SHORT).show()
            return
        }
        val svc = Intent(this, ScreenService::class.java).apply {
            putExtra("code", resultCode)
            putExtra("data", data)
            putExtra("key", keyField.text.toString().trim())
            putExtra("interval", (intervalField.text.toString().toLongOrNull() ?: 5L).coerceIn(2L, 60L))
            putExtra("prompt", promptField.text.toString())
            putExtra("mode", modeIndex())
        }
        startForegroundService(svc)
        finish()
    }
}
