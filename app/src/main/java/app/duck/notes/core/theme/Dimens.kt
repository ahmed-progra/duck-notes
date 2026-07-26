package app.duck.notes.core.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The spacing scale: 4 / 8 / 12 / 16 / 24 / 32.
 *
 * Not theme-varying — all four themes share it — so unlike [DuckColors] these carry defaults.
 */
@Immutable
data class DuckSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
)

/**
 * Corner radii: 8 / 12 / 16.
 *
 * The radii are the source of truth and the shapes are derived from them, so `TokenTotalityTest` can
 * assert the scale against the spec's literals — a `RoundedCornerShape` does not read its own corner
 * size back out conveniently.
 */
@Immutable
data class DuckShapes(
    val smallRadius: Dp = 8.dp,
    val mediumRadius: Dp = 12.dp,
    val largeRadius: Dp = 16.dp,
) {
    val small: CornerBasedShape = RoundedCornerShape(smallRadius)
    val medium: CornerBasedShape = RoundedCornerShape(mediumRadius)
    val large: CornerBasedShape = RoundedCornerShape(largeRadius)
}
