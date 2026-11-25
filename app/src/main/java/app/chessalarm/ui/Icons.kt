package app.chessalarm.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** A small hand-drawn icon set (24x24 grid, stroked), so the app needs no icon library. */
object Icons {
    private fun stroke(name: String, vararg paths: String, filled: Boolean = false): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in paths) {
            val nodes = PathParser().parsePathString(d).toNodes()
            if (filled) {
                b.addPath(nodes, fill = SolidColor(Color.White))
            } else {
                b.addPath(
                    nodes,
                    stroke = SolidColor(Color.White),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }
        return b.build()
    }

    val Plus = stroke("plus", "M12 5v14M5 12h14")
    val ChevronRight = stroke("chevronRight", "M9 5l7 7-7 7")
    val ChevronLeft = stroke("chevronLeft", "M15 5l-7 7 7 7")
    val Gear = stroke(
        "gear",
        "M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z",
        "M19.4 15a1.6 1.6 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.6 1.6 0 0 0-1.8-.3 1.6 1.6 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.6 1.6 0 0 0-1-1.5 1.6 1.6 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.6 1.6 0 0 0 .3-1.8 1.6 1.6 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.6 1.6 0 0 0 1.5-1 1.6 1.6 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.6 1.6 0 0 0 1.8.3H9a1.6 1.6 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.6 1.6 0 0 0 1 1.5 1.6 1.6 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.6 1.6 0 0 0-.3 1.8V9a1.6 1.6 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.6 1.6 0 0 0-1.5 1z",
    )
    val Play = stroke("play", "M8 5.5v13l10-6.5z", filled = true)
    val Stop = stroke("stop", "M7 7h10v10H7z", filled = true)
    val Check = stroke("check", "M5 12.5l4.5 4.5L19 7.5")
    val Music = stroke("music", "M9 18V6l11-2v12", "M9 18a3 3 0 1 1-6 0 3 3 0 0 1 6 0z", "M20 16a3 3 0 1 1-6 0 3 3 0 0 1 6 0z")
    val Chart = stroke("chart", "M4 20V10M10 20V4M16 20v-7M22 20H2")
    val Bell = stroke("bell", "M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9", "M13.7 21a2 2 0 0 1-3.4 0")
    val Shield = stroke("shield", "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z")
    val Film = stroke("film", "M3 4h18v16H3z", "M7 4v16M17 4v16M3 9h4M3 15h4M17 9h4M17 15h4")
    val Folder = stroke("folder", "M3 6a1 1 0 0 1 1-1h5l2 2h9a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z")
    val Phone = stroke("phone", "M7 2h10a1 1 0 0 1 1 1v18a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1z", "M11 18h2")
    val Search = stroke("search", "M10.5 18a7.5 7.5 0 1 0 0-15 7.5 7.5 0 0 0 0 15zM21 21l-5.2-5.2")
    val Trash = stroke("trash", "M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3")
    val Spotify = stroke("spotify", "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20z", "M7 9.5c3.5-1 7.5-.6 10.5 1M7.5 13c3-.8 6.2-.4 8.7 1M8 16.2c2.4-.6 4.7-.3 6.8.8")
}
