package com.stevedaydream.autodora

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Macro(
    val id: String,
    val name: String,
    val actions: List<MacroAction>,
    val createdAt: Long
)

object MacroStorage {

    private fun dir(ctx: Context): File = File(ctx.filesDir, "macros").also { it.mkdirs() }
    private fun file(ctx: Context, id: String): File = File(dir(ctx), "macro_$id.json")

    fun listAll(ctx: Context): List<Macro> =
        dir(ctx).listFiles { f -> f.name.startsWith("macro_") && f.name.endsWith(".json") }
            ?.mapNotNull { f ->
                runCatching { load(ctx, f.nameWithoutExtension.removePrefix("macro_")) }.getOrNull()
            }
            ?.filterNotNull()
            ?.sortedBy { it.createdAt }
            ?: emptyList()

    fun save(ctx: Context, macro: Macro) {
        val obj = JSONObject().apply {
            put("id", macro.id)
            put("name", macro.name)
            put("createdAt", macro.createdAt)
            put("actions", JSONArray().also { arr -> macro.actions.forEach { arr.put(it.toJson()) } })
        }
        file(ctx, macro.id).writeText(obj.toString())
    }

    fun delete(ctx: Context, id: String) {
        file(ctx, id).delete()
    }

    fun load(ctx: Context, id: String): Macro? {
        val f = file(ctx, id)
        if (!f.exists()) return null
        return runCatching {
            val obj = JSONObject(f.readText())
            val actArr = obj.getJSONArray("actions")
            Macro(
                id = obj.getString("id"),
                name = obj.getString("name"),
                createdAt = obj.getLong("createdAt"),
                actions = (0 until actArr.length()).map { MacroAction.fromJson(actArr.getJSONObject(it)) }
            )
        }.getOrNull()
    }

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(8)
}
