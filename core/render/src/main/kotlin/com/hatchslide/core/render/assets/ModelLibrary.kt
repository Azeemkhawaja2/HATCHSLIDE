package com.hatchslide.core.render.assets

import android.content.res.AssetManager
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.MaterialInstance
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.FilamentInstance
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.hatchslide.core.render.readDirect

/**
 * One placed copy of a model: its gltfio instance, the renderable entities to add to a scene and a
 * lookup of node entities by glTF node name (for procedural animation).
 */
class ModelInstance internal constructor(
    val model: String,
    val lod: Int,
    internal val instance: FilamentInstance,
    private val asset: FilamentAsset,
    val renderables: IntArray,
    /** glTF material name of every primitive, per renderable, captured before any rebinding. */
    internal val materialNames: Array<Array<String>>,
) {
    /** The instance's root entity; its transform places the whole model. */
    val root: Int get() = instance.root
    private val byName = HashMap<String, Int>()

    init {
        for (entity in instance.entities) asset.getName(entity)?.let { byName[it] = entity }
    }

    /** The entity of the glTF node [name]; models are built with unique node names. */
    fun node(name: String): Int = byName[name] ?: error("$model has no node $name")

    fun hasNode(name: String): Boolean = name in byName
}

/**
 * Loads the generated glTF models with gltfio (spec section 6) and hands out instances from per-model
 * pools, so a level change reuses entities instead of reloading. Materials authored in the files are
 * placeholders: every primitive is bound to one of the game's own material instances by its glTF
 * material name ([MaterialBinder]).
 */
class ModelLibrary(private val assets: AssetManager, private val engine: Engine) {
    /** Picks the game material for a glTF material name, given the instance being bound. */
    fun interface MaterialBinder {
        fun bind(model: String, materialName: String): MaterialInstance
    }

    private class Entry(val asset: FilamentAsset, var unused: FilamentInstance?) {
        val free = ArrayDeque<ModelInstance>()
    }

    private val materials = UbershaderProvider(engine)
    private val loader = AssetLoader(engine, materials, EntityManager.get())
    private val resources = ResourceLoader(engine)
    private val entries = HashMap<String, Entry>()

    private fun entry(model: String, lod: Int): Entry = entries.getOrPut(AssetCatalog.Models.path(model, lod)) {
        val holder = arrayOfNulls<FilamentInstance>(1)
        val asset = loader.createInstancedAsset(assets.readDirect(AssetCatalog.Models.path(model, lod)), holder)
            ?: error("cannot parse model $model")
        resources.loadResources(asset)
        Entry(asset, holder[0])
    }

    /** Loads a model ahead of use (during a loading screen). */
    fun preload(model: String, lod: Int) {
        entry(model, lod)
    }

    /** A free instance of [model], with every primitive bound through [binder]. */
    fun acquire(model: String, lod: Int, binder: MaterialBinder): ModelInstance {
        val e = entry(model, lod)
        val placed = e.free.removeFirstOrNull() ?: run {
            val fresh = e.unused?.also { e.unused = null } ?: loader.createInstance(e.asset) ?: error("cannot instance $model")
            val rm = engine.renderableManager
            val renderables = fresh.entities.filter { rm.hasComponent(it) }.toIntArray()
            val names = Array(renderables.size) { i ->
                val ri = rm.getInstance(renderables[i])
                Array(rm.getPrimitiveCount(ri)) { p -> rm.getMaterialInstanceAt(ri, p).name ?: "" }
            }
            ModelInstance(model, lod, fresh, e.asset, renderables, names)
        }
        bind(placed, binder)
        return placed
    }

    /** Rebinds an instance's materials (for example after a theme or species change). */
    fun bind(placed: ModelInstance, binder: MaterialBinder) {
        val rm = engine.renderableManager
        for ((i, entity) in placed.renderables.withIndex()) {
            val ri = rm.getInstance(entity)
            val names = placed.materialNames[i]
            for (p in names.indices) rm.setMaterialInstanceAt(ri, p, binder.bind(placed.model, names[p]))
            rm.setCastShadows(ri, true)
            rm.setReceiveShadows(ri, true)
        }
    }

    /** Returns an instance to its pool; the caller has already removed it from the scene. */
    fun release(placed: ModelInstance) {
        entries[AssetCatalog.Models.path(placed.model, placed.lod)]?.free?.addLast(placed)
    }

    fun destroy() {
        entries.values.forEach { loader.destroyAsset(it.asset) }
        entries.clear()
        resources.destroy()
        loader.destroy()
        materials.destroyMaterials()
        materials.destroy()
    }
}
