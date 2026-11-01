package app.spectra.wallet.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.spectra.wallet.R

/** Aurora Glass design tokens, derived from the reference designs. Screens must use these instead of literals. */
@Immutable
data class SpectraColors(
    val background: Color,
    val text: Color,
    val textSecondary: Color,
    val textDisabled: Color,
    val primary: Color,
    val primaryPressed: Color,
    val onPrimary: Color,
    val link: Color,
    val inverse: Color,
    val onInverse: Color,
    val glass: Color,
    val glassStrong: Color,
    val glassBorder: Color,
    val glassHighlight: Color,
    val chip: Color,
    val disabledFill: Color,
    val shadow: Color,
    val positive: Color,
    val positiveSoft: Color,
    val negative: Color,
    val negativeStrong: Color,
    val negativeSoft: Color,
    val negativeBorder: Color,
    val warning: Color,
    val warningSoft: Color,
    val primarySoft: Color,
    val auroraLavender: Color,
    val auroraPeach: Color,
    val auroraBlue: Color,
    val auroraMint: Color,
    val auroraStrength: Float,
    val isDark: Boolean,
)

val LightColors = SpectraColors(
    background = Color(0xFFF5F3FA),
    text = Color(0xFF17151F),
    textSecondary = Color(0xFF5E5A6E),
    textDisabled = Color(0xFF8A8699),
    primary = Color(0xFF6046E8),
    primaryPressed = Color(0xFF4A33C9),
    onPrimary = Color.White,
    link = Color(0xFF4A33C9),
    inverse = Color(0xFF17151F),
    onInverse = Color.White,
    glass = Color(0x8CFFFFFF),          // rgba(255,255,255,0.55)
    glassStrong = Color(0xF2FFFFFF),    // 0.95
    glassBorder = Color(0xCCFFFFFF),    // 0.8
    glassHighlight = Color(0xBFFFFFFF), // 0.75 (phrase cells)
    chip = Color(0xCCFFFFFF),
    disabledFill = Color(0x0F17151F),   // rgba(23,21,31,0.06)
    shadow = Color(0xFF30246E),
    positive = Color(0xFF12814F),
    positiveSoft = Color(0x1F12814F),
    negative = Color(0xFFB42A40),
    negativeStrong = Color(0xFFC8324A),
    negativeSoft = Color(0x14C8324A),
    negativeBorder = Color(0x2EC8324A),
    warning = Color(0xFF9A5B00),
    warningSoft = Color(0x1FE09A2B),
    primarySoft = Color(0x1F6046E8),
    auroraLavender = Color(0xFFAB9FF2),
    auroraPeach = Color(0xFFFFBCA0),
    auroraBlue = Color(0xFF96C4FF),
    auroraMint = Color(0xFFA0E6C8),
    auroraStrength = 1f,
    isDark = false,
)

val DarkColors = SpectraColors(
    background = Color(0xFF110F18),
    text = Color(0xFFF3F1FA),
    textSecondary = Color(0xFFB1ACC4),
    textDisabled = Color(0xFF77728A),
    primary = Color(0xFF8B76FF),
    primaryPressed = Color(0xFF7560F0),
    onPrimary = Color(0xFF120E24),
    link = Color(0xFFB5A8FF),
    inverse = Color(0xFFF3F1FA),
    onInverse = Color(0xFF17151F),
    glass = Color(0x1AFFFFFF),
    glassStrong = Color(0x2EFFFFFF),
    glassBorder = Color(0x26FFFFFF),
    glassHighlight = Color(0x1FFFFFFF),
    chip = Color(0x24FFFFFF),
    disabledFill = Color(0x14FFFFFF),
    shadow = Color(0xFF000000),
    positive = Color(0xFF4FD49A),
    positiveSoft = Color(0x264FD49A),
    negative = Color(0xFFFF7A8E),
    negativeStrong = Color(0xFFFF6B82),
    negativeSoft = Color(0x1FFF6B82),
    negativeBorder = Color(0x40FF6B82),
    warning = Color(0xFFFFC266),
    warningSoft = Color(0x26FFC266),
    primarySoft = Color(0x338B76FF),
    auroraLavender = Color(0xFF6A58D6),
    auroraPeach = Color(0xFFB0644C),
    auroraBlue = Color(0xFF3E6FB5),
    auroraMint = Color(0xFF3A8C6E),
    auroraStrength = 0.45f,
    isDark = true,
)

/** Asset avatar gradients from the reference (Solana, Ethereum, Litecoin, generic token). */
object AssetGradients {
    val solana = listOf(Color(0xFF8B6CFF), Color(0xFF3DDBB0))
    val ethereum = listOf(Color(0xFF9AAEFF), Color(0xFF5A6CF0))
    val litecoin = listOf(Color(0xFFC3CCDA), Color(0xFF6E7F99))
    val token = listOf(Color(0xFFFFBCA0), Color(0xFFFF9A76))
    val avatar = listOf(Color(0xFFAB9FF2), Color(0xFFFFBCA0), Color(0xFF96C4FF), Color(0xFFAB9FF2))
}

object Spacing {
    val xxs: Dp = 4.dp
    val xs: Dp = 6.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 24.dp
    val xxxl: Dp = 32.dp
    val screenH: Dp = 20.dp
    val screenTop: Dp = 16.dp
}

object Radii {
    val chip: Dp = 18.dp
    val field: Dp = 14.dp
    val tile: Dp = 20.dp
    val card: Dp = 26.dp
    val cardLarge: Dp = 28.dp
    val button: Dp = 28.dp
    val nav: Dp = 32.dp
}

object Elevation {
    val glass: Dp = 10.dp
    val card: Dp = 14.dp
    val nav: Dp = 18.dp
    val primaryButton: Dp = 12.dp
}

object Motion {
    const val FAST = 180
    const val STANDARD = 260
    const val SLOW = 350
}

val InstrumentSans = FontFamily(
    Font(R.font.instrument_sans_regular, FontWeight.Normal),
    Font(R.font.instrument_sans_medium, FontWeight.Medium),
    Font(R.font.instrument_sans_semibold, FontWeight.SemiBold),
    Font(R.font.instrument_sans_bold, FontWeight.Bold),
)

@Immutable
data class SpectraTypography(
    val balanceHero: TextStyle = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.SemiBold, fontSize = 64.sp, lineHeight = 64.sp, letterSpacing = (-0.04).em),
    val balanceHeroDecimals: TextStyle = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 34.sp, letterSpacing = (-0.04).em),
    val amountEntry: TextStyle = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.SemiBold, fontSize = 56.sp, lineHeight = 60.sp, letterSpacing = (-0.03).em),
    val display: TextStyle = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.SemiBold, fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-0.03).em),
    val title: TextStyle = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.03).em),
    val headline: TextStyle = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.02).em),
    val topBar: TextStyle = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    val bodyStrong: TextStyle = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    val body: TextStyle = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    val bodyMedium: TextStyle = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp),
    val label: TextStyle = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    val caption: TextStyle = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    val captionStrong: TextStyle = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp),
    val micro: TextStyle = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp),
    val keypad: TextStyle = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.Medium, fontSize = 30.sp, lineHeight = 36.sp),
    val mono: TextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
)

val LocalSpectraColors = staticCompositionLocalOf { LightColors }
val LocalSpectraType = staticCompositionLocalOf { SpectraTypography() }
val LocalReducedMotion = staticCompositionLocalOf { false }
