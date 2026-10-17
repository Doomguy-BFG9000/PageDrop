/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.pagedrop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = PaperTextDark,
    onPrimary = InkDark,
    primaryContainer = InkSurfaceVariantDark,
    onPrimaryContainer = PaperTextDark,
    secondary = PaperSecondaryDark,
    onSecondary = InkDark,
    secondaryContainer = InkSurfaceVariantDark,
    onSecondaryContainer = PaperTextDark,
    tertiary = PaperSecondaryDark,
    onTertiary = InkDark,
    tertiaryContainer = InkSurfaceVariantDark,
    onTertiaryContainer = PaperTextDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    background = InkDark,
    onBackground = PaperTextDark,
    surface = InkSurfaceDark,
    onSurface = PaperTextDark,
    surfaceVariant = InkSurfaceVariantDark,
    onSurfaceVariant = PaperSecondaryDark,
    surfaceTint = Color.Transparent,
    outline = HairlineDark,
    outlineVariant = HairlineVariantDark,
    surfaceContainerLowest = InkDark,
    surfaceContainerLow = InkSurfaceDark,
    surfaceContainer = InkSurfaceDark,
    surfaceContainerHigh = InkSurfaceVariantDark,
    surfaceContainerHighest = InkSurfaceVariantDark,
)

private val LightColorScheme = lightColorScheme(
    primary = InkLight,
    onPrimary = PaperSurfaceLight,
    primaryContainer = PaperSurfaceVariantLight,
    onPrimaryContainer = InkLight,
    secondary = InkSecondaryLight,
    onSecondary = PaperSurfaceLight,
    secondaryContainer = PaperSurfaceVariantLight,
    onSecondaryContainer = InkLight,
    tertiary = InkSecondaryLight,
    onTertiary = PaperSurfaceLight,
    tertiaryContainer = PaperSurfaceVariantLight,
    onTertiaryContainer = InkLight,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    background = PaperLight,
    onBackground = InkLight,
    surface = PaperSurfaceLight,
    onSurface = InkLight,
    surfaceVariant = PaperSurfaceVariantLight,
    onSurfaceVariant = InkSecondaryLight,
    surfaceTint = Color.Transparent,
    outline = HairlineLight,
    outlineVariant = HairlineVariantLight,
    surfaceContainerLowest = PaperSurfaceLight,
    surfaceContainerLow = PaperSurfaceLight,
    surfaceContainer = PaperSurfaceLight,
    surfaceContainerHigh = PaperSurfaceVariantLight,
    surfaceContainerHighest = PaperSurfaceVariantLight,
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}
