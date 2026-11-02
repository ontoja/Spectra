package app.spectra.wallet.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

enum class ThemeMode { SYSTEM, LIGHT, DARK }

object Spectra {
    val colors: SpectraColors @Composable @ReadOnlyComposable get() = LocalSpectraColors.current
    val type: SpectraTypography @Composable @ReadOnlyComposable get() = LocalSpectraType.current
    val reducedMotion: Boolean @Composable @ReadOnlyComposable get() = LocalReducedMotion.current
}

/** Standard transition spec that collapses to an instant change when the user disabled animations. */
@Composable
fun <T> spectraTween(durationMs: Int = Motion.STANDARD): FiniteAnimationSpec<T> =
    if (Spectra.reducedMotion) snap() else tween(durationMs)

@Composable
fun SpectraTheme(mode: ThemeMode = ThemeMode.LIGHT, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val colors = if (dark) DarkColors else LightColors
    val context = LocalContext.current
    val reduced = remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    val material = if (dark) darkColorScheme(primary = colors.primary, background = colors.background, surface = colors.background, onSurface = colors.text, onBackground = colors.text)
    else lightColorScheme(primary = colors.primary, background = colors.background, surface = colors.background, onSurface = colors.text, onBackground = colors.text)
    CompositionLocalProvider(LocalSpectraColors provides colors, LocalSpectraType provides SpectraTypography(), LocalReducedMotion provides reduced) {
        MaterialTheme(colorScheme = material, content = content)
    }
}
