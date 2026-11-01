package app.spectra.wallet.ui.auth

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.spectra.wallet.domain.AppContainer
import app.spectra.wallet.security.AuthorizationCoordinator
import app.spectra.wallet.ui.common.SecureScreen
import app.spectra.wallet.ui.components.KeypadKey
import app.spectra.wallet.ui.components.LinkButton
import app.spectra.wallet.ui.components.NumericKeypad
import app.spectra.wallet.ui.components.PinDots
import app.spectra.wallet.ui.components.SpectraSheet
import app.spectra.wallet.ui.lock.rememberPinAuth
import app.spectra.wallet.ui.theme.Spectra

/** Hosts fresh-authorisation requests (signing, phrase reveal, account derivation). */
@Composable
fun AuthorizationHost(c: AppContainer) {
    val req by c.auth.pending.collectAsState()
    req?.let { AuthorizationSheet(c, it) }
}

@Composable
private fun AuthorizationSheet(c: AppContainer, req: AuthorizationCoordinator.Request) {
    val auth = rememberPinAuth(c.security) { dek -> req.complete(dek) }
    LaunchedEffect(req) { if (auth.biometricEnabled) auth.biometric() }
    SpectraSheet({ req.complete(null) }) {
        SecureScreen()
        Text(req.title, style = Spectra.type.headline, color = Spectra.colors.text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        req.detail?.let { Spacer(Modifier.height(6.dp)); Text(it, style = Spectra.type.body, color = Spectra.colors.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { PinDots(auth.entry.length, auth.state) }
        Box(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(top = 10.dp), contentAlignment = Alignment.Center) {
            val locked = auth.lockedUntil > System.currentTimeMillis()
            (if (locked) "Zbyt wiele prób. Spróbuj za ${((auth.lockedUntil - System.currentTimeMillis()) / 1000) + 1} s." else auth.message)?.let {
                Text(it, style = Spectra.type.label, color = Spectra.colors.negative, textAlign = TextAlign.Center)
            }
        }
        NumericKeypad(auth::onKey, leftKey = if (auth.biometricEnabled) KeypadKey.Biometric else KeypadKey.Empty, keySize = 68.dp, enabled = !auth.busy,
            modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(8.dp))
        LinkButton("Anuluj", { req.complete(null) }, Modifier.fillMaxWidth())
    }
}
