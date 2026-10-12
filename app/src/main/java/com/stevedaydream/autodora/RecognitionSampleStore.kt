package com.stevedaydream.autodora

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class SampleReview { PENDING, CONFIRMED, REJECTED }

data class RecognitionReviewSample(
    val id: String, val game: GameProfile, val row: Int, val col: Int,
    val createdAt: Long, val provider: String, val model: String,
    val localType: BoardAnalyzer.OrbType, val suggested: OrbAnnotation,
    val descriptor: FloatArray, val review: SampleReview = SampleReview.PENDING,
    val confirmed: OrbAnnotation? = null, val reviewedAt: Long? = null,
    val localFailure: String? = null
) {
    fun toJson() = JSONObject().apply {
        put("schemaVersion", 1); put("id", id); put("game", game.name); put("row", row); put("col", col)
        put("createdAt", createdAt); put("provider", provider); put("model", model); put("localType", localType.name)
        put("suggested", suggested.toJson()); put("descriptor", JSONArray(descriptor.map { it.toDouble() }))
        put("review", review.name); confirmed?.let { put("confirmed", it.toJson()) }
        reviewedAt?.let { put("reviewedAt", it) }; localFailure?.let { put("localFailure", it) }
    }

    companion object {
        fun fromJson(obj: JSONObject): RecognitionReviewSample {
            val id = obj.getString("id")
            require(id.matches(Regex("[a-f0-9-]{36}")))
            val descriptor = obj.getJSONArray("descriptor")
            require(descriptor.length() == 768)
            return RecognitionReviewSample(id, GameProfile.valueOf(obj.getString("game")), obj.getInt("row"), obj.getInt("col"),
                obj.getLong("createdAt"), obj.getString("provider"), obj.getString("model"),
                BoardAnalyzer.OrbType.valueOf(obj.getString("localType")), OrbAnnotation.fromJson(obj.getJSONObject("suggested")),
                FloatArray(768) { descriptor.getDouble(it).toFloat().also { value -> require(value.isFinite() && value in 0f..1f) } },
                SampleReview.valueOf(obj.getString("review")), obj.optJSONObject("confirmed")?.let { OrbAnnotation.fromJson(it) },
                if (obj.has("reviewedAt")) obj.getLong("reviewedAt") else null, obj.optString("localFailure").takeIf { it.isNotBlank() }).also {
                require(it.row in 0..4 && it.col in 0..5)
                if (it.review == SampleReview.CONFIRMED) require(it.confirmed != null && !it.confirmed.uncertain && it.confirmed.type != BoardAnalyzer.OrbType.UNKNOWN)
            }
        }
    }
}

class RecognitionSampleStore(private val context: Context) {
    private val directory = File(context.filesDir, "recognition_review").apply { mkdirs() }

    fun list(): List<RecognitionReviewSample> = directory.listFiles { file -> file.extension == "json" }
        ?.mapNotNull { file -> runCatching { RecognitionReviewSample.fromJson(JSONObject(file.readText())) }.getOrNull() }
        ?.sortedByDescending { it.createdAt } ?: emptyList()

    fun image(id: String): File {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        return File(directory, "$id.png")
    }

    private fun save(sample: RecognitionReviewSample) {
        val target = File(directory, "${sample.id}.json")
        val temporary = File(directory, "${sample.id}.tmp")
        temporary.writeText(sample.toJson().toString())
        java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        VisionTeacherSettings(context).prefs.edit().putLong("revision", System.nanoTime()).apply()
    }

    internal fun addBatch(board: Bitmap, game: GameProfile, local: Array<Array<BoardAnalyzer.OrbType>>,
                 answer: TeacherAnswer<List<OrbAnnotation>>) {
        require(answer.value.size == 30 && board.width % 6 == 0 && board.height % 5 == 0)
        check(list().size + 30 <= 1200) { "樣本已達 1200 筆，請先刪除不需要的樣本" }
        val size = board.width / 6
        require(board.height / 5 == size)
        val written = mutableListOf<String>()
        try {
            for (r in 0..4) for (c in 0..5) {
                val tile = Bitmap.createBitmap(board, c * size, r * size, size, size)
                try {
                    val id = UUID.randomUUID().toString()
                    written.add(id)
                    image(id).outputStream().use { check(tile.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    save(RecognitionReviewSample(id, game, r, c, System.currentTimeMillis(), answer.provider, answer.model,
                        local[r][c], answer.value[r * 6 + c], descriptor(tile), localFailure = answer.localFailure))
                } finally { tile.recycle() }
            }
        } catch (e: Exception) {
            written.forEach { File(directory, "$it.json").delete(); image(it).delete() }
            throw e
        }
    }

    fun review(sample: RecognitionReviewSample, annotation: OrbAnnotation?) {
        annotation?.let { require(it.type != BoardAnalyzer.OrbType.UNKNOWN && !it.uncertain) { "請先確認屬性與標記" } }
        save(sample.copy(review = if (annotation == null) SampleReview.REJECTED else SampleReview.CONFIRMED,
            confirmed = annotation, reviewedAt = System.currentTimeMillis()))
    }

    fun delete(sample: RecognitionReviewSample) {
        File(directory, "${sample.id}.json").delete()
        image(sample.id).delete()
        VisionTeacherSettings(context).prefs.edit().putLong("revision", System.nanoTime()).apply()
    }

    companion object {
        internal fun descriptor(bitmap: Bitmap): FloatArray {
            val scaled = Bitmap.createScaledBitmap(bitmap, 16, 16, true)
            try {
                val pixels = IntArray(256)
                scaled.getPixels(pixels, 0, 16, 0, 0, 16, 16)
                return FloatArray(768) { i -> ((pixels[i / 3] shr (16 - (i % 3) * 8)) and 255) / 255f }
            } finally { if (scaled !== bitmap) scaled.recycle() }
        }
    }
}
