package com.hatchslide.core.render.assets

/**
 * Every generated asset the renderer loads, by name (spec section 8: nothing ships unreferenced).
 * tools/assetgen writes exactly these files; AssetManifestTest checks the two lists agree.
 */
object AssetCatalog {
    /** Turtle species in skin order (persisted as the skin index). */
    val SPECIES = listOf("green", "loggerhead", "hawksbill", "leatherback", "kemps_ridley", "olive_ridley", "flatback")

    object Models {
        const val HATCHLING = "hatchling"
        const val EGG = "egg"
        val ROCKS = List(6) { "rock_$it" }
        const val SHELL_SCALLOP = "shell_scallop"
        const val SHELL_CONCH = "shell_conch"
        const val SHELL_COWRIE = "shell_cowrie"
        const val SHELL_CRACKED = "shell_cracked"
        const val COCONUT = "coconut"
        const val CRAB = "crab"
        const val PIT = "pit"
        const val CURRENT = "current"
        const val TIDE_POOL = "tide_pool"
        const val SEAGULL = "seagull"
        val PALMS = listOf("palm_0", "palm_1")
        val DRIFTWOOD = listOf("driftwood_0", "driftwood_1")
        val UMBRELLAS = listOf("umbrella_coral", "umbrella_teal")
        const val LANTERN = "lantern"
        val CORALS = listOf("coral_branch", "coral_brain", "coral_fan")
        const val STARFISH = "starfish"
        val CLIFFS = listOf("cliff_0", "cliff_1")
        val ISLANDS = List(5) { "island_${it + 1}" }
        const val SANDCASTLE = "sandcastle"
        const val NEST = "nest"
        const val PEBBLES = "pebbles"
        const val BUCKET = "bucket"

        val SHELLS = listOf(SHELL_SCALLOP, SHELL_CONCH, SHELL_COWRIE)

        val ALL: List<String> = listOf(HATCHLING, EGG) + ROCKS +
            listOf(SHELL_SCALLOP, SHELL_CONCH, SHELL_COWRIE, SHELL_CRACKED, COCONUT, CRAB, PIT, CURRENT, TIDE_POOL, SEAGULL) +
            PALMS + DRIFTWOOD + UMBRELLAS + LANTERN + CORALS + STARFISH + CLIFFS + ISLANDS +
            listOf(SANDCASTLE, NEST, PEBBLES, BUCKET)

        fun path(name: String, lod: Int): String = if (lod == 0) "models/$name.glb" else "models/${name}_lod1.glb"
    }

    object Textures {
        val SAND_LOOKS = listOf("sand_golden", "sand_coral", "sand_silver", "sand_amber", "sand_volcanic")
        const val SAND_FINE = "sand_fine"
        const val SAND_COARSE = "sand_coarse"
        const val ROCK = "rock"
        const val BASALT = "basalt"

        /** One cliff and boulder rock per world (granite, limestone, slate, sandstone, basalt). */
        const val LIMESTONE = "limestone"
        const val SLATE = "slate"
        const val SANDSTONE = "sandstone"
        const val SHELLS = "shells"
        const val TURTLE_DETAIL = "turtle_detail"
        const val TURTLE_SKIN = "turtle_skin"
        const val WATER_A = "water_a"
        const val WATER_B = "water_b"
        const val FOAM = "foam"
        const val CAUSTICS = "caustics"
        const val PROPS = "props"
        const val FX = "fx"

        fun turtle(species: String) = "turtle_$species"

        /** Material sets: base name to the maps it has. */
        private val SETS: List<Pair<String, List<String>>> =
            SAND_LOOKS.map { it to listOf("albedo") } +
                listOf(
                    SAND_FINE to listOf("normal", "orm"),
                    SAND_COARSE to listOf("normal", "orm"),
                    ROCK to listOf("albedo", "normal", "orm"),
                    BASALT to listOf("albedo", "normal", "orm"),
                    LIMESTONE to listOf("albedo", "normal", "orm"),
                    SLATE to listOf("albedo", "normal", "orm"),
                    SANDSTONE to listOf("albedo", "normal", "orm"),
                    SHELLS to listOf("albedo", "normal", "orm"),
                ) +
                SPECIES.map { turtle(it) to listOf("albedo") } +
                listOf(
                    TURTLE_DETAIL to listOf("normal", "orm"),
                    TURTLE_SKIN to listOf("albedo", "normal", "orm"),
                    WATER_A to listOf("normal"),
                    WATER_B to listOf("normal"),
                    FOAM to listOf("albedo"),
                    CAUSTICS to listOf("albedo"),
                    PROPS to listOf("albedo", "normal", "orm"),
                    FX to listOf("albedo"),
                )

        /** Texture file names without extension, e.g. `rock_normal`. */
        val ALL: List<String> = SETS.flatMap { (base, maps) -> maps.map { "${base}_$it" } }

        /** Names that keep a 4096 top level on the High tier (spec section 6: 4096 hero / 2048 rest). */
        val HERO = setOf("${TURTLE_DETAIL}_normal")

        /** Effects atlas cells, row-major in a 4x4 grid (tools/assetgen/textures/generators.py FX_CELLS). */
        val FX_CELLS = listOf(
            "sand_puff", "droplet", "spray", "sparkle",
            "confetti_pink", "confetti_teal", "confetti_yellow", "glow",
            "ash", "footprint", "ripple", "soft_circle",
            "bubble", "gull", "star", "glint",
        )
    }

    object Skies {
        val ALL = listOf("title", "world1", "world2", "world3", "world4", "world5")

        /** cmgen output: specular cubemap with the spherical harmonics in its metadata. */
        fun ibl(sky: String) = "skies/${sky}_ibl.ktx"

        /** Skybox cubemap, compressed like the other textures (so it lives in the TCF texture folder). */
        fun skybox(sky: String) = "sky_$sky"
    }

    /** Asset-relative paths of every generated file (texture folders without the TCF suffix). */
    fun referencedPaths(): Set<String> = buildSet {
        for (model in Models.ALL) {
            add(Models.path(model, 0))
            add(Models.path(model, 1))
        }
        for (texture in Textures.ALL) add("textures/$texture.ktx")
        for (sky in Skies.ALL) {
            add(Skies.ibl(sky))
            add("textures/${Skies.skybox(sky)}.ktx")
        }
    }
}
