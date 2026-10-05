package net.palaya.chessanalyzer.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Chess Analyzer palette — chess.com-INSPIRED (dark chrome, flat, green-accented,
 * high contrast), but original values, not sampled/copied assets.
 */

// Core chrome / surfaces
val ChromeDark = Color(0xFF302E2B)
val SurfaceDark = Color(0xFF262421)
val ElevatedDark = Color(0xFF3C3A37)
val ElevatedDarkHigh = Color(0xFF48453F)

// Brand green
val GreenPrimary = Color(0xFF81B64C)
val GreenHover = Color(0xFFA3D160)
val GreenPressed = Color(0xFF6B9A3D)

// Board
val BoardLight = Color(0xFFEBECD0)
val BoardDark = Color(0xFF739552)
val HighlightYellow = Color(0xFFF7F769)
val HighlightYellowAlpha = Color(0x99F7F769)
val LastMove = Color(0xFFBACA44)
val LastMoveAlpha = Color(0x99BACA44)
val CheckRed = Color(0xFFEE3B3B)
val LegalMoveDot = Color(0x593C3A37)
val SelectedSquare = Color(0x7581B64C)

// Neutral text on dark
val OnDarkPrimary = Color(0xFFFAF9F6)
// Raised from B8B5AE (R6a contrast pass): 4.04:1 on a tinted move chip, now 4.9:1 and more everywhere.
val OnDarkSecondary = Color(0xFFCBC8C1)
val OnDarkDisabled = Color(0xFF716E68)
val DividerDark = Color(0xFF474540)

// Semantic roles added by the R6a contrast pass (WCAG AA, dark theme). None of these is a
// move-quality colour: that palette (the Class* values below) is untouched.
/** Borders of text fields, outlined/segmented buttons and the switch: 3.2:1 on a card (was 1.2:1). */
val OutlineStrong = Color(0xFF8A877F)
/** Error TEXT and icons: 4.3:1 or more on every surface (ClassBlunder gave 3.2:1 on a card). */
val ErrorText = Color(0xFFFF9A8C)
/** Container of a selected chip / segmented button: 7.2:1 under [OnDarkPrimary]. */
val GreenContainer = Color(0xFF3E5C25)
/** Board coordinate letters: ink on the two square colours, 6.4:1 and 5.1:1. */
val BoardLabelOnLight = Color(0xFF3F5A2A)
val BoardLabelOnDark = Color(0xFF1A1917)

// Light scheme surfaces (secondary support — dark is default)
val ChromeLight = Color(0xFFF5F4F0)
val SurfaceLight = Color(0xFFFFFFFF)
val ElevatedLight = Color(0xFFEDEBE6)
val OnLightPrimary = Color(0xFF1E1D1B)
val OnLightSecondary = Color(0xFF5B5952)
val DividerLight = Color(0xFFD9D6CE)

// ---- Move classification colors (chess.com-style annotation badges) ----
val ClassBrilliant = Color(0xFF26C2A3)
val ClassGreat = Color(0xFF749BBF)
val ClassBest = Color(0xFF81B64C)
val ClassExcellent = Color(0xFF81B64C)
val ClassGood = Color(0xFF95B776)
val ClassBook = Color(0xFFA88865)
val ClassInaccuracy = Color(0xFFF7C631)
val ClassMistake = Color(0xFFE58F2A)
val ClassMiss = Color(0xFFFF7769)
val ClassBlunder = Color(0xFFFA412D)
val ClassForced = Color(0xFF9E9E9E)

// Eval bar / accuracy accents
val EvalWhiteFill = Color(0xFFF2F1EC)
val EvalBlackFill = Color(0xFF1A1917)
val AccuracyGood = GreenPrimary
val AccuracyMid = ClassInaccuracy
val AccuracyLow = ClassBlunder
