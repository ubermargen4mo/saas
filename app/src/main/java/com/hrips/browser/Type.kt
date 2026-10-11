package com.hrips.browser

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Roboto Flex. Раньше был один вариативный файл и вес задавался через ось wght при загрузке,
 * на части устройств это не срабатывало и всё рисовалось обычным начертанием.
 * Теперь пять статических начертаний (латиница + кириллица), каждое подключается своим файлом:
 * вес берётся из самого файла и от вариативных осей не зависит. Параметры такие же, как у шрифта
 * на сайте (opsz 14, остальные оси по умолчанию).
 */
val RobotoFlex = FontFamily(
    Font(R.font.roboto_flex_regular, FontWeight.Normal),
    Font(R.font.roboto_flex_medium, FontWeight.Medium),
    Font(R.font.roboto_flex_semibold, FontWeight.SemiBold),
    Font(R.font.roboto_flex_bold, FontWeight.Bold),
    Font(R.font.roboto_flex_extrabold, FontWeight.ExtraBold),
)

private val base = Typography()

private val plain = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = RobotoFlex),
    displayMedium = base.displayMedium.copy(fontFamily = RobotoFlex),
    displaySmall = base.displaySmall.copy(fontFamily = RobotoFlex),
    headlineLarge = base.headlineLarge.copy(fontFamily = RobotoFlex, fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontFamily = RobotoFlex, fontWeight = FontWeight.Bold),
    headlineSmall = base.headlineSmall.copy(fontFamily = RobotoFlex, fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontFamily = RobotoFlex, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontFamily = RobotoFlex, fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontFamily = RobotoFlex, fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(fontFamily = RobotoFlex),
    bodyMedium = base.bodyMedium.copy(fontFamily = RobotoFlex),
    bodySmall = base.bodySmall.copy(fontFamily = RobotoFlex),
    labelLarge = base.labelLarge.copy(fontFamily = RobotoFlex, fontWeight = FontWeight.Medium),
    labelMedium = base.labelMedium.copy(fontFamily = RobotoFlex, fontWeight = FontWeight.Medium),
    labelSmall = base.labelSmall.copy(fontFamily = RobotoFlex, fontWeight = FontWeight.Medium),
)

/**
 * Expressive-типографика: к обычной шкале добавлены акцентные начертания (`...Emphasized`) –
 * плотнее и жирнее обычных, для заголовков экранов, приветствия и главных подписей.
 * Обычные стили остаются как были, акцент включается точечно через `MaterialTheme.typography.xxxEmphasized`.
 */
val HripsTypography = plain.copy(
    displayLargeEmphasized = plain.displayLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em),
    displayMediumEmphasized = plain.displayMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em),
    displaySmallEmphasized = plain.displaySmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.015).em),
    headlineLargeEmphasized = plain.headlineLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.015).em),
    headlineMediumEmphasized = plain.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.01).em),
    headlineSmallEmphasized = plain.headlineSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.01).em),
    titleLargeEmphasized = plain.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMediumEmphasized = plain.titleMedium.copy(fontWeight = FontWeight.Bold),
    titleSmallEmphasized = plain.titleSmall.copy(fontWeight = FontWeight.Bold),
    labelLargeEmphasized = plain.labelLarge.copy(fontWeight = FontWeight.Bold),
    labelMediumEmphasized = plain.labelMedium.copy(fontWeight = FontWeight.Bold),
    labelSmallEmphasized = plain.labelSmall.copy(fontWeight = FontWeight.Bold),
    bodyLargeEmphasized = plain.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
    bodyMediumEmphasized = plain.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
    bodySmallEmphasized = plain.bodySmall.copy(fontWeight = FontWeight.SemiBold),
)

/**
 * Название "hrips" на главной: обычное начертание (Regular, 400), без жирности.
 * Межбуквенный интервал чуть уже обычного, чтобы слово держалось одним блоком.
 */
val WordmarkStyle = TextStyle(
    fontFamily = RobotoFlex,
    fontWeight = FontWeight.Normal,
    fontSize = 72.sp,
    letterSpacing = (-0.03).em,
)
