package com.hatchslide.tools.levelgen

import kotlin.random.Random

/** Original, theme-matched level names. Every name is unique within its world (and across dailies). */
object Names {
    private fun words(text: String) = text.trim().split(Regex("\\s+"))

    private val vocab: Map<Int, Pair<List<String>, List<String>>> = mapOf(
        1 to (
            words("Golden Peach Dawn Morning Amber Gentle Early Warm Honey Rosy Bright Soft Quiet Hazy Sleepy") to
                words("Steps Drift Path Shore Dunes Ripple Shallows Trail Glow Breeze Sands Crossing Bend Footprints Nest")
            ),
        2 to (
            words("Coral Noon Seafoam Pearl Reef Azure Sunlit Crystal Turquoise Glassy Sparkle Breezy Salty Lagoon Shimmer") to
                words("Lagoon Shelf Garden Arch Spiral Fan Ridge Shoal Cove Flats Terrace Bank Puddles Splash Boardwalk")
            ),
        3 to (
            words("Moon Silver Star Glow Midnight Lantern Velvet Dream Firefly Hush Twilight Comet Misty Indigo Wishing") to
                words("Current Tide Pool Path Wave Drift Ripple Swirl Hollow Channel Eddy Glade Lullaby Beacon Whirl")
            ),
        4 to (
            words("Sideways Copper Rockpool Barnacle Kelp Pincer Dusky Tawny Snappy Briny Clatter Scuttle Russet Amber Mossy") to
                words("Walk Ledge Shuffle Maze Crossing Hideout Burrow Shelf Parade Dance Corner Alley Patrol Crevice Market")
            ),
        5 to (
            words("Ember Ash Obsidian Basalt Cinder Smoky Dusk Ruby Glowing Scarlet Sooty Fiery Rumbling Charcoal Molten") to
                words("Pools Ridge Vents Cliffs Crater Steps Flats Bay Shelf Spire Hollow Terrace Run Rim Gate")
            ),
    )

    fun forWorld(world: Int, count: Int, seed: Long, reserved: Set<String> = emptySet()): List<String> {
        val (adjectives, nouns) = vocab.getValue(world)
        val all = adjectives.flatMap { a -> nouns.map { n -> "$a $n" } }.filter { it !in reserved && !it.startsWith("Lagoon Lagoon") }
        return all.shuffled(Random(seed)).take(count)
    }

    /** Daily Tide names draw from every world's vocabulary. */
    fun forDaily(count: Int, seed: Long): List<String> {
        val all = vocab.values.flatMap { (adjectives, nouns) -> adjectives.flatMap { a -> nouns.map { n -> "$a $n" } } }
            .distinct()
            .filter { name -> name.split(" ").let { it[0] != it[1] } }
        return all.shuffled(Random(seed)).take(count)
    }
}
