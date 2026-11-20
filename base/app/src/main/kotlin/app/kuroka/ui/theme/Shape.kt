package app.kuroka.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/*
 * The Classic look's corners: a poster is 8dp, a card 12dp, a sheet 20dp, a button a pill. The
 * Immersive look has its own (ui/look/ImmersiveTheme.kt), tighter, as that look's cards are glass.
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../LICENSE).
 */

val KurokaShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** A poster's corner: small enough that a grid of them reads as one shelf. */
val PosterShape = RoundedCornerShape(8.dp)

/** A card's corner. */
val CardShape = RoundedCornerShape(12.dp)

/** A pill, as a chip or a button. */
val PillShape = RoundedCornerShape(50)
