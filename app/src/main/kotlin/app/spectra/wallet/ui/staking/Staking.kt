package app.spectra.wallet.ui.staking

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.spectra.core.model.Amount
import app.spectra.core.model.NetworkEnv
import app.spectra.core.staking.StakePosition
import app.spectra.core.staking.StakeState
import app.spectra.core.staking.Validator
import app.spectra.wallet.domain.AppContainer
import app.spectra.wallet.domain.ExecuteResult
import app.spectra.wallet.domain.PreparedStake
import app.spectra.wallet.domain.StakeOp
import app.spectra.wallet.domain.StakingOverview
import app.spectra.wallet.domain.TransactionRepository
import app.spectra.wallet.ui.MainViewModel
import app.spectra.wallet.ui.common.LocalContainer
import app.spectra.wallet.ui.common.factory
import app.spectra.wallet.ui.common.humanMessage
import app.spectra.wallet.ui.components.Chip
import app.spectra.wallet.ui.components.EmptyState
import app.spectra.wallet.ui.components.ErrorState
import app.spectra.wallet.ui.components.GlassCard
import app.spectra.wallet.ui.components.GlassTextField
import app.spectra.wallet.ui.components.Hairline
import app.spectra.wallet.ui.components.KeyValueRow
import app.spectra.wallet.ui.components.LinkButton
import app.spectra.wallet.ui.components.LoadingState
import app.spectra.wallet.ui.components.NoticeCard
import app.spectra.wallet.ui.components.PrimaryButton
import app.spectra.wallet.ui.components.SegmentedTabs
import app.spectra.wallet.ui.components.SpectraIcons
import app.spectra.wallet.ui.components.SpectraSheet
import app.spectra.wallet.ui.components.StatusPill
import app.spectra.wallet.ui.components.Tone
import app.spectra.wallet.ui.components.glassSurface
import app.spectra.wallet.ui.format.Fmt
import app.spectra.wallet.ui.theme.Spectra
import app.spectra.wallet.ui.components.enterAnimation
import app.spectra.wallet.ui.components.Reveal
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.scaleIn
import androidx.compose.animation.core.tween
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

class StakingViewModel(private val c: AppContainer, private val main: MainViewModel) : ViewModel() {
    var overview by mutableStateOf<StakingOverview?>(null); private set
    var validators by mutableStateOf<List<Validator>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var loadError by mutableStateOf<String?>(null); private set
    var selected by mutableStateOf<Validator?>(null)
    var amountText by mutableStateOf("")
    var prepared by mutableStateOf<PreparedStake?>(null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var info by mutableStateOf<String?>(null)
    var rentReserve by mutableStateOf<Long?>(null); private set

    val env get() = main.session.value?.env ?: NetworkEnv.MAINNET
    private val accountId get() = main.session.value?.active?.account?.id

    fun load() {
        val id = accountId ?: return
        loading = true; loadError = null
        viewModelScope.launch {
            runCatching { c.stakingRepo.overview(id, env) }.onSuccess { overview = it }.onFailure { loadError = it.humanMessage() }
            if (validators.isEmpty()) runCatching { c.stakingRepo.validators(env) }.onSuccess { v -> validators = v; if (selected == null) selected = v.firstOrNull { !it.delinquent } }
            if (rentReserve == null) rentReserve = runCatching { c.staking.rentReserve(app.spectra.core.model.Network(app.spectra.core.model.Chain.SOLANA, env)) }.getOrNull()
            loading = false
        }
    }

    fun setFraction(f: Double) {
        val liquid = overview?.liquidLamports ?: return
        val reserve = (rentReserve ?: 2_282_880L) + 50_000L // keep rent reserve + fee margin
        val v = ((liquid - reserve) * f).toLong().coerceAtLeast(0)
        amountText = Fmt.exact(Amount.of(v, 9))
    }

    fun prepareDelegate() {
        val id = accountId ?: return
        val v = selected ?: run { error = "Wybierz walidatora"; return }
        val amt = Amount.parse(amountText, 9) ?: run { error = "Nieprawidłowa kwota"; return }
        if (amt.isZero) { error = "Wpisz kwotę"; return }
        busy = true; error = null
        viewModelScope.launch {
            runCatching { c.stakingRepo.prepareDelegate(id, env, v.votePubkey, amt.units.toLong()) }.onSuccess { prepared = it }.onFailure { error = it.humanMessage() }
            busy = false
        }
    }

    fun prepare(op: StakeOp, p: StakePosition) {
        val id = accountId ?: return
        busy = true; error = null
        viewModelScope.launch {
            runCatching { if (op == StakeOp.DEACTIVATE) c.stakingRepo.prepareDeactivate(id, env, p) else c.stakingRepo.prepareWithdraw(id, env, p) }
                .onSuccess { prepared = it }.onFailure { error = it.humanMessage() }
            busy = false
        }
    }

    fun dismiss() { prepared = null }

    fun execute() {
        val p = prepared ?: return
        val acc = main.session.value?.active ?: return
        busy = true; error = null
        viewModelScope.launch {
            when (val r = c.stakingRepo.execute(p, acc.vault.id, acc.account.id)) {
                is ExecuteResult.Submitted -> { info = "Transakcja wysłana. Zmiany stake'u są widoczne po potwierdzeniu i zależą od granic epok."; amountText = ""; prepared = null; load() }
                is ExecuteResult.Failed -> error = r.error.humanMessage()
                ExecuteResult.Cancelled -> Unit
            }
            busy = false
        }
    }
}

fun stakeStateLabel(s: StakeState) = when (s) {
    StakeState.INITIALIZED -> "Niedelegowany"; StakeState.ACTIVATING -> "Aktywacja (do końca epoki)"; StakeState.ACTIVE -> "Aktywny"
    StakeState.DEACTIVATING -> "Dezaktywacja (do końca epoki)"; StakeState.INACTIVE -> "Gotowy do wypłaty"
}

@Composable
fun StakingScreen(main: MainViewModel) {
    val c = LocalContainer.current
    val vm: StakingViewModel = viewModel(factory = factory { StakingViewModel(c, main) })
    val session by main.session.collectAsState()
    val col = Spectra.colors
    var tab by rememberSaveable { mutableStateOf(0) }
    var pickValidator by remember { mutableStateOf(false) }
    LaunchedEffect(session?.active?.account?.id, session?.env) { vm.load() }
    val currency = session?.prefs?.currency ?: "PLN"
    val solPrice = main.portfolio.collectAsState().value?.rows?.firstOrNull { it.token.id.isNative && it.token.id.network.chain == app.spectra.core.model.Chain.SOLANA }?.unitPrice
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 120.dp)) {
        Text("Staking", style = Spectra.type.title, color = col.text, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().glassSurface(RoundedCornerShape(26.dp)).padding(4.dp)) { SegmentedTabs(listOf("Solana", "Ethereum"), tab, { tab = it }, Modifier.fillMaxWidth(), fill = true) }
        Spacer(Modifier.height(12.dp))
        if (tab == 1) {
            NoticeCard(title = "Staking ETH niedostępny w v0.1", body = "Natywny staking Ethereum wymaga 32 ETH i infrastruktury walidatora, a staking płynny opiera się na kontraktach zewnętrznych. Spectra 0.1 nie oferuje żadnej z tych opcji.", tone = Tone.NEUTRAL, icon = SpectraIcons.Info, modifier = Modifier.enterAnimation(0, key = tab))
            return@Column
        }
        val o = vm.overview
        when {
            o == null && vm.loading -> LoadingState("Wczytywanie danych stakingu…")
            o == null && vm.loadError != null -> ErrorState(vm.loadError!!, vm::load)
        }
        GlassCard(Modifier.fillMaxWidth().enterAnimation(0), padding = PaddingValues(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Inflacja sieci", style = Spectra.type.label, color = col.textSecondary, modifier = Modifier.weight(1f))
                StatusPill(if (vm.env == NetworkEnv.TESTNET) "Solana Devnet" else "Sieć Solana", if (vm.env == NetworkEnv.TESTNET) Tone.WARNING else Tone.POSITIVE)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                val infl = o?.inflationPct?.let { app.spectra.wallet.ui.components.animatedNumber(it) }
                Text(infl?.let { String.format(java.util.Locale.ROOT, "%.2f", it).replace('.', ',') + "%" } ?: "—", style = Spectra.type.display, color = col.text)
                Spacer(Modifier.width(8.dp))
                Text("rocznie", style = Spectra.type.bodyStrong, color = col.textSecondary, modifier = Modifier.padding(bottom = 6.dp))
            }
            Text("Wskaźnik inflacji dla walidatorów — nie jest gwarantowaną stopą zwrotu. Rzeczywiste nagrody zależą od prowizji i działania walidatora.", style = Spectra.type.caption, color = col.textSecondary)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val staked = o?.positions?.sumOf { it.delegatedLamports } ?: 0
                val earned = o?.rewards?.sumOf { it.lamports } ?: 0
                listOf("W stakingu" to staked, "Nagrody (3 epoki)" to earned).forEach { (l, v) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).glassSurface(RoundedCornerShape(18.dp), strong = true).padding(12.dp)) {
                        Text(l, style = Spectra.type.caption, color = col.textSecondary)
                        Text(Fmt.amountWithSymbol(Amount.of(v, 9), "SOL"), style = Spectra.type.bodyStrong, color = col.text)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        GlassCard(Modifier.fillMaxWidth().enterAnimation(1), padding = PaddingValues(18.dp)) {
            Row { Text("Kwota", style = Spectra.type.label, color = col.text, modifier = Modifier.weight(1f)); Text("Dostępne " + Fmt.amountWithSymbol(Amount.of(o?.liquidLamports ?: 0, 9), "SOL"), style = Spectra.type.caption, color = col.textSecondary) }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Box(Modifier.weight(1f)) {
                    if (vm.amountText.isEmpty()) Text("0", style = Spectra.type.display, color = col.textDisabled)
                    BasicTextField(vm.amountText, { vm.amountText = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' }.take(18) }, Modifier.fillMaxWidth().semantics { contentDescription = "Kwota stakingu w SOL" },
                        textStyle = Spectra.type.display.copy(color = col.text), singleLine = true, cursorBrush = SolidColor(col.primary), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                val fiat = Amount.parse(vm.amountText, 9)?.let { a -> solPrice?.let { a.toDecimal() * it } }
                Text(fiat?.let { "≈ " + Fmt.fiat(it.setScale(2, RoundingMode.HALF_UP), currency) } ?: "", style = Spectra.type.body, color = col.textSecondary, modifier = Modifier.padding(bottom = 10.dp))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("25%" to .25, "50%" to .5, "75%" to .75, "Max" to 1.0).forEach { (l, f) -> Chip(l, false, { vm.setFraction(f) }, Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.height(12.dp))
        GlassCard(Modifier.fillMaxWidth().enterAnimation(2)) {
            KeyValueRow("Walidator", vm.selected?.let { Fmt.shortAddress(it.votePubkey, 6, 4) + " · ${it.commission}%" } ?: "wybierz", onClick = { pickValidator = true }, trailingIcon = SpectraIcons.Chevron)
            Hairline()
            KeyValueRow("Aktywacja / odblokowanie", "na granicy epoki" + (o?.epoch?.let { " (epoka ${it.epoch}: ${(it.progress * 100).toInt()}%)" } ?: ""))
            Hairline()
            KeyValueRow("Rezerwa rent (zwracana)", vm.rentReserve?.let { Fmt.amountWithSymbol(Amount.of(it, 9), "SOL") } ?: "—", strong = false)
            Text("Konto stake'u pozostaje pod twoją kontrolą: Ty jesteś zarówno uprawnionym do stakingu, jak i do wypłaty.", style = Spectra.type.caption, color = col.textSecondary, modifier = Modifier.padding(top = 6.dp))
        }
        Reveal(vm.error != null) { Spacer(Modifier.height(10.dp)); NoticeCard("Nie udało się", vm.error ?: "", Tone.NEGATIVE, SpectraIcons.Alert) }
        Reveal(vm.info != null) { Spacer(Modifier.height(10.dp)); NoticeCard("Wysłano", vm.info ?: "", Tone.PRIMARY, SpectraIcons.Info) }
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Stakuj ${vm.amountText.ifEmpty { "0" }} SOL", vm::prepareDelegate, enabled = vm.amountText.isNotBlank() && vm.selected != null, loading = vm.busy)
        Spacer(Modifier.height(20.dp))
        Text("Twoje pozycje", style = Spectra.type.bodyStrong, color = col.text)
        Spacer(Modifier.height(8.dp))
        val positions = o?.positions.orEmpty()
        if (positions.isEmpty()) EmptyState("Brak kont stake'u", "Po delegacji pojawią się tutaj wraz ze stanem aktywacji.", SpectraIcons.Earn)
        positions.forEachIndexed { pi, p ->
            GlassCard(Modifier.fillMaxWidth().enterAnimation(pi, key = p.stakeAccount).padding(bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(Fmt.amountWithSymbol(Amount.of(p.lamports, 9), "SOL"), style = Spectra.type.bodyStrong, color = col.text)
                        Text("Konto ${Fmt.shortAddress(p.stakeAccount)} · walidator ${p.voter?.let { Fmt.shortAddress(it) } ?: "—"}", style = Spectra.type.caption, color = col.textSecondary)
                    }
                    StatusPill(stakeStateLabel(p.state), when (p.state) { StakeState.ACTIVE -> Tone.POSITIVE; StakeState.INACTIVE -> Tone.PRIMARY; else -> Tone.WARNING }, dot = false,
                        pulsing = p.state == StakeState.ACTIVATING || p.state == StakeState.DEACTIVATING)
                }
                if (p.staker != session?.active?.view(app.spectra.core.model.Network(app.spectra.core.model.Chain.SOLANA, vm.env))?.primaryAddress)
                    Text("Uprawnienie do stakingu należy do innego adresu.", style = Spectra.type.caption, color = col.warning)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (p.canDeactivate) LinkButton("Dezaktywuj", { vm.prepare(StakeOp.DEACTIVATE, p) })
                    if (p.canWithdraw) LinkButton("Wypłać", { vm.prepare(StakeOp.WITHDRAW, p) })
                }
            }
        }
    }
    if (pickValidator) ValidatorSheet(vm.validators, { vm.selected = it; pickValidator = false }) { pickValidator = false }
    vm.prepared?.let { p -> StakeConfirmSheet(vm, p) }
}

@Composable
private fun ValidatorSheet(validators: List<Validator>, onPick: (Validator) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val col = Spectra.colors
    SpectraSheet(onDismiss) {
        Text("Walidatorzy", style = Spectra.type.headline, color = col.text)
        Text("Posortowani wg aktywnego stake'u. Nazwy walidatorów nie są pobierane w v0.1 — sprawdź klucz głosowania w eksploratorze.", style = Spectra.type.caption, color = col.textSecondary)
        Spacer(Modifier.height(10.dp))
        GlassTextField(q, { q = it.trim() }, "Szukaj po kluczu głosowania", sensitive = true)
        Spacer(Modifier.height(8.dp))
        validators.filter { q.isEmpty() || it.votePubkey.startsWith(q) }.take(60).forEach { v ->
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp)).clickable(enabled = !v.delinquent, role = Role.Button) { onPick(v) }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Fmt.shortAddress(v.votePubkey, 8, 6), style = Spectra.type.mono, color = if (v.delinquent) col.textDisabled else col.text)
                    Text("Prowizja ${v.commission}% · stake ${Fmt.amount(Amount.of(v.activatedStakeLamports / 1_000_000_000, 0))} SOL", style = Spectra.type.caption, color = col.textSecondary)
                }
                if (v.delinquent) StatusPill("nie głosuje", Tone.NEGATIVE, dot = false) else if (v.commission >= 50) StatusPill("wysoka prowizja", Tone.WARNING, dot = false)
            }
        }
    }
}

@Composable
private fun StakeConfirmSheet(vm: StakingViewModel, p: PreparedStake) {
    val col = Spectra.colors
    val allowed = TransactionRepository.signingAllowed(p.account.network)
    SpectraSheet(vm::dismiss) {
        Text(when (p.op) { StakeOp.DELEGATE -> "Delegacja SOL"; StakeOp.DEACTIVATE -> "Dezaktywacja stake'u"; StakeOp.WITHDRAW -> "Wypłata ze stake'u" }, style = Spectra.type.headline, color = col.text)
        Spacer(Modifier.height(12.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            KeyValueRow("Kwota", Fmt.amountWithSymbol(Amount.of(p.lamports, 9), "SOL"))
            KeyValueRow("Konto stake'u", Fmt.shortAddress(p.stakeAccount, 6, 6))
            p.validator?.let { KeyValueRow("Walidator", Fmt.shortAddress(it, 6, 6)) }
            KeyValueRow("Opłata sieciowa", Fmt.amountWithSymbol(p.message.fee, "SOL"))
            Text(p.message.feeDetail, style = Spectra.type.caption, color = col.textSecondary)
        }
        Spacer(Modifier.height(10.dp))
        Text(when (p.op) {
            StakeOp.DELEGATE -> "Stake zacznie przynosić nagrody po aktywacji na granicy epoki. Nagrody nie są gwarantowane; walidator może pobrać prowizję lub przestać działać."
            StakeOp.DEACTIVATE -> "Stake przestanie przynosić nagrody. Środki będzie można wypłacić po zakończeniu bieżącej epoki (zwykle 2–3 dni)."
            StakeOp.WITHDRAW -> "Całe saldo konta stake'u (wraz z rezerwą rent) wróci na twój adres."
        }, style = Spectra.type.body, color = col.textSecondary)
        if (!allowed) { Spacer(Modifier.height(10.dp)); NoticeCard("Podpisywanie wyłączone", "W tej eksperymentalnej kompilacji staking w sieci głównej jest zablokowany. Użyj Devnetu.", Tone.NEGATIVE, SpectraIcons.ShieldAlert) }
        vm.error?.let { Spacer(Modifier.height(10.dp)); NoticeCard("Nie udało się", it, Tone.NEGATIVE, SpectraIcons.Alert) }
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Potwierdź", vm::execute, enabled = allowed, loading = vm.busy)
    }
}
