package app.spectra.wallet.ui.lock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import app.spectra.core.crypto.SecureMemory
import app.spectra.wallet.security.KeystoreBackend
import app.spectra.wallet.security.LockoutPolicy
import app.spectra.wallet.security.SecurityManager
import app.spectra.wallet.security.UnlockResult
import app.spectra.wallet.ui.common.BiometricResult
import app.spectra.wallet.ui.common.authenticateBiometric
import app.spectra.wallet.ui.common.findActivity
import app.spectra.wallet.ui.components.KeypadKey
import app.spectra.wallet.ui.components.PinState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Shared PIN/biometric entry state for the lock screen and the authorisation sheet. */
class PinAuthState(private val security: SecurityManager, private val onDek: (ByteArray) -> Unit, private val activity: FragmentActivity?, private val launch: (suspend () -> Unit) -> Unit) {
    var entry by mutableStateOf("")
    var state by mutableStateOf(PinState.IDLE)
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var lockedUntil by mutableLongStateOf(0L)
    val biometricEnabled = security.biometricEnabled()

    fun refreshLockout() {
        lockedUntil = (security.lockoutDecision() as? LockoutPolicy.Decision.Locked)?.untilMillis ?: 0L
    }

    fun onKey(k: KeypadKey) {
        if (busy || lockedUntil > System.currentTimeMillis()) return
        when (k) {
            is KeypadKey.Digit -> {
                if (state == PinState.ERROR) { entry = ""; state = PinState.IDLE }
                if (entry.length < 6) entry += k.d
                if (entry.length == 6) submit()
            }
            KeypadKey.Delete -> { entry = entry.dropLast(1); state = PinState.IDLE; message = null }
            KeypadKey.Biometric -> biometric()
            else -> Unit
        }
    }

    private fun submit() {
        val pin = entry.toCharArray()
        busy = true
        launch {
            val r = security.unlockWithPin(pin)
            SecureMemory.wipe(pin)
            busy = false
            when (r) {
                is UnlockResult.Success -> { state = PinState.OK; message = null; onDek(r.dek) }
                is UnlockResult.WrongPin -> {
                    state = PinState.ERROR
                    message = if (r.lockedUntil != null) "Nieprawidłowy PIN. Kolejna próba możliwa za ${((r.lockedUntil - System.currentTimeMillis()) / 1000) + 1} s."
                    else "Nieprawidłowy PIN. Spróbuj ponownie." + if (r.failures >= 3) " (próba ${r.failures})" else ""
                    r.lockedUntil?.let { lockedUntil = it }
                }
                is UnlockResult.LockedOut -> { state = PinState.ERROR; lockedUntil = r.untilMillis; message = "Zbyt wiele prób." }
                UnlockResult.KeysLost -> { state = PinState.ERROR; message = "Klucze urządzenia zostały usunięte. Odtwórz portfel z frazy odzyskiwania." }
                UnlockResult.Corrupted -> { state = PinState.ERROR; message = "Dane zabezpieczeń są uszkodzone. Odtwórz portfel z frazy odzyskiwania." }
            }
            if (state == PinState.ERROR) { delay(600); entry = "" }
        }
    }

    fun biometric() {
        val act = activity ?: return
        if (!biometricEnabled) return
        launch {
            when (val prep = security.prepareBiometricUnlock()) {
                is KeystoreBackend.BioCipher.Ready -> when (val r = act.authenticateBiometric("Spectra", "Potwierdź tożsamość", prep.cipher)) {
                    is BiometricResult.Success -> {
                        val dek = security.completeBiometricUnlock(r.cipher)
                        if (dek != null) { state = PinState.OK; onDek(dek) } else message = "Nie udało się odszyfrować klucza. Użyj PIN-u."
                    }
                    BiometricResult.Cancelled -> Unit
                    is BiometricResult.LockedOut -> message = if (r.permanent) "Biometria zablokowana. Użyj PIN-u." else "Zbyt wiele prób biometrii. Użyj PIN-u."
                    is BiometricResult.Error -> message = r.message
                }
                KeystoreBackend.BioCipher.Invalidated -> message = "Zmieniono biometrię w telefonie. Ze względów bezpieczeństwa wyłączyliśmy ją — użyj PIN-u i włącz ponownie w ustawieniach."
                KeystoreBackend.BioCipher.Missing -> message = "Biometria nie jest skonfigurowana."
            }
        }
    }
}

@Composable
fun rememberPinAuth(security: SecurityManager, onDek: (ByteArray) -> Unit): PinAuthState {
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current.findActivity() as? FragmentActivity
    val s = remember { PinAuthState(security, onDek, activity) { block -> scope.launch { block() } } }
    LaunchedEffect(Unit) {
        while (true) { s.refreshLockout(); delay(1000) }
    }
    return s
}
