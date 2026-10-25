package com.hatchslide.core.render

import com.hatchslide.core.game.CrabSpec
import com.hatchslide.core.game.Direction
import com.hatchslide.core.game.GameState
import com.hatchslide.core.game.LevelCodec
import com.hatchslide.core.game.LevelFile
import com.hatchslide.core.game.MoveEvent
import com.hatchslide.core.game.Simulator
import com.hatchslide.core.render.board.MoveTimeline
import com.hatchslide.core.render.board.PiecePose
import com.hatchslide.core.render.camera.CameraAnimator
import com.hatchslide.core.render.camera.CameraRig
import com.hatchslide.core.render.camera.FreeViewport
import com.hatchslide.core.render.math.PerspectiveProjector
import com.hatchslide.core.render.math.Vec3
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraAndTimelineTest {
    private fun level(vararg rows: String, crabs: List<CrabSpec> = emptyList()) =
        LevelCodec.parse(LevelFile("t", 1, 1, "T", rows.toList(), crabs = crabs, par = 1, gullMoves = 20, seed = 1))

    private fun checkFits(halfW: Float, halfD: Float, viewport: FreeViewport) {
        val rig = CameraRig()
        val pose = rig.frame(halfW, halfD, 0.6f, viewport)
        val projector = PerspectiveProjector()
        projector.configure(pose.eye, pose.target, rig.fovDegrees, viewport.width.toFloat() / viewport.height)
        val out = Vec3()
        val left = -1f + 2f * viewport.left / viewport.width
        val right = 1f - 2f * viewport.right / viewport.width
        val bottom = -1f + 2f * viewport.bottom / viewport.height
        val top = 1f - 2f * viewport.top / viewport.height
        var minX = 9f
        var maxX = -9f
        var minY = 9f
        var maxY = -9f
        for (x in listOf(-halfW, halfW)) {
            for (z in listOf(-halfD, halfD)) {
                for (y in listOf(0f, 0.6f)) {
                    assertTrue(projector.project(x, y, z, out))
                    minX = minOf(minX, out.x + pose.shiftX)
                    maxX = maxOf(maxX, out.x + pose.shiftX)
                    minY = minOf(minY, out.y + pose.shiftY)
                    maxY = maxOf(maxY, out.y + pose.shiftY)
                }
            }
        }
        assertTrue("left edge inside: $minX >= $left", minX >= left - 1e-3f)
        assertTrue("right edge inside", maxX <= right + 1e-3f)
        assertTrue("bottom edge inside", minY >= bottom - 1e-3f)
        assertTrue("top edge inside", maxY <= top + 1e-3f)
        // Tight: the limiting dimension uses (1 - 2 * margin) of the free space.
        val fillX = (maxX - minX) / (right - left)
        val fillY = (maxY - minY) / (top - bottom)
        assertEquals(0.84f, maxOf(fillX, fillY), 0.01f)
        // Centred between the bars.
        assertEquals((left + right) / 2, (minX + maxX) / 2, 1e-3f)
        assertEquals((bottom + top) / 2, (minY + maxY) / 2, 1e-3f)
    }

    @Test
    fun boardFitsPortraitPhoneBetweenHudBars() = checkFits(3.8f, 4.8f, FreeViewport(1080, 2340, top = 260, bottom = 380))

    @Test
    fun boardFitsLandscapeTabletBetweenSideColumns() = checkFits(4.8f, 6.3f, FreeViewport(2560, 1600, left = 420, right = 420))

    @Test
    fun cameraPitchKeepsScreenUpAsBoardUp() {
        val pose = CameraRig().frame(3f, 4f, 0.6f, FreeViewport(1080, 1920))
        // The eye sits behind the near (bottom) edge, so board row 0 (negative z) appears higher on screen.
        assertTrue(pose.eye.z > 0f)
        assertEquals(0f, pose.eye.x, 1e-4f)
        val projector = PerspectiveProjector()
        projector.configure(pose.eye, pose.target, 35f, 1080f / 1920f)
        val far = Vec3()
        val near = Vec3()
        projector.project(0f, 0f, -4f, far)
        projector.project(0f, 0f, 4f, near)
        assertTrue(far.y > near.y)
    }

    @Test
    fun dollyEndsAtTheRestPose() {
        val pose = CameraRig().frame(3f, 4f, 0.6f, FreeViewport(1080, 1920))
        val animator = CameraAnimator().apply { reducedMotion = true }
        animator.setRest(pose)
        animator.startDolly(20f)
        animator.update(0.01f)
        assertTrue(abs(animator.eye.y - pose.eye.y) > 1f)
        repeat(200) { animator.update(0.01f) }
        assertEquals(pose.eye.y, animator.eye.y, 1e-3f)
        assertEquals(pose.eye.z, animator.eye.z, 1e-3f)
        assertFalse(animator.isAnimating)
    }

    @Test
    fun timelineSlidesPiecesTickByTick() {
        val level = level("~....", ".....", "....T")
        val outcome = Simulator(level).apply(GameState.initial(level), Direction.LEFT)
        val tl = MoveTimeline(outcome.result, level.board)
        val pose = PiecePose()
        val id = outcome.result.tracks.single().pieceId
        tl.pose(id, 0f, pose)
        assertEquals(4f, pose.x, 1e-4f)
        assertEquals(MoveTimeline.headingOf(Direction.LEFT), pose.heading, 1e-4f)
        tl.pose(id, tl.slideStart + 2 * tl.tickSeconds, pose)
        assertEquals(2f, pose.x, 1e-3f)
        assertTrue(pose.moving || pose.x == 2f)
        tl.pose(id, tl.slideEnd + 0.01f, pose)
        assertEquals(0f, pose.x, 1e-4f)
        assertTrue(tl.duration >= tl.slideEnd)
        assertEquals(4, outcome.result.ticks)
    }

    @Test
    fun savedHatchlingsDiveAndDisappear() {
        val level = level("~", "T")
        val outcome = Simulator(level).apply(GameState.initial(level), Direction.UP)
        val tl = MoveTimeline(outcome.result, level.board)
        val pose = PiecePose()
        val id = outcome.result.tracks.single().pieceId
        tl.pose(id, tl.duration + 0.1f, pose)
        assertFalse(pose.visible)
        val schedule = tl.schedule()
        assertEquals(schedule.map { it.first }.sorted(), schedule.map { it.first })
        assertTrue(schedule.any { it.second is MoveEvent.Saved })
    }

    @Test
    fun hazardsAnimateAfterTheSlide() {
        val level = level("~....", "c....", "....T", crabs = listOf(CrabSpec(listOf(listOf(0, 1), listOf(1, 1)))))
        val outcome = Simulator(level).apply(GameState.initial(level), Direction.LEFT)
        val tl = MoveTimeline(outcome.result, level.board)
        assertEquals(0f, tl.hazardProgress(tl.slideEnd), 1e-4f)
        assertEquals(1f, tl.hazardProgress(tl.hazardStart + tl.hazardDuration), 1e-4f)
        assertTrue(tl.duration >= tl.hazardStart + tl.hazardDuration)
    }

    @Test
    fun longSlidesIntoBlockersShakeTheCamera() {
        val level = level("~.....", "T....#")
        val outcome = Simulator(level).apply(GameState.initial(level), Direction.RIGHT)
        val tl = MoveTimeline(outcome.result, level.board)
        assertEquals(1, tl.hardStops().size)
    }
}
