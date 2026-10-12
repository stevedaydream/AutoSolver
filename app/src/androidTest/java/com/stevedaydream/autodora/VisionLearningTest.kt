package com.stevedaydream.autodora

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VisionLearningTest {
    private val annotation = OrbAnnotation(BoardAnalyzer.OrbType.FIRE, setOf(OrbMark.ENHANCED, OrbMark.WEATHER, OrbMark.SHIELD, OrbMark.MUSIC), setOf("龍"))

    private fun response() = JSONObject().put("cells", JSONArray().apply {
        for (i in 0..29) put(annotation.toJson().put("row", i / 6).put("col", i % 6))
    })

    @Test fun protocolAcceptsMultipleMarksAndRejectsIncompleteDuplicateOrInvalidCells() {
        val expected = List(30) { annotation }
        assertEquals(expected, TeacherResponse.parse("```json\n${response()}\n```"))
        val incomplete = response().apply { getJSONArray("cells").remove(0) }
        assertThrows(IllegalArgumentException::class.java) { TeacherResponse.parse(incomplete.toString()) }
        val duplicate = response().apply { getJSONArray("cells").getJSONObject(1).put("col", 0) }
        assertThrows(IllegalArgumentException::class.java) { TeacherResponse.parse(duplicate.toString()) }
        val fractional = response().apply { getJSONArray("cells").getJSONObject(0).put("row", 0.5) }
        assertThrows(IllegalArgumentException::class.java) { TeacherResponse.parse(fractional.toString()) }
        val invalid = response().apply { getJSONArray("cells").getJSONObject(0).put("marks", JSONArray().put("INVENTED")) }
        assertThrows(IllegalArgumentException::class.java) { TeacherResponse.parse(invalid.toString()) }
    }

    @Test fun pendingSamplesCannotClassifyUntilConfirmedAndRejectionRevokesThem() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = RecognitionSampleStore(context)
        val before = store.list().map { it.id }.toSet()
        val bitmap = Bitmap.createBitmap(120, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        try {
            store.addBatch(bitmap, GameProfile.TOS, Array(5) { Array(6) { BoardAnalyzer.OrbType.WATER } }, TeacherAnswer(List(30) { annotation }, "TEST", model = "fixture"))
            val pending = store.list().filter { it.id !in before }
            assertEquals(30, pending.size)
            fun active() = store.list().filter { it.id !in before && it.review == SampleReview.CONFIRMED }
                .map { VisualSampleMatcher.Entry(it.descriptor, it.confirmed!!) }
            assertNull(VisualSampleMatcher.match(pending[0].descriptor, active()))
            assertTrue(store.image(pending[0].id).exists())
            store.review(pending[0], annotation)
            assertEquals(annotation, VisualSampleMatcher.match(pending[0].descriptor, active()))
            assertEquals(annotation, store.list().single { it.id == pending[0].id }.confirmed)
            store.review(pending[0], null)
            assertNull(VisualSampleMatcher.match(pending[0].descriptor, active()))
            assertThrows(IllegalArgumentException::class.java) { store.image("../../invalid") }
        } finally {
            store.list().filter { it.id !in before }.forEach { store.delete(it) }
            bitmap.recycle()
        }
    }

    @Test fun secretsAreEncryptedAndCanBeCleared() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = VisionTeacherSettings(context)
        try {
            settings.setSecret("testSecret", "fixture-value")
            assertEquals("fixture-value", settings.secret("testSecret"))
            assertFalse(settings.prefs.getString("testSecret", "")!!.contains("fixture-value"))
            settings.setSecret("testSecret", "")
            assertEquals("", settings.secret("testSecret"))
        } finally { settings.setSecret("testSecret", "") }
    }

    @Test fun manualUiFixture() {
        val arguments = InstrumentationRegistry.getArguments()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = RecognitionSampleStore(context)
        if (arguments.getString("fixture") == "seed") {
            val bitmap = Bitmap.createBitmap(600, 500, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            try {
                store.addBatch(bitmap, GameProfile.TOS, Array(5) { Array(6) { BoardAnalyzer.OrbType.WATER } }, TeacherAnswer(List(30) { annotation }, "UI_FIXTURE", model = "合成驗證圖片"))
            } finally { bitmap.recycle() }
        } else if (arguments.getString("fixture") == "cleanup") {
            store.list().filter { it.provider == "UI_FIXTURE" }.forEach { store.delete(it) }
        }
    }

    @Test fun manualLocalEndpointProbe() {
        val endpoint = InstrumentationRegistry.getArguments().getString("visionEndpoint") ?: return
        val settings = VisionTeacherSettings(InstrumentationRegistry.getInstrumentation().targetContext)
        val oldEndpoint = settings.endpoint
        val oldModel = settings.model
        val oldCloud = settings.cloudEnabled
        val bitmap = Bitmap.createBitmap(600, 500, Bitmap.Config.ARGB_8888)
        try {
            settings.endpoint = endpoint; settings.model = ""; settings.cloudEnabled = false
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint()
            val colors = intArrayOf(Color.BLUE, Color.RED, Color.GREEN, Color.YELLOW, Color.MAGENTA, Color.rgb(255, 128, 180))
            for (r in 0..4) for (c in 0..5) {
                paint.color = colors[(r + c) % 6]
                canvas.drawRect(c * 100f, r * 100f, (c + 1) * 100f, (r + 1) * 100f, paint)
            }
            val jpeg = java.io.ByteArrayOutputStream().use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it); it.toByteArray()
            }
            val answer = VisionTeacherClient(settings).recognize(jpeg, GameProfile.TOS)
            assertEquals("QWEN", answer.provider)
            assertEquals(30, answer.value.size)
            assertTrue(answer.model.isNotBlank())
        } finally {
            settings.endpoint = oldEndpoint; settings.model = oldModel; settings.cloudEnabled = oldCloud
            bitmap.recycle()
        }
    }
}
