package com.hrips.browser

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object HripsIcons {
    val Back by lazy { icon("Back") { moveTo(19f, 12f); horizontalLineTo(5f); moveTo(12f, 19f); lineToRelative(-7f, -7f); lineToRelative(7f, -7f) } }
    val Close by lazy { icon("Close") { moveTo(18f, 6f); lineTo(6f, 18f); moveTo(6f, 6f); lineTo(18f, 18f) } }
    val Add by lazy { icon("Add") { moveTo(5f, 12f); horizontalLineTo(19f); moveTo(12f, 5f); verticalLineTo(19f) } }
    val Check by lazy { icon("Check") { moveTo(5f, 12.5f); lineToRelative(4.5f, 4.5f); lineTo(19f, 7.5f) } }

    val Home by lazy { icon("Home") { home() } }

    val Copy by lazy {
        icon("Copy") {
            roundRect(9f, 9f, 11f, 11f, 2.5f)
            moveTo(15f, 9f); verticalLineTo(6f)
            arcToRelative(2f, 2f, 0f, false, false, -2f, -2f)
            horizontalLineTo(6f)
            arcToRelative(2f, 2f, 0f, false, false, -2f, 2f)
            verticalLineTo(13f)
            arcToRelative(2f, 2f, 0f, false, false, 2f, 2f)
            horizontalLineTo(9f)
        }
    }
    val ChevronDown by lazy { icon("ChevronDown") { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) } }
    val InsertQuery by lazy { icon("InsertQuery") { moveTo(17f, 17f); lineTo(7f, 7f); moveTo(7f, 16f); verticalLineTo(7f); horizontalLineTo(16f) } }
    val Link by lazy {
        icon("Link") {
            moveTo(10f, 14f); lineToRelative(4f, -4f)
            moveTo(8.5f, 11.5f); lineToRelative(-2f, 2f)
            arcToRelative(3.5f, 3.5f, 0f, false, false, 5f, 5f)
            lineToRelative(2f, -2f)
            moveTo(15.5f, 12.5f); lineToRelative(2f, -2f)
            arcToRelative(3.5f, 3.5f, 0f, false, false, -5f, -5f)
            lineToRelative(-2f, 2f)
        }
    }

    val Download by lazy {
        icon("Download") {
            moveTo(12f, 15f); verticalLineTo(3f)
            moveTo(21f, 15f); verticalLineToRelative(4f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
            horizontalLineTo(5f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
            verticalLineToRelative(-4f)
            moveTo(7f, 10f); lineToRelative(5f, 5f); lineToRelative(5f, -5f)
        }
    }

    val Search by lazy { icon("Search") { circle(11f, 11f, 7.5f); moveTo(21f, 21f); lineToRelative(-4.6f, -4.6f) } }

    val Lock by lazy {
        icon("Lock") {
            roundRect(4f, 11f, 16f, 10f, 2.5f)
            moveTo(8f, 11f); verticalLineTo(7f)
            arcToRelative(4f, 4f, 0f, false, true, 8f, 0f)
            verticalLineToRelative(4f)
        }
    }

    val Info by lazy { icon("Info") { info() } }
    val Alert by lazy { icon("Alert") { circle(12f, 12f, 9.5f); moveTo(12f, 7.5f); verticalLineTo(12.5f); moveTo(12f, 16.2f); horizontalLineToRelative(0.01f) } }
    val History by lazy { icon("History") { circle(12f, 12f, 9.5f); moveTo(12f, 7f); verticalLineToRelative(5f); lineToRelative(3f, 2f) } }

    val Settings by lazy { icon("Settings") { gear() } }

    val Palette by lazy {
        icon("Palette") {
            moveTo(12f, 3f); arcToRelative(9f, 9f, 0f, true, false, 0f, 18f)
            curveToRelative(1.1f, 0f, 1.8f, -0.9f, 1.8f, -1.8f)
            curveToRelative(0f, -0.5f, -0.2f, -0.9f, -0.5f, -1.3f)
            curveToRelative(-0.3f, -0.4f, -0.5f, -0.8f, -0.5f, -1.3f)
            curveToRelative(0f, -1f, 0.8f, -1.8f, 1.8f, -1.8f)
            horizontalLineTo(17f)
            arcToRelative(4f, 4f, 0f, false, false, 4f, -4f)
            curveToRelative(0f, -4.4f, -4f, -7.8f, -9f, -7.8f)
            close()
            moveTo(13.5f, 6.5f); horizontalLineToRelative(0.01f)
            moveTo(17.5f, 10.5f); horizontalLineToRelative(0.01f)
            moveTo(8.5f, 7.5f); horizontalLineToRelative(0.01f)
            moveTo(6.5f, 12.5f); horizontalLineToRelative(0.01f)
        }
    }

    val EyeOff by lazy {
        icon("EyeOff") {
            moveTo(2.5f, 12f)
            curveTo(5f, 7.5f, 8.3f, 5.5f, 12f, 5.5f)
            curveTo(15.7f, 5.5f, 19f, 7.5f, 21.5f, 12f)
            curveTo(19f, 16.5f, 15.7f, 18.5f, 12f, 18.5f)
            curveTo(8.3f, 18.5f, 5f, 16.5f, 2.5f, 12f)
            close()
            circle(12f, 12f, 3f)
            moveTo(4f, 4f); lineTo(20f, 20f)
        }
    }

    val Eye by lazy {
        icon("Eye") {
            moveTo(2.5f, 12f)
            curveTo(5f, 7.5f, 8.3f, 5.5f, 12f, 5.5f)
            curveTo(15.7f, 5.5f, 19f, 7.5f, 21.5f, 12f)
            curveTo(19f, 16.5f, 15.7f, 18.5f, 12f, 18.5f)
            curveTo(8.3f, 18.5f, 5f, 16.5f, 2.5f, 12f)
            close()
            circle(12f, 12f, 3f)
        }
    }

    val ShieldCheck by lazy { icon("ShieldCheck") { shield() } }

    val Signal by lazy {
        icon("Signal") {
            circle(12f, 12f, 1.3f)
            moveTo(8.46f, 8.46f); arcTo(5f, 5f, 0f, false, false, 8.46f, 15.54f)
            moveTo(15.54f, 8.46f); arcTo(5f, 5f, 0f, false, true, 15.54f, 15.54f)
            moveTo(5.64f, 5.64f); arcTo(9f, 9f, 0f, false, false, 5.64f, 18.36f)
            moveTo(18.36f, 5.64f); arcTo(9f, 9f, 0f, false, true, 18.36f, 18.36f)
        }
    }

    val LinkOff by lazy {
        icon("LinkOff") {
            moveTo(10f, 14f); lineToRelative(4f, -4f)
            moveTo(8.5f, 11.5f); lineToRelative(-2f, 2f)
            arcToRelative(3.5f, 3.5f, 0f, false, false, 5f, 5f)
            lineToRelative(2f, -2f)
            moveTo(15.5f, 12.5f); lineToRelative(2f, -2f)
            arcToRelative(3.5f, 3.5f, 0f, false, false, -5f, -5f)
            lineToRelative(-2f, 2f)
            moveTo(3.5f, 3.5f); lineTo(20.5f, 20.5f)
        }
    }

    val Compass by lazy {
        icon("Compass") {
            circle(12f, 12f, 9.5f)
            moveTo(15.8f, 8.2f); lineTo(13.6f, 13.6f); lineTo(8.2f, 15.8f); lineTo(10.4f, 10.4f); close()
        }
    }

    val Globe by lazy {
        icon("Globe") {
            circle(12f, 12f, 9.5f)
            moveTo(2.5f, 12f); horizontalLineTo(21.5f)
            moveTo(12f, 2.5f)
            curveTo(15f, 5.5f, 16f, 8.8f, 16f, 12f)
            curveTo(16f, 15.2f, 15f, 18.5f, 12f, 21.5f)
            curveTo(9f, 18.5f, 8f, 15.2f, 8f, 12f)
            curveTo(8f, 8.8f, 9f, 5.5f, 12f, 2.5f)
        }
    }

    val PlayCircle by lazy { icon("PlayCircle") { circle(12f, 12f, 9.5f); moveTo(10f, 8.5f); lineTo(15.5f, 12f); lineTo(10f, 15.5f); close() } }

    val Pip by lazy { icon("Pip") { roundRect(3f, 5f, 18f, 14f, 2.5f); roundRect(12.5f, 11.5f, 5.5f, 4.5f, 1.2f) } }

    val Sparkles by lazy {
        icon("Sparkles") {
            moveTo(12f, 3f); lineTo(14f, 10f); lineTo(21f, 12f); lineTo(14f, 14f); lineTo(12f, 21f); lineTo(10f, 14f); lineTo(3f, 12f); lineTo(10f, 10f); close()
        }
    }

    val Gauge by lazy {
        icon("Gauge") {
            moveTo(5.64f, 19.36f); arcTo(9f, 9f, 0f, true, true, 18.36f, 19.36f)
            moveTo(12f, 13f); lineTo(16f, 8f)
        }
    }

    val Layers by lazy {
        icon("Layers") {
            moveTo(12f, 3.5f); lineTo(21f, 8.5f); lineTo(12f, 13.5f); lineTo(3f, 8.5f); close()
            moveTo(3f, 12.5f); lineTo(12f, 17.5f); lineTo(21f, 12.5f)
            moveTo(3f, 16.5f); lineTo(12f, 21.5f); lineTo(21f, 16.5f)
        }
    }

    val HelpCircle by lazy {
        icon("HelpCircle") {
            circle(12f, 12f, 9.5f)
            moveTo(9.5f, 9.5f)
            curveTo(9.5f, 8.2f, 10.6f, 7.3f, 12f, 7.3f)
            curveTo(13.4f, 7.3f, 14.5f, 8.2f, 14.5f, 9.5f)
            curveTo(14.5f, 11.3f, 12f, 11.2f, 12f, 13.3f)
            moveTo(12f, 16.6f); horizontalLineToRelative(0.01f)
        }
    }

    val Chip by lazy {
        icon("Chip") {
            roundRect(6f, 6f, 12f, 12f, 2f); roundRect(9.5f, 9.5f, 5f, 5f, 1f)
            moveTo(9f, 3f); verticalLineTo(6f); moveTo(15f, 3f); verticalLineTo(6f)
            moveTo(9f, 18f); verticalLineTo(21f); moveTo(15f, 18f); verticalLineTo(21f)
            moveTo(3f, 9f); horizontalLineTo(6f); moveTo(3f, 15f); horizontalLineTo(6f)
            moveTo(18f, 9f); horizontalLineTo(21f); moveTo(18f, 15f); horizontalLineTo(21f)
        }
    }

    val Tag by lazy {
        icon("Tag") {
            moveTo(3f, 3f); horizontalLineTo(12f); lineTo(21f, 12f); lineTo(12f, 21f); lineTo(3f, 12f); close()
            circle(7.5f, 7.5f, 1f)
        }
    }

    val Phone by lazy { icon("Phone") { roundRect(7f, 2.5f, 10f, 19f, 2.5f); moveTo(11f, 18.5f); horizontalLineToRelative(2f) } }

    val FindInPage by lazy {
        icon("FindInPage") {
            moveTo(3f, 6f); horizontalLineTo(21f)
            moveTo(3f, 12f); horizontalLineTo(8f)
            moveTo(3f, 18f); horizontalLineTo(8f)
            circle(15.5f, 14.5f, 3.5f)
            moveTo(21f, 20f); lineToRelative(-3f, -3f)
        }
    }

    // Поиск по странице в меню параметров: листок с лупой в правом нижнем углу (как в образце).
    val FindPage by lazy {
        icon("FindPage", stroke = 1.7f) {
            moveTo(12.5f, 21f); horizontalLineTo(7f)
            arcToRelative(3f, 3f, 0f, false, true, -3f, -3f)
            verticalLineTo(6f)
            arcToRelative(3f, 3f, 0f, false, true, 3f, -3f)
            horizontalLineTo(15f)
            arcToRelative(3f, 3f, 0f, false, true, 3f, 3f)
            verticalLineTo(10f)
            moveTo(8f, 8f); horizontalLineTo(14f)
            moveTo(8f, 12f); horizontalLineTo(11f)
            circle(16f, 16.5f, 3f)
            moveTo(21f, 21.5f); lineToRelative(-2.9f, -2.9f)
        }
    }

    val QrScan by lazy {
        icon("QrScan") {
            moveTo(3f, 8f); verticalLineTo(6f); arcToRelative(2f, 2f, 0f, false, true, 2f, -2f); horizontalLineTo(8f)
            moveTo(16f, 4f); horizontalLineTo(19f); arcToRelative(2f, 2f, 0f, false, true, 2f, 2f); verticalLineTo(8f)
            moveTo(21f, 16f); verticalLineTo(18f); arcToRelative(2f, 2f, 0f, false, true, -2f, 2f); horizontalLineTo(16f)
            moveTo(8f, 20f); horizontalLineTo(5f); arcToRelative(2f, 2f, 0f, false, true, -2f, -2f); verticalLineTo(16f)
            moveTo(7f, 12f); horizontalLineTo(17f)
        }
    }

    val Rows by lazy { icon("Rows") { roundRect(3f, 4f, 18f, 7f, 2.5f); roundRect(3f, 13f, 18f, 7f, 2.5f) } }
    val Grid by lazy {
        icon("Grid") {
            roundRect(3f, 3f, 7.5f, 7.5f, 2.2f); roundRect(13.5f, 3f, 7.5f, 7.5f, 2.2f)
            roundRect(3f, 13.5f, 7.5f, 7.5f, 2.2f); roundRect(13.5f, 13.5f, 7.5f, 7.5f, 2.2f)
        }
    }

    val Star by lazy { icon("Star") { star() } }
    val StarFilled by lazy { icon("StarFilled", filled = true) { star() } }

    val Mask by lazy {
        icon("Mask") {
            moveTo(3f, 11f); horizontalLineTo(21f)
            moveTo(6f, 11f); lineTo(8f, 4f); horizontalLineTo(16f); lineTo(18f, 11f)
            circle(8f, 17f, 3f); circle(16f, 17f, 3f)
            moveTo(11f, 16.5f); horizontalLineTo(13f)
        }
    }
    val MoreVert by lazy { BarMore }

    /** Человек (голова и плечи): значок группы «Личные» по умолчанию. */
    val Person by lazy {
        icon("Person", filled = true) {
            circle(12f, 7.6f, 3.9f)
            moveTo(4.6f, 19.6f)
            curveTo(4.6f, 16.3f, 7.9f, 14.2f, 12f, 14.2f)
            curveTo(16.1f, 14.2f, 19.4f, 16.3f, 19.4f, 19.6f)
            curveTo(19.4f, 20.1f, 19f, 20.5f, 18.5f, 20.5f)
            horizontalLineTo(5.5f)
            curveTo(5f, 20.5f, 4.6f, 20.1f, 4.6f, 19.6f)
            close()
        }
    }

    // Залитые значки групп вкладок (выбор значка группы, пилюля группы, меню групп). Порядок и номера задаёт GROUP_ICONS.
    val GroupHome by lazy {
        fillIcon("GroupHome") {
            moveTo(3f, 11.1f)
            curveTo(3f, 10.5f, 3.3f, 9.9f, 3.8f, 9.5f)
            lineTo(11.1f, 3.2f)
            curveTo(11.65f, 2.75f, 12.35f, 2.75f, 12.9f, 3.2f)
            lineTo(20.2f, 9.5f)
            curveTo(20.7f, 9.9f, 21f, 10.5f, 21f, 11.1f)
            verticalLineTo(19f)
            arcTo(2f, 2f, 0f, false, true, 19f, 21f)
            horizontalLineTo(15f)
            verticalLineTo(15f)
            arcTo(3f, 3f, 0f, false, false, 9f, 15f)
            verticalLineTo(21f)
            horizontalLineTo(5f)
            arcTo(2f, 2f, 0f, false, true, 3f, 19f)
            close()
        }
    }
    val GroupFolder by lazy {
        fillIcon("GroupFolder") {
            moveTo(2.5f, 6.5f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, -2f); horizontalLineToRelative(4.3f)
            lineToRelative(2.2f, 2.8f); horizontalLineToRelative(8.5f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, 2f); verticalLineTo(17.5f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 2f); horizontalLineTo(4.5f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f); close()
        }
    }
    /** Земной шар: диск с вырезанными экватором и меридианным кольцом (EvenOdd). */
    val GroupGlobe by lazy {
        fillIcon("GroupGlobe", evenOdd = true) {
            circle(12f, 12f, 10f)
            roundRect(2.6f, 11.1f, 18.8f, 1.8f, 0.9f)
            ellipse(12f, 12f, 4.8f, 9.4f)
            ellipse(12f, 12f, 3f, 9.4f)
        }
    }
    val GroupLayers by lazy {
        icon("GroupLayers", filled = true, stroke = 1.4f) {
            moveTo(12f, 3f); lineTo(20.8f, 8f); lineTo(12f, 12.9f); lineTo(3.2f, 8f); close()
            moveTo(3.2f, 9.9f); lineTo(12f, 14.9f); lineTo(20.8f, 9.9f); lineTo(20.8f, 11.1f); lineTo(12f, 16.1f); lineTo(3.2f, 11.1f); close()
            moveTo(3.2f, 13.1f); lineTo(12f, 18.1f); lineTo(20.8f, 13.1f); lineTo(20.8f, 14.3f); lineTo(12f, 19.3f); lineTo(3.2f, 14.3f); close()
        }
    }
    /** Залитая звезда с округлёнными лучами: чистая заливка без контура, углы скруглены кривыми. */
    val GroupStar by lazy { fillIcon("GroupStar") { roundedStar(12f, 12.9f, 10.2f, 4.7f, 1.7f) } }
    val GroupGrid by lazy {
        fillIcon("GroupGrid") {
            roundRect(3f, 3f, 8f, 8f, 2.4f); roundRect(13f, 3f, 8f, 8f, 2.4f)
            roundRect(3f, 13f, 8f, 8f, 2.4f); roundRect(13f, 13f, 8f, 8f, 2.4f)
        }
    }

    val Pencil by lazy {
        icon("Pencil") {
            moveTo(16f, 4f); lineToRelative(4f, 4f); lineTo(8f, 20f); lineTo(3.5f, 20.5f); lineTo(4f, 16f); close()
            moveTo(13.5f, 6.5f); lineToRelative(4f, 4f)
        }
    }

    val Trash by lazy {
        icon("Trash") {
            moveTo(3.5f, 6f); horizontalLineTo(20.5f)
            moveTo(18.5f, 6f); verticalLineToRelative(13f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
            horizontalLineToRelative(-9f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
            verticalLineTo(6f)
            moveTo(8.5f, 6f); verticalLineTo(4.5f)
            arcToRelative(1.5f, 1.5f, 0f, false, true, 1.5f, -1.5f)
            horizontalLineToRelative(4f)
            arcToRelative(1.5f, 1.5f, 0f, false, true, 1.5f, 1.5f)
            verticalLineTo(6f)
        }
    }

    val Share by lazy {
        icon("Share") {
            moveTo(4f, 12f); verticalLineToRelative(7f)
            arcToRelative(2f, 2f, 0f, false, false, 2f, 2f)
            horizontalLineToRelative(12f)
            arcToRelative(2f, 2f, 0f, false, false, 2f, -2f)
            verticalLineToRelative(-7f)
            moveTo(16f, 6f); lineToRelative(-4f, -4f); lineToRelative(-4f, 4f)
            moveTo(12f, 2f); verticalLineTo(15f)
        }
    }

    val Open by lazy { icon("Open") { moveTo(7f, 17f); lineTo(17f, 7f); moveTo(8f, 7f); horizontalLineTo(17f); verticalLineTo(16f) } }

    val File by lazy { icon("File") { fileBody() } }
    val FileDoc by lazy { icon("FileDoc") { fileBody(); moveTo(16f, 13f); horizontalLineTo(8f); moveTo(16f, 17f); horizontalLineTo(8f); moveTo(10f, 9f); horizontalLineTo(8f) } }
    val FilePdf by lazy { icon("FilePdf") { fileBody(); moveTo(8f, 13f); horizontalLineToRelative(2f); moveTo(8f, 17f); horizontalLineToRelative(8f); moveTo(13f, 13f); horizontalLineToRelative(3f) } }

    val Image by lazy {
        icon("Image") {
            roundRect(3f, 3f, 18f, 18f, 3f)
            circle(9f, 9f, 1.6f)
            moveTo(21f, 16f); lineToRelative(-5f, -5f); lineTo(6f, 21f)
        }
    }

    val Video by lazy {
        icon("Video") {
            roundRect(2f, 6f, 14f, 12f, 2.5f)
            moveTo(16f, 10.5f); lineTo(21.2f, 7.6f); verticalLineTo(16.4f); lineTo(16f, 13.5f)
        }
    }

    // Нота: ножка с перекладиной линией, головки залиты.
    val Audio by lazy {
        ImageVector.Builder("Audio", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(9f, 18f); verticalLineTo(5f); lineToRelative(12f, -2f); verticalLineToRelative(13f)
            }
            path(
                fill = SolidColor(Color.Black),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                circle(6f, 18f, 3f); circle(18f, 16f, 3f)
            }
        }.build()
    }

    // Робот Android (Material Symbols, залитый): значок для установочных файлов .apk.
    val Android by lazy { symbol("Android", "M40-240q9-107 65.5-197T256-580l-74-128q-6-9-3-19t13-15q8-5 18-2t16 12l74 128q86-36 180-36t180 36l74-128q6-9 16-12t18 2q10 5 13 15t-3 19l-74 128q94 53 150.5 143T920-240H40Zm240-110q21 0 35.5-14.5T330-400q0-21-14.5-35.5T280-450q-21 0-35.5 14.5T230-400q0 21 14.5 35.5T280-350Zm400 0q21 0 35.5-14.5T730-400q0-21-14.5-35.5T680-450q-21 0-35.5 14.5T630-400q0 21 14.5 35.5T680-350Z") }

    val Package by lazy {
        icon("Package") {
            moveTo(11f, 21.73f)
            arcToRelative(2f, 2f, 0f, false, false, 2f, 0f)
            lineToRelative(7f, -4f)
            arcTo(2f, 2f, 0f, false, false, 21f, 16f)
            verticalLineTo(8f)
            arcToRelative(2f, 2f, 0f, false, false, -1f, -1.73f)
            lineToRelative(-7f, -4f)
            arcToRelative(2f, 2f, 0f, false, false, -2f, 0f)
            lineToRelative(-7f, 4f)
            arcTo(2f, 2f, 0f, false, false, 3f, 8f)
            verticalLineToRelative(8f)
            arcToRelative(2f, 2f, 0f, false, false, 1f, 1.73f)
            close()
            moveTo(12f, 22f); verticalLineTo(12f)
            moveTo(3.3f, 7f); lineToRelative(8.7f, 5f); lineToRelative(8.7f, -5f)
        }
    }

    val Archive by lazy {
        icon("Archive") {
            roundRect(2f, 3f, 20f, 5f, 1.5f)
            moveTo(4f, 8f); verticalLineToRelative(11f)
            arcToRelative(2f, 2f, 0f, false, false, 2f, 2f)
            horizontalLineToRelative(12f)
            arcToRelative(2f, 2f, 0f, false, false, 2f, -2f)
            verticalLineTo(8f)
            moveTo(10f, 12f); horizontalLineToRelative(4f)
        }
    }

    val Camera by lazy {
        icon("Camera") {
            moveTo(3f, 8f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, -2f); horizontalLineToRelative(8f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, 2f); verticalLineToRelative(8f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 2f); horizontalLineTo(5f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f); close()
            moveTo(16f, 10.5f); lineToRelative(5f, -3f); verticalLineToRelative(9f); lineToRelative(-5f, -3f)
        }
    }

    val Mic by lazy {
        icon("Mic") {
            moveTo(12f, 3f)
            arcToRelative(3f, 3f, 0f, false, false, -3f, 3f); verticalLineToRelative(6f)
            arcToRelative(3f, 3f, 0f, false, false, 6f, 0f); verticalLineTo(6f)
            arcToRelative(3f, 3f, 0f, false, false, -3f, -3f); close()
            moveTo(5f, 11f); arcToRelative(7f, 7f, 0f, false, false, 14f, 0f)
            moveTo(12f, 18f); verticalLineToRelative(3f)
        }
    }

    val Pin by lazy {
        icon("Pin") {
            moveTo(12f, 21f)
            curveToRelative(0f, 0f, -7f, -6.2f, -7f, -11f)
            arcToRelative(7f, 7f, 0f, false, true, 14f, 0f)
            curveToRelative(0f, 4.8f, -7f, 11f, -7f, 11f); close()
            circle(12f, 10f, 2.5f)
        }
    }

    val Bell by lazy {
        icon("Bell") {
            moveTo(6f, 9f)
            arcToRelative(6f, 6f, 0f, false, true, 12f, 0f)
            curveToRelative(0f, 6f, 2f, 7.5f, 2f, 7.5f); horizontalLineTo(4f)
            curveToRelative(0f, 0f, 2f, -1.5f, 2f, -7.5f); close()
            moveTo(10f, 20f); arcToRelative(2f, 2f, 0f, false, false, 4f, 0f)
        }
    }

    val Key by lazy {
        icon("Key") {
            circle(7.5f, 15.5f, 4.5f)
            moveTo(10.7f, 12.3f); lineTo(20f, 3f)
            moveTo(16f, 7f); lineToRelative(3f, 3f)
            moveTo(13.5f, 9.5f); lineToRelative(2f, 2f)
        }
    }

    val Puzzle by lazy { icon("Puzzle") { puzzlePath() } }

    val Translate by lazy {
        icon("Translate") {
            moveTo(3f, 6f); horizontalLineTo(13f)
            moveTo(8f, 3.5f); verticalLineTo(6f)
            moveTo(5f, 10.5f); curveTo(6.5f, 9f, 9f, 7.5f, 11f, 6f)
            moveTo(5.5f, 6f); curveTo(6.5f, 9.5f, 9f, 12f, 12f, 13.2f)
            moveTo(12f, 20.5f); lineTo(16.5f, 10f); lineTo(21f, 20.5f)
            moveTo(13.8f, 17f); horizontalLineTo(19.2f)
        }
    }

    val Reader by lazy {
        icon("Reader") {
            moveTo(12f, 6.5f)
            curveTo(10.5f, 5f, 7.5f, 4.5f, 3.5f, 5f)
            verticalLineTo(18.5f)
            curveTo(7.5f, 18f, 10.5f, 18.5f, 12f, 20f)
            curveTo(13.5f, 18.5f, 16.5f, 18f, 20.5f, 18.5f)
            verticalLineTo(5f)
            curveTo(16.5f, 4.5f, 13.5f, 5f, 12f, 6.5f)
            moveTo(12f, 6.5f); verticalLineTo(20f)
        }
    }

    val PlusCircle by lazy { icon("PlusCircle") { circle(12f, 12f, 9.5f); moveTo(8f, 12f); horizontalLineTo(16f); moveTo(12f, 8f); verticalLineTo(16f) } }
    // Значки закрепления вкладки (кнопка-гвоздик) и «выбрать».
    val TabPin by lazy {
        icon("TabPin") {
            moveTo(9f, 3.5f); horizontalLineTo(15f)
            moveTo(10f, 3.5f); lineTo(10f, 9f); lineTo(7f, 13f); horizontalLineTo(17f); lineTo(14f, 9f); lineTo(14f, 3.5f)
            moveTo(12f, 13f); verticalLineTo(20.5f)
        }
    }
    val TabUnpin by lazy {
        icon("TabUnpin") {
            moveTo(9f, 3.5f); horizontalLineTo(15f)
            moveTo(10f, 3.5f); lineTo(10f, 9f); lineTo(7f, 13f); horizontalLineTo(17f); lineTo(14f, 9f); lineTo(14f, 3.5f)
            moveTo(12f, 13f); verticalLineTo(20.5f)
            moveTo(4f, 4f); lineTo(20f, 20f)
        }
    }
    val CheckCircle by lazy { icon("CheckCircle") { circle(12f, 12f, 9.5f); moveTo(8f, 12.5f); lineToRelative(3f, 3f); lineTo(16.5f, 9f) } }
    val ChevronRight by lazy { icon("ChevronRight") { moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f) } }

    val Fullscreen by lazy {
        icon("Fullscreen") {
            moveTo(4f, 9f); verticalLineTo(5f); arcToRelative(1f, 1f, 0f, false, true, 1f, -1f); horizontalLineTo(9f)
            moveTo(15f, 4f); horizontalLineTo(19f); arcToRelative(1f, 1f, 0f, false, true, 1f, 1f); verticalLineTo(9f)
            moveTo(20f, 15f); verticalLineTo(19f); arcToRelative(1f, 1f, 0f, false, true, -1f, 1f); horizontalLineTo(15f)
            moveTo(9f, 20f); horizontalLineTo(5f); arcToRelative(1f, 1f, 0f, false, true, -1f, -1f); verticalLineTo(15f)
        }
    }

    val Capture by lazy {
        icon("Capture") {
            moveTo(4f, 9f); verticalLineTo(5f); arcToRelative(1f, 1f, 0f, false, true, 1f, -1f); horizontalLineTo(9f)
            moveTo(15f, 4f); horizontalLineTo(19f); arcToRelative(1f, 1f, 0f, false, true, 1f, 1f); verticalLineTo(9f)
            moveTo(20f, 15f); verticalLineTo(19f); arcToRelative(1f, 1f, 0f, false, true, -1f, 1f); horizontalLineTo(15f)
            moveTo(9f, 20f); horizontalLineTo(5f); arcToRelative(1f, 1f, 0f, false, true, -1f, -1f); verticalLineTo(15f)
            circle(12f, 12f, 2.5f)
        }
    }

    val Qr by lazy {
        fillIcon("Qr", evenOdd = true) {
            for ((x, y) in listOf(3f to 3f, 15f to 3f, 3f to 15f)) {
                roundRect(x, y, 6f, 6f, 0.5f)
                rect(x + 2f, y + 2f, 2f, 2f)
            }
            moduleRect(11f, 5f, 2f, 4f)
            moduleRect(3f, 11f, 2f, 2f)
            moduleRect(9f, 11f, 4f, 2f)
            moduleRect(11f, 13f, 2f, 2f)
            moduleRect(15f, 11f, 2f, 2f)
            moduleRect(19f, 11f, 2f, 2f)
            moduleRect(17f, 13f, 2f, 2f)
            moduleRect(15f, 15f, 2f, 4f)
            moduleRect(19f, 15f, 2f, 4f)
            moduleRect(11f, 17f, 4f, 2f)
            moduleRect(11f, 19f, 2f, 2f)
            moduleRect(17f, 19f, 2f, 2f)
        }
    }

    val Print by lazy {
        icon("Print") {
            moveTo(7f, 8f); verticalLineTo(3f); horizontalLineTo(17f); verticalLineTo(8f)
            roundRect(3f, 8f, 18f, 9f, 2.5f)
            moveTo(7f, 14f); horizontalLineTo(17f); verticalLineTo(21f); horizontalLineTo(7f); close()
            moveTo(17f, 11.5f); horizontalLineToRelative(0.01f)
        }
    }

    val Desktop by lazy {
        icon("Desktop") {
            roundRect(3f, 4f, 18f, 12f, 2.5f)
            moveTo(8f, 20f); horizontalLineTo(16f)
            moveTo(12f, 16f); verticalLineTo(20f)
        }
    }

    val Shield by lazy { icon("Shield") { shield(withCheck = false) } }

    val Block by lazy {
        icon("Block") {
            circle(12f, 12f, 9f)
            moveTo(5.6f, 5.6f); lineTo(18.4f, 18.4f)
        }
    }

    val Folder by lazy {
        icon("Folder") {
            moveTo(3f, 7f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, -2f); horizontalLineToRelative(4f)
            lineToRelative(2f, 2.5f); horizontalLineToRelative(7f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, 2f); verticalLineTo(17f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 2f); horizontalLineTo(5f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f); close()
        }
    }

    val BarBack by lazy {
        icon("BarBack", stroke = 2f) {
            moveTo(18.85f, 12f); horizontalLineTo(5.15f)
            moveTo(11.65f, 18.5f); lineTo(5.15f, 12f); lineTo(11.65f, 5.5f)
        }
    }
    val BarForward by lazy {
        icon("BarForward", stroke = 2f) {
            moveTo(5.15f, 12f); horizontalLineTo(18.85f)
            moveTo(12.35f, 5.5f); lineTo(18.85f, 12f); lineTo(12.35f, 18.5f)
        }
    }

    // Шевроны «‹ ›» для нижней панели телефона (как в Safari).
    val BarChevronBack by lazy {
        icon("BarChevronBack", stroke = 2.4f) { moveTo(14.5f, 5.5f); lineTo(8f, 12f); lineTo(14.5f, 18.5f) }
    }
    val BarChevronForward by lazy {
        icon("BarChevronForward", stroke = 2.4f) { moveTo(9.5f, 5.5f); lineTo(16f, 12f); lineTo(9.5f, 18.5f) }
    }

    val BarRefresh by lazy {
        icon("BarRefresh", stroke = 2f) {
            moveTo(18.34f, 14.96f)
            arcTo(7f, 7f, 0f, true, true, 12f, 5f)
            curveTo(13.96f, 5f, 15.84f, 5.78f, 17.15f, 7.18f)
            lineTo(19f, 10f)
            moveTo(19f, 5f); verticalLineTo(10f); horizontalLineTo(14f)
        }
    }

    val BarClose by lazy { icon("BarClose", stroke = 2f) { moveTo(19f, 5f); lineTo(5f, 19f); moveTo(5f, 5f); lineTo(19f, 19f) } }

    val BarDownload by lazy {
        icon("BarDownload", stroke = 2f) {
            moveTo(12f, 5.15f); verticalLineTo(18.85f)
            moveTo(5.5f, 12.35f); lineTo(12f, 18.85f); lineTo(18.5f, 12.35f)
        }
    }

    val BarMore by lazy { fillIcon("BarMore") { circle(12f, 6f, 2f); circle(12f, 12f, 2f); circle(12f, 18f, 2f) } }
    // Ряд поиска: «галочка» кружка «Готово» и лупа с шевроном (параметры поиска), как в Safari.
    val FindCheck by lazy { icon("FindCheck", stroke = 2.3f) { moveTo(5f, 12.5f); lineToRelative(4.5f, 4.5f); lineTo(19f, 7.5f) } }
    // Параметры страницы: листок с двумя ползунками (настройки именно этой страницы).
    val PageOptions by lazy {
        icon("PageOptions", stroke = 1.8f) {
            roundRect(4f, 2.5f, 16f, 19f, 3.4f)
            moveTo(7.6f, 9f); lineTo(9.2f, 9f); circle(10.9f, 9f, 1.7f); moveTo(12.6f, 9f); lineTo(16.4f, 9f)
            moveTo(7.6f, 15f); lineTo(12f, 15f); circle(13.7f, 15f, 1.7f); moveTo(15.4f, 15f); lineTo(16.4f, 15f)
        }
    }
    // Кнопка меню сайта в адресной панели (как в Safari): три строки убывающей длины, прижатые влево.
    val SiteMenu by lazy {
        icon("SiteMenu", stroke = 2.2f) {
            moveTo(4.5f, 7f); horizontalLineTo(19.5f)
            moveTo(4.5f, 12f); horizontalLineTo(15.5f)
            moveTo(4.5f, 17f); horizontalLineTo(11.5f)
        }
    }
    val BarMoreH by lazy { fillIcon("BarMoreH") { circle(5f, 12f, 1.9f); circle(12f, 12f, 1.9f); circle(19f, 12f, 1.9f) } }


    // ── Значки панели планшета ──────────────────────────────────────────────────────────────────────────
    // Одна сетка 24×24, один штрих 2, круглые концы и углы. Все рисуются в рамке 24 dp без пересчёта размеров,
    // чтобы вес и размер совпадали. Координаты целые или с шагом 0,5: при 2 px/dp линии ложатся ровно на пиксели.
    // Мелкие значки внутри поля (замок, лупа, обновление) уменьшены через scale, штрих при этом остаётся 2.
    private const val TB = 2f
    // Размеры сняты со скриншота образца (2 px на dp, штрих 2 dp, габарит значка вместе со штрихом):
    // стрелка 15,5 x 15, домик 16 x 17,5, обновить 16 x 16, счётчик вкладок 20 x 20, три точки 4 x 16.
    // Шеврон берёт высоту стрелки (15 dp), угол 45 градусов.
    val TbBack by lazy { icon("TbBack", stroke = TB) { moveTo(15f, 5.5f); lineTo(8.5f, 12f); lineTo(15f, 18.5f) } }
    val TbForward by lazy { icon("TbForward", stroke = TB) { moveTo(9f, 5.5f); lineTo(15.5f, 12f); lineTo(9f, 18.5f) } }
    val TbPlus by lazy { icon("TbPlus", stroke = TB) { moveTo(5f, 12f); horizontalLineTo(19f); moveTo(12f, 5f); verticalLineTo(19f) } }
    // Открытые значки (стрелка, плюс, загрузка) занимают 16 dp, как «обновить» в образце, рамочные (счётчик, пазл,
    // фото, QR, боковая панель) 20 dp, как счётчик вкладок. Контур от 3 до 21 вместе со штрихом даёт 20 dp,
    // плюс от 5 до 19 даёт 16 dp.
    val TbNewTab by lazy { icon("TbNewTab", stroke = TB) { moveTo(5f, 12f); horizontalLineTo(19f); moveTo(12f, 5f); verticalLineTo(19f) } }
    // Три точки по вертикали: диаметр 4 dp, шаг 6 dp, общая высота 16 dp.
    val TbMoreV by lazy { fillIcon("TbMoreV") { circle(12f, 6f, 2f); circle(12f, 12f, 2f); circle(12f, 18f, 2f) } }
    val TbImage by lazy {
        icon("TbImage", stroke = TB) {
            roundRect(3f, 3f, 18f, 18f, 3f)
            circle(9f, 9f, 1.5f)
            moveTo(21f, 16f); lineToRelative(-5f, -5f); lineTo(6f, 21f)
        }
    }
    // QR в той же сетке и с тем же штрихом, что и остальные значки панели: три угла контуром и точки.
    val TbQr by lazy {
        icon("TbQr", stroke = TB) {
            roundRect(3f, 3f, 6f, 6f, 1f); roundRect(15f, 3f, 6f, 6f, 1f); roundRect(3f, 15f, 6f, 6f, 1f)
            moveTo(15f, 15f); horizontalLineToRelative(0.01f)
            moveTo(21f, 15f); horizontalLineToRelative(0.01f)
            moveTo(18f, 18f); horizontalLineToRelative(0.01f)
            moveTo(15f, 21f); horizontalLineToRelative(0.01f)
            moveTo(21f, 21f); horizontalLineToRelative(0.01f)
        }
    }
    val TbSidebar by lazy { icon("TbSidebar", stroke = TB) { roundRect(3f, 4f, 18f, 16f, 3f); moveTo(9f, 4f); verticalLineTo(20f) } }
    val TbClose by lazy { icon("TbClose", stroke = TB) { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) } }
    val TbCloseSmall by lazy { icon("TbCloseSmall", stroke = TB, scale = 0.75f) { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) } }
    val TbHome by lazy {
        icon("TbHome", stroke = TB) {
            moveTo(5f, 10f); lineTo(12f, 4.5f); lineTo(19f, 10f); verticalLineTo(18f)
            quadTo(19f, 20f, 17f, 20f); horizontalLineTo(7f); quadTo(5f, 20f, 5f, 18f); close()
            moveTo(9.5f, 20f); verticalLineTo(14.5f); horizontalLineTo(14.5f); verticalLineTo(20f)
        }
    }
    val TbRefresh by lazy {
        icon("TbRefresh", stroke = TB, scale = 0.78f) {
            moveTo(21f, 12f); arcToRelative(9f, 9f, 0f, true, true, -9f, -9f)
            curveToRelative(2.52f, 0f, 4.93f, 1f, 6.74f, 2.74f); lineTo(21f, 8f)
            moveTo(21f, 3f); verticalLineToRelative(5f); horizontalLineToRelative(-5f)
        }
    }
    val TbStop by lazy { icon("TbStop", stroke = TB, scale = 0.7f) { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) } }
    val TbDownload by lazy { icon("TbDownload", stroke = TB) { moveTo(12f, 5f); verticalLineTo(19f); moveTo(5f, 12f); lineTo(12f, 19f); lineTo(19f, 12f) } }
    val TbPuzzle by lazy { icon("TbPuzzle", stroke = TB, scale = 1f) { puzzlePath() } }
    val TbMore by lazy { fillIcon("TbMore") { circle(5f, 12f, 1.75f); circle(12f, 12f, 1.75f); circle(19f, 12f, 1.75f) } }
    // Значки, которые можно закрепить рядом с расширениями: тот же штрих 2 и рамка 20 dp.
    val TbStar by lazy { icon("TbStar", stroke = TB, scale = 0.95f) { star() } }
    val TbHistory by lazy { icon("TbHistory", stroke = TB) { circle(12f, 12f, 9f); moveTo(12f, 7f); verticalLineToRelative(5f); lineToRelative(3f, 2f) } }
    val TbFind by lazy {
        icon("TbFind", stroke = TB) {
            moveTo(3f, 6f); horizontalLineTo(21f)
            moveTo(3f, 12f); horizontalLineTo(8f)
            moveTo(3f, 18f); horizontalLineTo(8f)
            circle(15.5f, 14.5f, 3.5f)
            moveTo(21f, 20f); lineToRelative(-3f, -3f)
        }
    }
    val TbSettings by lazy { icon("TbSettings", stroke = TB, scale = 0.95f) { gear() } }
    val TbShield by lazy { icon("TbShield", stroke = TB, scale = 0.85f) { shield() } }
    val TbInfo by lazy { icon("TbInfo", stroke = TB, scale = 0.8f) { info() } }
    val TbSearch by lazy { icon("TbSearch", stroke = TB, scale = 0.85f) { circle(10.5f, 10.5f, 6.5f); moveTo(15.5f, 15.5f); lineTo(20.5f, 20.5f) } }

    val BarHome by lazy { icon("BarHome", stroke = 2f, scale = 0.8f) { home() } }
    val Pause by lazy {
        icon("Pause", filled = true) { roundRect(6.5f, 5f, 3.8f, 14f, 1.4f); roundRect(13.7f, 5f, 3.8f, 14f, 1.4f) }
    }
    val Play by lazy {
        icon("Play", filled = true) { moveTo(8f, 5.8f); lineTo(18.2f, 12f); lineTo(8f, 18.2f); close() }
    }
    val BarShield by lazy { icon("BarShield", stroke = 2f, scale = 0.833f) { shield() } }
    val BarInfo by lazy { icon("BarInfo", stroke = 2f, scale = 0.75f) { info() } }

    // Значки из Google Material Symbols (Rounded, Apache 2.0): PasswordKey заливной, остальные контурные (FILL=0). Окно 960x960, начало по Y смещено на -960.
    // Остальные значки настроек нарисованы своим контурным стилем выше.
    val PasswordKey by lazy { symbol("PasswordKey", "M188.5-388.5Q151-426 151-480t37.5-91.5Q226-609 280-609t91.5 37.5Q409-534 409-480t-37.5 91.5Q334-351 280-351t-91.5-37.5ZM280-240q81 0 142.5-46T503-407h32l50 50q5 5 10 7t11 2q6 0 11-2t10-7l63-63 63 63q5 5 10 7t11 2q6 0 11-2t10-7l104-104q5-5 7-10t2-11q0-6-2-11t-7-10l-43-43q-5-5-10-7t-11-2H503q-23-72-80.5-118.5T280-720q-100 0-170 70T40-480q0 100 70 170t170 70Z") }
    val PaletteLine by lazy { symbol("PaletteLine", "M480-80q-82 0-155-31.5t-127.5-86Q143-252 111.5-325T80-480q0-85 32-158t87.5-127q55.5-54 130-84.5T489-880q79 0 150 26.5T763.5-780q53.5 47 85 111.5T880-527q0 108-63 170.5T650-294h-75q-18 0-31 14t-13 31q0 27 14.5 46t14.5 44q0 38-21 58.5T480-80Zm0-400Zm-198 11q15-15 15-35t-15-35q-15-15-35-15t-35 15q-15 15-15 35t15 35q15 15 35 15t35-15Zm126-170q15-15 15-35t-15-35q-15-15-35-15t-35 15q-15 15-15 35t15 35q15 15 35 15t35-15Zm214 0q15-15 15-35t-15-35q-15-15-35-15t-35 15q-15 15-15 35t15 35q15 15 35 15t35-15Zm131 170q15-15 15-35t-15-35q-15-15-35-15t-35 15q-15 15-15 35t15 35q15 15 35 15t35-15ZM480-140q11 0 15.5-4.5T500-159q0-14-14.5-26T471-238q0-46 30-81t76-35h73q76 0 123-44.5T820-527q0-132-100-212.5T489-820q-146 0-247.5 98.5T140-480q0 141 99.5 240.5T480-140Z") }
    val NotificationsLine by lazy { symbol("NotificationsLine", "M190-200q-12.75 0-21.37-8.68-8.63-8.67-8.63-21.5 0-12.82 8.63-21.32 8.62-8.5 21.37-8.5h50v-304q0-84 49.5-150.5T420-798v-22q0-25 17.5-42.5T480-880q25 0 42.5 17.5T540-820v22q81 17 130.5 83.5T720-564v304h50q12.75 0 21.38 8.68 8.62 8.67 8.62 21.5 0 12.82-8.62 21.32-8.63 8.5-21.38 8.5H190Zm290-302Zm0 422q-33 0-56.5-23.5T400-160h160q0 33-23.5 56.5T480-80ZM300-260h360v-304q0-75-52.5-127.5T480-744q-75 0-127.5 52.5T300-564v304Z") }
    val MotionPlayLine by lazy { symbol("MotionPlayLine", "m429-337 179-131q6-5 6-12.5t-6-12.5L429-623q-8-5-16-.93-8 4.06-8 12.93v261q0 9.39 8 13.7 8 4.3 16-.7Zm51 257q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-34 5.5-67.01Q91-580.02 102-612q4-11 15-14.5t21 2.8q10.71 6.3 15.36 18.5Q158-593 155-581q-6 25-10.5 50.15Q140-505.71 140-480q0 142.37 99 241.19Q338-140 480-140t241-98.81q99-98.82 99-241.19 0-142.38-98.81-241.19T480-820q-27.57 0-54.57 4T372-804q-12 4-24-1t-17-16q-5-11 1-22t17-15q31-10 63-16t64-6q83 0 156.5 31.5T760.95-763q54.94 54 87 127Q880-563 880-480t-31.5 156Q817-251 763-197t-127 85.5Q563-80 480-80ZM177.5-713.62q-14.5-14.62-14.5-35.5 0-20.88 14.62-35.38 14.62-14.5 35.5-14.5 20.88 0 35.38 14.62 14.5 14.62 14.5 35.5 0 20.88-14.62 35.38-14.62 14.5-35.5 14.5-20.88 0-35.38-14.62ZM480-480Z") }
    val KeyboardLine by lazy { symbol("KeyboardLine", "M140-200q-24 0-42-18.5T80-260v-440q0-24 18-42t42-18h680q24 0 42 18t18 42v440q0 23-18 41.5T820-200H140Zm0-60h680v-440H140v440Zm190-65h300q12.75 0 21.38-8.68 8.62-8.67 8.62-21.5 0-12.82-8.62-21.32-8.63-8.5-21.38-8.5H330q-12.75 0-21.37 8.68-8.63 8.67-8.63 21.5 0 12.82 8.63 21.32 8.62 8.5 21.37 8.5Zm-190 65v-440 440Zm114.5-323.68q8.5-8.67 8.5-21.5 0-12.82-8.68-21.32-8.67-8.5-21.5-8.5-12.82 0-21.32 8.68-8.5 8.67-8.5 21.5 0 12.82 8.68 21.32 8.67 8.5 21.5 8.5 12.82 0 21.32-8.68Zm124 0q8.5-8.67 8.5-21.5 0-12.82-8.68-21.32-8.67-8.5-21.5-8.5-12.82 0-21.32 8.68-8.5 8.67-8.5 21.5 0 12.82 8.68 21.32 8.67 8.5 21.5 8.5 12.82 0 21.32-8.68Zm123 0q8.5-8.67 8.5-21.5 0-12.82-8.68-21.32-8.67-8.5-21.5-8.5-12.82 0-21.32 8.68-8.5 8.67-8.5 21.5 0 12.82 8.68 21.32 8.67 8.5 21.5 8.5 12.82 0 21.32-8.68Zm124 0q8.5-8.67 8.5-21.5 0-12.82-8.68-21.32-8.67-8.5-21.5-8.5-12.82 0-21.32 8.68-8.5 8.67-8.5 21.5 0 12.82 8.68 21.32 8.67 8.5 21.5 8.5 12.82 0 21.32-8.68Zm123 0q8.5-8.67 8.5-21.5 0-12.82-8.68-21.32-8.67-8.5-21.5-8.5-12.82 0-21.32 8.68-8.5 8.67-8.5 21.5 0 12.82 8.68 21.32 8.67 8.5 21.5 8.5 12.82 0 21.32-8.68Zm-494 125q8.5-8.67 8.5-21.5 0-12.82-8.68-21.32-8.67-8.5-21.5-8.5-12.82 0-21.32 8.68-8.5 8.67-8.5 21.5 0 12.82 8.68 21.32 8.67 8.5 21.5 8.5 12.82 0 21.32-8.68Zm124 0q8.5-8.67 8.5-21.5 0-12.82-8.68-21.32-8.67-8.5-21.5-8.5-12.82 0-21.32 8.68-8.5 8.67-8.5 21.5 0 12.82 8.68 21.32 8.67 8.5 21.5 8.5 12.82 0 21.32-8.68Zm123 0q8.5-8.67 8.5-21.5 0-12.82-8.68-21.32-8.67-8.5-21.5-8.5-12.82 0-21.32 8.68-8.5 8.67-8.5 21.5 0 12.82 8.68 21.32 8.67 8.5 21.5 8.5 12.82 0 21.32-8.68Zm124 0q8.5-8.67 8.5-21.5 0-12.82-8.68-21.32-8.67-8.5-21.5-8.5-12.82 0-21.32 8.68-8.5 8.67-8.5 21.5 0 12.82 8.68 21.32 8.67 8.5 21.5 8.5 12.82 0 21.32-8.68Zm123 0q8.5-8.67 8.5-21.5 0-12.82-8.68-21.32-8.67-8.5-21.5-8.5-12.82 0-21.32 8.68-8.5 8.67-8.5 21.5 0 12.82 8.68 21.32 8.67 8.5 21.5 8.5 12.82 0 21.32-8.68Z") }

    private fun symbol(name: String, d: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 960f, 960f).apply {
            addGroup(translationY = 960f)
            addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black))
            clearGroup()
        }.build()
}

private fun icon(
    name: String,
    filled: Boolean = false,
    stroke: Float = 1.8f,
    scale: Float = 1f,
    block: PathBuilder.() -> Unit,
): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        if (scale != 1f) addGroup(pivotX = 12f, pivotY = 12f, scaleX = scale, scaleY = scale)
        path(
            fill = if (filled) SolidColor(Color.Black) else null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = stroke / scale,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
        if (scale != 1f) clearGroup()
    }.build()

private fun fillIcon(name: String, evenOdd: Boolean = false, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(
            fill = SolidColor(Color.Black),
            stroke = null,
            pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
            pathBuilder = block,
        )
    }.build()

private fun PathBuilder.rect(x: Float, y: Float, w: Float, h: Float) {
    moveTo(x, y); horizontalLineToRelative(w); verticalLineToRelative(h); horizontalLineToRelative(-w); close()
}

private fun PathBuilder.moduleRect(x: Float, y: Float, w: Float, h: Float) = roundRect(x, y, w, h, 0.2f)

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx + r, cy)
    arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
    arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
}

/** Пятиконечная звезда (10 вершин), каждая вершина скруглена квадратичной кривой на отрезке [round] по сторонам. */
private fun PathBuilder.roundedStar(cx: Float, cy: Float, outer: Float, inner: Float, round: Float) {
    val n = 10
    val xs = FloatArray(n)
    val ys = FloatArray(n)
    for (i in 0 until n) {
        val r = if (i % 2 == 0) outer else inner
        val a = -PI / 2 + i * PI / 5
        xs[i] = cx + (r * cos(a)).toFloat()
        ys[i] = cy + (r * sin(a)).toFloat()
    }
    for (i in 0 until n) {
        val p = (i + n - 1) % n
        val q = (i + 1) % n
        val d1 = kotlin.math.hypot(xs[p] - xs[i], ys[p] - ys[i])
        val d2 = kotlin.math.hypot(xs[q] - xs[i], ys[q] - ys[i])
        val k1 = minOf(round, d1 / 2f) / d1
        val k2 = minOf(round, d2 / 2f) / d2
        val sx = xs[i] + (xs[p] - xs[i]) * k1
        val sy = ys[i] + (ys[p] - ys[i]) * k1
        val ex = xs[i] + (xs[q] - xs[i]) * k2
        val ey = ys[i] + (ys[q] - ys[i]) * k2
        if (i == 0) moveTo(sx, sy) else lineTo(sx, sy)
        quadTo(xs[i], ys[i], ex, ey)
    }
    close()
}

private fun PathBuilder.ellipse(cx: Float, cy: Float, rx: Float, ry: Float) {
    moveTo(cx + rx, cy)
    arcToRelative(rx, ry, 0f, true, true, -2 * rx, 0f)
    arcToRelative(rx, ry, 0f, true, true, 2 * rx, 0f)
}

private fun PathBuilder.home() {
    moveTo(15f, 21f); verticalLineToRelative(-8f)
    arcToRelative(1f, 1f, 0f, false, false, -1f, -1f)
    horizontalLineToRelative(-4f)
    arcToRelative(1f, 1f, 0f, false, false, -1f, 1f)
    verticalLineToRelative(8f)
    moveTo(3f, 10f)
    arcToRelative(2f, 2f, 0f, false, true, 0.709f, -1.528f)
    lineToRelative(7f, -5.999f)
    arcToRelative(2f, 2f, 0f, false, true, 2.582f, 0f)
    lineToRelative(7f, 5.999f)
    arcTo(2f, 2f, 0f, false, true, 21f, 10f)
    verticalLineToRelative(9f)
    arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
    horizontalLineTo(5f)
    arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
    close()
}

private fun PathBuilder.shield(withCheck: Boolean = true) {
    moveTo(12f, 3f); lineToRelative(7f, 3f); verticalLineToRelative(5.5f)
    curveToRelative(0f, 4.4f, -3f, 7.6f, -7f, 9.5f)
    curveToRelative(-4f, -1.9f, -7f, -5.1f, -7f, -9.5f)
    verticalLineTo(6f); close()
    if (withCheck) { moveTo(9f, 12f); lineToRelative(2f, 2f); lineToRelative(4f, -4f) }
}

private fun PathBuilder.info() {
    circle(12f, 12f, 9.5f); moveTo(12f, 16.5f); verticalLineTo(11.5f); moveTo(12f, 8f); horizontalLineToRelative(0.01f)
}

private fun PathBuilder.roundRect(x: Float, y: Float, w: Float, h: Float, r: Float) {
    moveTo(x + r, y)
    horizontalLineToRelative(w - 2 * r)
    arcToRelative(r, r, 0f, false, true, r, r)
    verticalLineToRelative(h - 2 * r)
    arcToRelative(r, r, 0f, false, true, -r, r)
    horizontalLineToRelative(-(w - 2 * r))
    arcToRelative(r, r, 0f, false, true, -r, -r)
    verticalLineToRelative(-(h - 2 * r))
    arcToRelative(r, r, 0f, false, true, r, -r)
    close()
}

private fun PathBuilder.fileBody() {
    moveTo(14f, 2f)
    horizontalLineTo(6f)
    arcToRelative(2f, 2f, 0f, false, false, -2f, 2f)
    verticalLineToRelative(16f)
    arcToRelative(2f, 2f, 0f, false, false, 2f, 2f)
    horizontalLineToRelative(12f)
    arcToRelative(2f, 2f, 0f, false, false, 2f, -2f)
    verticalLineTo(8f)
    close()
    moveTo(14f, 2f); verticalLineToRelative(6f); horizontalLineToRelative(6f)
}

private fun PathBuilder.gear() {
            moveTo(10.27f, 4.80f); lineTo(10.72f, 2.49f); lineTo(13.28f, 2.49f); lineTo(13.73f, 4.80f); lineTo(15.87f, 5.69f); lineTo(17.82f, 4.37f); lineTo(19.63f, 6.18f); lineTo(18.31f, 8.13f); lineTo(19.20f, 10.27f); lineTo(21.51f, 10.72f); lineTo(21.51f, 13.28f); lineTo(19.20f, 13.73f); lineTo(18.31f, 15.87f); lineTo(19.63f, 17.82f); lineTo(17.82f, 19.63f); lineTo(15.87f, 18.31f); lineTo(13.73f, 19.20f); lineTo(13.28f, 21.51f); lineTo(10.72f, 21.51f); lineTo(10.27f, 19.20f); lineTo(8.13f, 18.31f); lineTo(6.18f, 19.63f); lineTo(4.37f, 17.82f); lineTo(5.69f, 15.87f); lineTo(4.80f, 13.73f); lineTo(2.49f, 13.28f); lineTo(2.49f, 10.72f); lineTo(4.80f, 10.27f); lineTo(5.69f, 8.13f); lineTo(4.37f, 6.18f); lineTo(6.18f, 4.37f); lineTo(8.13f, 5.69f); close(); circle(12f, 12f, 3.2f)
}

private fun PathBuilder.star() {
    val outer = 9.2f
    val inner = 4.3f
    val cx = 12f
    val cy = 12.8f
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) outer else inner
        val a = -PI / 2 + i * PI / 5
        val x = cx + (r * cos(a)).toFloat()
        val y = cy + (r * sin(a)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private fun PathBuilder.puzzlePath() {
            moveTo(18.88f, 11.57f); horizontalLineTo(17.59f); verticalLineTo(8.13f)
            curveToRelative(0f, -0.946f, -0.774f, -1.72f, -1.72f, -1.72f); horizontalLineToRelative(-3.44f); verticalLineTo(5.12f)
            curveTo(12.43f, 3.933f, 11.467f, 2.97f, 10.28f, 2.97f); curveTo(9.093f, 2.97f, 8.13f, 3.933f, 8.13f, 5.12f); verticalLineTo(6.41f); horizontalLineTo(4.69f)
            curveToRelative(-0.946f, 0f, -1.72f, 0.774f, -1.72f, 1.72f); verticalLineToRelative(3.268f); horizontalLineTo(4.26f)
            curveToRelative(1.281f, 0f, 2.322f, 1.041f, 2.322f, 2.322f); curveToRelative(0f, 1.281f, -1.041f, 2.322f, -2.322f, 2.322f); horizontalLineTo(2.97f)
            verticalLineTo(19.31f); curveToRelative(0f, 0.946f, 0.774f, 1.72f, 1.72f, 1.72f); horizontalLineToRelative(3.268f); verticalLineToRelative(-1.29f)
            curveToRelative(0f, -1.281f, 1.041f, -2.322f, 2.322f, -2.322f); curveToRelative(1.281f, 0f, 2.322f, 1.041f, 2.322f, 2.322f); verticalLineTo(21.03f)
            horizontalLineTo(15.87f); curveToRelative(0.946f, 0f, 1.72f, -0.774f, 1.72f, -1.72f); verticalLineToRelative(-3.44f); horizontalLineToRelative(1.29f)
            curveToRelative(1.187f, 0f, 2.15f, -0.963f, 2.15f, -2.15f); curveTo(21.03f, 12.533f, 20.067f, 11.57f, 18.88f, 11.57f); close()
}
