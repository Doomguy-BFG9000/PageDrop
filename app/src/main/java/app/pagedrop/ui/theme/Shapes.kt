package app.pagedrop.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// E-ink look: nearly square corners everywhere.
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(4.dp),
    extraLarge = RoundedCornerShape(4.dp),
)

/** Kindle dialog buttons: filled ink / outlined rectangles with barely rounded corners. */
val ButtonShape = RoundedCornerShape(2.dp)
