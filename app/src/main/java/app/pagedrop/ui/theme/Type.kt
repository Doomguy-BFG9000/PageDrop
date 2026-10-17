package app.pagedrop.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.pagedrop.R

@OptIn(ExperimentalTextApi::class)
private fun literata(resId: Int, weight: FontWeight, style: FontStyle) = Font(
    resId,
    weight,
    style,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
)

/** Book serif: Literata (SIL OFL), bundled as variable fonts. */
val BookSerif = FontFamily(
    literata(R.font.literata, FontWeight.Normal, FontStyle.Normal),
    literata(R.font.literata, FontWeight.Medium, FontStyle.Normal),
    literata(R.font.literata, FontWeight.SemiBold, FontStyle.Normal),
    literata(R.font.literata, FontWeight.Bold, FontStyle.Normal),
    literata(R.font.literata_italic, FontWeight.Normal, FontStyle.Italic),
    literata(R.font.literata_italic, FontWeight.Medium, FontStyle.Italic),
    literata(R.font.literata_italic, FontWeight.SemiBold, FontStyle.Italic),
    literata(R.font.literata_italic, FontWeight.Bold, FontStyle.Italic),
)

private val Serif = BookSerif
private val Sans = FontFamily.Default

private fun serif(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, italic: Boolean = false) = TextStyle(
    fontFamily = Serif,
    fontWeight = weight,
    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    fontSize = size.sp,
    lineHeight = line.sp,
)

private fun sans(size: Int, line: Int, weight: FontWeight = FontWeight.Medium, spacing: Double = 0.4) = TextStyle(
    fontFamily = Sans,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = spacing.sp,
)

// Serif for headings, titles and reading text; plain sans only for small UI labels.
val Typography = Typography(
    displayLarge = serif(57, 64),
    displayMedium = serif(45, 52),
    displaySmall = serif(36, 44),
    headlineLarge = serif(32, 40, FontWeight.SemiBold),
    headlineMedium = serif(28, 36, FontWeight.SemiBold),
    headlineSmall = serif(24, 32, FontWeight.SemiBold),
    titleLarge = serif(22, 28, FontWeight.SemiBold),
    titleMedium = serif(16, 24, FontWeight.SemiBold),
    titleSmall = serif(14, 20, FontWeight.SemiBold),
    bodyLarge = serif(16, 24),
    bodyMedium = serif(14, 20),
    bodySmall = serif(12, 16),
    labelLarge = sans(14, 20),
    labelMedium = sans(12, 16),
    labelSmall = sans(11, 16, spacing = 0.5),
)
