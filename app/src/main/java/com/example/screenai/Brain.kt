package com.example.screenai

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.MediaStore
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class BrainAction(
    val action: String,
    val reply: String,
    val query: String = "",
    val app: String = "",
    val key: String = ""
)

object Brain {

    private val history = ArrayList<Pair<String, String>>() // (что сказал пользователь, что ответила К.А.П.Р.И)
    private var modelIdx = 0

    private const val SYSTEM = """Тебя зовут К.А.П.Р.И — Кибер-Ассистент Принятия Решений Игрока. Ты голосовой и чат-помощник на телефоне пользователя. Слово «капри» в начале фразы — это твоё имя, а не часть команды. Отвечай по-русски, коротко: 1–3 предложения, без списков и эмодзи (твой ответ могут зачитать вслух). Верни ТОЛЬКО JSON одного из видов:
{"action":"chat","reply":"текст ответа"} — обычный разговор или вопрос;
{"action":"play_music","query":"исполнитель - название трека","reply":"Включаю ..."} — когда просят включить песню, трек, альбом, музыку или исполнителя;
{"action":"open_app","app":"название приложения как на телефоне","reply":"Открываю ..."} — когда просят открыть приложение;
{"action":"media","key":"pause","reply":"..."} — управление плеером, key: pause, play, next или previous.
Название трека пиши так, как сказал пользователь, исправляя очевидные ошибки распознавания речи."""

    fun process(apiKey: String, text: String): BrainAction {
        val contents = JSONArray()
        for ((u, a) in history.takeLast(6)) {
            contents.put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", u))))
            contents.put(JSONObject().put("role", "model")
                .put("parts", JSONArray().put(JSONObject().put("text", a))))
        }
        contents.put(JSONObject().put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", text))))

        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", SYSTEM))))
            .put("contents", contents)

        val models = ScreenService.MODELS
        for (i in modelIdx until models.size) {
            val model = models[i]
            val cfg = JSONObject()
                .put("maxOutputTokens", 400)
                .put("responseMimeType", "application/json")
            if (model.contains("2.5")) cfg.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            body.put("generationConfig", cfg)

            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
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
            val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if ((code == 404 || code == 429 || code == 503) && i < models.size - 1) continue

            val json = JSONObject(resp)
            if (code !in 200..299) {
                throw RuntimeException(json.optJSONObject("error")?.optString("message") ?: "HTTP $code")
            }
            modelIdx = i
            var raw = ""
            val cands = json.optJSONArray("candidates")
            if (cands != null && cands.length() > 0) {
                val ps = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                if (ps != null) {
                    for (k in 0 until ps.length()) raw += ps.getJSONObject(k).optString("text")
                }
            }
            val act = parse(raw)
            history.add(text to raw.ifBlank { act.reply })
            return act
        }
        throw RuntimeException("модель Gemini не найдена, проверь ключ")
    }

    private fun parse(raw: String): BrainAction {
        val s = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try {
            val j = JSONObject(s.substring(s.indexOf('{'), s.lastIndexOf('}') + 1))
            BrainAction(
                j.optString("action", "chat"),
                j.optString("reply", ""),
                j.optString("query", ""),
                j.optString("app", ""),
                j.optString("key", "")
            )
        } catch (e: Exception) {
            BrainAction("chat", raw.trim())
        }
    }

    /** Выполняет действие. Возвращает текст, если что-то не получилось, иначе null. */
    fun execute(ctx: Context, a: BrainAction): String? {
        return try {
            when (a.action) {
                "play_music" -> {
                    if (a.query.isBlank()) "Не поняла, что включить." else { playMusic(ctx, a.query); null }
                }
                "open_app" -> if (openApp(ctx, a.app)) null else "Не нашла приложение «${a.app}»."
                "media" -> { mediaKey(ctx, a.key); null }
                else -> null
            }
        } catch (e: Exception) {
            "Не получилось: ${e.message}"
        }
    }

    private fun playMusic(ctx: Context, query: String) {
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(SearchManager.QUERY, query)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(i)
        } catch (e: Exception) {
            val web = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(web)
        }
    }

    private fun openApp(ctx: Context, name: String): Boolean {
        val n = name.lowercase().trim()
        if (n.isEmpty()) return false
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(main, 0)
            .firstOrNull { it.loadLabel(pm).toString().lowercase().contains(n) } ?: return false
        val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(launch)
        return true
    }

    private fun mediaKey(ctx: Context, key: String) {
        val code = when (key.lowercase()) {
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
}
