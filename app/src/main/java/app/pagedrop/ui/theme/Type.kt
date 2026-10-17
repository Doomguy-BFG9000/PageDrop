package app.pagedrop.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font as GoogleFont
import androidx.compose.ui.text.googlefonts.GoogleFont as GoogleFontName
import androidx.compose.ui.unit.sp
import app.pagedrop.R

private val fontProvider = GoogleFontName.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

private val literata = GoogleFontName("Literata")

/** Book serif (Literata, downloaded on demand) with the system serif as fallback. */
val BookSerif = FontFamily(
    GoogleFont(literata, fontProvider, FontWeight.Normal),
    GoogleFont(literata, fontProvider, FontWeight.Normal, FontStyle.Italic),
    GoogleFont(literata, fontProvider, FontWeight.Medium),
    GoogleFont(literata, fontProvider, FontWeight.SemiBold),
    GoogleFont(literata, fontProvider, FontWeight.Bold),
    // Fallback while the download is pending or unavailable.
    Font(DeviceFontFamilyName("serif"), FontWeight.Normal),
    Font(DeviceFontFamilyName("serif"), FontWeight.Normal, FontStyle.Italic),
    Font(DeviceFontFamilyName("serif"), FontWeight.Bold),
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
