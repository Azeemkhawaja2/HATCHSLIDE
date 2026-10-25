package com.hatchslide.core.render.fx

import com.google.android.filament.Box
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.VertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Draws a [ParticlePool] as one renderable of quads (spec section 6). Every frame the live quads are
 * written into one of three preallocated buffers in turn, so a buffer is never rewritten while
 * Filament may still be uploading it from an earlier frame. The index buffer never changes; only
 * the drawn index count does.
 */
class ParticleSystem(private val engine: Engine, private val scene: Scene, material: MaterialInstance, capacity: Int = CAPACITY) {
    val pool = ParticlePool(capacity)
    private val vertexBuffer: VertexBuffer = VertexBuffer.Builder()
        .bufferCount(1)
        .vertexCount(capacity * 4)
        .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, ParticlePool.STRIDE)
        .attribute(VertexBuffer.VertexAttribute.UV0, 0, VertexBuffer.AttributeType.FLOAT2, 12, ParticlePool.STRIDE)
        .attribute(VertexBuffer.VertexAttribute.COLOR, 0, VertexBuffer.AttributeType.UBYTE4, 20, ParticlePool.STRIDE)
        .normalized(VertexBuffer.VertexAttribute.COLOR)
        .build(engine)
    private val indexBuffer: IndexBuffer = IndexBuffer.Builder()
        .indexCount(capacity * 6)
        .bufferType(IndexBuffer.Builder.IndexType.USHORT)
        .build(engine)
    private val staging = Array(3) { ByteBuffer.allocateDirect(capacity * 4 * ParticlePool.STRIDE).order(ByteOrder.nativeOrder()) }
    private var frame = 0
    private val left = FloatArray(3)
    private val up = FloatArray(3)
    private var visible = false

    val entity: Int = EntityManager.get().create()

    init {
        val indices = ByteBuffer.allocateDirect(capacity * 12).order(ByteOrder.nativeOrder())
        for (q in 0 until capacity) {
            val v = q * 4
            for (k in intArrayOf(0, 1, 2, 0, 2, 3)) indices.putShort((v + k).toShort())
        }
        indices.flip()
        indexBuffer.setBuffer(engine, indices)
        RenderableManager.Builder(1)
            .boundingBox(Box(0f, 0f, 0f, 400f, 100f, 400f))
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertexBuffer, indexBuffer, 0, 6)
            .material(0, material)
            .culling(false)
            .castShadows(false)
            .receiveShadows(false)
            // Drawn after the water and everything else that blends.
            .priority(7)
            .layerMask(0xFF, 0)
            .build(engine, entity)
        scene.addEntity(entity)
    }

    fun clear() = pool.clear()

    fun update(dt: Float, camera: Camera) {
        pool.update(dt)
        val rm = engine.renderableManager
        val instance = rm.getInstance(entity)
        if (pool.count == 0) {
            if (visible) rm.setLayerMask(instance, 0xFF, 0)
            visible = false
            return
        }
        camera.getLeftVector(left)
        camera.getUpVector(up)
        val buffer = staging[frame]
        frame = (frame + 1) % staging.size
        val quads = pool.write(buffer, left, up)
        vertexBuffer.setBufferAt(engine, 0, buffer, 0, quads * 4 * ParticlePool.STRIDE)
        rm.setGeometryAt(instance, 0, RenderableManager.PrimitiveType.TRIANGLES, vertexBuffer, indexBuffer, 0, quads * 6)
        if (!visible) rm.setLayerMask(instance, 0xFF, 1)
        visible = true
    }

    fun destroy() {
        scene.remove(entity)
        engine.renderableManager.destroy(entity)
        EntityManager.get().destroy(entity)
        engine.destroyIndexBuffer(indexBuffer)
        engine.destroyVertexBuffer(vertexBuffer)
    }

    companion object {
        const val CAPACITY = 1024
    }
}
