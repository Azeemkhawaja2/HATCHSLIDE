package com.hatchslide.core.render.board

import com.google.android.filament.Texture
import com.hatchslide.core.render.GpuMesh
import com.hatchslide.core.render.RenderEngine

/**
 * The uploaded form of an [IslandGeometry]: tile and island meshes drawn with the shared terrain
 * material, plus the stage's wet-sand mask and shoreline textures. The terrain and water material
 * instances are shared by every stage, so a stage calls [bind] whenever it becomes visible.
 */
class Scenery(private val host: RenderEngine) {
    private val meshes = ArrayList<GpuMesh>()
    private val entities = ArrayList<Int>()
    private var mask: Texture? = null
    private var shore: Texture? = null
    private var maskTransform = FloatArray(4)
    private var shoreTransform = FloatArray(4)
    private var attached = false

    fun build(island: IslandGeometry, withTiles: Boolean = true) {
        release()
        if (withTiles && island.tiles.vertexCount > 0) add(island, tiles = true)
        add(island, tiles = false)
        mask = host.textures.rgba(island.mask.width, island.mask.height, island.mask.pixels)
        shore = host.textures.rgba(island.shore.width, island.shore.height, island.shore.pixels)
        maskTransform = island.mask.transform
        shoreTransform = island.shore.transform
    }

    private fun add(island: IslandGeometry, tiles: Boolean) {
        val mesh = host.meshes.upload(if (tiles) island.tiles else island.chunk)
        meshes += mesh
        entities += host.meshes.renderable(mesh, host.surfaces.terrain, castShadows = tiles, receiveShadows = true)
    }

    /** Points the shared terrain and water materials at this scenery's textures. */
    fun bind() {
        val m = mask ?: return
        val s = shore ?: return
        host.surfaces.setBoardTextures(m, maskTransform, s, shoreTransform)
    }

    fun attach() {
        if (attached) return
        for (e in entities) host.scene.addEntity(e)
        attached = true
        bind()
    }

    fun detach() {
        if (!attached) return
        for (e in entities) host.scene.remove(e)
        attached = false
    }

    fun release() {
        detach()
        mask?.let { host.surfaces.clearBoardTextures(it) }
        entities.forEach { host.meshes.destroyEntity(it) }
        entities.clear()
        meshes.forEach { host.meshes.release(it) }
        meshes.clear()
        mask?.let { host.textures.release(it) }
        shore?.let { host.textures.release(it) }
        mask = null
        shore = null
    }
}
