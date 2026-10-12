package com.stevedaydream.autodora

import java.util.concurrent.CancellationException

internal data class TeacherAnswer<T>(val value: T, val provider: String, val localFailure: String? = null, val model: String = "")

internal object TeacherFallback {
    fun <T> run(local: () -> T, cloud: (() -> T)?, usable: (T) -> Boolean): TeacherAnswer<T> {
        val failure = try {
            val value = local()
            if (usable(value) || cloud == null) return TeacherAnswer(value, "QWEN")
            "本機結果含未知或不確定格"
        } catch (e: Exception) {
            if (e is CancellationException || e is InterruptedException) throw e
            if (cloud == null) throw e
            e.javaClass.simpleName
        }
        return try { TeacherAnswer(requireNotNull(cloud).invoke(), "GEMINI", failure) }
        catch (e: Exception) {
            if (e is CancellationException || e is InterruptedException) throw e
            throw IllegalStateException("本機：$failure；Gemini：${e.message ?: e.javaClass.simpleName}", e)
        }
    }
}
