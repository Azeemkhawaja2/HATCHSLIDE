package com.hatchslide.core.render

import android.content.res.AssetManager
import com.google.android.filament.Colors
import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads an asset into a direct, native-order buffer as Filament's loaders expect. */
fun AssetManager.readDirect(path: String): ByteBuffer {
    val bytes = open(path).use { it.readBytes() }
    return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
        put(bytes)
        flip()
    }
}

/**
 * Compiled Filament materials (`assets/materials/<name>.filamat`, built by matc at build time) and
 * the instances made from them. Everything created here is destroyed by [destroy].
 */
class MaterialLibrary(private val assets: AssetManager, private val engine: Engine) {
    private val materials = HashMap<String, Material>()
    private val instances = ArrayList<MaterialInstance>()

    fun material(name: String): Material = materials.getOrPut(name) {
        Material.Builder().payload(assets.readDirect("materials/$name.filamat")).build(engine)
    }

    fun instance(name: String): MaterialInstance = material(name).createInstance().also { instances += it }

    /** A lit solid-colour instance; colours are sRGB in 0..1. */
    fun litColor(r: Float, g: Float, b: Float, roughness: Float = 0.7f, metallic: Float = 0f, alpha: Float = 1f): MaterialInstance =
        instance("lit_color").apply {
            setParameter("baseColor", Colors.RgbaType.SRGB, r, g, b, alpha)
            setParameter("roughness", roughness)
            setParameter("metallic", metallic)
        }

    /** An unlit transparent instance for decals (gull shadows, hint arrows, markers). */
    fun decal(r: Float, g: Float, b: Float, alpha: Float): MaterialInstance = instance("decal").apply {
        setParameter("color", Colors.RgbaType.SRGB, r, g, b, alpha)
    }

    fun release(instance: MaterialInstance) {
        if (instances.remove(instance)) engine.destroyMaterialInstance(instance)
    }

    fun destroy() {
        instances.asReversed().forEach { engine.destroyMaterialInstance(it) }
        instances.clear()
        materials.values.reversed().forEach { engine.destroyMaterial(it) }
        materials.clear()
    }
}
