package com.stevedaydream.autodora

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

class TeacherFallbackTest {
    @Test fun successfulLocalDoesNotCallCloud() {
        val answer = TeacherFallback.run({ "本機" }, { fail("不可呼叫雲端"); "雲端" }, { true })
        assertEquals("QWEN", answer.provider)
        assertEquals("本機", answer.value)
    }

    @Test fun localFailureCallsCloudExactlyOnce() {
        var calls = 0
        val answer = TeacherFallback.run<String>({ throw IOException("連線失敗") }, { calls++; "雲端" }, { true })
        assertEquals(1, calls)
        assertEquals("GEMINI", answer.provider)
        assertEquals("IOException", answer.localFailure)
    }

    @Test fun uncertainLocalFallsBackButCanRemainPendingWithoutCloud() {
        assertEquals("GEMINI", TeacherFallback.run({ false }, { true }, { it }).provider)
        assertFalse(TeacherFallback.run({ false }, null, { it }).value)
    }

    @Test fun cancellationDoesNotCauseACloudRequest() {
        assertThrows(CancellationException::class.java) {
            TeacherFallback.run<String>({ throw CancellationException() }, { fail("取消不可觸發雲端"); "" }, { true })
        }
    }

    @Test fun bothFailuresAreReportedAndNotRetried() {
        var calls = 0
        val error = assertThrows(IllegalStateException::class.java) {
            TeacherFallback.run<String>({ throw IOException() }, { calls++; error("HTTP 429") }, { true })
        }
        assertEquals(1, calls)
        assertTrue(error.message!!.contains("IOException"))
        assertTrue(error.message!!.contains("HTTP 429"))
    }
}
