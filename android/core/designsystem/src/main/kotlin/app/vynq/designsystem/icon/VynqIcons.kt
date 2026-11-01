package app.vynq.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * vynq icon set: 24-unit grid, 1.75 monoline stroke, round caps and joins,
 * 2-unit optical padding. Tinted by `Icon(tint = …)`. Keep new icons on the same grid.
 */
object VynqIcons {
    val Chats by lazy { icon("chats", "M12 4.5a7.5 7.5 0 1 1-3.6 14.08L4.5 19.5l1.02-3.6A7.5 7.5 0 0 1 12 4.5z") }
    val Phone by lazy {
        icon("phone", "M7.2 4.5h2.4l1.4 3.6-1.8 1.3a10 10 0 0 0 5.4 5.4l1.3-1.8 3.6 1.4v2.4a1.7 1.7 0 0 1-1.8 1.7A14.3 14.3 0 0 1 5.5 6.3a1.7 1.7 0 0 1 1.7-1.8z")
    }
    val Video by lazy { icon("video", "M5.5 7h7.5a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H5.5a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z", "M15 10.5l5-2.8v8.6l-5-2.8") }
    val Contacts by lazy { icon("contacts", "M12 11.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z", "M5 19.5a7 7 0 0 1 14 0") }
    val AddContact by lazy {
        icon("add_contact", "M10 11.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z", "M3.5 19.5a6.5 6.5 0 0 1 13 0", "M19 8.5v5", "M16.5 11h5")
    }
    val Settings by lazy {
        icon("settings", "M4.5 8h9", "M18.5 8h1", "M4.5 16h2", "M11.5 16h8",
            "M16 10a2 2 0 1 0 0-4 2 2 0 0 0 0 4z", "M9 18a2 2 0 1 0 0-4 2 2 0 0 0 0 4z")
    }
    val Check by lazy { icon("check", "M5.5 12.5l4 4 9-9") }
    val Plus by lazy { icon("plus", "M12 5.5v13", "M5.5 12h13") }
    val Search by lazy { icon("search", "M11 17.5a6.5 6.5 0 1 0 0-13 6.5 6.5 0 0 0 0 13z", "M16 16l3.5 3.5") }
    val ChevronRight by lazy { icon("chevron_right", "M10 6.5l5.5 5.5-5.5 5.5") }
    val Back by lazy { icon("back", "M14.5 5.5L8 12l6.5 6.5") }
    val Shield by lazy { icon("shield", "M12 3.8l6.5 2.7v5c0 4.2-2.8 7.4-6.5 8.8-3.7-1.4-6.5-4.6-6.5-8.8v-5z", "M9.2 12l2 2 3.6-3.8") }
    val Lock by lazy { icon("lock", "M7 10.5h10a1.5 1.5 0 0 1 1.5 1.5v6a1.5 1.5 0 0 1-1.5 1.5H7A1.5 1.5 0 0 1 5.5 18v-6A1.5 1.5 0 0 1 7 10.5z", "M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5") }
    val Bell by lazy { icon("bell", "M6.5 16v-4.5a5.5 5.5 0 0 1 11 0V16l1.5 2h-14z", "M10 20.5a2 2 0 0 0 4 0") }
    val Network by lazy {
        icon("network", "M4.5 9.5a10.6 10.6 0 0 1 15 0", "M7.3 12.6a6.6 6.6 0 0 1 9.4 0", "M10.1 15.6a2.7 2.7 0 0 1 3.8 0", "M12 19h.01")
    }
    val Appearance by lazy {
        ImageVector.Builder("appearance", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(addPathNodes("M12 20a8 8 0 1 0 0-16 8 8 0 0 0 0 16z"), stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            addPath(addPathNodes("M12 4a8 8 0 0 1 0 16z"), fill = SolidColor(Color.Black))
        }.build()
    }
    val Devices by lazy { icon("devices", "M8.5 3.5h7a1.5 1.5 0 0 1 1.5 1.5v14a1.5 1.5 0 0 1-1.5 1.5h-7A1.5 1.5 0 0 1 7 19V5a1.5 1.5 0 0 1 1.5-1.5z", "M11 17.5h2") }
    val Info by lazy { icon("info", "M12 20a8 8 0 1 0 0-16 8 8 0 0 0 0 16z", "M12 11v5", "M12 8h.01") }
    val Mail by lazy { icon("mail", "M5.5 6h13A1.5 1.5 0 0 1 20 7.5v9a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 16.5v-9A1.5 1.5 0 0 1 5.5 6z", "M4.5 7l7.5 6 7.5-6") }
    val Gauge by lazy { icon("gauge", "M4.5 16a7.5 7.5 0 1 1 15 0", "M12 16l3.5-4.5", "M12 16h.01") }

    private const val STROKE = 1.75f

    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = addPathNodes(d),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = STROKE,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}
