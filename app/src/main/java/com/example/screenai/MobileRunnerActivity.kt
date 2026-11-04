package com.example.screenai

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local companion for KAPRI-LAB. No silent permission grants or remote control. */
class MobileRunnerActivity : Activity() {
    private val green = Color.rgb(105, 220, 135)
    private val muted = Color.rgb(180, 195, 184)
    private lateinit var status: TextView
    private var report: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(Color.rgb(7, 10, 8))
        }

        fun text(value: String, size: Float = 14f, color: Int = muted) =
            TextView(this).apply {
                text = value
                textSize = size
                setTextColor(color)
                setPadding(0, 0, 0, pad / 2)
            }

        fun button(value: String, action: () -> Unit) =
            Button(this).apply {
                text = value
                setTextColor(green)
                background = GradientDrawable().apply {
                    setColor(Color.rgb(18, 25, 20))
                    cornerRadius = 14 * resources.displayMetrics.density
                    setStroke((1 * resources.displayMetrics.density).toInt(), green)
                }
                setOnClickListener { action() }
            }

        root.addView(text("KAPRI MOBILE RUNNER", 22f, green).apply {
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(text("Локальный помощник KAPRI-LAB. Проверяет готовность телефона и формирует отчёт."))
        status = text("Нажми «Проверить телефон», чтобы начать.", 14f, Color.WHITE)
        root.addView(status)

        root.addView(button("ПРОВЕРИТЬ ТЕЛЕФОН") { runDiagnostics() }, matchWidth())
        root.addView(button("Настройки специальных возможностей") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, matchWidth())
        root.addView(button("Настройки показа поверх приложений") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }, matchWidth())
        root.addView(button("Открыть Geometry Dash") { openGeometryDash() }, matchWidth())
        root.addView(button("Поделиться отчётом") { shareReport() }, matchWidth())

        root.addView(text(
            "Важно: версия 0.1 работает локально. Связь с GitHub Actions, удалённые задания и автоматическая отправка отчётов ещё не подключены. " +
                "Runner не устанавливает APK и не подтверждает системные окна за тебя. Доступ к экрану и специальные разрешения включаются вручную.",
            12f
        ).apply { setPadding(0, pad, 0, 0) })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(7, 10, 8))
            addView(root)
        })
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = (8 * resources.displayMetrics.density).toInt() }

    private fun hasAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.contains("com.example.screenai.KapriAccessibilityService", true)
    }

    private fun isInstalled(packageId: String): Boolean = try {
        packageManager.getPackageInfo(packageId, 0)
        true
    } catch (_: Exception) {
        false
    }

    private fun runDiagnostics() {
        val overlay = Settings.canDrawOverlays(this)
        val accessibility = hasAccessibilityEnabled()
        val notifications = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val game = isInstalled("com.robtopx.geometryjump")
        val lines = listOf(
            "KAPRI-LAB Mobile Runner report",
            "time_utc=${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())}",
            "runner_version=0.1-local",
            "android_sdk=${Build.VERSION.SDK_INT}",
            "device=${Build.MANUFACTURER} ${Build.MODEL}",
            "kapri_package=$packageName",
            "overlay_permission=$overlay",
            "accessibility_service_enabled=$accessibility",
            "notification_permission=$notifications",
            "geometry_dash_installed=$game",
            "cloud_connection=not_configured",
            "remote_task_execution=not_available",
            "screen_capture=not_started"
        )
        report = lines.joinToString("\n")
        status.text = lines.drop(1).joinToString("\n")
        Toast.makeText(this, "Проверка завершена", Toast.LENGTH_SHORT).show()
    }

    private fun openGeometryDash() {
        val intent = packageManager.getLaunchIntentForPackage("com.robtopx.geometryjump")
        if (intent == null) {
            Toast.makeText(this, "Geometry Dash не найдена на телефоне", Toast.LENGTH_LONG).show()
        } else {
            startActivity(intent)
        }
    }

    private fun shareReport() {
        if (report.isBlank()) runDiagnostics()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "KAPRI-LAB Mobile Runner report")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(send, "Отправить отчёт"))
    }
}
