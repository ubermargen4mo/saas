package com.hrips.browser

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Свой набор иконок: только контур (outline), концы линий и углы скруглены.
 * Сетка 24x24, толщина линии 1.8. Все иконки рисуются одной линией без заливки,
 * поэтому красятся через tint как обычные Icon. Исключение: [StarFilled] (закладка добавлена).
 */
object HripsIcons {
    val Back by lazy { icon("Back") { moveTo(19f, 12f); horizontalLineTo(5f); moveTo(12f, 19f); lineToRelative(-7f, -7f); lineToRelative(7f, -7f) } }
    val Forward by lazy { icon("Forward") { moveTo(5f, 12f); horizontalLineTo(19f); moveTo(12f, 5f); lineToRelative(7f, 7f); lineToRelative(-7f, 7f) } }
    val Close by lazy { icon("Close") { moveTo(18f, 6f); lineTo(6f, 18f); moveTo(6f, 6f); lineTo(18f, 18f) } }
    val Add by lazy { icon("Add") { moveTo(5f, 12f); horizontalLineTo(19f); moveTo(12f, 5f); verticalLineTo(19f) } }
    val Check by lazy { icon("Check") { moveTo(5f, 12.5f); lineToRelative(4.5f, 4.5f); lineTo(19f, 7.5f) } }
    val Menu by lazy { icon("Menu") { moveTo(4f, 6f); horizontalLineTo(20f); moveTo(4f, 12f); horizontalLineTo(20f); moveTo(4f, 18f); horizontalLineTo(20f) } }

    val Refresh by lazy {
        icon("Refresh") {
            moveTo(21f, 12f)
            arcToRelative(9f, 9f, 0f, true, true, -9f, -9f)
            curveToRelative(2.52f, 0f, 4.93f, 1f, 6.74f, 2.74f)
            lineTo(21f, 8f)
            moveTo(21f, 3f); verticalLineToRelative(5f); horizontalLineToRelative(-5f)
        }
    }

    val Speed by lazy {
        icon("Speed") {
            moveTo(13f, 2f); lineTo(5f, 13f); horizontalLineTo(11f); lineTo(10f, 22f); lineTo(19f, 10f); horizontalLineTo(13f); close()
        }
    }

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

    /** Настройки: два ползунка (шестерёнка из линий получается слишком мелкой). */
    val Settings by lazy {
        icon("Settings") {
            moveTo(4f, 7f); horizontalLineTo(10f); moveTo(16f, 7f); horizontalLineTo(20f); circle(13f, 7f, 3f)
            moveTo(4f, 17f); horizontalLineTo(8f); moveTo(14f, 17f); horizontalLineTo(20f); circle(11f, 17f, 3f)
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
            // шляпа
            moveTo(3f, 11f); horizontalLineTo(21f)
            moveTo(6f, 11f); lineTo(8f, 4f); horizontalLineTo(16f); lineTo(18f, 11f)
            // очки
            circle(8f, 17f, 3f); circle(16f, 17f, 3f)
            moveTo(11f, 16.5f); horizontalLineTo(13f)
        }
    }
    val MoreVert by lazy { icon("MoreVert") { circle(12f, 5f, 1.4f); circle(12f, 12f, 1.4f); circle(12f, 19f, 1.4f) } }

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

    // Иконки типов файлов
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

    val Audio by lazy {
        icon("Audio") {
            moveTo(9f, 18f); verticalLineTo(5f); lineToRelative(12f, -2f); verticalLineToRelative(13f)
            circle(6f, 18f, 3f); circle(18f, 16f, 3f)
        }
    }

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

    /** Пазл: расширения. */
    val Puzzle by lazy {
        icon("Puzzle") {
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
    }

    /** Перевод: «A» и иероглиф. */
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

    val PlusCircle by lazy { icon("PlusCircle") { circle(12f, 12f, 9.5f); moveTo(8f, 12f); horizontalLineTo(16f); moveTo(12f, 8f); verticalLineTo(16f) } }
    val ChevronRight by lazy { icon("ChevronRight") { moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f) } }

    /** Рамка по углам: полноэкранный режим. */
    val Fullscreen by lazy {
        icon("Fullscreen") {
            moveTo(4f, 9f); verticalLineTo(5f); arcToRelative(1f, 1f, 0f, false, true, 1f, -1f); horizontalLineTo(9f)
            moveTo(15f, 4f); horizontalLineTo(19f); arcToRelative(1f, 1f, 0f, false, true, 1f, 1f); verticalLineTo(9f)
            moveTo(20f, 15f); verticalLineTo(19f); arcToRelative(1f, 1f, 0f, false, true, -1f, 1f); horizontalLineTo(15f)
            moveTo(9f, 20f); horizontalLineTo(5f); arcToRelative(1f, 1f, 0f, false, true, -1f, -1f); verticalLineTo(15f)
        }
    }

    /** Те же углы, но с точкой в центре: снимок области. */
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
        icon("Qr") {
            roundRect(3f, 3f, 7f, 7f, 1.5f)
            roundRect(14f, 3f, 7f, 7f, 1.5f)
            roundRect(3f, 14f, 7f, 7f, 1.5f)
            moveTo(14f, 17.5f); horizontalLineTo(17.5f); verticalLineTo(14f)
            moveTo(21f, 14f); verticalLineToRelative(0.01f)
            moveTo(14f, 21f); horizontalLineToRelative(0.01f)
            moveTo(17.5f, 21f); horizontalLineTo(21f)
        }
    }

    /** Монитор: версия для ПК. */
    val Desktop by lazy {
        icon("Desktop") {
            roundRect(3f, 4f, 18f, 12f, 2.5f)
            moveTo(8f, 20f); horizontalLineTo(16f)
            moveTo(12f, 16f); verticalLineTo(20f)
        }
    }

    val Shield by lazy { icon("Shield") { shield() } }

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

    // ---- Значки верхней панели (планшет). Размеры сняты со скриншота Оперы 1 в 1 (плотность 2.0) ----
    // Стрелки, обновление, три точки: линия 2dp, рисунок 16x16dp в рамке 24dp. Остальные значки панели - наши, но тоже приведены
    // к размерам значков Оперы: линейные (домой, щит) 16-17dp, значки в рамке (вкладки, расширения) 20dp.

    /** Назад: стержень 13.7dp, «голова» 6.5dp под 45 градусов, линия 2dp. */
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

    /** Обновить: кольцо r=7dp, разрыв справа (от 25 градусов), «уголок» стрелки 5x5dp в правом верхнем углу. */
    val BarRefresh by lazy {
        icon("BarRefresh", stroke = 2f) {
            moveTo(18.34f, 14.96f)
            arcTo(7f, 7f, 0f, true, true, 12f, 5f)
            curveTo(13.96f, 5f, 15.84f, 5.78f, 17.15f, 7.18f)
            lineTo(19f, 10f)
            moveTo(19f, 5f); verticalLineTo(10f); horizontalLineTo(14f)
        }
    }

    /** Остановить загрузку: тот же размер, что у остальных значков панели. */
    val BarClose by lazy { icon("BarClose", stroke = 2f) { moveTo(19f, 5f); lineTo(5f, 19f); moveTo(5f, 5f); lineTo(19f, 19f) } }

    /** Три точки: круги 4dp, шаг 6dp, высота 16dp. */
    val BarMore by lazy { fillIcon("BarMore") { circle(12f, 6f, 2f); circle(12f, 12f, 2f); circle(12f, 18f, 2f) } }

    /** Наши значки в размерах Оперы: домой 16x17dp, щит 14x17dp, «инфо» 16dp; линия 2dp. */
    val BarHome by lazy { icon("BarHome", stroke = 2f, scale = 0.8f) { home() } }
    val BarShield by lazy { icon("BarShield", stroke = 2f, scale = 0.833f) { shield() } }
    val BarInfo by lazy { icon("BarInfo", stroke = 2f, scale = 0.75f) { info() } }
}

/**
 * [stroke] - итоговая толщина линии в dp (в сетке 24 при размере иконки 24dp это 1 к 1).
 * [scale] - уменьшение рисунка вокруг центра сетки; толщину линии он не меняет: её компенсируем здесь.
 */
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

/** Только заливка, без обводки: точки меню. */
private fun fillIcon(name: String, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black), stroke = null, pathBuilder = block)
    }.build()

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx + r, cy)
    arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
    arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
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

private fun PathBuilder.shield() {
    moveTo(12f, 3f); lineToRelative(7f, 3f); verticalLineToRelative(5.5f)
    curveToRelative(0f, 4.4f, -3f, 7.6f, -7f, 9.5f)
    curveToRelative(-4f, -1.9f, -7f, -5.1f, -7f, -9.5f)
    verticalLineTo(6f); close()
    moveTo(9f, 12f); lineToRelative(2f, 2f); lineToRelative(4f, -4f)
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

/** Лист с загнутым уголком. */
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

/** Пятиконечная звезда; скругление даёт толстая линия с round-стыками. */
private fun PathBuilder.star() {
    val cx = 12f
    val cy = 12.8f
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) 9.2f else 4.3f
        val a = -PI / 2 + i * PI / 5
        val x = cx + (r * cos(a)).toFloat()
        val y = cy + (r * sin(a)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}
