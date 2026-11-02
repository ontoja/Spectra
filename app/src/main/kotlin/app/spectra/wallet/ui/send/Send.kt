package app.spectra.wallet.ui.send

import android.content.ClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.spectra.core.chain.AddressCheck
import app.spectra.core.chain.AddressError
import app.spectra.core.chain.AddressValidator
import app.spectra.core.chain.FeeLevel
import app.spectra.core.chain.PreparedTransaction
import app.spectra.core.chain.TransferRequest
import app.spectra.core.chain.TxWarning
import app.spectra.core.model.Amount
import app.spectra.core.model.AssetId
import app.spectra.core.model.TokenSupport
import app.spectra.wallet.domain.AppContainer
import app.spectra.wallet.domain.AssetRow
import app.spectra.wallet.domain.ExecuteResult
import app.spectra.wallet.domain.TransactionRepository
import app.spectra.wallet.ui.MainViewModel
import app.spectra.wallet.ui.common.LocalContainer
import app.spectra.wallet.ui.common.factory
import app.spectra.wallet.ui.common.humanMessage
import app.spectra.wallet.ui.components.AssetAvatar
import app.spectra.wallet.ui.components.AuroraBackground
import app.spectra.wallet.ui.components.AuroraPreset
import app.spectra.wallet.ui.components.Chip
import app.spectra.wallet.ui.components.GlassCard
import app.spectra.wallet.ui.components.GlassIconButton
import app.spectra.wallet.ui.components.Hairline
import app.spectra.wallet.ui.components.KeyValueRow
import app.spectra.wallet.ui.components.KeypadKey
import app.spectra.wallet.ui.components.NoticeCard
import app.spectra.wallet.ui.components.NumericKeypad
import app.spectra.wallet.ui.components.PrimaryButton
import app.spectra.wallet.ui.components.SpectraIcons
import app.spectra.wallet.ui.components.SpectraSheet
import app.spectra.wallet.ui.components.Tone
import app.spectra.wallet.ui.components.TopBar
import app.spectra.wallet.ui.components.glassSurface
import app.spectra.wallet.ui.home.AssetRowItem
import app.spectra.wallet.ui.format.Fmt
import app.spectra.wallet.ui.theme.Spectra
import app.spectra.wallet.ui.components.enterAnimation
import app.spectra.wallet.ui.components.Reveal
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.SizeTransform
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

fun warningText(w: TxWarning) = when (w) {
    TxWarning.RECIPIENT_IS_SELF -> "Wysyłasz na własny adres."
    TxWarning.RECIPIENT_NEW_ACCOUNT -> "Adres odbiorcy nie ma jeszcze środków w tej sieci — upewnij się, że jest poprawny."
    TxWarning.CREATES_TOKEN_ACCOUNT -> "Transakcja utworzy konto tokena dla odbiorcy (koszt rent jest płatny w SOL)."
    TxWarning.OFF_CURVE_RECIPIENT -> "Adres jest adresem programu (PDA), a nie zwykłym portfelem. Wysyłaj tylko, jeśli wiesz, że jest to zamierzone."
    TxWarning.RECIPIENT_IS_CONTRACT -> "Odbiorca jest kontraktem. Nie każdy kontrakt potrafi obsłużyć przychodzące środki."
    TxWarning.FEE_ESTIMATE_HIGH -> "Opłata jest wyjątkowo wysoka."
    TxWarning.DUST_CHANGE_ADDED_TO_FEE -> "Niewielka reszta (poniżej progu pyłu) zostanie doliczona do opłaty."
    TxWarning.UNCONFIRMED_FUNDS_EXCLUDED -> "Niepotwierdzone środki nie zostały użyte."
    TxWarning.NO_ADDRESS_CHECKSUM -> "Adres nie ma sumy kontrolnej (EIP-55). Sprawdź go dokładnie."
    TxWarning.TOKEN_EXTENSION_WARNING -> "Token używa rozszerzeń Token-2022, które mogą dawać emitentowi dodatkowe uprawnienia."
    TxWarning.MAX_LEAVES_RESIDUAL -> "Opłata jest liczona z maksymalnej ceny gazu; niewykorzystana część zostanie na koncie."
}

class SendViewModel(private val c: AppContainer, private val main: MainViewModel, initial: String?) : ViewModel() {
    var asset by mutableStateOf<AssetRow?>(null)
    var recipient by mutableStateOf("")
    var amountText by mutableStateOf("")
    var sendMax by mutableStateOf(false)
    var feeLevel by mutableStateOf(FeeLevel.NORMAL)
    var preparing by mutableStateOf(false); private set
    var prepared by mutableStateOf<PreparedTransaction?>(null); private set
    var error by mutableStateOf<String?>(null)
    var submitting by mutableStateOf(false); private set

    init {
        viewModelScope.launch {
            val rows = main.portfolio.filterNotNull().first().rows
            if (asset == null) asset = rows.firstOrNull { it.token.id.key == initial } ?: rows.firstOrNull { it.token.id.isNative && it.amount.units.signum() > 0 } ?: rows.firstOrNull()
        }
    }

    val addressCheck: AddressCheck? get() = asset?.let { if (recipient.isBlank()) null else AddressValidator.validate(it.token.id.network, recipient) }
    val amount: Amount? get() = asset?.let { a -> if (sendMax) a.amount else Amount.parse(amountText, a.token.decimals ?: return@let null) }

    fun key(k: KeypadKey) {
        val dec = asset?.token?.decimals ?: return
        sendMax = false
        amountText = when (k) {
            is KeypadKey.Digit -> {
                val next = if (amountText == "0" && k.d != '0') k.d.toString() else amountText + k.d
                val frac = next.substringAfter(',', "")
                if (next.length > 24 || frac.length > dec) amountText else next
            }
            KeypadKey.Decimal -> if (amountText.contains(',')) amountText else (amountText.ifEmpty { "0" } + ",")
            KeypadKey.Delete -> amountText.dropLast(1)
            else -> amountText
        }
    }

    fun max() { val a = asset ?: return; sendMax = true; amountText = Fmt.exact(a.amount) }

    val canContinue get() = asset != null && addressCheck is AddressCheck.Valid && (sendMax || (amount?.units?.signum() ?: 0) > 0) &&
        asset?.token?.support != TokenSupport.UNSUPPORTED

    fun prepare() {
        val a = asset ?: return
        val s = main.session.value ?: return
        val view = s.active?.view(a.token.id.network) ?: return
        val amt = amount ?: Amount.zero(a.token.decimals ?: 0)
        val check = addressCheck as? AddressCheck.Valid ?: return
        preparing = true; error = null
        viewModelScope.launch {
            runCatching { c.transactions.prepare(TransferRequest(view, a.token, check.normalized, amt, sendMax, feeLevel)) }
                .onSuccess { prepared = it }.onFailure { error = it.humanMessage() }
            preparing = false
        }
    }

    fun changeFee(level: FeeLevel) { feeLevel = level; prepare() }
    fun backToCompose() { prepared = null; error = null }

    fun submit(onSubmitted: (String) -> Unit) {
        val p = prepared ?: return
        val s = main.session.value ?: return
        val acc = s.active ?: return
        submitting = true; error = null
        viewModelScope.launch {
            when (val r = c.transactions.execute(p, acc.vault.id, acc.account.id)) {
                is ExecuteResult.Submitted -> onSubmitted(r.recordId)
                ExecuteResult.Cancelled -> Unit
                is ExecuteResult.Failed -> { error = r.error.humanMessage(); r.recordId?.let(onSubmitted) }
            }
            submitting = false
        }
    }
}

@Composable
fun SendScreen(main: MainViewModel, initialAsset: String?, onClose: () -> Unit, onSubmitted: (String) -> Unit) {
    val c = LocalContainer.current
    val vm: SendViewModel = viewModel(factory = factory { SendViewModel(c, main, initialAsset) })
    val p = vm.prepared
    AuroraBackground(AuroraPreset.DETAIL) {
        val d = if (Spectra.reducedMotion) 0 else 300
        AnimatedContent(p, transitionSpec = {
            val dir = if (targetState != null) 1 else -1
            (fadeIn(tween(d)) + slideInHorizontally(tween(d)) { dir * it / 4 }) togetherWith (fadeOut(tween(d / 2)) + slideOutHorizontally(tween(d)) { -dir * it / 4 })
        }, contentKey = { it != null }, label = "sendStage") { prepared ->
            if (prepared == null) Compose(main, vm, onClose) else Review(main, vm, prepared, onSubmitted)
        }
    }
}

@Composable
private fun Compose(main: MainViewModel, vm: SendViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val session by main.session.collectAsState()
    val portfolio by main.portfolio.collectAsState()
    val col = Spectra.colors
    var picker by remember { mutableStateOf(false) }
    var scanInfo by remember { mutableStateOf(false) }
    val a = vm.asset
    val currency = session?.prefs?.currency ?: "PLN"
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
        TopBar("Wyślij", onClose = onClose)
        Spacer(Modifier.height(16.dp))
        GlassCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button) { picker = true }, verticalAlignment = Alignment.CenterVertically) {
                if (a != null) {
                    AssetAvatar(a.token.symbol, a.token.id.network.chain, a.token.id.isNative, 38.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.token.name + if (!a.token.id.isNative) " · ${a.token.id.network.displayName}" else "", style = Spectra.type.bodyStrong, color = col.text)
                        Text("Dostępne " + Fmt.amountWithSymbol(a.amount, a.token.symbol), style = Spectra.type.caption, color = col.textSecondary)
                    }
                } else Text("Wybierz aktywo", style = Spectra.type.bodyStrong, color = col.text, modifier = Modifier.weight(1f))
                Icon(SpectraIcons.ChevronDown, null, Modifier.size(18.dp), tint = col.textSecondary)
            }
            Hairline()
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Do", style = Spectra.type.bodyStrong.copy(fontSize = Spectra.type.body.fontSize), color = col.text)
                Spacer(Modifier.width(16.dp))
                Box(Modifier.weight(1f)) {
                    if (vm.recipient.isEmpty()) Text(if (a?.token?.id?.network?.chain == app.spectra.core.model.Chain.SOLANA) "Adres Solana" else "Adres odbiorcy", style = Spectra.type.body, color = col.textDisabled)
                    BasicTextField(vm.recipient, { vm.recipient = it.trim() }, Modifier.fillMaxWidth().semantics { contentDescription = "Adres odbiorcy" },
                        textStyle = Spectra.type.mono.copy(color = col.text), singleLine = true, cursorBrush = SolidColor(col.primary),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii))
                }
                GlassIconButton(SpectraIcons.Clipboard, "Wklej adres", {
                    context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()?.trim()?.take(120)?.let { vm.recipient = it }
                }, size = 36.dp, tint = col.primary, iconSize = 18.dp)
                Spacer(Modifier.width(6.dp))
                GlassIconButton(SpectraIcons.Scan, "Skanuj kod QR", { scanInfo = true }, size = 36.dp, tint = col.primary, iconSize = 18.dp)
            }
            val netName = a?.token?.id?.network?.displayName ?: ""
            // (message, isValid) — animated so the hint fades/updates as the user types.
            val hint: Pair<String, Boolean>? = when (val ch = vm.addressCheck) {
                is AddressCheck.Invalid -> when (ch.reason) {
                    AddressError.WRONG_NETWORK -> "Ten adres należy do innej sieci."
                    AddressError.BAD_CHECKSUM -> "Błędna suma kontrolna — adres jest uszkodzony."
                    AddressError.UNSUPPORTED_TYPE -> "Ten typ adresu (np. MWEB) nie jest obsługiwany."
                    else -> "Nieprawidłowy adres dla sieci $netName."
                } to false
                is AddressCheck.Valid -> "Adres poprawny dla $netName" to true
                null -> null
            }
            val hd = if (Spectra.reducedMotion) 0 else 200
            AnimatedContent(hint, transitionSpec = { fadeIn(tween(hd)) togetherWith fadeOut(tween(hd / 2)) }, label = "addrCheck") { h ->
                if (h != null) Text(h.first, style = Spectra.type.captionStrong, color = if (h.second) col.positive else col.negative, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                val ad = if (Spectra.reducedMotion) 0 else 180
                AnimatedContent(vm.amountText, transitionSpec = {
                    val up = targetState.length >= initialState.length
                    ((fadeIn(tween(ad)) + slideInVertically(tween(ad)) { if (up) it / 2 else -it / 2 }) togetherWith
                        (fadeOut(tween(ad / 2)) + slideOutVertically(tween(ad)) { if (up) -it / 2 else it / 2 })).using(SizeTransform(clip = false))
                }, label = "amount") { text ->
                    Text(text.ifEmpty { "0" }, style = Spectra.type.amountEntry, color = if (text.isEmpty()) col.textDisabled else col.text, maxLines = 1)
                }
                Spacer(Modifier.width(8.dp))
                Text(a?.token?.symbol ?: "", style = Spectra.type.headline, color = col.textSecondary, modifier = Modifier.padding(bottom = 8.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val fiat = (vm.amount ?: a?.token?.decimals?.let { Amount.zero(it) }.takeIf { vm.amountText.isEmpty() })?.let { amt -> a?.unitPrice?.let { amt.toDecimal() * it } }
                Text("≈ " + (fiat?.let { Fmt.fiat(it.setScale(2, RoundingMode.HALF_UP), currency) } ?: "— ${Fmt.currencySymbol(currency)}"), style = Spectra.type.bodyMedium, color = col.textSecondary)
                Spacer(Modifier.width(10.dp))
                Chip("Max", vm.sendMax, vm::max)
            }
            Text("Opłata sieciowa zostanie wyliczona przed potwierdzeniem", style = Spectra.type.caption, color = col.textSecondary, modifier = Modifier.padding(top = 8.dp))
            if (a?.token?.support == TokenSupport.UNSUPPORTED) Text(a.token.supportNote ?: "Wysyłanie tego tokena nie jest obsługiwane.", style = Spectra.type.captionStrong, color = col.negative, textAlign = TextAlign.Center)
            Reveal(vm.error != null) { Text(vm.error ?: "", style = Spectra.type.captionStrong, color = col.negative, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp)) }
        }
        Spacer(Modifier.weight(1f))
        NumericKeypad(vm::key, leftKey = KeypadKey.Decimal, glass = false)
        Spacer(Modifier.height(12.dp))
        PrimaryButton("Dalej", vm::prepare, enabled = vm.canContinue, loading = vm.preparing)
    }
    if (picker) SpectraSheet({ picker = false }) {
        Text("Wybierz aktywo", style = Spectra.type.headline, color = col.text)
        Spacer(Modifier.height(12.dp))
        val active = session?.active?.account?.id
        portfolio?.rows.orEmpty().filter { it.accountId == active }.forEach { r ->
            AssetRowItem(r, currency, false) { vm.asset = r; vm.amountText = ""; vm.sendMax = false; picker = false }
        }
    }
    if (scanInfo) SpectraSheet({ scanInfo = false }) {
        Text("Skanowanie kodów QR", style = Spectra.type.headline, color = col.text)
        Spacer(Modifier.height(10.dp))
        Text("Ta wersja nie prosi o dostęp do aparatu, więc skanowanie QR jest niedostępne. Skopiuj adres i użyj przycisku Wklej, a następnie porównaj początek i koniec adresu.", style = Spectra.type.body, color = col.textSecondary)
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Rozumiem", { scanInfo = false })
    }
}

@Composable
private fun Review(main: MainViewModel, vm: SendViewModel, p: PreparedTransaction, onSubmitted: (String) -> Unit) {
    val session by main.session.collectAsState()
    val col = Spectra.colors
    val r = p.request
    val n = r.account.network
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(p) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val expired = p.expiresAtMillis?.let { now > it } == true
    val allowed = TransactionRepository.signingAllowed(n)
    val currency = session?.prefs?.currency ?: "PLN"
    val nativeSym = n.chain.symbol
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
        TopBar("Sprawdź transakcję", onBack = vm::backToCompose)
        Spacer(Modifier.height(20.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            AssetAvatar(r.token.symbol, n.chain, r.token.id.isNative, 52.dp, Modifier.enterAnimation(0))
            Spacer(Modifier.height(12.dp))
            Text(Fmt.exact(p.recipientAmount) + " " + r.token.symbol, style = Spectra.type.title, color = col.text, textAlign = TextAlign.Center)
            vm.asset?.unitPrice?.let { Text("≈ " + Fmt.fiat((p.recipientAmount.toDecimal() * it).setScale(2, RoundingMode.HALF_UP), currency), style = Spectra.type.body, color = col.textSecondary) }
        }
        Spacer(Modifier.height(20.dp))
        GlassCard(Modifier.fillMaxWidth().enterAnimation(2)) {
            KeyValueRow("Sieć", n.displayName, if (n.isTestnet) col.warning else col.text)
            KeyValueRow("Z konta", (session?.active?.account?.name ?: "") + " · " + Fmt.shortAddress(r.account.primaryAddress))
            Text("Do", style = Spectra.type.body, color = col.textSecondary, modifier = Modifier.padding(top = 8.dp))
            Text(r.recipient, style = Spectra.type.mono.copy(fontSize = Spectra.type.body.fontSize), color = col.text, modifier = Modifier.padding(bottom = 8.dp))
            Hairline()
            KeyValueRow("Opłata sieciowa" + if (n.chain == app.spectra.core.model.Chain.ETHEREUM) " (maks.)" else "", Fmt.exact(p.networkFee) + " " + nativeSym)
            if (!p.extraNativeCost.isZero) KeyValueRow("Utworzenie konta tokena", Fmt.exact(p.extraNativeCost) + " " + nativeSym)
            if (r.token.id.isNative) KeyValueRow("Łącznie", Fmt.exact(p.recipientAmount + p.networkFee + p.extraNativeCost) + " " + nativeSym)
            Text(p.feeDetail, style = Spectra.type.caption, color = col.textSecondary, modifier = Modifier.padding(vertical = 6.dp))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(FeeLevel.SLOW to "Wolno", FeeLevel.NORMAL to "Normalnie", FeeLevel.FAST to "Szybko").forEach { (l, t) -> Chip(t, vm.feeLevel == l, { vm.changeFee(l) }, enabled = !vm.preparing) }
        }
        p.warnings.forEachIndexed { i, w -> Spacer(Modifier.height(10.dp)); NoticeCard("Uwaga", warningText(w), Tone.WARNING, SpectraIcons.Alert, Modifier.enterAnimation(3 + i)) }
        if (n.isTestnet) { Spacer(Modifier.height(10.dp)); NoticeCard("Sieć testowa", "Ta transakcja odbywa się w sieci testowej. Tokeny nie mają wartości.", Tone.PRIMARY, SpectraIcons.Info) }
        if (!allowed) { Spacer(Modifier.height(10.dp)); NoticeCard("Wysyłanie w sieci głównej wyłączone",
            "To eksperymentalna wersja bez niezależnego audytu bezpieczeństwa. Podpisywanie transakcji w sieci głównej jest zablokowane w tej kompilacji. Możesz testować w sieciach testowych (Profil → Sieci testowe).", Tone.NEGATIVE, SpectraIcons.ShieldAlert) }
        Reveal(vm.error != null) { Spacer(Modifier.height(10.dp)); NoticeCard("Nie udało się", vm.error ?: "", Tone.NEGATIVE, SpectraIcons.Alert) }
        Spacer(Modifier.height(16.dp))
        p.expiresAtMillis?.let {
            Text(if (expired) "Wycena wygasła — odśwież opłatę." else "Ważne jeszcze ${((it - now) / 1000).coerceAtLeast(0)} s", style = Spectra.type.captionStrong,
                color = if (expired) col.negative else col.textSecondary, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
        }
        if (expired) PrimaryButton("Odśwież opłatę", vm::prepare, loading = vm.preparing)
        else PrimaryButton("Potwierdź i wyślij", { vm.submit(onSubmitted) }, enabled = allowed && !vm.preparing, loading = vm.submitting)
        Spacer(Modifier.height(8.dp))
        Text("Transakcja zostanie podpisana lokalnie po potwierdzeniu PIN-em lub biometrią.", style = Spectra.type.caption, color = col.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}
