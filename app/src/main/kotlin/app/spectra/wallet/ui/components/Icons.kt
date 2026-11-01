package app.spectra.wallet.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Stroke icons transcribed from the reference designs (24×24 viewport, round caps/joins). */
object SpectraIcons {
    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"
    private fun rect(x: Float, y: Float, w: Float, h: Float, rx: Float) =
        "M${x + rx} ${y}h${w - 2 * rx}a$rx $rx 0 0 1 $rx $rx" + "v${h - 2 * rx}a$rx $rx 0 0 1 ${-rx} $rx" +
            "h${-(w - 2 * rx)}a$rx $rx 0 0 1 ${-rx} ${-rx}" + "v${-(h - 2 * rx)}a$rx $rx 0 0 1 $rx ${-rx}z"

    private fun icon(name: String, stroke: Float, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        paths.forEach { d ->
            b.addPath(PathParser().parsePathString(d).toNodes(), stroke = SolidColor(Color.Black), strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
        return b.build()
    }

    val Back = icon("back", 2f, "M15 18l-6-6 6-6")
    val Chevron = icon("chevron", 2f, "M9 6l6 6-6 6")
    val ChevronDown = icon("chevronDown", 2f, "M6 9l6 6 6-6")
    val Close = icon("close", 2f, "M6 6l12 12M18 6L6 18")
    val Check = icon("check", 2.6f, "M5 12.5l4.5 4.5L19 7.5")
    val CheckCircle = icon("checkCircle", 2f, circle(12f, 12f, 9f), "M8 12.5l2.8 2.8L16.5 9.5")
    val Backspace = icon("backspace", 1.7f, "M9 5h11a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H9l-6-7z", "M13 10l4 4M17 10l-4 4")
    val Send = icon("send", 2.2f, "M7 17L17 7M8 7h9v9")
    val Receive = icon("receive", 2.2f, "M17 7L7 17M16 17H7V8")
    val TrendDown = icon("trendDown", 2.6f, "M7 7l10 10M17 8v9H8")
    val TrendUpSmall = icon("trendUpSmall", 2.6f, "M7 17L17 7M8 7h9v9")
    val Swap = icon("swap", 2f, "M7 4L3 8l4 4M3 8h14M17 20l4-4-4-4M21 16H7")
    val Plus = icon("plus", 2f, "M12 5v14M5 12h14")
    val Earn = icon("earn", 2f, "M3 17l6-6 4 4 8-8M15 7h6v6")
    val Home = icon("home", 2f, "M3 10.5L12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6h-6v6H4a1 1 0 0 1-1-1z")
    val History = icon("history", 1.8f, circle(12f, 12f, 9f), "M12 7v5l3 2")
    val Profile = icon("profile", 1.8f, circle(12f, 8f, 4f), "M4 21a8 8 0 0 1 16 0")
    val Search = icon("search", 2f, circle(11f, 11f, 7f), "M20 20l-3.5-3.5")
    val Bell = icon("bell", 1.8f, "M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9M10.3 21a1.94 1.94 0 0 0 3.4 0")
    val Eye = icon("eye", 2f, "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z", circle(12f, 12f, 3f))
    val EyeOff = icon("eyeOff", 2f, "M3 3l18 18M10.6 5.1A10 10 0 0 1 12 5c6.5 0 10 7 10 7a17 17 0 0 1-2.4 3.3M6.6 6.6C3.9 8.3 2 12 2 12s3.5 7 10 7a9.7 9.7 0 0 0 5.4-1.6M9.9 9.9a3 3 0 0 0 4.2 4.2")
    val Star = icon("star", 1.8f, "M12 3l2.8 5.7 6.2.9-4.5 4.4 1.1 6.2L12 17.3 6.4 20.2l1.1-6.2L3 9.6l6.2-.9z")
    val Info = icon("info", 1.8f, circle(12f, 12f, 9f), "M12 11v5M12 8v.01")
    val Lock = icon("lock", 1.8f, rect(4f, 10f, 16f, 11f, 3f), "M8 10V7a4 4 0 0 1 8 0v3")
    val Vault = icon("vault", 1.8f, rect(3f, 5f, 18f, 15f, 3f), circle(12f, 12.5f, 3f))
    val Fingerprint = icon("fingerprint", 1.5f, "M6.5 7.5A7 7 0 0 1 18.8 10M5 12.5a7 7 0 0 1 .5-3M19 13.5v.5a10 10 0 0 1-1.2 4.6M8 12a4 4 0 0 1 8 0v1.5a8 8 0 0 1-1.3 4.4M12 12v2a12 12 0 0 1-2.5 7M8.2 15.5a10 10 0 0 1-1.4 3.6M15.4 20.6l.3-.5")
    val FaceScan = icon("faceScan", 1.7f, "M4 8V5a1 1 0 0 1 1-1h3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3M9 9v1.5M15 9v1.5M12 9v4h-1M9 16c1.7 1.3 4.3 1.3 6 0")
    val Scan = icon("scan", 1.8f, "M4 8V5a1 1 0 0 1 1-1h3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3M4 12h16")
    val Clipboard = icon("clipboard", 1.8f, rect(8f, 3f, 8f, 4f, 1f), "M16 5h2a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1h2")
    val Copy = icon("copy", 2f, rect(9f, 9f, 11f, 11f, 2.5f), "M5 15V6a2 2 0 0 1 2-2h8")
    val Share = icon("share", 2f, "M12 15V3M8 7l4-4 4 4M5 12v7a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-7")
    val External = icon("external", 2f, "M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5")
    val Qr = icon("qr", 1.9f, rect(3f, 3f, 7f, 7f, 1.5f), rect(14f, 3f, 7f, 7f, 1.5f), rect(3f, 14f, 7f, 7f, 1.5f), "M14 14h3v3M21 14v.01M14 21h.01M17.5 17.5H21V21h-3.5z")
    val Shield = icon("shield", 2f, "M12 3l8 3v6c0 4.5-3.4 8.3-8 9-4.6-.7-8-4.5-8-9V6z")
    val ShieldAlert = icon("shieldAlert", 2f, "M12 3l8 3v6c0 4.5-3.4 8.3-8 9-4.6-.7-8-4.5-8-9V6z", "M12 8v5M12 16.5v.01")
    val Bolt = icon("bolt", 2f, "M13 2L4 14h7l-1 8 9-12h-7z")
    val Alert = icon("alert", 2f, "M12 3l9.5 17h-19z", "M12 10v4M12 17v.01")
    val Refresh = icon("refresh", 2f, "M20 11a8 8 0 0 0-14.9-3.5M4 4v4h4M4 13a8 8 0 0 0 14.9 3.5M20 20v-4h-4")
    val Key = icon("key", 1.8f, circle(8f, 15f, 4f), "M10.8 12.2L20 3M16 7l3 3M14 9l2 2")
    val Globe = icon("globe", 1.8f, circle(12f, 12f, 9f), "M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18")
    val Moon = icon("moon", 1.8f, "M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z")
    val Trash = icon("trash", 1.8f, "M4 7h16M10 11v6M14 11v6M6 7l1 13a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1l1-13M9 7V4h6v3")
    val Filter = icon("filter", 1.8f, "M4 5h16l-6 8v6l-4-2v-4z")
    val Server = icon("server", 1.8f, rect(3f, 4f, 18f, 7f, 2f), rect(3f, 13f, 18f, 7f, 2f), "M7 7.5v.01M7 16.5v.01")
    val Coins = icon("coins", 1.8f, circle(9f, 9f, 6f), "M15.5 9.5a6 6 0 1 1-6 6")
    val Clock = History
    val Settings = icon("settings", 1.8f, circle(12f, 12f, 3f), "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z")
}
