package app.kuroka.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/*
 * The Classic look's colours: a dark surface, one accent, and greys that say what is a title, what
 * is a caption and what is disabled. Kuroka's own palette; the token names and the way the shades
 * are derived follow Orpheon (GPL-3.0, © kota-zo, base/app/src/main/java/com/music/spotui/ui/theme/Color.kt).
 *
 * This file is part of Kuroka (GPL-3.0, see ../../../../../../LICENSE).
 */

/** The accent the app is built around, and the eight others Settings, Display offers. */
val KurokaRose = Color(0xFFF1316F)
val KurokaEmber = Color(0xFFFF6B35)
val KurokaAmber = Color(0xFFFFB020)
val KurokaJade = Color(0xFF2ED3A0)
val KurokaTeal = Color(0xFF20C4D8)
val KurokaAzure = Color(0xFF4C8DFF)
val KurokaIris = Color(0xFF8B6CFF)
val KurokaOrchid = Color(0xFFD96CFF)
val KurokaSlate = Color(0xFFB3B9C4)

/** The background: near-black, and pure black when the OLED setting is on. */
val AppBackground = Color(0xFF121212)
val OledBackground = Color(0xFF000000)

/** A card, a row, a sheet: one step lighter than the background, then two. */
val CardBackground = Color(0xFF181818)
val RaisedBackground = Color(0xFF1F1F1F)
val HighBackground = Color(0xFF282828)
val GridBackground = Color(0xFF2A2A2A)

/** The words: a title, a caption, something disabled. */
val AppText = Color(0xFFFFFFFF)
val AppMuted = Color(0xFFB3B3B3)
val AppFaint = Color(0xFF727272)
val AppOutline = Color(0xFF3E3E3E)

/** What went wrong, and what a destructive action is. */
val AppError = Color(0xFFF3727F)
val AppDestructive = Color(0xFFF15E6C)

/** The player's own colours: everything behind the picture is black, whatever the look is. */
val PlayerBackground = Color(0xFF000000)
val PlayerScrim = Color(0xB3000000)
val PlayerChrome = Color(0xE6101014)

/**
 * A shade of [base] at [alpha], as the two looks mix their surfaces.
 * (Orpheon's `tone()`.)
 */
fun tone(base: Long, alpha: Float = 1f): Color = Color(base).copy(alpha = alpha)

/**
 * A header's tint: [base] pulled [amount] of the way towards black, so a section title over a
 * poster stays readable without a scrim of its own.
 */
fun headerTint(base: Color, amount: Float): Color = lerp(base, Color.Black, amount)
