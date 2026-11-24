package com.stevedaydream.autodora

import org.junit.Assert.*
import org.junit.Test

class PathPreviewTest {
    private val board = BoardSimulatorTest.cascadeBoard()
    private val geometry = BoardGeometry(20f, 300f, 100f)

    private fun candidates(n: Int = 5) = BoardAnalyzer().calculateOptimalCandidates(board, false, 0, 0, 8, 60, maxCandidates = n)
    private fun loaded(list: List<PathCandidate> = candidates()) = PathPreviewController().apply {
        load(list, PathReplay.fingerprint(board), geometry.calibrationKey)
    }

    @Test fun loadDedupesCapsAndSelectsFirst() {
        val list = candidates()
        val controller = PathPreviewController(maxCandidates = 3)
        controller.load(list + list, PathReplay.fingerprint(board), geometry.calibrationKey)
        assertEquals(minOf(3, list.size), controller.state.candidates.size)
        assertEquals(0, controller.state.selectedIndex)
        assertEquals(list.first(), controller.state.selected)
        // 不同盤面的候選不可混入
        val other = PathPreviewController()
        other.load(list, "other-board", geometry.calibrationKey)
        assertTrue(other.state.isEmpty)
        assertEquals("無可用路線", other.state.label)
    }

    @Test fun indexSwitchingDisablesAtBothEnds() {
        val controller = loaded()
        val n = controller.state.candidates.size
        assertTrue(n >= 2)
        assertFalse(controller.state.hasPrevious)
        controller.previous()
        assertEquals(0, controller.state.selectedIndex)
        repeat(n + 3) { controller.next() }
        assertEquals(n - 1, controller.state.selectedIndex)
        assertFalse(controller.state.hasNext)
        assertTrue(controller.state.hasPrevious)
        assertEquals("路線 $n / $n", controller.state.label)
        controller.select(99)
        assertEquals(n - 1, controller.state.selectedIndex)
    }

    @Test fun playbackIsPureStateAndResetsOnSwitch() {
        val controller = loaded()
        var notifications = 0
        controller.listener = { notifications++ }
        controller.play()
        assertTrue(controller.state.playing)
        assertEquals(0f, controller.state.progress)
        controller.setProgress(0.4f)
        controller.next()
        assertTrue("切換時持續播放", controller.state.playing)
        assertEquals("切換後從頭播放", 0f, controller.state.progress)
        controller.setProgress(0.5f)
        controller.pause()
        assertFalse(controller.state.playing)
        assertEquals(0.5f, controller.state.progress)
        controller.resume()
        assertTrue(controller.state.playing)
        controller.setProgress(1f)
        assertFalse("播完自動停止", controller.state.playing)
        controller.toggleVisible()
        assertFalse(controller.state.visible)
        assertTrue(notifications >= 8)
    }

    @Test fun invalidationClearsCandidates() {
        val controller = loaded()
        controller.invalidate("新截圖")
        assertTrue(controller.state.isEmpty)
        assertNull(controller.state.selected)
        assertNull(controller.state.boardFingerprint)
        assertEquals("新截圖", controller.state.message)
        assertTrue(controller.validateForExecution(geometry.calibrationKey, null) is ExecutionCheck.Rejected)
    }

    @Test fun executionRequiresSameCalibrationBoardAndVerifiedShapes() {
        val fingerprint = PathReplay.fingerprint(board)
        val ok = loaded()
        ok.next()
        val ready = ok.validateForExecution(geometry.calibrationKey, fingerprint)
        assertTrue(ready is ExecutionCheck.Ready)
        assertEquals("執行的是目前選中的路線", ok.state.selected, (ready as ExecutionCheck.Ready).candidate)

        val recalibrated = loaded()
        assertTrue(recalibrated.validateForExecution(BoardGeometry(20f, 301f, 100f).calibrationKey, fingerprint) is ExecutionCheck.Rejected)
        assertTrue("校正改變後預覽失效", recalibrated.state.isEmpty)

        val boardChanged = loaded()
        val changed = PathReplay.fingerprint(PathReplay.replay(board, listOf(0, 1)))
        assertTrue(boardChanged.validateForExecution(geometry.calibrationKey, changed) is ExecutionCheck.Rejected)
        assertTrue(boardChanged.state.isEmpty)

        val goals = listOf(ShapeGoal(ShapePattern.T, BoardAnalyzer.OrbType.DARK))
        val unverified = CandidateFactory.build(board, listOf(24, 25), false, ShapeVerifier(goals), CandidateSource.BEAM)!!
        val shapeController = loaded(listOf(unverified))
        val rejected = shapeController.validateForExecution(geometry.calibrationKey, fingerprint)
        assertTrue(rejected is ExecutionCheck.Rejected)
        assertTrue((rejected as ExecutionCheck.Rejected).reason.contains("形狀"))
    }

    @Test fun geometryOffsetsRepeatedEdgesWithoutTouchingGesturePoints() {
        // 0→1→0? 不允許立即往返；改用繞圈：0→1→7→6→0→1 重複走 0-1 邊
        val cells = listOf(0, 1, 7, 6, 0, 1)
        val candidate = CandidateFactory.build(board, cells, false, null, CandidateSource.BEAM)!!
        val model = PathGeometryBuilder.build(candidate, geometry, lateralPx = 10f)
        assertEquals(5, model.segments.size)
        val first = model.segments[0]; val repeat = model.segments[4]
        assertEquals(-5f, first.lateralOffset, 1e-4f)
        assertEquals(5f, repeat.lateralOffset, 1e-4f)
        assertNotEquals(first.y1, repeat.y1)
        assertEquals(0f, model.segments[1].lateralOffset, 1e-4f)
        // raw 端點 = 格心；手勢座標仍只由格位推導
        val gesture = geometry.toGesturePoints(cells)
        for ((i, s) in model.segments.withIndex()) {
            assertEquals(gesture[i].first, s.rawX1, 1e-4f); assertEquals(gesture[i].second, s.rawY1, 1e-4f)
            assertEquals(gesture[i + 1].first, s.rawX2, 1e-4f); assertEquals(gesture[i + 1].second, s.rawY2, 1e-4f)
        }
        assertEquals(gesture.first(), model.startX to model.startY)
        assertEquals(gesture.last(), model.endX to model.endY)
        // 步序標籤：每格一個標籤（不互相覆蓋），但每一步都有編號；重複經過的格子合併成「1,5」
        assertEquals(model.stepLabels.size, model.stepLabels.map { it.x to it.y }.toSet().size)
        assertEquals((1..5).toList(), model.stepLabels.flatMap { it.steps }.sorted())
        val revisited = model.stepLabels.first { it.steps == listOf(1, 5) }
        assertEquals("1,5", revisited.text)
        // 預演：手指走到哪一步，標籤才顯示到哪一步
        assertNull(revisited.textAt(0))
        assertEquals("1", revisited.textAt(1))
        assertEquals("1", revisited.textAt(4))
        assertEquals("1,5", revisited.textAt(5))
        val backToStart = model.stepLabels.first { it.steps == listOf(4) }
        assertNull(backToStart.textAt(3))
        assertEquals("4", backToStart.textAt(4))
    }

    @Test fun arrowsPointAlongMovementAndThinOutOnLongPaths() {
        val cells = listOf(24, 25, 26, 20, 14)  // 右、右、上、上
        val candidate = CandidateFactory.build(board, cells, false, null, CandidateSource.BEAM)!!
        val model = PathGeometryBuilder.build(candidate, geometry, arrowSize = 20f)
        assertEquals(4, model.arrows.size)
        val right = model.arrows[0]
        assertTrue("向右箭頭尖端在右", right.tipX > right.leftX && right.tipX > right.rightX)
        assertEquals(right.leftY, -right.rightY + 2 * model.segments[0].y1, 1e-3f)
        val up = model.arrows[3]
        assertTrue("向上箭頭尖端在上", up.tipY < up.leftY && up.tipY < up.rightY)

        val long = listOf(0, 1, 2, 3, 4, 5, 11, 10, 9, 8, 7, 6, 12, 13, 14, 15, 16, 17, 23, 22, 21, 20, 19, 18, 24, 25, 26, 27, 28, 29)
        val longCandidate = CandidateFactory.build(board, long, false, null, CandidateSource.BEAM)!!
        val dense = PathGeometryBuilder.build(longCandidate, geometry, maxArrows = 10)
        assertTrue(dense.arrows.size <= 11)
        assertEquals("最後一段一定有箭頭", long.size - 2, dense.arrows.last().segmentIndex)
        assertEquals(dense.arrows.size, dense.arrows.map { it.segmentIndex }.toSet().size)
    }

    @Test fun pointAtFollowsProgress() {
        val cells = listOf(24, 25, 26)
        val model = PathGeometryBuilder.build(CandidateFactory.build(board, cells, false, null, CandidateSource.BEAM)!!, geometry)
        assertEquals(model.startX to model.startY, model.pointAt(0f))
        assertEquals(model.endX to model.endY, model.pointAt(1f))
        assertEquals(geometry.centerX(25), model.pointAt(0.5f).first, 1e-3f)
        assertEquals(0, model.visibleSegments(0.1f))
        assertEquals(1, model.visibleSegments(0.6f))
        assertEquals(2, model.visibleSegments(1f))
    }
}
