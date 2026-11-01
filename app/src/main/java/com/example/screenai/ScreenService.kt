package com.example.screenai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class ScreenService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.screenai.STOP"
        val MODELS = listOf("gemini-flash-lite-latest", "gemini-2.5-flash-lite", "gemini-2.5-flash", "gemini-3-flash-preview")
        const val CHANNEL = "screenai"
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var overlay: TextView? = null
    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()
    private val history = ArrayList<Pair<String, String>>() // (что видел, что ответил)

    @Volatile private var busy = false
    private var running = false
    private var apiKey = ""
    private var prompt = ""
    private var mode = 0
    private var modelIdx = 0
    private var intervalMs = 5000L
    private var w = 0
    private var h = 0
    private var dpi = 0

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            checkSize()
            capture()
            main.postDelayed(this, intervalMs)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null || running) return START_NOT_STICKY

        try {
            startAsForeground()

            apiKey = intent.getStringExtra("key") ?: ""
            prompt = intent.getStringExtra("prompt") ?: ""
            mode = intent.getIntExtra("mode", 0)
            intervalMs = intent.getLongExtra("interval", 5L) * 1000L
            val code = intent.getIntExtra("code", 0)
            val data: Intent = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra("data", Intent::class.java)!!
            else
                intent.getParcelableExtra("data")!!

            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(code, data)
            projection = proj
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { main.post { stopSelf() } }
            }, main)

            val dm = DisplayMetrics()
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
            w = dm.widthPixels
            h = dm.heightPixels
            dpi = dm.densityDpi

            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            reader = r
            display = proj.createVirtualDisplay(
                "screenai", w, h, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
            )

            addOverlay()
            running = true
            main.postDelayed(tick, 1500)
        } catch (e: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "К.А.П.Р.И", NotificationManager.IMPORTANCE_LOW))
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, ScreenService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Стоп", stopIntent
        ).build()
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("К.А.П.Р.И следит за экраном")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(action)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun addOverlay() {
        val pad = (10 * resources.displayMetrics.density).toInt()
        val tv = TextView(this).apply {
            text = "К.А.П.Р.И на связи…"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xCC000000.toInt())
            textSize = 14f
            setPadding(pad, pad, pad, pad)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = (60 * resources.displayMetrics.density).toInt()
        }
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(tv, lp)
        overlay = tv
    }

    @Suppress("DEPRECATION")
    private fun checkSize() {
        val dm = DisplayMetrics()
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        if (dm.widthPixels == w && dm.heightPixels == h) return
        w = dm.widthPixels
        h = dm.heightPixels
        val newReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        display?.resize(w, h, dpi)
        display?.setSurface(newReader.surface)
        reader?.close()
        reader = newReader
    }

    private fun capture() {
        if (busy) return
        val img = reader?.acquireLatestImage() ?: return // экран не менялся — кадра нет
        val cropped: Bitmap
        try {
            val plane = img.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * w
            val full = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            cropped = Bitmap.createBitmap(full, 0, 0, w, h)
            full.recycle()
        } finally {
            img.close()
        }
        val s = min(1f, 1568f / max(w, h))
        val scaled = Bitmap.createScaledBitmap(cropped, (w * s).toInt(), (h * s).toInt(), true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)

        val t0 = System.currentTimeMillis()
        busy = true
        net.execute {
            val reply = try { askClaude(b64) } catch (e: Exception) { "Ошибка: ${e.message}" }
            main.post {
                busy = false
                show(reply, t0)
            }
        }
    }

    private fun askClaude(b64: String): String {
        val contents = JSONArray()
        for ((u, a) in history.takeLast(3)) {
            contents.put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", u))))
            contents.put(JSONObject().put("role", "model")
                .put("parts", JSONArray().put(JSONObject().put("text", a))))
        }
        val parts = JSONArray()
            .put(JSONObject().put("inlineData",
                JSONObject().put("mimeType", "image/jpeg").put("data", b64)))
            .put(JSONObject().put("text", "Новый кадр экрана. Что важного?"))
        contents.put(JSONObject().put("role", "user").put("parts", parts))

        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", systemPrompt()))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("maxOutputTokens", 1024))

        for (i in modelIdx until MODELS.size) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/${MODELS[i]}:generateContent"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream.bufferedReader().use { it.readText() }
            if ((code == 404 || code == 429 || code == 503) && i < MODELS.size - 1) continue

            val json = JSONObject(text)
            if (code !in 200..299) {
                throw RuntimeException(json.optJSONObject("error")?.optString("message") ?: "HTTP $code")
            }
            modelIdx = i
            var reply = ""
            val cands = json.optJSONArray("candidates")
            if (cands != null && cands.length() > 0) {
                val ps = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                if (ps != null) {
                    for (k in 0 until ps.length()) reply += ps.getJSONObject(k).optString("text")
                }
            }
            reply = reply.trim()
            if (reply.isEmpty()) reply = "ок"
            history.add("Новый кадр экрана." to reply)
            return reply
        }
        throw RuntimeException("модель Gemini не найдена, проверь ключ")
    }

    private fun systemPrompt(): String {
        val persona = "Тебя зовут К.А.П.Р.И — Кибер-Ассистент Принятия Решений Игрока. "
        val base = persona + when (mode) {
            0 -> "Ты игровой помощник. По скриншоту определи игру и ситуацию. " +
                "Если это выбор героев (пик или бан) — назови ОДНОГО лучшего героя для пика или бана, " +
                "учитывая уже выбранных героев обеих команд и роль игрока, и одним предложением объясни почему. " +
                "Если это игра на логику, головоломка или настольная игра — назови ОДИН лучший следующий ход " +
                "конкретно: что и куда сделать. Если ты не видишь на экране ни выбора героя, ни позиции, где нужен ход " +
                "(лобби, меню, магазин, загрузка, сам бой), ответь «ок» и ничего не советуй. " +
                "Формат: «Ход: … Почему: …». " +
                "Если ситуация не изменилась с прошлого ответа или это не игра — ответь одним словом «ок»."
            1 -> "Ты игровой помощник. По скриншоту определи игру и ситуацию. " +
                "Если на экране нет игровой ситуации, где нужно решение (лобби, меню, магазин, загрузка), ответь «ок». " +
                "Иначе предложи 2–3 РАЗНЫХ способа действовать сейчас, каждый одной короткой строкой, " +
                "с нумерацией «1.», «2.», «3.». Самый сильный вариант пометь словом «лучший». " +
                "Если ситуация не изменилась с прошлого ответа или это не игра — ответь одним словом «ок»."
            else -> "Я пишу код. Смотри на экран и коротко (1–3 предложения) говори только о важном: " +
                "ошибки, баги, что можно улучшить. Если нового важного нет, ответь одним словом «ок»."
        }
        val extra = if (prompt.isBlank()) "" else "\nДополнительно от пользователя: $prompt"
        return base + extra +
            "\nОтвечай по-русски и коротко. Игнорируй тёмное плавающее окошко с твоими прошлыми подсказками."
    }

    private fun show(reply: String, t0: Long) {
        if (reply.lowercase().trim().trimEnd('.', '!') == "ок") return
        val sec = (System.currentTimeMillis() - t0) / 1000
        overlay?.text = "К.А.П.Р.И: $reply\n(ответ готовился $sec с)"
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        try { overlay?.let { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) } } catch (_: Exception) {}
        display?.release()
        reader?.close()
        try { projection?.stop() } catch (_: Exception) {}
        net.shutdownNow()
        super.onDestroy()
    }
}
