package com.hatchslide.core.render

import com.hatchslide.core.game.Direction
import com.hatchslide.core.game.GameState
import com.hatchslide.core.game.LevelCodec
import com.hatchslide.core.game.LevelFile
import com.hatchslide.core.game.Simulator
import com.hatchslide.core.render.board.BoardEffects
import com.hatchslide.core.render.board.MoveTimeline
import com.hatchslide.core.render.board.PiecePose
import com.hatchslide.core.render.camera.CameraAnimator
import com.hatchslide.core.render.camera.CameraRig
import com.hatchslide.core.render.camera.FreeViewport
import com.hatchslide.core.render.fx.ParticlePool
import com.hatchslide.core.render.quality.FrameBudgetMonitor
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-frame path allocates nothing (spec section 11): runs the pure-Kotlin parts of a frame many
 * times and checks the thread's allocated bytes. Filament calls are covered by the device checks.
 */
class FrameAllocationTest {
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    /** A frame body taking a primitive index, so the harness itself never boxes. */
    private fun interface Frame {
        fun run(frame: Int)
    }

    private fun allocatedBy(frames: Int, frame: Frame): Long {
        // Warm up so class loading and lazy initialisation are not counted.
        for (i in 0 until frames) frame.run(i)
        val id = Thread.currentThread().id
        val before = threads.getThreadAllocatedBytes(id)
        for (i in 0 until frames) frame.run(i)
        return threads.getThreadAllocatedBytes(id) - before
    }

    private val level = LevelCodec.parse(
        LevelFile(
            "t",
            1,
            1,
            "T",
            listOf("~~~~~~", "......", "T.w...", "....T.", "..#...", "......"),
            par = 1,
            gullMoves = 20,
            seed = 3,
        ),
    )

    @Test
    fun particlesBoardEffectsAndTimelineAllocateNothingPerFrame() {
        val pool = ParticlePool(1024)
        val effects = BoardEffects(pool).also {
            it.begin(WorldTheme.VOLCANIC_ISLE, level.board, FloatArray(level.board.cellCount), slots = 4)
        }
        val out = ByteBuffer.allocateDirect(pool.capacity * 4 * ParticlePool.STRIDE).order(ByteOrder.nativeOrder())
        val left = floatArrayOf(-1f, 0f, 0f)
        val up = floatArrayOf(0f, 1f, 0f)
        val outcome = Simulator(level).apply(GameState.initial(level), Direction.UP)
        val timeline = MoveTimeline(outcome.result, level.board)
        val pose = PiecePose()
        val tracks = outcome.result.tracks
        val bytes = allocatedBy(2000) { frame ->
            val time = (frame % 120) / 60f
            for (i in 0 until tracks.size) {
                val id = tracks[i].pieceId
                timeline.pose(id, time, pose)
                effects.piece(id, pose.x, pose.y, pose.x - 2.5f, pose.y - 2.5f, pose.moving, hatchling = true)
            }
            if (frame % 30 == 0) effects.stop(0f, 0f, cell = 7)
            effects.update()
            pool.update(1f / 60f)
            pool.write(out, left, up)
        }
        assertTrue("allocated $bytes bytes over 2000 frames", bytes < 1024)
    }

    @Test
    fun cameraAndFrameMonitorAllocateNothingPerFrame() {
        val camera = CameraAnimator()
        camera.setRest(CameraRig().frame(3f, 3f, 0.6f, FreeViewport(1080, 2400, top = 200, bottom = 300)))
        camera.startDolly(20f)
        val monitor = FrameBudgetMonitor()
        val bytes = allocatedBy(5000) { frame ->
            if (frame % 200 == 0) camera.shake()
            camera.update(1f / 60f)
            monitor.onFrame(if (frame % 7 == 0) 33_000_000L else 16_666_667L, 16_666_667L)
        }
        assertTrue("allocated $bytes bytes over 5000 frames", bytes < 1024)
    }
}
