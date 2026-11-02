package app.spectra.wallet.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.spectra.wallet.ui.theme.Motion
import app.spectra.wallet.ui.theme.Spectra
import kotlinx.coroutines.delay

/*
 * Shared motion vocabulary. Everything here collapses to a static state when the system animator scale is 0
 * (accessibility "remove animations"), via Spectra.reducedMotion.
 */

/** Springy scale-down while pressed. Pair with the same [interaction] passed to `clickable`. */
@Composable
fun Modifier.pressScale(interaction: InteractionSource, pressed: Float = 0.96f): Modifier {
    if (Spectra.reducedMotion) return this
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) pressed else 1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * Fade + rise on first appearance, staggered by [index]. Re-runs when [key] changes.
 */
@Composable
fun Modifier.enterAnimation(index: Int = 0, key: Any? = Unit, distance: Float = 18f, stepMs: Long = 45): Modifier {
    if (Spectra.reducedMotion) return this
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        delay((index * stepMs).coerceAtMost(420))
        progress.animateTo(1f, tween(Motion.SLOW, easing = FastOutSlowInEasing))
    }
    val px = with(LocalDensity.current) { distance.dp.toPx() }
    return graphicsLayer { alpha = progress.value; translationY = (1f - progress.value) * px }
}

/** Horizontal shake (wrong PIN). Plays whenever [trigger] changes to a non-zero value. */
@Composable
fun Modifier.shake(trigger: Int): Modifier {
    if (Spectra.reducedMotion) return this
    val offset = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        for (v in listOf(14f, -12f, 9f, -6f, 3f, 0f)) offset.animateTo(v, tween(48))
    }
    val density = LocalDensity.current.density
    return graphicsLayer { translationX = offset.value * density }
}

/** Gentle vertical float for hero illustrations. */
@Composable
fun Modifier.floating(amplitudeDp: Float = 6f, periodMs: Int = 3200): Modifier {
    if (Spectra.reducedMotion) return this
    val t by rememberInfiniteTransition(label = "float").animateFloat(-1f, 1f, infiniteRepeatable(tween(periodMs, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "y")
    val density = LocalDensity.current.density
    return graphicsLayer { translationY = t * amplitudeDp * density }
}

/** Soft breathing alpha for "in progress" indicators. */
@Composable
fun Modifier.pulse(enabled: Boolean = true, min: Float = 0.35f, periodMs: Int = 900): Modifier {
    if (!enabled || Spectra.reducedMotion) return this
    val a by rememberInfiniteTransition(label = "pulse").animateFloat(1f, min, infiniteRepeatable(tween(periodMs), RepeatMode.Reverse), label = "a")
    return graphicsLayer { alpha = a }
}

/** Expanding/collapsing reveal for conditional notices and messages. */
@Composable
fun Reveal(visible: Boolean, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    if (Spectra.reducedMotion) { if (visible) Column(modifier, content = content); return }
    AnimatedVisibility(visible, modifier, enter = fadeIn(tween(Motion.STANDARD)) + expandVertically(tween(Motion.STANDARD)),
        exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(tween(Motion.FAST))) { Column(content = content) }
}

/** Moving highlight used for loading placeholders. */
@Composable
fun Modifier.shimmer(): Modifier {
    val c = Spectra.colors
    val base = c.text.copy(alpha = 0.06f)
    if (Spectra.reducedMotion) return background(base)
    val x by rememberInfiniteTransition(label = "shimmer").animateFloat(-1f, 2f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "x")
    return drawWithContent {
        val w = size.width
        drawRect(Brush.linearGradient(listOf(base, c.text.copy(alpha = 0.13f), base), start = Offset(w * x - w * 0.5f, 0f), end = Offset(w * x + w * 0.5f, 0f)))
        drawContent()
    }
}

/** Placeholder asset row while balances load (instead of an anonymous spinner). */
@Composable
fun SkeletonRow() {
    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(CircleShape).shimmer())
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.width(110.dp).height(14.dp).clip(RoundedCornerShape(7.dp)).shimmer())
            Spacer(Modifier.height(8.dp))
            Box(Modifier.width(70.dp).height(11.dp).clip(RoundedCornerShape(6.dp)).shimmer())
        }
        Box(Modifier.width(64.dp).height(14.dp).clip(RoundedCornerShape(7.dp)).shimmer())
    }
}

/** Animates a numeric value (e.g. fiat balance) between updates; returns the current display value. */
@Composable
fun animatedNumber(target: Double, durationMs: Int = 700): Double {
    if (Spectra.reducedMotion) return target
    val anim = remember { Animatable(target.toFloat()) }
    LaunchedEffect(target) { anim.animateTo(target.toFloat(), tween(durationMs, easing = FastOutSlowInEasing)) }
    // Float precision is enough for the animation frames; the final frame shows the exact target.
    return if (anim.isRunning) anim.value.toDouble() else target
}


/** Overlay that fades and gently scales away when [visible] becomes false (callable from any layout scope). */
@Composable
fun FadeAwayOverlay(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val d = if (Spectra.reducedMotion) 0 else 260
    AnimatedVisibility(visible, modifier, enter = fadeIn(tween(d)),
        exit = fadeOut(tween(d)) + androidx.compose.animation.scaleOut(tween(d), 1.04f)) { content() }
}
