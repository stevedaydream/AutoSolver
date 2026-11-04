package com.stevedaydream.autodora

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ComboStorage {

    private fun dir(ctx: Context): File = File(ctx.filesDir, "macro_combos").also { it.mkdirs() }
    private fun file(ctx: Context, id: String): File = File(dir(ctx), "combo_$id.json")

    fun listAll(ctx: Context): List<MacroCombo> =
        dir(ctx).listFiles { f -> f.name.startsWith("combo_") && f.name.endsWith(".json") }
            ?.mapNotNull { f -> load(ctx, f.nameWithoutExtension.removePrefix("combo_")) }
            ?.sortedBy { it.createdAt }
            ?: emptyList()

    fun toJson(combo: MacroCombo): JSONObject = JSONObject().apply {
        put("id", combo.id)
        put("name", combo.name)
        put("createdAt", combo.createdAt)
        put("steps", JSONArray().also { arr ->
            combo.steps.forEach { s -> arr.put(JSONObject().put("macroId", s.macroId).put("repeat", s.repeat)) }
        })
    }

    fun fromJson(obj: JSONObject): MacroCombo {
        val arr = obj.optJSONArray("steps") ?: JSONArray()
        val steps = (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            ComboStep(s.optString("macroId", ""), s.optInt("repeat", 1))
        }
        return MacroCombo(
            id = obj.getString("id"),
            name = obj.getString("name"),
            steps = ComboPlan.sanitize(steps),
            createdAt = obj.optLong("createdAt", 0L)
        )
    }

    fun save(ctx: Context, combo: MacroCombo) {
        val target = android.util.AtomicFile(file(ctx, combo.id))
        val stream = target.startWrite()
        try {
            stream.write(toJson(combo).toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
        } catch (e: Exception) {
            target.failWrite(stream)
            throw e
        }
    }

    fun load(ctx: Context, id: String): MacroCombo? {
        val f = file(ctx, id)
        if (!f.exists()) return null
        return runCatching { fromJson(JSONObject(f.readText())) }.getOrNull()
    }

    fun delete(ctx: Context, id: String) { file(ctx, id).delete() }
}
