package app.spectra.wallet.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import app.spectra.core.model.WalletError
import app.spectra.wallet.domain.AppContainer
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.crypto.Cipher
import kotlin.coroutines.resume

val LocalContainer = compositionLocalOf<AppContainer> { error("No container") }

inline fun <reified VM : ViewModel> factory(crossinline create: () -> VM) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = create() as T
}

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

object SecureWindow {
    private var count = 0
    var globalEnabled = false
        set(v) { field = v }

    fun acquire(a: Activity) { count++; a.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    fun release(a: Activity) {
        count = (count - 1).coerceAtLeast(0)
        if (count == 0 && !globalEnabled) a.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

/** Blocks screenshots, screen recording and recents thumbnails while this composable is on screen. */
@Composable
fun SecureScreen() {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(Unit) {
        SecureWindow.acquire(activity)
        if (android.os.Build.VERSION.SDK_INT >= 33) activity.setRecentsScreenshotEnabled(false)
        onDispose {
            SecureWindow.release(activity)
            if (android.os.Build.VERSION.SDK_INT >= 33) activity.setRecentsScreenshotEnabled(true)
        }
    }
}

enum class BiometricAvailability { AVAILABLE, NONE_ENROLLED, NO_HARDWARE, UNAVAILABLE, SECURITY_UPDATE_REQUIRED }

fun biometricAvailability(context: Context): BiometricAvailability = when (BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)) {
    BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NONE_ENROLLED
    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricAvailability.NO_HARDWARE
    BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> BiometricAvailability.SECURITY_UPDATE_REQUIRED
    else -> BiometricAvailability.UNAVAILABLE
}

sealed interface BiometricResult {
    data class Success(val cipher: Cipher) : BiometricResult
    data object Cancelled : BiometricResult
    data class LockedOut(val permanent: Boolean) : BiometricResult
    data class Error(val message: String) : BiometricResult
}

/** Shows BiometricPrompt bound to [cipher] (CryptoObject). Strong biometrics only; the PIN remains the fallback. */
suspend fun FragmentActivity.authenticateBiometric(title: String, subtitle: String?, cipher: Cipher): BiometricResult = suspendCancellableCoroutine { cont ->
    val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            val c = result.cryptoObject?.cipher
            if (cont.isActive) cont.resume(if (c != null) BiometricResult.Success(c) else BiometricResult.Error("Brak obiektu kryptograficznego"))
        }
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            if (!cont.isActive) return
            cont.resume(when (errorCode) {
                BiometricPrompt.ERROR_LOCKOUT -> BiometricResult.LockedOut(false)
                BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> BiometricResult.LockedOut(true)
                BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_CANCELED -> BiometricResult.Cancelled
                else -> BiometricResult.Error(errString.toString())
            })
        }
    })
    val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).apply { subtitle?.let { setSubtitle(it) } }
        .setNegativeButtonText("Użyj PIN-u").setAllowedAuthenticators(BIOMETRIC_STRONG).setConfirmationRequired(true).build()
    prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    cont.invokeOnCancellation { prompt.cancelAuthentication() }
}

/** Human-readable Polish message for any error. Never includes secrets. */
fun Throwable.humanMessage(): String = when (this) {
    is WalletError.NoInternet -> "Brak połączenia z internetem."
    is WalletError.RpcUnavailable -> "Węzeł sieci jest niedostępny. Spróbuj później lub zmień RPC w ustawieniach."
    is WalletError.RateLimited -> "Dostawca ogranicza liczbę zapytań. Spróbuj ponownie za chwilę."
    is WalletError.Timeout -> "Przekroczono czas oczekiwania na odpowiedź sieci."
    is WalletError.WrongNetwork -> "Punkt końcowy RPC obsługuje inną sieć (${actual}). Połączenie zostało zablokowane."
    is WalletError.InvalidAddress -> "Nieprawidłowy adres odbiorcy."
    is WalletError.InsufficientBalance -> "Niewystarczające saldo."
    is WalletError.InsufficientFeeBalance -> "Za mało środków na opłatę sieciową."
    is WalletError.BelowRentExemption -> "Kwota pozostawiłaby konto poniżej minimalnego salda (rent-exempt) w Solanie."
    is WalletError.BlockhashExpired -> "Transakcja Solany wygasła (blockhash). Przygotuj ją ponownie."
    is WalletError.NonceConflict -> "Konflikt nonce Ethereum — inna transakcja użyła tego numeru."
    is WalletError.UtxoConflict -> "Wybrane środki LTC zostały już wydane. Odśwież saldo."
    is WalletError.DustAmount -> "Kwota jest zbyt mała (próg pyłu)."
    is WalletError.UnknownDecimals -> "Nie udało się ustalić liczby miejsc dziesiętnych tokena."
    is WalletError.UnsupportedToken -> "Ten token nie jest obsługiwany: $reason"
    is WalletError.SimulationFailed -> "Symulacja transakcji nie powiodła się: $detail"
    is WalletError.Rejected -> "Sieć odrzuciła transakcję: $detail"
    is WalletError.QuoteExpired -> "Wycena wygasła. Pobierz nową."
    is WalletError.RouteUnavailable -> reason
    is WalletError.ProviderError -> message ?: "Błąd dostawcy."
    is WalletError.FeatureUnavailable -> reason
    is WalletError.Unexpected -> message ?: "Nieoczekiwany błąd."
    else -> "Wystąpił błąd: ${javaClass.simpleName}"
}
