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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** CPU-side triangle mesh: positions, normals and UVs per vertex, 16-bit indices. */
class MeshData(val positions: FloatArray, val normals: FloatArray, val uvs: FloatArray, val indices: ShortArray) {
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

/** Builds [MeshData] incrementally. */
class MeshBuilder {
    private val positions = ArrayList<Float>()
    private val normals = ArrayList<Float>()
    private val uvs = ArrayList<Float>()
    private val indices = ArrayList<Short>()
    val vertexCount: Int get() = positions.size / 3

    fun vertex(px: Float, py: Float, pz: Float, nx: Float, ny: Float, nz: Float, u: Float, v: Float): Int {
        positions += px
        positions += py
        positions += pz
        normals += nx
        normals += ny
        normals += nz
        uvs += u
        uvs += v
        return vertexCount - 1
    }

    fun triangle(a: Int, b: Int, c: Int) {
        indices += a.toShort()
        indices += b.toShort()
        indices += c.toShort()
    }

    fun quad(a: Int, b: Int, c: Int, d: Int) {
        triangle(a, b, c)
        triangle(a, c, d)
    }

    fun build() = MeshData(positions.toFloatArray(), normals.toFloatArray(), uvs.toFloatArray(), indices.toShortArray())
}

/** Procedural primitives (used for simple props and as stand-ins until models load). */
object Primitives {
    /** Axis-aligned box centred on the origin. */
    fun box(sx: Float, sy: Float, sz: Float): MeshData {
        val b = MeshBuilder()
        val hx = sx / 2
        val hy = sy / 2
        val hz = sz / 2
        // (normal, u axis, v axis)
        val faces = arrayOf(
            floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f),
            floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, -1f),
            floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f),
            floatArrayOf(-1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f),
            floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 0f),
            floatArrayOf(0f, 0f, -1f, -1f, 0f, 0f, 0f, 1f, 0f),
        )
        for (f in faces) {
            val (nx, ny, nz) = Triple(f[0], f[1], f[2])
            val ux = f[3]
            val uy = f[4]
            val uz = f[5]
            val vx = f[6]
            val vy = f[7]
            val vz = f[8]
            val base = b.vertexCount
            for ((su, sv) in listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)) {
                b.vertex(
                    (nx + ux * su + vx * sv) * hx,
                    (ny + uy * su + vy * sv) * hy,
                    (nz + uz * su + vz * sv) * hz,
                    nx,
                    ny,
                    nz,
                    (su + 1) / 2,
                    (sv + 1) / 2,
                )
            }
            b.quad(base, base + 1, base + 2, base + 3)
        }
        return b.build()
    }

    /** UV sphere scaled to an ellipsoid with radii (rx, ry, rz). */
    fun ellipsoid(rx: Float, ry: Float, rz: Float, segments: Int = 20, rings: Int = 12): MeshData {
        val b = MeshBuilder()
        for (r in 0..rings) {
            val phi = PI * r / rings
            for (s in 0..segments) {
                val theta = 2 * PI * s / segments
                val nx = (sin(phi) * cos(theta)).toFloat()
                val ny = cos(phi).toFloat()
                val nz = (sin(phi) * sin(theta)).toFloat()
                // Normal of an ellipsoid: gradient of (x/rx)^2 + ...
                val gx = nx / rx
                val gy = ny / ry
                val gz = nz / rz
                val gl = kotlin.math.sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(1e-6f)
                b.vertex(nx * rx, ny * ry, nz * rz, gx / gl, gy / gl, gz / gl, s.toFloat() / segments, r.toFloat() / rings)
            }
        }
        val stride = segments + 1
        for (r in 0 until rings) {
            for (s in 0 until segments) {
                val a = r * stride + s
                b.quad(a, a + stride, a + stride + 1, a + 1)
            }
        }
        return b.build()
    }

    /** Vertical cylinder from y = 0 to y = height. */
    fun cylinder(radius: Float, height: Float, segments: Int = 24): MeshData {
        val b = MeshBuilder()
        for (s in 0..segments) {
            val t = 2 * PI * s / segments
            val nx = cos(t).toFloat()
            val nz = sin(t).toFloat()
            b.vertex(nx * radius, 0f, nz * radius, nx, 0f, nz, s.toFloat() / segments, 0f)
            b.vertex(nx * radius, height, nz * radius, nx, 0f, nz, s.toFloat() / segments, 1f)
        }
        for (s in 0 until segments) {
            val a = s * 2
            b.quad(a, a + 1, a + 3, a + 2)
        }
        val top = b.vertex(0f, height, 0f, 0f, 1f, 0f, 0.5f, 0.5f)
        val ring = b.vertexCount
        for (s in 0..segments) {
            val t = 2 * PI * s / segments
            b.vertex(
                cos(t).toFloat() * radius,
                height,
                sin(t).toFloat() * radius,
                0f,
                1f,
                0f,
                0.5f + cos(t).toFloat() / 2,
                0.5f + sin(t).toFloat() / 2,
            )
        }
        for (s in 0 until segments) b.triangle(top, ring + s + 1, ring + s)
        return b.build()
    }

    /** Horizontal quad on y = 0 facing up, centred on the origin. */
    fun plane(sx: Float, sz: Float, subdivisions: Int = 1): MeshData {
        val b = MeshBuilder()
        val n = subdivisions.coerceAtLeast(1)
        for (j in 0..n) {
            for (i in 0..n) {
                val u = i.toFloat() / n
                val v = j.toFloat() / n
                b.vertex((u - 0.5f) * sx, 0f, (v - 0.5f) * sz, 0f, 1f, 0f, u, v)
            }
        }
        for (j in 0 until n) {
            for (i in 0 until n) {
                val a = j * (n + 1) + i
                b.quad(a, a + n + 1, a + n + 2, a + 1)
            }
        }
        return b.build()
    }

    /** Flat arrow pointing toward -Z (board-up), lying on y = 0. Used for current tiles and hints. */
    fun arrow(length: Float, width: Float): MeshData {
        val b = MeshBuilder()
        val shaft = width * 0.38f
        val headStart = -length * 0.05f
        val points = listOf(
            -shaft to length * 0.5f,
            shaft to length * 0.5f,
            shaft to headStart,
            width / 2 to headStart,
            0f to -length * 0.5f,
            -width / 2 to headStart,
            -shaft to headStart,
        )
        val idx = points.map { (x, z) -> b.vertex(x, 0f, z, 0f, 1f, 0f, x / width + 0.5f, z / length + 0.5f) }
        b.triangle(idx[0], idx[2], idx[1])
        b.triangle(idx[0], idx[6], idx[2])
        b.triangle(idx[3], idx[5], idx[4])
        b.triangle(idx[6], idx[5], idx[3])
        b.triangle(idx[6], idx[3], idx[2])
        return b.build()
    }
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
        val positions = floatBuffer(data.positions)
        val normals = floatBuffer(data.normals)
        val uvs = floatBuffer(data.uvs)
        val indices = shortBuffer(data.indices)
        val orientation = SurfaceOrientation.Builder()
            .vertexCount(vertexCount)
            .normals(normals)
            .uvs(uvs)
            .positions(positions)
            .triangleCount(data.indices.size / 3)
            .triangles_uint16(indices)
            .build()
        val quats = ByteBuffer.allocateDirect(vertexCount * 8).order(ByteOrder.nativeOrder())
        orientation.getQuatsAsShort(quats)
        orientation.destroy()
        positions.rewind()
        uvs.rewind()
        indices.rewind()
        quats.rewind()

        val vertexBuffer = VertexBuffer.Builder()
            .bufferCount(3)
            .vertexCount(vertexCount)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 1, VertexBuffer.AttributeType.SHORT4, 0, 8)
            .normalized(VertexBuffer.VertexAttribute.TANGENTS)
            .attribute(VertexBuffer.VertexAttribute.UV0, 2, VertexBuffer.AttributeType.FLOAT2, 0, 8)
            .build(engine)
        vertexBuffer.setBufferAt(engine, 0, positions)
        vertexBuffer.setBufferAt(engine, 1, quats)
        vertexBuffer.setBufferAt(engine, 2, uvs)
        val indexBuffer = IndexBuffer.Builder()
            .indexCount(data.indices.size)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        indexBuffer.setBuffer(engine, indices)
        return GpuMesh(vertexBuffer, indexBuffer, data.bounds(), data.indices.size).also { owned += it }
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
