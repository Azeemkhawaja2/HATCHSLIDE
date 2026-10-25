package com.hatchslide.core.designsystem

/** Licence texts bundled as assets for the credits screen (OFL requires shipping them with the fonts). */
object Licenses {
    data class Entry(val name: String, val licence: String, val asset: String)

    val FONTS = listOf(
        Entry("Fredoka", "SIL Open Font License 1.1", "licenses/OFL-Fredoka.txt"),
        Entry("Nunito", "SIL Open Font License 1.1", "licenses/OFL-Nunito.txt"),
    )
}
