package app.droidnux.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** The app's line icons (24 dp grid, 1.9 dp strokes), tinted by Icon(). */
object Icons {
    val Desktop = stroke("M5 4h14a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M8 21h8M12 17v4")
    val Apps = stroke(
        "M5.5 4h3A1.5 1.5 0 0 1 10 5.5v3A1.5 1.5 0 0 1 8.5 10h-3A1.5 1.5 0 0 1 4 8.5v-3A1.5 1.5 0 0 1 5.5 4z",
        "M15.5 4h3A1.5 1.5 0 0 1 20 5.5v3a1.5 1.5 0 0 1-1.5 1.5h-3A1.5 1.5 0 0 1 14 8.5v-3A1.5 1.5 0 0 1 15.5 4z",
        "M5.5 14h3a1.5 1.5 0 0 1 1.5 1.5v3A1.5 1.5 0 0 1 8.5 20h-3A1.5 1.5 0 0 1 4 18.5v-3A1.5 1.5 0 0 1 5.5 14z",
        "M15.5 14h3a1.5 1.5 0 0 1 1.5 1.5v3a1.5 1.5 0 0 1-1.5 1.5h-3a1.5 1.5 0 0 1-1.5-1.5v-3a1.5 1.5 0 0 1 1.5-1.5z",
    )
    val Settings = stroke("M4 7h10M18 7h2M4 17h4M12 17h8", "M14 7a2 2 0 1 0 4 0a2 2 0 1 0-4 0", "M8 17a2 2 0 1 0 4 0a2 2 0 1 0-4 0")
    val System = stroke(
        "M8 6h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z",
        "M9 2v4M15 2v4M9 18v4M15 18v4M2 9h4M2 15h4M18 9h4M18 15h4",
    )
    val Terminal = stroke("M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M7 9l3 3-3 3M12 15h5")
    val Check = stroke("M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0", "M8 12.5l3 3 5-6")
    val Warning = stroke("M12 3l9.5 17h-19z", "M12 10v4M12 17.5v.01")
    val Info = stroke("M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0", "M12 8v5M12 16v.01")
    val Pending = stroke("M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0")
    val Plus = stroke("M12 5v14M5 12h14")
    val Visibility = stroke("M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z", "M9 12a3 3 0 1 0 6 0a3 3 0 1 0-6 0")
    val VisibilityOff = stroke("M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z", "M9 12a3 3 0 1 0 6 0a3 3 0 1 0-6 0", "M4 4l16 16")
    val Back = stroke("M15 5l-7 7 7 7")
    val Chevron = stroke("M9 5l7 7-7 7")
    val Selected = stroke("M5 12.5l4.5 4.5L19 7.5", width = 2.2f)
    val Debian = stroke("M14 4.5a7.5 7.5 0 1 0 5 9.5", "M14.5 9a3 3 0 1 0 0 6")
    val Play = ImageVector.Builder("play", 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString("M8 5v14l11-7z").toNodes(), fill = SolidColor(Color.White))
        .build()

    private fun stroke(vararg paths: String, width: Float = 1.9f): ImageVector =
        ImageVector.Builder(paths.first().take(8), 24.dp, 24.dp, 24f, 24f).apply {
            for (path in paths) {
                addPath(
                    PathParser().parsePathString(path).toNodes(),
                    stroke = SolidColor(Color.White),
                    strokeLineWidth = width,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}
