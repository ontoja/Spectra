package app.spectra.wallet.ui.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.spectra.core.crypto.SecureMemory
import app.spectra.wallet.domain.AppContainer
import app.spectra.wallet.ui.common.SecureScreen
import app.spectra.wallet.ui.components.AuroraBackground
import app.spectra.wallet.ui.components.AuroraPreset
import app.spectra.wallet.ui.components.KeypadKey
import app.spectra.wallet.ui.components.LinkButton
import app.spectra.wallet.ui.components.NoticeCard
import app.spectra.wallet.ui.components.NumericKeypad
import app.spectra.wallet.ui.components.PinDots
import app.spectra.wallet.ui.components.PrimaryButton
import app.spectra.wallet.ui.components.SpectraSheet
import app.spectra.wallet.ui.components.Tone
import app.spectra.wallet.ui.components.glassSurface
import app.spectra.wallet.ui.theme.AssetGradients
import app.spectra.wallet.ui.theme.Spectra
import app.spectra.wallet.ui.components.enterAnimation
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import kotlinx.coroutines.launch

@Composable
fun AccountAvatar(size: androidx.compose.ui.unit.Dp, animated: Boolean = false) {
    val spin = if (animated && !Spectra.reducedMotion) androidx.compose.animation.core.rememberInfiniteTransition(label = "avatar").animateFloat(0f, 360f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(12_000, easing = androidx.compose.animation.core.LinearEasing)), label = "spin") else null
    Box(Modifier.size(size).glassSurface(CircleShape, strong = true).padding(size * 0.07f)) {
        Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = spin?.value ?: 0f }.clip(CircleShape).background(Brush.sweepGradient(AssetGradients.avatar)))
    }
}

@Composable
fun LockScreen(c: AppContainer, accountName: String) {
    val auth = rememberPinAuth(c.security) { dek -> SecureMemory.wipe(dek); c.security.markUnlocked() }
    var forgot by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (auth.biometricEnabled) auth.biometric() }
    AuroraBackground(AuroraPreset.PIN) {
        SecureScreen()
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp).padding(top = 72.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.enterAnimation(0, distance = 24f)) { AccountAvatar(76.dp, animated = true) }
            Spacer(Modifier.height(22.dp))
            Text("Witaj ponownie", style = Spectra.type.title, color = Spectra.colors.text, modifier = Modifier.enterAnimation(1).semantics { heading() })
            Spacer(Modifier.height(6.dp))
            Text("Wpisz PIN, aby odblokować $accountName", style = Spectra.type.body, color = Spectra.colors.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.enterAnimation(2))
            Spacer(Modifier.height(32.dp))
            PinDots(auth.entry.length, auth.state)
            Box(Modifier.heightIn(min = 44.dp).padding(top = 12.dp)) {
                val locked = auth.lockedUntil > System.currentTimeMillis()
                val msg = if (locked) "Zbyt wiele nieudanych prób. Spróbuj za ${((auth.lockedUntil - System.currentTimeMillis()) / 1000) + 1} s." else auth.message
                msg?.let { Text(it, style = Spectra.type.label, color = Spectra.colors.negative, textAlign = TextAlign.Center) }
            }
            Spacer(Modifier.weight(1f))
            NumericKeypad(auth::onKey, leftKey = if (auth.biometricEnabled) KeypadKey.Biometric else KeypadKey.Empty, enabled = !auth.busy, modifier = Modifier.enterAnimation(4, distance = 30f))
            Spacer(Modifier.height(12.dp))
            LinkButton("Nie pamiętasz PIN-u?", { forgot = true })
        }
    }
    if (forgot) ForgotPinSheet(c) { forgot = false }
}

/** A PIN cannot be recovered: the only way back is restoring from the recovery phrase on a wiped app. */
@Composable
fun ForgotPinSheet(c: AppContainer, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    SpectraSheet(onDismiss) {
        Text("Nie pamiętasz PIN-u?", style = Spectra.type.headline, color = Spectra.colors.text)
        Spacer(Modifier.height(10.dp))
        Text("Spectra nie przechowuje PIN-u i nie może go odzyskać. Możesz usunąć portfel z tego telefonu i odtworzyć go z frazy odzyskiwania (12 lub 24 słowa).",
            style = Spectra.type.body, color = Spectra.colors.textSecondary)
        Spacer(Modifier.height(14.dp))
        NoticeCard("Bez frazy środki przepadną", "Jeśli nie masz zapisanej frazy dla każdego portfela, usunięcie danych spowoduje trwałą utratę dostępu do środków.", Tone.NEGATIVE)
        Spacer(Modifier.height(16.dp))
        if (!confirm) PrimaryButton("Mam frazę — usuń portfel z telefonu", { confirm = true }, destructive = true)
        else PrimaryButton("Potwierdzam: usuń wszystkie dane", {
            scope.launch {
                c.wallets.accountsNow().map { it.vault.id }.distinct().forEach { c.wallets.removeVault(it) }
                if (c.security.hasCredentials()) c.security.wipeAll()
                onDismiss()
            }
        }, destructive = true)
        Spacer(Modifier.height(8.dp))
        LinkButton("Anuluj", onDismiss, Modifier.fillMaxWidth())
    }
}
