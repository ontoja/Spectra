package app.spectra.wallet.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.spectra.wallet.data.UserPreferences
import app.spectra.wallet.domain.AppContainer
import app.spectra.wallet.security.DeviceIntegrity
import app.spectra.wallet.security.LockState
import app.spectra.wallet.ui.auth.AuthorizationHost
import app.spectra.wallet.ui.common.LocalContainer
import app.spectra.wallet.ui.common.SecureWindow
import app.spectra.wallet.ui.common.factory
import app.spectra.wallet.ui.components.AuroraBackground
import app.spectra.wallet.ui.components.AuroraPreset
import app.spectra.wallet.ui.components.NoticeCard
import app.spectra.wallet.ui.components.PrimaryButton
import app.spectra.wallet.ui.components.SpectraIcons
import app.spectra.wallet.ui.components.SpectraSheet
import app.spectra.wallet.ui.components.Tone
import app.spectra.wallet.ui.lock.ForgotPinSheet
import app.spectra.wallet.ui.lock.LockScreen
import app.spectra.wallet.ui.onboarding.OnboardingFlow
import app.spectra.wallet.ui.onboarding.OnboardingViewModel
import app.spectra.wallet.ui.theme.Spectra
import kotlinx.coroutines.launch

/**
 * Top-level state machine. When the app locks, the whole main navigation graph leaves composition, so sensitive screens
 * cannot be reached through the back stack.
 */
@Composable
fun SpectraRoot(c: AppContainer, prefs: UserPreferences) {
    SecureWindow.globalEnabled = prefs.blockScreenshotsEverywhere
    CompositionLocalProvider(LocalContainer provides c) {
        val lock by c.security.state.collectAsState()
        val accounts by c.wallets.accounts.collectAsState(initial = null)
        var onboarding by rememberSaveable { mutableStateOf(false) }
        val needsOnboarding = lock == LockState.NO_WALLET || (lock == LockState.UNLOCKED && accounts?.isEmpty() == true)
        LaunchedEffect(needsOnboarding) { if (needsOnboarding) onboarding = true }
        val accs = accounts
        val screen = when {
            accs == null -> RootScreen.BLANK
            lock == LockState.KEYS_LOST -> RootScreen.KEYS_LOST
            onboarding || needsOnboarding -> RootScreen.ONBOARDING
            lock == LockState.LOCKED -> RootScreen.LOCK
            else -> RootScreen.MAIN
        }
        val d = if (Spectra.reducedMotion) 0 else 420
        AnimatedContent(screen, transitionSpec = {
            (fadeIn(tween(d)) + scaleIn(tween(d), initialScale = if (targetState == RootScreen.MAIN) 0.96f else 1.02f)) togetherWith fadeOut(tween(d / 2))
        }, label = "root") { target ->
            when (target) {
                RootScreen.BLANK -> AuroraBackground(AuroraPreset.PIN) { }
                RootScreen.KEYS_LOST -> KeysLostScreen(c)
                RootScreen.ONBOARDING -> {
                    val vm: OnboardingViewModel = viewModel(key = "onboarding", factory = factory { OnboardingViewModel(c, addingWallet = false) })
                    OnboardingFlow(vm, onFinished = { onboarding = false })
                }
                RootScreen.LOCK -> LockScreen(c, accs?.firstOrNull { it.account.id == prefs.activeAccountId }?.account?.name ?: accs?.firstOrNull()?.account?.name ?: "portfel")
                RootScreen.MAIN -> {
                    val context = LocalContext.current
                    val main: MainViewModel = viewModel(factory = factory { MainViewModel(c, context.applicationContext) })
                    MainNavigation(c, main)
                }
            }
        }
        AuthorizationHost(c)
        RootWarning(c, prefs)
    }
}

private enum class RootScreen { BLANK, KEYS_LOST, ONBOARDING, LOCK, MAIN }

@Composable
private fun RootWarning(c: AppContainer, prefs: UserPreferences) {
    val indicators = remember { DeviceIntegrity.rootIndicators() }
    val scope = rememberCoroutineScope()
    if (indicators.isNotEmpty() && !prefs.rootWarningAcknowledged) {
        SpectraSheet({ scope.launch { c.prefs.setRootAck(true) } }) {
            NoticeCard("Podwyższone ryzyko na tym urządzeniu",
                "Wykryto oznaki uprawnień root lub modyfikacji systemu (${indicators.take(2).joinToString()}). Inne aplikacje mogą wtedy uzyskać dostęp do danych portfela. " +
                    "To wykrywanie jest heurystyczne i nie daje gwarancji w żadną stronę. Możesz kontynuować na własne ryzyko.", Tone.WARNING, SpectraIcons.Alert)
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Rozumiem", { scope.launch { c.prefs.setRootAck(true) } })
        }
    }
}

@Composable
private fun KeysLostScreen(c: AppContainer) {
    var reset by remember { mutableStateOf(false) }
    AuroraBackground(AuroraPreset.PIN) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp)) {
            Spacer(Modifier.height(48.dp))
            Text("Nie można odszyfrować portfela", style = Spectra.type.title, color = Spectra.colors.text)
            Spacer(Modifier.height(12.dp))
            NoticeCard("Klucze urządzenia są niedostępne",
                "Magazyn kluczy Androida nie zawiera już klucza, którym zaszyfrowano portfel (np. po przywróceniu kopii systemu lub zmianie zabezpieczeń). " +
                    "Zaszyfrowanych danych nie da się odzyskać na tym urządzeniu. Odtwórz portfel z frazy odzyskiwania.", Tone.NEGATIVE)
            Box(Modifier.weight(1f))
            PrimaryButton("Odtwórz z frazy odzyskiwania", { reset = true })
        }
    }
    if (reset) ForgotPinSheet(c) { reset = false }
}
