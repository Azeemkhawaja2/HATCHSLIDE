package com.hatchslide.core.render

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.SurfaceOrientation
import com.google.android.filament.VertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * CPU-side triangle mesh: positions and normals per vertex, optional UVs and ambient occlusion (one
 * value per vertex, uploaded as vertex colour), 16-bit indices.
 */
class MeshData(
    val positions: FloatArray,
    val normals: FloatArray,
    val uvs: FloatArray,
    val indices: ShortArray,
    val occlusion: FloatArray? = null,
) {
    val vertexCount: Int get() = positions.size / 3

    fun bounds(): Box {
        val min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val max = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in 0 until vertexCount) {
            for (a in 0 until 3) {
                min[a] = minOf(min[a], positions[i * 3 + a])
                max[a] = maxOf(max[a], positions[i * 3 + a])
            }
        }
        return Box(min, max)
    }
}

/** Builds [MeshData] incrementally into growable primitive arrays. */
class MeshBuilder(private val withUvs: Boolean = true) {
    private var positions = FloatArray(1024)
    private var normals = FloatArray(1024)
    private var uvs = FloatArray(if (withUvs) 1024 else 0)
    private var occlusion = FloatArray(512)
    private var indices = ShortArray(1024)
    var vertexCount = 0
        private set
    private var indexCount = 0

    fun vertex(px: Float, py: Float, pz: Float, nx: Float, ny: Float, nz: Float, u: Float = 0f, v: Float = 0f, ao: Float = 1f): Int {
        require(vertexCount < 65535) { "mesh exceeds 16-bit indices" }
        if (vertexCount * 3 + 3 > positions.size) {
            positions = positions.copyOf(positions.size * 2)
            normals = normals.copyOf(normals.size * 2)
            occlusion = occlusion.copyOf(occlusion.size * 2)
            if (withUvs) uvs = uvs.copyOf(uvs.size * 2)
        }
        val p = vertexCount * 3
        positions[p] = px
        positions[p + 1] = py
        positions[p + 2] = pz
        normals[p] = nx
        normals[p + 1] = ny
        normals[p + 2] = nz
        if (withUvs) {
            uvs[vertexCount * 2] = u
            uvs[vertexCount * 2 + 1] = v
        }
        occlusion[vertexCount] = ao
        return vertexCount++
    }

    fun triangle(a: Int, b: Int, c: Int) {
        if (indexCount + 3 > indices.size) indices = indices.copyOf(indices.size * 2)
        indices[indexCount++] = a.toShort()
        indices[indexCount++] = b.toShort()
        indices[indexCount++] = c.toShort()
    }

    /** Two triangles a-b-c and a-c-d (counter-clockwise when seen from the front). */
    fun quad(a: Int, b: Int, c: Int, d: Int) {
        triangle(a, b, c)
        triangle(a, c, d)
    }

    fun build() = MeshData(
        positions.copyOf(vertexCount * 3),
        normals.copyOf(vertexCount * 3),
        if (withUvs) uvs.copyOf(vertexCount * 2) else FloatArray(0),
        indices.copyOf(indexCount),
        occlusion.copyOf(vertexCount),
    )
}

/** GPU buffers for one mesh. */
class GpuMesh(val vertexBuffer: VertexBuffer, val indexBuffer: IndexBuffer, val bounds: Box, val indexCount: Int)

/**
 * Uploads meshes to Filament. Tangent frames come from [SurfaceOrientation], as Filament expects
 * a TANGENTS quaternion attribute for lit materials.
 */
class MeshFactory(private val engine: Engine) {
    private val cache = HashMap<String, GpuMesh>()
    private val owned = ArrayList<GpuMesh>()
    private val entities = ArrayList<Int>()

    fun cached(key: String, build: () -> MeshData): GpuMesh = cache.getOrPut(key) { upload(build()) }

    fun upload(data: MeshData): GpuMesh {
        val vertexCount = data.vertexCount
        val hasUvs = data.uvs.isNotEmpty()
        val positions = floatBuffer(data.positions)
        val normals = floatBuffer(data.normals)
        val uvs = if (hasUvs) floatBuffer(data.uvs) else null
        val indices = shortBuffer(data.indices)
        val orientation = SurfaceOrientation.Builder()
            .vertexCount(vertexCount)
            .normals(normals)
            .apply {
                if (uvs != null) {
                    uvs(uvs)
                    positions(positions)
                    triangleCount(data.indices.size / 3)
                    triangles_uint16(indices)
                }
            }
            .build()
        val quats = ByteBuffer.allocateDirect(vertexCount * 8).order(ByteOrder.nativeOrder())
        orientation.getQuatsAsShort(quats)
        orientation.destroy()
        positions.rewind()
        uvs?.rewind()
        indices.rewind()
        quats.rewind()
        val colors = ByteBuffer.allocateDirect(vertexCount * 4).order(ByteOrder.nativeOrder())
        val ao = data.occlusion
        for (i in 0 until vertexCount) {
            val v = ((ao?.get(i) ?: 1f).coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
            colors.put(v).put(v).put(v).put(-1)
        }
        colors.flip()

        val builder = VertexBuffer.Builder()
            .bufferCount(if (hasUvs) 4 else 3)
            .vertexCount(vertexCount)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 1, VertexBuffer.AttributeType.SHORT4, 0, 8)
            .normalized(VertexBuffer.VertexAttribute.TANGENTS)
            .attribute(VertexBuffer.VertexAttribute.COLOR, 2, VertexBuffer.AttributeType.UBYTE4, 0, 4)
            .normalized(VertexBuffer.VertexAttribute.COLOR)
        if (hasUvs) builder.attribute(VertexBuffer.VertexAttribute.UV0, 3, VertexBuffer.AttributeType.FLOAT2, 0, 8)
        val vertexBuffer = builder.build(engine)
        vertexBuffer.setBufferAt(engine, 0, positions)
        vertexBuffer.setBufferAt(engine, 1, quats)
        vertexBuffer.setBufferAt(engine, 2, colors)
        if (uvs != null) vertexBuffer.setBufferAt(engine, 3, uvs)
        val indexBuffer = IndexBuffer.Builder()
            .indexCount(data.indices.size)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        indexBuffer.setBuffer(engine, indices)
        return GpuMesh(vertexBuffer, indexBuffer, data.bounds(), data.indices.size).also { owned += it }
    }

    /** Destroys a mesh uploaded with [upload] once nothing renders it any more. */
    fun release(mesh: GpuMesh) {
        if (!owned.remove(mesh)) return
        cache.values.remove(mesh)
        engine.destroyIndexBuffer(mesh.indexBuffer)
        engine.destroyVertexBuffer(mesh.vertexBuffer)
    }

    /** Creates an entity with a transform and one renderable primitive. */
    fun renderable(
        mesh: GpuMesh,
        material: MaterialInstance,
        castShadows: Boolean = true,
        receiveShadows: Boolean = true,
        priority: Int = 4,
    ): Int {
        val entity = EntityManager.get().create()
        RenderableManager.Builder(1)
            .boundingBox(mesh.bounds)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, mesh.vertexBuffer, mesh.indexBuffer, 0, mesh.indexCount)
            .material(0, material)
            .castShadows(castShadows)
            .receiveShadows(receiveShadows)
            .priority(priority)
            .build(engine, entity)
        engine.transformManager.create(entity)
        entities += entity
        return entity
    }

    /** An entity with only a transform, used as a parent for groups. */
    fun group(): Int {
        val entity = EntityManager.get().create()
        engine.transformManager.create(entity)
        entities += entity
        return entity
    }

    fun destroyEntity(entity: Int) {
        if (!entities.remove(entity)) return
        engine.renderableManager.destroy(entity)
        engine.transformManager.destroy(entity)
        EntityManager.get().destroy(entity)
    }

    fun destroy() {
        entities.asReversed().forEach {
            engine.renderableManager.destroy(it)
            engine.transformManager.destroy(it)
            EntityManager.get().destroy(it)
        }
        entities.clear()
        owned.asReversed().forEach {
            engine.destroyIndexBuffer(it.indexBuffer)
            engine.destroyVertexBuffer(it.vertexBuffer)
        }
        owned.clear()
        cache.clear()
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(values.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(values)
            flip()
        }

    private fun shortBuffer(values: ShortArray): ShortBuffer = ByteBuffer.allocateDirect(values.size * 2)
        .order(ByteOrder.nativeOrder()).asShortBuffer().apply {
            put(values)
            flip()
        }
}
