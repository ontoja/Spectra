package app.spectra.wallet.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.spectra.core.model.Chain
import app.spectra.wallet.ui.theme.AssetGradients
import app.spectra.wallet.ui.theme.Elevation
import app.spectra.wallet.ui.theme.Motion
import app.spectra.wallet.ui.theme.Radii
import app.spectra.wallet.ui.theme.Spacing
import app.spectra.wallet.ui.theme.Spectra
import app.spectra.wallet.ui.theme.spectraTween

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false, icon: ImageVector? = null, destructive: Boolean = false) {
    val c = Spectra.colors
    val active = enabled && !loading
    val bg by animateColorAsState(if (active) (if (destructive) c.negativeStrong else c.primary) else c.disabledFill, spectraTween(Motion.FAST), label = "btn")
    val shape = RoundedCornerShape(Radii.button)
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier.fillMaxWidth().height(56.dp).pressScale(interaction, 0.97f)
            .then(if (active) Modifier.shadow(Elevation.primaryButton, shape, ambientColor = c.primary.copy(alpha = .3f), spotColor = c.primary.copy(alpha = .35f)) else Modifier)
            .clip(shape).background(bg)
            .clickable(interaction, ripple(), enabled = active, role = Role.Button, onClick = onClick)
            .semantics { if (loading) stateDescription = "Trwa przetwarzanie" },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        val d = if (Spectra.reducedMotion) 0 else Motion.FAST
        androidx.compose.animation.AnimatedContent(loading, transitionSpec = {
            (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(d)) + androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(d), 0.8f)) togetherWith
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(d))
        }, label = "btnContent") { isLoading ->
            if (isLoading) CircularProgressIndicator(Modifier.size(20.dp), color = c.onPrimary, strokeWidth = 2.dp)
            else Row(verticalAlignment = Alignment.CenterVertically) {
                icon?.let { Icon(it, null, Modifier.size(18.dp), tint = if (active) c.onPrimary else c.textDisabled); Spacer(Modifier.width(10.dp)) }
                Text(text, style = Spectra.type.bodyStrong, color = if (active) c.onPrimary else c.textDisabled)
            }
        }
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    val c = Spectra.colors
    val shape = RoundedCornerShape(Radii.button)
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier.fillMaxWidth().height(56.dp).pressScale(interaction, 0.97f).glassSurface(shape, strong = true).clip(shape)
            .clickable(interaction, ripple(), enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, Modifier.size(18.dp), tint = if (enabled) c.text else c.textDisabled); Spacer(Modifier.width(10.dp)) }
        Text(text, style = Spectra.type.bodyStrong, color = if (enabled) c.text else c.textDisabled)
    }
}

@Composable
fun LinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, color: Color = Spectra.colors.link) {
    val interaction = remember { MutableInteractionSource() }
    Row(modifier.defaultMinSize(minHeight = 44.dp).pressScale(interaction).clip(RoundedCornerShape(22.dp)).clickable(interaction, ripple(), role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Text(text, style = Spectra.type.label.copy(fontSize = Spectra.type.bodyStrong.fontSize), color = color)
        icon?.let { Spacer(Modifier.width(6.dp)); Icon(it, null, Modifier.size(16.dp), tint = color) }
    }
}

@Composable
fun GlassIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 44.dp, tint: Color = Spectra.colors.text, iconSize: Dp = 20.dp, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier.size(size).pressScale(interaction, 0.9f).glassSurface(CircleShape).clip(CircleShape)
            .clickable(interaction, ripple(), enabled = enabled, role = Role.Button, onClick = onClick).semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(iconSize), tint = if (enabled) tint else Spectra.colors.textDisabled) }
}

@Composable
fun TopBar(title: String?, onBack: (() -> Unit)? = null, onClose: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null, titleContent: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            onBack != null -> GlassIconButton(SpectraIcons.Back, "Wróć", onBack)
            onClose != null -> GlassIconButton(SpectraIcons.Close, "Zamknij", onClose)
            else -> Spacer(Modifier.size(44.dp))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (titleContent != null) titleContent()
            else if (title != null) Text(title, style = Spectra.type.topBar, color = Spectra.colors.text, modifier = Modifier.semantics { heading() }, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) trailing() else Spacer(Modifier.size(44.dp))
    }
}

/** "1 z 3" segmented progress used in onboarding. */
@Composable
fun StepProgress(step: Int, total: Int, onBack: (() -> Unit)?) {
    val c = Spectra.colors
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (onBack != null) GlassIconButton(SpectraIcons.Back, "Wróć", onBack) else Spacer(Modifier.size(44.dp))
        Row(Modifier.weight(1f).semantics { contentDescription = "Krok $step z $total" }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(total) { i -> Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (i < step) c.primary else c.text.copy(alpha = .10f))) }
        }
        Text("$step z $total", style = Spectra.type.captionStrong, color = c.textSecondary, modifier = Modifier.width(44.dp), textAlign = TextAlign.End)
    }
}

@Composable
fun SegmentedTabs(items: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, fill: Boolean = false) {
    val c = Spectra.colors
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Measured x/width of each tab so a single pill can slide between them.
    val bounds = remember(items) { androidx.compose.runtime.mutableStateMapOf<Int, Pair<Float, Float>>() }
    val target = bounds[selected]
    val indicatorX by androidx.compose.animation.core.animateFloatAsState(target?.first ?: 0f,
        if (Spectra.reducedMotion) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 500f), label = "tabX")
    val indicatorW by androidx.compose.animation.core.animateFloatAsState(target?.second ?: 0f,
        if (Spectra.reducedMotion) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 500f), label = "tabW")
    Box(modifier.padding(2.dp)) {
        if (target != null) Box(Modifier.graphicsLayer { translationX = indicatorX }.width(with(density) { indicatorW.toDp() }).height(36.dp)
            .clip(RoundedCornerShape(Radii.chip)).background(c.inverse))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEachIndexed { i, label ->
                val sel = i == selected
                val fg by animateColorAsState(if (sel) c.onInverse else c.textSecondary, spectraTween(Motion.FAST), label = "tabFg")
                val interaction = remember { MutableInteractionSource() }
                Box(
                    (if (fill) Modifier.weight(1f) else Modifier).height(36.dp)
                        .onGloballyPositioned { bounds[i] = it.positionInParent().x to it.size.width.toFloat() }
                        .pressScale(interaction, 0.95f).clip(RoundedCornerShape(Radii.chip))
                        .clickable(interaction, ripple(), role = Role.Tab) { onSelect(i) }.semantics { stateDescription = if (sel) "Wybrano" else "" }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(label, style = Spectra.type.label, color = fg, maxLines = 1) }
            }
        }
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, leading: (@Composable () -> Unit)? = null) {
    val c = Spectra.colors
    val bg by animateColorAsState(if (selected) c.inverse else c.chip, spectraTween(Motion.FAST), label = "chip")
    val interaction = remember { MutableInteractionSource() }
    Box(modifier.height(44.dp).pressScale(interaction, 0.93f).clip(RoundedCornerShape(22.dp)).background(bg).clickable(interaction, ripple(), enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { stateDescription = if (selected) "Wybrano" else "" }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            leading?.let { it(); Spacer(Modifier.width(8.dp)) }
            Text(text, style = Spectra.type.label, color = if (!enabled) c.textDisabled else if (selected) c.onInverse else c.text, maxLines = 1)
        }
    }
}

enum class Tone { NEUTRAL, POSITIVE, NEGATIVE, WARNING, PRIMARY }

@Composable
fun toneColors(tone: Tone): Pair<Color, Color> {
    val c = Spectra.colors
    return when (tone) {
        Tone.NEUTRAL -> c.disabledFill to c.textSecondary
        Tone.POSITIVE -> c.positiveSoft to c.positive
        Tone.NEGATIVE -> c.negativeSoft to c.negative
        Tone.WARNING -> c.warningSoft to c.warning
        Tone.PRIMARY -> c.primarySoft to c.link
    }
}

@Composable
fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier, dot: Boolean = true, pulsing: Boolean = false) {
    val (bg, fg) = toneColors(tone)
    Row(modifier.clip(RoundedCornerShape(12.dp)).background(bg).animateContentSize(spectraTween(Motion.STANDARD)).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (dot || pulsing) { Box(Modifier.size(6.dp).pulse(pulsing).clip(CircleShape).background(fg)); Spacer(Modifier.width(6.dp)) }
        Text(text, style = Spectra.type.captionStrong, color = fg)
    }
}

/** Notice card (e.g. "Nigdy nikomu jej nie podawaj"). */
@Composable
fun NoticeCard(title: String, body: String?, tone: Tone, icon: ImageVector = SpectraIcons.ShieldAlert, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val c = Spectra.colors
    val (bg, fg) = toneColors(tone)
    val border = if (tone == Tone.NEGATIVE) c.negativeBorder else fg.copy(alpha = .18f)
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(bg).border(1.dp, border, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(fg.copy(alpha = .12f)), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(18.dp), tint = fg) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = Spectra.type.bodyStrong.copy(fontSize = Spectra.type.body.fontSize), color = if (tone == Tone.NEGATIVE && !c.isDark) androidx.compose.ui.graphics.Color(0xFF8E1F33) else fg)
            body?.let { Text(it, style = Spectra.type.caption, color = c.textSecondary) }
            action?.invoke()
        }
    }
}

@Composable
fun SettingRow(title: String, subtitle: String? = null, icon: ImageVector? = null, onClick: (() -> Unit)? = null, trailing: @Composable (RowScope.() -> Unit)? = null, destructive: Boolean = false) {
    val c = Spectra.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).pressScale(interaction, 0.98f).clip(RoundedCornerShape(Radii.tile))
            .then(if (onClick != null) Modifier.clickable(interaction, ripple(), role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.let {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(if (destructive) c.negativeSoft else c.primarySoft), contentAlignment = Alignment.Center) {
                Icon(it, null, Modifier.size(20.dp), tint = if (destructive) c.negative else c.primary)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Spectra.type.bodyStrong.copy(fontSize = Spectra.type.body.fontSize), color = if (destructive) c.negative else c.text)
            subtitle?.let { Text(it, style = Spectra.type.caption, color = c.textSecondary) }
        }
        if (trailing != null) trailing() else if (onClick != null) Icon(SpectraIcons.Chevron, null, Modifier.size(18.dp), tint = c.textSecondary)
    }
}

@Composable
fun SpectraSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true, description: String) {
    val c = Spectra.colors
    Switch(checked, onChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = description },
        colors = SwitchDefaults.colors(checkedTrackColor = c.primary, checkedThumbColor = c.onPrimary, uncheckedTrackColor = c.disabledFill, uncheckedBorderColor = c.glassBorder, uncheckedThumbColor = c.textSecondary))
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = Spectra.type.micro, color = Spectra.colors.textSecondary, modifier = modifier.padding(start = 6.dp, top = 8.dp, bottom = 6.dp).semantics { heading() })
}

@Composable
fun KeyValueRow(label: String, value: String, valueColor: Color = Spectra.colors.text, strong: Boolean = true, onClick: (() -> Unit)? = null, trailingIcon: ImageVector? = null) {
    val c = Spectra.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Spectra.type.body, color = c.textSecondary, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Text(value, style = if (strong) Spectra.type.label else Spectra.type.body, color = valueColor, textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 210.dp))
        trailingIcon?.let { Spacer(Modifier.width(6.dp)); Icon(it, null, Modifier.size(16.dp), tint = c.textSecondary) }
    }
}

@Composable
fun Hairline() { Box(Modifier.fillMaxWidth().height(1.dp).background(Spectra.colors.text.copy(alpha = .07f))) }

@Composable
fun CheckRow(checked: Boolean, onChange: (Boolean) -> Unit, label: String) {
    val c = Spectra.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Checkbox) { onChange(!checked) }
        .semantics { stateDescription = if (checked) "Zaznaczono" else "Nie zaznaczono" }, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)).background(if (checked) c.primary else Color.Transparent)
            .border(1.5.dp, if (checked) c.primary else c.text.copy(alpha = .4f), RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
            val checkScale by androidx.compose.animation.core.animateFloatAsState(if (checked) 1f else 0f,
                if (Spectra.reducedMotion) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(dampingRatio = 0.5f), label = "check")
            Icon(SpectraIcons.Check, null, Modifier.size(16.dp).graphicsLayer { scaleX = checkScale; scaleY = checkScale }, tint = c.onPrimary)
        }
        Spacer(Modifier.width(Spacing.md))
        Text(label, style = Spectra.type.bodyMedium.copy(fontSize = Spectra.type.label.fontSize), color = c.text)
    }
}

@Composable
fun rememberNoRippleSource() = remember { MutableInteractionSource() }

