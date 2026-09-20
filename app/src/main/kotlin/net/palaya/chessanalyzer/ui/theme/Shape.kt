package net.palaya.chessanalyzer.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val ChessAnalyzerShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Extra radii used by bespoke components (badges, comment card) beyond the Material scale. */
object ExtraShapes {
    val badge = RoundedCornerShape(6.dp)
    val pill = RoundedCornerShape(50)
    val card = RoundedCornerShape(14.dp)
}
