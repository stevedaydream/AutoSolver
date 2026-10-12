package com.stevedaydream.autodora

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal class VisionTeacherClient(private val settings: VisionTeacherSettings) {
    private fun request(url: String, body: JSONObject? = null, headers: Map<String, String> = emptyMap()): JSONObject {
        val target = URL(url)
        require(target.protocol in listOf("http", "https") && target.userInfo == null) { "端點格式錯誤" }
        val connection = target.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = if (body == null) 10000 else if (target.host == "generativelanguage.googleapis.com") 60000 else 120000
            connection.instanceFollowRedirects = false
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            check(code in 200..299) { "模型服務 HTTP $code" }
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 1_048_576) { "模型回應過大" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            check(bytes.size <= 1_048_576) { "模型回應過大" }
            return JSONObject(String(bytes, Charsets.UTF_8))
        } finally { connection.disconnect() }
    }

    private fun localHeaders() = settings.secret("localKey").takeIf { it.isNotBlank() }
        ?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()

    fun discoverLocalModel(): String {
        require(settings.endpoint.isNotBlank()) { "請設定本機端點" }
        val models = request("${settings.endpoint.removeSuffix("/v1")}/v1/models", headers = localHeaders()).getJSONArray("data")
        check(models.length() > 0) { "模型服務尚未載入模型" }
        return models.getJSONObject(0).getString("id")
    }

    fun discoverGeminiModels(): List<String> {
        val key = settings.secret("geminiKey")
        require(key.isNotBlank()) { "請先儲存 Gemini API 金鑰" }
        val result = mutableListOf<String>()
        var page = ""
        repeat(10) {
            val response = request("https://generativelanguage.googleapis.com/v1beta/models?pageSize=100$page", headers = mapOf("x-goog-api-key" to key))
            val models = response.optJSONArray("models") ?: JSONArray()
            for (i in 0 until models.length()) {
                val model = models.getJSONObject(i)
                val methods = model.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
                if (methods.contains("generateContent")) result.add(model.getString("name").removePrefix("models/"))
            }
            val next = response.optString("nextPageToken")
            if (next.isEmpty()) return result
            page = "&pageToken=${java.net.URLEncoder.encode(next, "UTF-8")}"
        }
        return result
    }

    fun recognize(jpeg: ByteArray, game: GameProfile): TeacherAnswer<List<OrbAnnotation>> {
        val image = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val prompt = """
            你是轉珠遊戲 ${game.name} 的盤面標記員。圖片只含 5 列 6 欄盤面。
            逐格辨識，由上到下 row=0..4、由左到右 col=0..5。不可依排列規律猜測。
            type 僅能為 WATER,FIRE,WOOD,LIGHT,DARK,HEART,UNKNOWN。
            marks 是可同時存在的陣列：ENHANCED=強化、WEATHER=風化、SHIELD=加護、MUSIC=音符、BURN=燃燒、POISON=腐化或毒、STICKY=黏著、FREEZE=冰凍。一般珠為 []。
            races 是可看見的種族標記名稱陣列，沒有為 []。不要把普通珠花紋或顏色當成特殊狀態。
            不清楚、遮擋或不在標記範圍時 uncertain=true，屬性不清楚時 type=UNKNOWN。
            僅輸出 JSON {"cells":[{"row":0,"col":0,"type":"FIRE","marks":[],"races":[],"uncertain":false},...]}，必須恰好 30 格，不要說明。
        """.trimIndent()
        val cloud: (() -> List<OrbAnnotation>)? = if (settings.cloudEnabled) ({
            val model = settings.geminiModel
            require(model.matches(Regex("[a-zA-Z0-9._-]+"))) { "請設定 Gemini 模型名稱" }
            val key = settings.secret("geminiKey")
            require(key.isNotBlank()) { "請設定 Gemini API 金鑰" }
            val response = request("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                    .put(JSONObject().put("text", prompt))
                    .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", image))))))
                put("generationConfig", JSONObject().put("temperature", 0).put("maxOutputTokens", 4096).put("responseMimeType", "application/json"))
            }, mapOf("x-goog-api-key" to key))
            val candidate = response.getJSONArray("candidates").getJSONObject(0)
            check(candidate.optString("finishReason") == "STOP") { "Gemini 回應未完成" }
            val parts = candidate.getJSONObject("content").getJSONArray("parts")
            val text = (0 until parts.length()).filter { !parts.getJSONObject(it).optBoolean("thought", false) }
                .joinToString("") { parts.getJSONObject(it).optString("text") }
            TeacherResponse.parse(text)
        }) else null
        var localModel = ""
        return TeacherFallback.run(local = {
            val model = settings.model.ifBlank { discoverLocalModel() }
            localModel = model
            val response = request("${settings.endpoint.removeSuffix("/v1")}/v1/chat/completions", JSONObject().apply {
                put("model", model)
                put("temperature", 0); put("max_tokens", 4096); put("stream", false)
                put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
                put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray()
                    .put(JSONObject().put("type", "text").put("text", prompt))
                    .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$image"))))))
            }, localHeaders())
            val choice = response.getJSONArray("choices").getJSONObject(0)
            check(choice.optString("finish_reason") == "stop") { "本機回應未完成" }
            TeacherResponse.parse(choice.getJSONObject("message").getString("content"))
        }, cloud = cloud, usable = { cells -> cells.none { it.uncertain || it.type == BoardAnalyzer.OrbType.UNKNOWN } })
            .let { it.copy(model = if (it.provider == "QWEN") localModel else settings.geminiModel) }
    }
}
