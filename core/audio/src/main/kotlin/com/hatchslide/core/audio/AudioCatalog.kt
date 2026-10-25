package com.hatchslide.core.audio

/**
 * Every generated audio file (spec section 7): six music tracks and two stingers in the HD pack,
 * four ambience loops and the effects in the base module. tools/assetgen/audio writes exactly these.
 */
object AudioCatalog {
    enum class Track(val file: String) {
        TITLE("title"),
        WORLD_1("world1"),
        WORLD_2("world2"),
        WORLD_3("world3"),
        WORLD_4("world4"),
        WORLD_5("world5"),
        ;

        companion object {
            fun forWorld(world: Int): Track = entries.getOrElse(world) { TITLE }
        }
    }

    enum class Stinger(val file: String) { VICTORY("victory"), FAIL("fail") }

    enum class Ambience(val file: String) { SURF("surf"), WIND("wind"), INSECTS("insects"), RUMBLE("rumble") }

    /** Effects, with the world whose mechanic needs them (0 = every world). */
    enum class Sfx(val file: String, val world: Int = 0) {
        SWIPE("swipe"),
        SLIDE_LOOP("slide_loop"),
        STOP("stop"),
        BUMP("bump"),
        SHELL_CLACK("shell_clack"),
        SHELL_CRACK("shell_crack", 2),
        WET_SQUISH("wet_squish", 2),
        CURRENT("current", 3),
        COCONUT_ROLL("coconut_roll", 3),
        COCONUT_PLOP("coconut_plop", 3),
        PIT_FALL("pit_fall", 3),
        PIT_FILL("pit_fill", 3),
        CRAB_CLICK("crab_click", 4),
        POOL_SHIMMER("pool_shimmer", 5),
        EGG_WOBBLE("egg_wobble", 4),
        EGG_CRACK("egg_crack"),
        HATCH_CHIRP("hatch_chirp"),
        SAVE_SPLASH("save_splash"),
        SAVE_CHIME("save_chime"),
        GULL_CALL("gull_call"),
        GULL_SWOOP("gull_swoop"),
        GULL_SQUAWK("gull_squawk"),
        STAR_1("star_1"),
        STAR_2("star_2"),
        STAR_3("star_3"),
        UI_TAP("ui_tap"),
        UI_TOGGLE("ui_toggle"),
        PAGE_SWIPE("page_swipe"),
        UNLOCK_FANFARE("unlock_fanfare"),
        ACHIEVEMENT("achievement"),
        TELEPORT_POP("teleport_pop", 5),
        HINT_SPARKLE("hint_sparkle"),
        UNDO("undo"),
        RESTART("restart"),
        TUCK("tuck"),
        OVERLAY_OPEN("overlay_open"),
        SWIM_PADDLE("swim_paddle"),
    }

    fun trackPath(track: Track) = "music/${track.file}.ogg"

    /** Loudness envelope of a track, one byte per [ENVELOPE_STEP_MS]. */
    fun envelopePath(track: Track) = "music/${track.file}.env"

    const val ENVELOPE_STEP_MS = 250L

    fun stingerPath(stinger: Stinger) = "music/${stinger.file}.ogg"

    fun ambiencePath(ambience: Ambience) = "ambience/${ambience.file}.ogg"

    fun sfxPath(sfx: Sfx) = "sfx/${sfx.file}.ogg"

    /** Asset-relative paths of every generated audio file. */
    fun referencedPaths(): Set<String> = buildSet {
        for (t in Track.entries) {
            add(trackPath(t))
            add(envelopePath(t))
        }
        for (s in Stinger.entries) add(stingerPath(s))
        for (a in Ambience.entries) add(ambiencePath(a))
        for (s in Sfx.entries) add(sfxPath(s))
    }
}
