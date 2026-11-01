package app.spectra.wallet.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.spectra.core.crypto.Bip39
import app.spectra.core.crypto.SecureMemory
import app.spectra.core.model.Chain
import app.spectra.core.model.DerivationScheme
import app.spectra.wallet.domain.AccountSummary
import app.spectra.wallet.domain.AppContainer
import app.spectra.wallet.domain.SchemeCandidate
import app.spectra.wallet.ui.common.humanMessage
import kotlinx.coroutines.launch
import javax.crypto.Cipher

enum class OnboardingStep { WELCOME, PIN_CREATE, PIN_CONFIRM, BIOMETRICS, PHRASE_SHOW, PHRASE_VERIFY, IMPORT_PHRASE, IMPORT_SCHEMES, PREPARING, READY }
enum class OnboardingMode { CREATE, IMPORT }

data class VerifyQuestion(val position: Int, val options: List<String>)

class OnboardingViewModel(private val c: AppContainer, val addingWallet: Boolean) : ViewModel() {
    var step by mutableStateOf(if (addingWallet) OnboardingStep.IMPORT_PHRASE else OnboardingStep.WELCOME); private set
    var mode by mutableStateOf(if (addingWallet) OnboardingMode.IMPORT else OnboardingMode.CREATE); private set
    val pinExists: Boolean get() = c.security.hasCredentials()

    // PIN
    var pinEntry by mutableStateOf(""); private set
    var pinError by mutableStateOf<String?>(null); private set
    private var firstPin: CharArray? = null
    private var dek: ByteArray? = null
    var biometricsEnabled by mutableStateOf(false); private set

    // Phrase
    var words by mutableStateOf<List<String>>(emptyList()); private set
    var revealed by mutableStateOf(false)
    var acknowledged by mutableStateOf(false)
    var questions by mutableStateOf<List<VerifyQuestion>>(emptyList()); private set
    var picks by mutableStateOf<Map<Int, String>>(emptyMap()); private set

    // Import
    var importText by mutableStateOf("")
    var candidates by mutableStateOf<Map<Chain, List<SchemeCandidate>>>(emptyMap()); private set
    var discovering by mutableStateOf(false); private set
    var discoveryError by mutableStateOf<String?>(null); private set
    var selectedSchemes by mutableStateOf(Chain.entries.associateWith { DerivationScheme.defaultFor(it) }); private set

    // Preparing
    var progressStep by mutableStateOf(0); private set
    var progressTotal by mutableStateOf(6); private set
    var progressLabel by mutableStateOf(""); private set
    var prepareError by mutableStateOf<String?>(null); private set
    var created by mutableStateOf<AccountSummary?>(null); private set

    fun startCreate() { mode = OnboardingMode.CREATE; step = if (pinExists) OnboardingStep.PHRASE_SHOW.also { generate() } else OnboardingStep.PIN_CREATE }
    fun startImport() { mode = OnboardingMode.IMPORT; step = if (pinExists) OnboardingStep.IMPORT_PHRASE else OnboardingStep.PIN_CREATE }

    fun back() {
        step = when (step) {
            OnboardingStep.PIN_CREATE -> { pinEntry = ""; OnboardingStep.WELCOME }
            OnboardingStep.PIN_CONFIRM -> { pinEntry = ""; firstPin?.let { SecureMemory.wipe(it) }; firstPin = null; OnboardingStep.PIN_CREATE }
            OnboardingStep.PHRASE_VERIFY -> OnboardingStep.PHRASE_SHOW
            OnboardingStep.IMPORT_SCHEMES -> OnboardingStep.IMPORT_PHRASE
            OnboardingStep.PHRASE_SHOW, OnboardingStep.IMPORT_PHRASE -> if (addingWallet || pinExists) step else OnboardingStep.BIOMETRICS
            else -> step
        }
    }

    // ---- PIN ----
    fun pinDigit(d: Char) {
        if (pinEntry.length >= 6) return
        pinError = null
        pinEntry += d
        if (pinEntry.length == 6) onPinComplete()
    }
    fun pinDelete() { pinEntry = pinEntry.dropLast(1); pinError = null }

    private fun weak(p: String): Boolean {
        if (p.all { it == p[0] }) return true
        val asc = "0123456789012"; val desc = "9876543210987"
        return asc.contains(p) || desc.contains(p) || p in setOf("121212", "112233", "111222", "123123", "101010", "696969", "000123")
    }

    private fun onPinComplete() {
        if (step == OnboardingStep.PIN_CREATE) {
            if (weak(pinEntry)) { pinError = "Ten PIN jest zbyt łatwy do odgadnięcia. Wybierz inny."; pinEntry = ""; return }
            firstPin = pinEntry.toCharArray(); pinEntry = ""; step = OnboardingStep.PIN_CONFIRM
        } else if (step == OnboardingStep.PIN_CONFIRM) {
            val second = pinEntry.toCharArray()
            pinEntry = ""
            if (!second.contentEquals(firstPin)) {
                SecureMemory.wipe(second); firstPin?.let { SecureMemory.wipe(it) }; firstPin = null
                pinError = "PIN-y nie są zgodne. Ustaw PIN ponownie."; step = OnboardingStep.PIN_CREATE; return
            }
            viewModelScope.launch {
                try {
                    dek = c.security.createCredentials(second)
                    step = OnboardingStep.BIOMETRICS
                } catch (e: Throwable) {
                    pinError = "Nie udało się zabezpieczyć PIN-u w magazynie kluczy urządzenia: ${e.humanMessage()}"
                    step = OnboardingStep.PIN_CREATE
                } finally { SecureMemory.wipe(second); firstPin?.let { SecureMemory.wipe(it) }; firstPin = null }
            }
        }
    }

    // ---- Biometrics ----
    fun prepareBiometricCipher(): Cipher? = when (val r = c.security.prepareBiometricEnable()) {
        is app.spectra.wallet.security.KeystoreBackend.BioCipher.Ready -> r.cipher
        else -> null
    }

    fun completeBiometric(cipher: Cipher): Boolean = runCatching { c.security.completeBiometricEnable(cipher, dek!!); biometricsEnabled = true }.isSuccess

    fun afterBiometrics() {
        step = if (mode == OnboardingMode.CREATE) { generate(); OnboardingStep.PHRASE_SHOW } else OnboardingStep.IMPORT_PHRASE
    }

    // ---- Phrase ----
    private fun generate() {
        if (words.isEmpty()) words = Bip39.generate(12)
        revealed = false; acknowledged = false
    }

    fun toVerify() {
        val positions = (0 until words.size).shuffled(SecureMemory.random).take(3).sorted()
        questions = positions.map { p ->
            val decoys = words.filterIndexed { i, w -> i != p && w != words[p] }.distinct().shuffled(SecureMemory.random).take(2)
            VerifyQuestion(p + 1, (decoys + words[p]).shuffled(SecureMemory.random))
        }
        picks = emptyMap()
        step = OnboardingStep.PHRASE_VERIFY
    }

    fun pick(position: Int, word: String) { picks = picks + (position to word) }
    fun isCorrect(position: Int) = picks[position] == words[position - 1]
    val allCorrect get() = questions.isNotEmpty() && questions.all { isCorrect(it.position) }

    // ---- Import ----
    val importWords get() = Bip39.normalizeInput(importText)
    val importValidation get() = Bip39.validate(importWords)

    fun discover() {
        if (importValidation != Bip39.Validation.Valid) return
        words = importWords
        step = OnboardingStep.IMPORT_SCHEMES
        discovering = true; discoveryError = null
        viewModelScope.launch {
            try { candidates = c.wallets.discoverSchemes(words) } catch (e: Throwable) { discoveryError = e.humanMessage() }
            discovering = false
        }
    }

    fun selectScheme(s: DerivationScheme) { selectedSchemes = selectedSchemes + (s.chain to s) }

    // ---- Create vault ----
    fun prepare() {
        step = OnboardingStep.PREPARING
        prepareError = null
        viewModelScope.launch {
            val key = dek ?: c.auth.authorize("Potwierdź PIN", "Zaszyfrowanie nowego portfela wymaga potwierdzenia.")
            if (key == null) { prepareError = "Anulowano potwierdzenie."; return@launch }
            try {
                val id = c.wallets.createVault(key, words, selectedSchemes, mode == OnboardingMode.IMPORT) { s, total, label ->
                    progressStep = s; progressTotal = total; progressLabel = label
                }
                c.prefs.setActiveAccount(id)
                created = c.wallets.account(id)
                step = OnboardingStep.READY
            } catch (e: Throwable) {
                prepareError = e.humanMessage()
            } finally {
                if (dek == null) SecureMemory.wipe(key)
            }
        }
    }

    fun finish() {
        words = emptyList(); importText = ""
        dek?.let { SecureMemory.wipe(it) }; dek = null
        c.security.markUnlocked()
    }

    fun unlockedAfterCreation(): Boolean = c.security.state.value == app.spectra.wallet.security.LockState.UNLOCKED

    override fun onCleared() {
        dek?.let { SecureMemory.wipe(it) }; dek = null
        firstPin?.let { SecureMemory.wipe(it) }
        words = emptyList()
    }

}
