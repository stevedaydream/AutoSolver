package com.stevedaydream.autodora

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class RecognitionLearningEngine(private val context: Context) {
    private val settings = VisionTeacherSettings(context)
    private val store = RecognitionSampleStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = AtomicBoolean(false)
    private var lastAttempt = 0L
    private var lastHash = ""
    private var revision = Long.MIN_VALUE
    private var entries = emptyList<VisualSampleMatcher.Entry>()
    private var cachedGame: GameProfile? = null

    @Synchronized fun match(tile: Bitmap): OrbAnnotation? {
        val game = settings.game
        val currentRevision = settings.prefs.getLong("revision", 0)
        if (currentRevision != revision || cachedGame != game) {
            entries = store.list().filter { it.game == game && it.review == SampleReview.CONFIRMED && it.confirmed != null }
                .map { VisualSampleMatcher.Entry(it.descriptor, it.confirmed!!) }
            revision = currentRevision; cachedGame = game
        }
        if (entries.isEmpty()) return null
        return VisualSampleMatcher.match(RecognitionSampleStore.descriptor(tile), entries)
    }

    fun offer(screenshot: Bitmap, x: Int, y: Int, size: Int, local: Array<Array<BoardAnalyzer.OrbType>>) {
        if (!settings.enabled || size <= 0 || x < 0 || y < 0 || x + size * 6 > screenshot.width || y + size * 5 > screenshot.height) return
        if (!busy.compareAndSet(false, true)) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastAttempt < 60_000 && lastAttempt != 0L) { busy.set(false); return }
        val cropped = try {
            val region = Bitmap.createBitmap(screenshot, x, y, size * 6, size * 5)
            if (region === screenshot) region.copy(Bitmap.Config.ARGB_8888, false) else region
        }
            catch (_: Exception) { busy.set(false); return }
        val game = settings.game
        val localCopy = local.map { it.clone() }.toTypedArray()
        lastAttempt = now
        scope.launch {
            try {
                val jpeg = ByteArrayOutputStream().use { stream ->
                    check(cropped.compress(Bitmap.CompressFormat.JPEG, 90, stream)); stream.toByteArray()
                }
                val hash = MessageDigest.getInstance("SHA-256").digest(jpeg).joinToString("") { "%02x".format(it) }
                if (hash != lastHash) {
                    settings.prefs.edit().putString("lastStatus", "正在辨識並收錄待審樣本…").apply()
                    val answer = VisionTeacherClient(settings).recognize(jpeg, game)
                    store.addBatch(cropped, game, localCopy, answer)
                    lastHash = hash
                    settings.prefs.edit().putString("lastStatus", "已收錄 30 格待審樣本（${answer.provider}）").apply()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                settings.prefs.edit().putString("lastStatus", "收錄失敗：${e.message?.take(120) ?: e.javaClass.simpleName}").apply()
            } finally { cropped.recycle(); busy.set(false) }
        }
    }

    companion object { var instance: RecognitionLearningEngine? = null }
}
