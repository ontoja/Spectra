package app.spectra.wallet.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.spectra.wallet.ui.theme.Elevation
import app.spectra.wallet.ui.theme.Radii
import app.spectra.wallet.ui.theme.Spectra

/** One soft colour field of the aurora background, positioned in fractions of the screen. */
data class AuroraBlob(val x: Float, val y: Float, val radiusFraction: Float, val color: Color, val alpha: Float)

enum class AuroraPreset { HOME, PIN, ONBOARDING, PHRASE, DETAIL, SUCCESS }

@Composable
fun auroraBlobs(preset: AuroraPreset): List<AuroraBlob> {
    val c = Spectra.colors
    return when (preset) {
        AuroraPreset.HOME -> listOf(AuroraBlob(.22f, .14f, .55f, c.auroraLavender, .75f), AuroraBlob(.84f, .20f, .48f, c.auroraPeach, .60f),
            AuroraBlob(.76f, .62f, .58f, c.auroraBlue, .55f), AuroraBlob(.16f, .84f, .48f, c.auroraMint, .50f))
        AuroraPreset.PIN -> listOf(AuroraBlob(.26f, .18f, .58f, c.auroraLavender, .80f), AuroraBlob(.80f, .26f, .50f, c.auroraPeach, .65f),
            AuroraBlob(.70f, .70f, .62f, c.auroraBlue, .60f), AuroraBlob(.18f, .86f, .52f, c.auroraMint, .55f))
        AuroraPreset.ONBOARDING -> listOf(AuroraBlob(.22f, .10f, .55f, c.auroraLavender, .70f), AuroraBlob(.88f, .22f, .48f, c.auroraPeach, .55f),
            AuroraBlob(.80f, .78f, .58f, c.auroraBlue, .50f), AuroraBlob(.12f, .90f, .48f, c.auroraMint, .45f))
        AuroraPreset.PHRASE -> listOf(AuroraBlob(.18f, .12f, .55f, c.auroraLavender, .70f), AuroraBlob(.86f, .34f, .48f, c.auroraPeach, .55f),
            AuroraBlob(.20f, .70f, .58f, c.auroraBlue, .50f), AuroraBlob(.84f, .92f, .48f, c.auroraMint, .50f))
        AuroraPreset.DETAIL -> listOf(AuroraBlob(.90f, .08f, .50f, c.auroraLavender, .65f), AuroraBlob(.08f, .34f, .45f, c.auroraMint, .45f),
            AuroraBlob(.90f, .80f, .50f, c.auroraPeach, .45f), AuroraBlob(.10f, .96f, .45f, c.auroraBlue, .45f))
        AuroraPreset.SUCCESS -> listOf(AuroraBlob(.50f, .10f, .60f, c.auroraMint, .70f), AuroraBlob(.10f, .40f, .45f, c.auroraLavender, .55f),
            AuroraBlob(.92f, .46f, .45f, c.auroraPeach, .50f), AuroraBlob(.50f, .95f, .50f, c.auroraBlue, .45f))
    }
}

/** Full-screen pastel aurora gradient behind content, as in the reference screens. */
@Composable
fun AuroraBackground(preset: AuroraPreset = AuroraPreset.HOME, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = Spectra.colors
    val blobs = auroraBlobs(preset)
    Box(modifier.fillMaxSize().background(c.background)) {
        Box(
            Modifier.fillMaxSize()
                .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(40.dp) else Modifier)
                .drawBehind {
                    blobs.forEach { b ->
                        val r = size.maxDimension * b.radiusFraction
                        drawCircle(
                            Brush.radialGradient(listOf(b.color.copy(alpha = b.alpha * c.auroraStrength), b.color.copy(alpha = 0f)),
                                center = Offset(size.width * b.x, size.height * b.y), radius = r),
                            radius = r, center = Offset(size.width * b.x, size.height * b.y),
                        )
                    }
                },
        )
        content()
    }
}

/** Glass surface: translucent white fill, hairline light border and a soft violet-tinted shadow. */
fun Modifier.glass(shape: Shape, fill: Color, border: Color, shadowColor: Color, elevation: Dp = Elevation.glass): Modifier =
    this.shadow(elevation, shape, clip = false, ambientColor = shadowColor.copy(alpha = 0.10f), spotColor = shadowColor.copy(alpha = 0.16f))
        .background(fill, shape)
        .border(1.dp, border, shape)

@Composable
fun Modifier.glassSurface(shape: Shape = RoundedCornerShape(Radii.card), strong: Boolean = false, elevation: Dp = Elevation.glass): Modifier {
    val c = Spectra.colors
    return glass(shape, if (strong) c.glassStrong else c.glass, c.glassBorder, c.shadow, elevation)
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: Dp = Radii.card,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.glassSurface(RoundedCornerShape(radius), elevation = Elevation.card).padding(padding), content = content)
}
