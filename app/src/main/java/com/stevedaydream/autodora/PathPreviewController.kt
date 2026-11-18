package com.stevedaydream.autodora

/** 預覽狀態：不可變，任何改變都產生新物件後通知畫面。 */
data class PathPreviewState(
    val candidates: List<PathCandidate> = emptyList(),
    val selectedIndex: Int = -1,
    val visible: Boolean = true,
    val playing: Boolean = false,
    val progress: Float = 1f,
    val boardFingerprint: String? = null,
    val calibrationKey: String? = null,
    val shapeStatus: ShapeSolveStatus? = null,
    val message: String = ""
) {
    val selected: PathCandidate? get() = candidates.getOrNull(selectedIndex)
    val hasPrevious: Boolean get() = selectedIndex > 0
    val hasNext: Boolean get() = selectedIndex in 0 until candidates.size - 1
    val isEmpty: Boolean get() = candidates.isEmpty()
    val label: String get() = if (candidates.isEmpty()) "無可用路線" else "路線 ${selectedIndex + 1} / ${candidates.size}"
}

sealed class ExecutionCheck {
    data class Ready(val candidate: PathCandidate) : ExecutionCheck()
    data class Rejected(val reason: String) : ExecutionCheck()
}

/**
 * 選路、播放、顯示開關與失效策略（純邏輯，可 JVM 測試）。
 * 預演只改 progress，從不觸發手勢；執行只接受目前選中、且通過重新驗證的候選。
 */
class PathPreviewController(private val maxCandidates: Int = 5) {
    var state = PathPreviewState()
        private set
    var listener: ((PathPreviewState) -> Unit)? = null

    private fun update(next: PathPreviewState) {
        state = next
        listener?.invoke(next)
    }

    fun load(
        candidates: List<PathCandidate>,
        boardFingerprint: String,
        calibrationKey: String,
        shapeStatus: ShapeSolveStatus? = null,
        message: String = ""
    ) {
        val unique = CandidateFactory.dedupe(candidates.filter { it.boardFingerprint == boardFingerprint })
            .take(maxCandidates.coerceAtLeast(1))
        update(PathPreviewState(
            candidates = unique,
            selectedIndex = if (unique.isEmpty()) -1 else 0,
            visible = state.visible,
            boardFingerprint = boardFingerprint,
            calibrationKey = calibrationKey,
            shapeStatus = shapeStatus,
            message = message
        ))
    }

    fun select(index: Int) {
        if (index !in state.candidates.indices || index == state.selectedIndex) return
        // 切換時若正在預演，從頭播放新路線；否則直接顯示完整路線
        update(state.copy(selectedIndex = index, progress = if (state.playing) 0f else 1f))
    }

    fun next() { if (state.hasNext) select(state.selectedIndex + 1) }
    fun previous() { if (state.hasPrevious) select(state.selectedIndex - 1) }

    fun toggleVisible() = update(state.copy(visible = !state.visible, playing = false, progress = 1f))
    fun setVisible(visible: Boolean) { if (state.visible != visible) toggleVisible() }

    fun play() { if (state.selected != null) update(state.copy(playing = true, visible = true, progress = 0f)) }
    fun pause() { if (state.playing) update(state.copy(playing = false)) }
    fun resume() { if (state.selected != null && !state.playing && state.progress < 1f) update(state.copy(playing = true)) }
    fun setProgress(progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        update(state.copy(progress = p, playing = state.playing && p < 1f))
    }

    /** 新截圖、重新校正、取消、手勢結束或離開時呼叫：清空所有候選。 */
    fun invalidate(reason: String = "") {
        if (state.candidates.isEmpty() && state.boardFingerprint == null) return
        update(PathPreviewState(visible = state.visible, message = reason))
    }

    /**
     * 執行前的最後一道檢查。currentFingerprint 為執行前重新辨識的盤面；
     * 任何不一致都拒絕並使預覽失效，避免「看的是 A 路、執行的是 B 路」。
     */
    fun validateForExecution(currentCalibrationKey: String, currentFingerprint: String?): ExecutionCheck {
        val candidate = state.selected ?: return ExecutionCheck.Rejected("沒有選擇路線")
        val reject = { reason: String -> invalidate(reason); ExecutionCheck.Rejected(reason) }
        if (!candidate.executable) {
            return ExecutionCheck.Rejected(if (candidate.shapeRequired > 0 && !candidate.shapeVerified) "指定形狀未達成，不可執行" else "路線無效")
        }
        if (state.calibrationKey != currentCalibrationKey) return reject("盤面校正已變更，請重新計算")
        if (candidate.boardFingerprint != state.boardFingerprint) return reject("候選與盤面不一致")
        if (currentFingerprint != null && currentFingerprint != candidate.boardFingerprint) return reject("盤面已變更，請重新計算")
        if (!PathReplay.isLegal(candidate.pathCells, candidate.is8Way)) return reject("路線不合法")
        return ExecutionCheck.Ready(candidate)
    }
}
