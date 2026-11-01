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
        Row(
            Modifier.weight(1f).height(64.dp).glass(RoundedCornerShape(Radii.nav), c.glassStrong.copy(alpha = if (c.isDark) .18f else .6f), c.glassBorder, c.shadow, Elevation.nav).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            MainTab.entries.filter { it != MainTab.SWAP }.forEach { tab ->
                val sel = tab == selected
                val bg by animateColorAsState(if (sel) c.glassStrong else Color.Transparent, spectraTween(Motion.FAST), label = "nav")
                Column(
                    Modifier.weight(1f).fillMaxHeight().then(if (sel) Modifier.shadow(4.dp, RoundedCornerShape(26.dp), ambientColor = c.shadow.copy(alpha = .1f), spotColor = c.shadow.copy(alpha = .1f)) else Modifier)
                        .clip(RoundedCornerShape(26.dp)).background(bg).clickable(role = Role.Tab) { onSelect(tab) }.semantics { this.selected = sel },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Icon(tab.icon, null, Modifier.size(20.dp), tint = if (sel) c.primary else c.textSecondary)
                    Text(tab.label, style = Spectra.type.micro, color = if (sel) c.primary else c.textSecondary, maxLines = 1)
                }
            }
        }
        val swapSel = selected == MainTab.SWAP
        Box(
            Modifier.size(64.dp).glass(CircleShape, if (swapSel) c.primary else c.glassStrong.copy(alpha = if (c.isDark) .18f else .6f), c.glassBorder, c.shadow, Elevation.nav).clip(CircleShape)
                .clickable(role = Role.Tab) { onSelect(MainTab.SWAP) }.semantics { contentDescription = "Wymiana"; this.selected = swapSel },
            contentAlignment = Alignment.Center,
        ) { Icon(SpectraIcons.Swap, null, Modifier.size(24.dp), tint = if (swapSel) c.onPrimary else c.primary) }
    }
}
