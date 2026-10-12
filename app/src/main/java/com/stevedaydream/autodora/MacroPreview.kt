package com.stevedaydream.autodora

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.math.BigDecimal
import java.util.UUID
import org.json.JSONObject

data class MacroPreview(val image: String, val width: Int, val height: Int) {
    fun toJson() = JSONObject().put("image", image).put("width", width).put("height", height)
    companion object {
        fun fromJson(obj: JSONObject) = MacroPreview(
            obj.getString("image"), obj.getInt("width"), obj.getInt("height")
        ).also { require(it.width > 0 && it.height > 0) }
    }
}

object MacroImages {
    private fun dir(context: Context) = File(context.filesDir, "macro_images").also { it.mkdirs() }
    fun file(context: Context, name: String): File? =
        if (Regex("[a-f0-9-]+\\.jpg").matches(name)) File(dir(context), name) else null

    fun capture(context: Context): MacroPreview? {
        val bitmap = ScreenCaptureService.instance?.captureSnapshot() ?: return null
        val name = "${UUID.randomUUID()}.jpg"
        return try {
            file(context, name)!!.outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 75, it))
            }
            MacroPreview(name, bitmap.width, bitmap.height)
        } catch (e: Exception) {
            file(context, name)?.delete()
            throw e
        } finally { bitmap.recycle() }
    }

    fun discard(context: Context, names: Collection<String>) {
        val used = MacroStorage.listAll(context).flatMap { it.actions }.mapNotNull { it.preview?.image }.toSet()
        names.filterNot { it in used }.forEach { file(context, it)?.delete() }
    }
}

object MacroTime {
    fun seconds(ms: Long): String = BigDecimal.valueOf(ms, 3).stripTrailingZeros().toPlainString()
    fun milliseconds(value: String, allowZero: Boolean = false): Long? = runCatching {
        val seconds = BigDecimal(value.trim())
        require(seconds.scale() <= 3 && seconds.signum() >= 0)
        val ms = seconds.movePointRight(3).longValueExact()
        require(allowZero || ms > 0)
        ms
    }.getOrNull()
}
