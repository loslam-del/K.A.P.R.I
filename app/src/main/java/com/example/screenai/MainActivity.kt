package com.example.screenai

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
        }

        fun label(t: String) = TextView(this).apply {
            text = t
            textSize = 13f
            setPadding(0, pad, 0, 4)
        }

        root.addView(TextView(this).apply { text = "К.А.П.Р.И"; textSize = 28f })
        root.addView(TextView(this).apply {
            text = "Кибер-Ассистент Принятия Решений Игрока\nПодсказки появляются поверх любого приложения."
            setPadding(0, 4, 0, 0)
        })

        root.addView(label("API-ключ Gemini"))
        keyField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("key", ""))
            hint = "AIza..."
        }
        root.addView(keyField)

        root.addView(label("Как часто смотреть (секунды)"))
        intervalField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getString("interval", "5"))
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
            modes.addView(RadioButton(this).apply { id = 100 + i; text = n })
        }
        modes.check(100 + prefs.getInt("mode", 0))
        root.addView(modes)

        root.addView(label("При зажатии на К.А.П.Р.И"))
        assist = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        listOf("Голосовой помощник", "Чат-помощник", "Ничего не открывать").forEachIndexed { i, n ->
            assist.addView(RadioButton(this).apply { id = 200 + i; text = n })
        }
        assist.check(200 + prefs.getInt("assistant", 0))
        root.addView(assist)

        root.addView(label("Свои пожелания (необязательно)"))
        promptField = EditText(this).apply {
            setText(prefs.getString("extra", ""))
            hint = "Например: я играю за Радиант, ранг Бог"
        }
        root.addView(promptField)

        val start = Button(this).apply { text = "Начать" }
        root.addView(start, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = pad })

        root.addView(TextView(this).apply {
            text = "Остановить: кнопка «Стоп» в уведомлении."
            textSize = 13f
            setPadding(0, pad, 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(root) })

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
