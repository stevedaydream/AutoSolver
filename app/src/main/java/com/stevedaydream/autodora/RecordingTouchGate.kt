package com.stevedaydream.autodora

internal class RecordingTouchGate {
    private var active: Long? = null
    fun down(id: Long): Boolean {
        if (active != null) return false
        active = id
        return true
    }
    fun matches(id: Long) = active == id
    fun up(id: Long): Boolean {
        if (!matches(id)) return false
        active = null
        return true
    }
    fun cancel() { active = null }
}
