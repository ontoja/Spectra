package app.spectra.wallet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.spectra.wallet.ui.theme.Spectra

@Composable
fun EmptyState(title: String, body: String, icon: ImageVector = SpectraIcons.Vault, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val c = Spectra.colors
    Column(modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(18.dp)).background(c.primarySoft), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(24.dp), tint = c.primary) }
        Text(title, style = Spectra.type.bodyStrong, color = c.text, textAlign = TextAlign.Center)
        Text(body, style = Spectra.type.caption, color = c.textSecondary, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

/** Errors are always shown with a human-readable explanation and, when possible, a retry — never an endless spinner. */
@Composable
fun ErrorState(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    val c = Spectra.colors
    Column(modifier.fillMaxWidth().padding(vertical = 20.dp, horizontal = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(c.negativeSoft), contentAlignment = Alignment.Center) { Icon(SpectraIcons.Alert, null, Modifier.size(22.dp), tint = c.negative) }
        Text(message, style = Spectra.type.body, color = c.text, textAlign = TextAlign.Center)
        onRetry?.let { LinkButton("Spróbuj ponownie", it, icon = SpectraIcons.Refresh) }
    }
}

@Composable
fun LoadingState(label: String, modifier: Modifier = Modifier) {
    val c = Spectra.colors
    Column(modifier.fillMaxWidth().padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(28.dp), color = c.primary, strokeWidth = 2.5.dp)
        Text(label, style = Spectra.type.caption, color = c.textSecondary)
    }
}
