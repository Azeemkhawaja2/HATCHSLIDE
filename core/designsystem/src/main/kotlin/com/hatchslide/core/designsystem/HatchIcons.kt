package com.hatchslide.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** HATCHSLIDE's own icon set, drawn as vectors on a 24-unit grid so they stay sharp at any density. */
object HatchIcons {
    private fun icon(name: String, vararg paths: String, evenOdd: Boolean = false): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in paths) {
            builder.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = SolidColor(Color.Black),
                pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
            )
        }
        return builder.build()
    }

    val Pause =
        icon("pause", "M7.2 5.5a1.6 1.6 0 0 1 3.2 0v13a1.6 1.6 0 0 1-3.2 0z", "M13.6 5.5a1.6 1.6 0 0 1 3.2 0v13a1.6 1.6 0 0 1-3.2 0z")
    val Play = icon("play", "M8 5.6v12.8c0 .9 1 1.4 1.7.9l9.4-6.4a1.1 1.1 0 0 0 0-1.8L9.7 4.7C9 4.2 8 4.7 8 5.6z")
    val Undo =
        icon(
            "undo",
            "M9.3 6.2 4.4 10.6a.8.8 0 0 0 0 1.2l4.9 4.4c.5.5 1.4.1 1.4-.6v-2.4c3.8-.1 6.6.9 8.6 3.6.4.5 1.2.2 1.1-.4-.8-4.5-4-7.7-9.7-8V6.8c0-.7-.9-1.1-1.4-.6z",
        )
    val Restart = icon(
        "restart",
        "M12 4.5V2.8c0-.6-.7-.9-1.1-.5L7.8 5.4a.7.7 0 0 0 0 1L10.9 9.5c.4.4 1.1.1 1.1-.5V7.3a5.7 5.7 0 1 1-5.6 6.4 1.4 1.4 0 0 0-2.8.4A8.5 8.5 0 1 0 12 4.5z",
    )
    val Hint = icon(
        "hint",
        "M12 2.2a7 7 0 0 0-4.2 12.6c.5.4.8.9.8 1.5v.9c0 .6.5 1 1 1h4.8c.6 0 1-.4 1-1v-.9c0-.6.3-1.1.8-1.5A7 7 0 0 0 12 2.2z",
        "M9.4 19.6h5.2a.8.8 0 0 1 0 1.6c-.3.9-1.4 1.6-2.6 1.6s-2.3-.7-2.6-1.6a.8.8 0 0 1 0-1.6z",
    )
    val Star =
        icon(
            "star",
            "M12 2.6l2.8 5.9 6.4.8c.7.1 1 1 .5 1.5l-4.7 4.4 1.2 6.4c.1.7-.6 1.2-1.2.9L12 19.4l-5.7 3.1c-.6.3-1.3-.2-1.2-.9l1.2-6.4-4.7-4.4c-.5-.5-.2-1.4.5-1.5l6.4-.8z",
        )
    val Seagull =
        icon(
            "seagull",
            "M1.5 11.6c3.2-2.8 6.4-3.1 10.5.6 4.1-3.7 7.3-3.4 10.5-.6.3.3 0 .8-.4.6-3.3-1.1-6.4-.2-9.7 3.3a.6.6 0 0 1-.8 0C8.3 12 5.2 11.1 1.9 12.2c-.4.2-.7-.3-.4-.6z",
        )
    val Lock = icon(
        "lock",
        "M7 10V7.4a5 5 0 0 1 10 0V10h.8c.8 0 1.4.6 1.4 1.4v8.2c0 .8-.6 1.4-1.4 1.4H6.2c-.8 0-1.4-.6-1.4-1.4v-8.2c0-.8.6-1.4 1.4-1.4zm2.4 0h5.2V7.4a2.6 2.6 0 0 0-5.2 0z",
        evenOdd = true,
    )
    val Home =
        icon(
            "home",
            "M11.2 3.3 3.3 10c-.6.5-.2 1.4.5 1.4H5v7.8c0 .7.5 1.2 1.2 1.2H10v-5h4v5h3.8c.7 0 1.2-.5 1.2-1.2v-7.8h1.2c.7 0 1.1-.9.5-1.4l-7.9-6.7a1.2 1.2 0 0 0-1.6 0z",
        )
    val Back =
        icon(
            "back",
            "M10.6 5.4 4.4 11.2a1.1 1.1 0 0 0 0 1.6l6.2 5.8c.7.7 1.9.2 1.9-.8V14h6.5c.7 0 1.2-.5 1.2-1.2v-1.6c0-.7-.5-1.2-1.2-1.2h-6.5V6.2c0-1-1.2-1.5-1.9-.8z",
        )
    val Close =
        icon(
            "close",
            "M6.3 4.9a1 1 0 0 0-1.4 1.4L10.6 12l-5.7 5.7a1 1 0 1 0 1.4 1.4l5.7-5.7 5.7 5.7a1 1 0 0 0 1.4-1.4L13.4 12l5.7-5.7a1 1 0 0 0-1.4-1.4L12 10.6z",
        )
    val Check = icon("check", "M9.4 15.6 5.6 11.8a1.3 1.3 0 0 0-1.8 1.8l4.7 4.7c.5.5 1.3.5 1.8 0L20.2 8.4a1.3 1.3 0 0 0-1.8-1.8z")
    val Settings = icon(
        "settings",
        "M13.4 2.5h-2.8l-.5 2.6a7.4 7.4 0 0 0-1.9 1.1L5.7 5.3 4.3 7.7l2 1.8a7.5 7.5 0 0 0 0 2.2l-2 1.8 1.4 2.4 2.5-.9c.6.5 1.2.8 1.9 1.1l.5 2.6h2.8l.5-2.6c.7-.3 1.3-.6 1.9-1.1l2.5.9 1.4-2.4-2-1.8a7.5 7.5 0 0 0 0-2.2l2-1.8-1.4-2.4-2.5.9a7.4 7.4 0 0 0-1.9-1.1zM12 8.4a2.6 2.6 0 1 1 0 5.2 2.6 2.6 0 0 1 0-5.2z",
        evenOdd = true,
    )
    val Journal = icon(
        "journal",
        "M5.5 3h11.3c1 0 1.7.8 1.7 1.7v14.6c0 1-.8 1.7-1.7 1.7H5.5A1.5 1.5 0 0 1 4 19.5v-15C4 3.7 4.7 3 5.5 3zm2 3.4v1.8h7.8V6.4zm0 3.6v1.6h5.4V10z",
        evenOdd = true,
    )
    val Trophy = icon(
        "trophy",
        "M7 3h10v2h2.6c.5 0 .9.4.8.9-.3 2.8-2 4.6-4.4 5a5 5 0 0 1-3 2.4v2.2h2.6c.6 0 1 .4 1 1V20H7.4v-3.5c0-.6.4-1 1-1H11v-2.2a5 5 0 0 1-3-2.4C5.6 10.5 3.9 8.7 3.6 5.9c-.1-.5.3-.9.8-.9H7zm10 3.6v3c1.1-.4 1.7-1.4 2-3zm-10 0H5c.3 1.6.9 2.6 2 3z",
        evenOdd = true,
    )
    val Calendar = icon(
        "calendar",
        "M7.5 2.5c.6 0 1 .4 1 1V4h7v-.5a1 1 0 0 1 2 0V4h1.2c.9 0 1.6.7 1.6 1.6v13.2c0 .9-.7 1.6-1.6 1.6H5.3c-.9 0-1.6-.7-1.6-1.6V5.6c0-.9.7-1.6 1.6-1.6h1.2v-.5c0-.6.4-1 1-1zM5.7 9v9.4h12.6V9z",
        evenOdd = true,
    )
    val Map =
        icon(
            "map",
            "M9 4.2 3.8 6.1c-.5.2-.8.6-.8 1.1v11.5c0 .6.6 1 1.2.8L9 17.8l6 2 5.2-1.9c.5-.2.8-.6.8-1.1V5.3c0-.6-.6-1-1.2-.8L15 6.2zm.9 1.9 4.2 1.4v10.4l-4.2-1.4z",
            evenOdd = true,
        )
    val Music =
        icon("music", "M18 3.2 9.5 5a1 1 0 0 0-.8 1v9.1a3.2 3.2 0 1 0 1.6 2.8V9.6l7-1.5v5.4a3.2 3.2 0 1 0 1.6 2.8V4.2a1 1 0 0 0-1-1z")
    val Speaker =
        icon(
            "speaker",
            "M11.4 4.6 7 8.3H4.5c-.6 0-1 .4-1 1v5.4c0 .6.4 1 1 1H7l4.4 3.7c.6.5 1.6.1 1.6-.7V5.3c0-.8-1-1.2-1.6-.7z",
            "M16.2 8.4a.9.9 0 0 0-.1 1.3 3.3 3.3 0 0 1 0 4.6.9.9 0 1 0 1.3 1.3 5.1 5.1 0 0 0 0-7.1.9.9 0 0 0-1.2-.1z",
        )
    val Hatchling = icon(
        "hatchling",
        "M12 6.2c3.3 0 6 2.6 6 6.1s-2.7 6.1-6 6.1-6-2.7-6-6.1 2.7-6.1 6-6.1z",
        "M12 1.8c1.4 0 2.4 1.1 2.4 2.4S13.4 6.4 12 6.4 9.6 5.5 9.6 4.2 10.6 1.8 12 1.8z",
        "M5.9 8.4c-1.3-.6-2.7-.4-3.4.5.9.9 2.2 1.4 3.6 1.4z M18.1 8.4c1.3-.6 2.7-.4 3.4.5-.9.9-2.2 1.4-3.6 1.4z",
        "M7 16.4c-1 .5-1.7 1.4-1.8 2.5 1.1.1 2.1-.4 2.7-1.2z M17 16.4c1 .5 1.7 1.4 1.8 2.5-1.1.1-2.1-.4-2.7-1.2z",
    )
    val Shell =
        icon(
            "shell",
            "M12 3.4c-5 0-9 3.9-9 8.7 0 .4.3.7.7.7H7l1.3 4.6c.2.6.7 1 1.3 1h4.8c.6 0 1.1-.4 1.3-1L17 12.8h3.3c.4 0 .7-.3.7-.7 0-4.8-4-8.7-9-8.7z",
        )
    val ArrowPad =
        icon("arrow_pad", "M12 2.5 15 6h-2v4h-2V6H9z M12 21.5 9 18h2v-4h2v4h2z M2.5 12 6 9v2h4v2H6v2z M21.5 12 18 15v-2h-4v-2h4V9z")
}
