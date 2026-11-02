package app.spectra.wallet.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.spectra.wallet.ui.theme.Elevation
import app.spectra.wallet.ui.theme.Motion
import app.spectra.wallet.ui.theme.Radii
import app.spectra.wallet.ui.theme.Spectra
import app.spectra.wallet.ui.theme.spectraTween

enum class MainTab(val label: String, val icon: ImageVector) {
    HOME("Start", SpectraIcons.Home),
    STAKING("Zarabiaj", SpectraIcons.Earn),
    ACTIVITY("Historia", SpectraIcons.History),
    SETTINGS("Profil", SpectraIcons.Profile),
    SWAP("Wymiana", SpectraIcons.Swap),
}

/** Floating glass navigation pill with four tabs plus the separate circular swap button, as in the reference. */
@Composable
fun SpectraBottomBar(selected: MainTab, onSelect: (MainTab) -> Unit, modifier: Modifier = Modifier) {
    val c = Spectra.colors
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        val tabs = MainTab.entries.filter { it != MainTab.SWAP }
        val selIndex = tabs.indexOf(selected)
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier.weight(1f).height(64.dp).glass(RoundedCornerShape(Radii.nav), c.glassStrong.copy(alpha = if (c.isDark) .18f else .6f), c.glassBorder, c.shadow, Elevation.nav).padding(6.dp),
        ) {
            val gap = 2.dp
            val itemW = (maxWidth - gap * (tabs.size - 1)) / tabs.size
            // Single highlight that slides to the selected tab (hidden while the swap button is active).
            val x by androidx.compose.animation.core.animateDpAsState((itemW + gap) * selIndex.coerceAtLeast(0),
                if (Spectra.reducedMotion) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(dampingRatio = 0.75f, stiffness = 420f), label = "navX")
            val alpha by androidx.compose.animation.core.animateFloatAsState(if (selIndex >= 0) 1f else 0f, spectraTween(Motion.FAST), label = "navA")
            Box(Modifier.offset(x = x).width(itemW).fillMaxHeight().graphicsLayer { this.alpha = alpha }
                .shadow(4.dp, RoundedCornerShape(26.dp), ambientColor = c.shadow.copy(alpha = .1f), spotColor = c.shadow.copy(alpha = .1f))
                .clip(RoundedCornerShape(26.dp)).background(c.glassStrong))
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                tabs.forEach { tab ->
                    val sel = tab == selected
                    val fg by animateColorAsState(if (sel) c.primary else c.textSecondary, spectraTween(Motion.FAST), label = "navFg")
                    val iconScale by androidx.compose.animation.core.animateFloatAsState(if (sel) 1.12f else 1f,
                        if (Spectra.reducedMotion) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 600f), label = "navIcon")
                    val interaction = remember { MutableInteractionSource() }
                    Column(
                        Modifier.width(itemW).fillMaxHeight().pressScale(interaction, 0.92f).clip(RoundedCornerShape(26.dp))
                            .clickable(interaction, ripple(), role = Role.Tab) { onSelect(tab) }.semantics { this.selected = sel },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(tab.icon, null, Modifier.size(20.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale }, tint = fg)
                        Text(tab.label, style = Spectra.type.micro, color = fg, maxLines = 1)
                    }
                }
            }
        }
        val swapSel = selected == MainTab.SWAP
        val swapInteraction = remember { MutableInteractionSource() }
        val swapRotation by androidx.compose.animation.core.animateFloatAsState(if (swapSel) 180f else 0f,
            if (Spectra.reducedMotion) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 300f), label = "swapRot")
        val swapBg by animateColorAsState(if (swapSel) c.primary else c.glassStrong.copy(alpha = if (c.isDark) .18f else .6f), spectraTween(Motion.STANDARD), label = "swapBg")
        Box(
            Modifier.size(64.dp).pressScale(swapInteraction, 0.9f).glass(CircleShape, swapBg, c.glassBorder, c.shadow, Elevation.nav).clip(CircleShape)
                .clickable(swapInteraction, ripple(), role = Role.Tab) { onSelect(MainTab.SWAP) }.semantics { contentDescription = "Wymiana"; this.selected = swapSel },
            contentAlignment = Alignment.Center,
        ) { Icon(SpectraIcons.Swap, null, Modifier.size(24.dp).graphicsLayer { rotationZ = swapRotation }, tint = if (swapSel) c.onPrimary else c.primary) }
    }
}
