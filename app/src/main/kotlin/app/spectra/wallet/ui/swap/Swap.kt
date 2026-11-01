package app.spectra.wallet.ui.swap

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.spectra.core.model.Amount
import app.spectra.core.model.Chain
import app.spectra.core.model.Network
import app.spectra.core.model.TokenInfo
import app.spectra.core.swap.RouteSupport
import app.spectra.core.swap.SwapQuote
import app.spectra.core.swap.SwapRequest
import app.spectra.core.swap.SwapStatus
import app.spectra.core.swap.SwapTransaction
import app.spectra.wallet.domain.AppContainer
import app.spectra.wallet.domain.ExecuteResult
import app.spectra.wallet.domain.SwapInspection
import app.spectra.wallet.domain.TransactionRepository
import app.spectra.wallet.domain.toInfo
import app.spectra.wallet.ui.MainViewModel
import app.spectra.wallet.ui.common.LocalContainer
import app.spectra.wallet.ui.common.factory
import app.spectra.wallet.ui.common.humanMessage
import app.spectra.wallet.ui.components.AssetAvatar
import app.spectra.wallet.ui.components.Chip
import app.spectra.wallet.ui.components.GlassCard
import app.spectra.wallet.ui.components.GlassIconButton
import app.spectra.wallet.ui.components.GlassTextField
import app.spectra.wallet.ui.components.Hairline
import app.spectra.wallet.ui.components.KeyValueRow
import app.spectra.wallet.ui.components.NoticeCard
import app.spectra.wallet.ui.components.PrimaryButton
import app.spectra.wallet.ui.components.SecondaryButton
import app.spectra.wallet.ui.components.SectionLabel
import app.spectra.wallet.ui.components.SpectraIcons
import app.spectra.wallet.ui.components.SpectraSheet
import app.spectra.wallet.ui.components.StatusPill
import app.spectra.wallet.ui.components.Tone
import app.spectra.wallet.ui.format.Fmt
import app.spectra.wallet.ui.theme.Spectra
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigInteger

class SwapViewModel(private val c: AppContainer, private val main: MainViewModel) : ViewModel() {
    var fromChain by mutableStateOf(Chain.SOLANA)
    var toChain by mutableStateOf(Chain.SOLANA)
    var fromToken by mutableStateOf<TokenInfo?>(null)
    var toToken by mutableStateOf<TokenInfo?>(null)
    var amountText by mutableStateOf("")
    var slippageBps by mutableStateOf(50)
    var quote by mutableStateOf<SwapQuote?>(null); private set
    var reviewed by mutableStateOf<Pair<SwapTransaction, SwapInspection>?>(null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var info by mutableStateOf<String?>(null)

    private val env get() = main.session.value?.env ?: app.spectra.core.model.NetworkEnv.MAINNET
    fun network(ch: Chain) = Network(ch, env)
    val route: RouteSupport get() = c.swaps.route(network(fromChain), network(toChain))

    init { reset() }

    fun reset() {
        fromToken = TokenInfo.native(network(fromChain)); toToken = if (fromChain == toChain) null else TokenInfo.native(network(toChain))
        quote = null; reviewed = null; error = null
    }

    fun setFrom(ch: Chain) { fromChain = ch; fromToken = TokenInfo.native(network(ch)); invalidate() }
    fun setTo(ch: Chain) { toChain = ch; toToken = if (ch != fromChain) TokenInfo.native(network(ch)) else null; invalidate() }
    fun flip() { val f = fromChain; val ft = fromToken; fromChain = toChain; fromToken = toToken ?: TokenInfo.native(network(toChain)); toChain = f; toToken = ft; invalidate() }
    fun invalidate() { quote = null; reviewed = null; error = null; info = null }

    fun available(): Amount? {
        val t = fromToken ?: return null
        val active = main.session.value?.active?.account?.id
        return main.portfolio.value?.rows?.firstOrNull { it.token.id == t.id && it.accountId == active }?.amount
    }

    fun getQuote() {
        val s = main.session.value ?: return
        val acc = s.active ?: return
        val ft = fromToken ?: return; val tt = toToken ?: run { error = "Wybierz aktywo docelowe"; return }
        val amt = Amount.parse(amountText, ft.decimals ?: return) ?: run { error = "Nieprawidłowa kwota"; return }
        if (amt.isZero) { error = "Wpisz kwotę"; return }
        available()?.let { if (amt > it) { error = "Kwota przekracza dostępne saldo"; return } }
        val fromView = acc.view(network(fromChain)) ?: return
        val toView = acc.view(network(toChain)) ?: return
        busy = true; error = null; quote = null; reviewed = null
        viewModelScope.launch {
            runCatching { c.swaps.quote(SwapRequest(fromView, toView, ft, tt, amt, slippageBps)) }.onSuccess { quote = it }.onFailure { error = it.humanMessage() }
            busy = false
        }
    }

    fun approve() {
        val q = quote ?: return
        val acc = main.session.value?.active ?: return
        busy = true; error = null
        viewModelScope.launch {
            when (val r = c.swaps.approve(q, acc.vault.id, acc.account.id)) {
                is ExecuteResult.Submitted -> info = "Zgoda wysłana. Po jej potwierdzeniu w sieci pobierz nową wycenę."
                is ExecuteResult.Failed -> error = r.error.humanMessage()
                ExecuteResult.Cancelled -> Unit
            }
            quote = null; busy = false
        }
    }

    fun review() {
        val q = quote ?: return
        busy = true; error = null
        viewModelScope.launch {
            runCatching { c.swaps.buildForReview(q) }.onSuccess { reviewed = it }.onFailure { error = it.humanMessage() }
            busy = false
        }
    }

    fun execute() {
        val q = quote ?: return; val (tx, _) = reviewed ?: return
        val acc = main.session.value?.active ?: return
        busy = true; error = null
        viewModelScope.launch {
            when (val r = c.swaps.execute(q, tx, acc.vault.id, acc.account.id)) {
                is ExecuteResult.Submitted -> { info = "Wymiana wysłana. Postęp śledzisz poniżej i w Historii."; quote = null; reviewed = null; amountText = "" }
                is ExecuteResult.Failed -> error = r.error.humanMessage()
                ExecuteResult.Cancelled -> Unit
            }
            busy = false
        }
    }
}

fun swapStatusLabel(s: SwapStatus) = when (s) {
    SwapStatus.QUOTED -> "Wyceniona"; SwapStatus.AWAITING_APPROVAL -> "Wymaga zgody"; SwapStatus.APPROVAL_PENDING -> "Zgoda w toku"
    SwapStatus.AWAITING_AUTHORIZATION -> "Czeka na podpis"; SwapStatus.SUBMITTED -> "Wysłana"; SwapStatus.SOURCE_CONFIRMED -> "Źródło potwierdzone"
    SwapStatus.BRIDGING -> "Most w toku"; SwapStatus.COMPLETED -> "Zakończona"; SwapStatus.PARTIAL -> "Częściowa"; SwapStatus.REFUNDED -> "Zwrócona"
    SwapStatus.FAILED -> "Nieudana"; SwapStatus.EXPIRED -> "Wygasła"; SwapStatus.UNKNOWN -> "Status nieznany"
}

@Composable
fun SwapScreen(main: MainViewModel) {
    val c = LocalContainer.current
    val vm: SwapViewModel = viewModel(factory = factory { SwapViewModel(c, main) })
    val swaps by c.swaps.swaps.collectAsState(initial = emptyList())
    val tokens by c.db.portfolio().tokens().collectAsState(initial = emptyList())
    @Suppress("UNUSED_VARIABLE") val portfolioState by main.portfolio.collectAsState() // recompose when balances change
    val col = Spectra.colors
    var pickFor by remember { mutableStateOf<Boolean?>(null) } // true = from, false = to
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val route = vm.route
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 120.dp)) {
        Text("Wymiana", style = Spectra.type.title, color = col.text, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(16.dp))
        SwapSide("Wysyłasz", vm.fromChain, vm.fromToken, { vm.setFrom(it) }, { pickFor = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (vm.amountText.isEmpty()) Text("0", style = Spectra.type.headline, color = col.textDisabled)
                    BasicTextField(vm.amountText, { vm.amountText = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' }.take(24); vm.invalidate() },
                        Modifier.fillMaxWidth().semantics { contentDescription = "Kwota do wymiany" }, textStyle = Spectra.type.headline.copy(color = col.text), singleLine = true,
                        cursorBrush = SolidColor(col.primary), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                vm.available()?.let { a -> Chip("Max", false, { vm.amountText = Fmt.exact(a); vm.invalidate() }) }
            }
            vm.available()?.let { Text("Dostępne ${Fmt.amountWithSymbol(it, vm.fromToken?.symbol ?: "")}", style = Spectra.type.caption, color = col.textSecondary) }
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
            GlassIconButton(SpectraIcons.Swap, "Zamień kierunek", vm::flip, Modifier.size(44.dp), tint = col.primary)
        }
        SwapSide("Otrzymujesz", vm.toChain, vm.toToken, { vm.setTo(it) }, { pickFor = false }) {
            val q = vm.quote
            Text(q?.let { Fmt.amount(it.estimatedOut) } ?: "—", style = Spectra.type.headline, color = if (q == null) col.textDisabled else col.text)
            if (q != null) Text("Szacunek, nie gwarancja. Minimum: ${Fmt.amountWithSymbol(q.minimumOut, vm.toToken?.symbol ?: "")}", style = Spectra.type.caption, color = col.textSecondary)
        }
        Spacer(Modifier.height(12.dp))
        if (route is RouteSupport.Unsupported) NoticeCard("Trasa niedostępna", route.reason, Tone.WARNING, SpectraIcons.Info)
        else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Poślizg", style = Spectra.type.label, color = col.textSecondary)
                listOf(50, 100, 300).forEach { b -> Chip("${b / 100.0}%".replace('.', ','), vm.slippageBps == b, { vm.slippageBps = b; vm.invalidate() }) }
            }
            Text("Dostawca: ${(route as RouteSupport.Supported).provider.displayName}" + if (vm.fromChain != vm.toChain) " · wymiana międzysieciowa" else "", style = Spectra.type.caption, color = col.textSecondary, modifier = Modifier.padding(top = 6.dp))
        }
        vm.error?.let { Spacer(Modifier.height(10.dp)); NoticeCard("Nie udało się", it, Tone.NEGATIVE, SpectraIcons.Alert) }
        vm.info?.let { Spacer(Modifier.height(10.dp)); NoticeCard("Informacja", it, Tone.PRIMARY, SpectraIcons.Info) }
        val q = vm.quote
        if (q != null) {
            val left = ((q.expiresAtMillis - now) / 1000).coerceAtLeast(0)
            Spacer(Modifier.height(12.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                KeyValueRow("Szacunkowo otrzymasz", Fmt.amountWithSymbol(q.estimatedOut, vm.toToken?.symbol ?: ""))
                KeyValueRow("Minimum do otrzymania", Fmt.amountWithSymbol(q.minimumOut, vm.toToken?.symbol ?: ""))
                q.priceImpactPct?.let { KeyValueRow("Wpływ na cenę", String.format(java.util.Locale.ROOT, "%.2f%%", it), if (it > 1) col.warning else col.text) }
                KeyValueRow("Trasa", q.route.take(4).joinToString(" → ").ifEmpty { "—" }, strong = false)
                q.estimatedDurationSec?.let { KeyValueRow("Szacowany czas", if (it < 90) "$it s" else "${it / 60} min") }
                Hairline()
                q.providerFees.forEach { f -> KeyValueRow(f.label, f.amount?.let { Fmt.amountWithSymbol(it, f.symbol ?: "") } ?: (if (f.included) "wliczone w kurs" else "—"), strong = false) }
                q.networkFees.forEach { f -> KeyValueRow(f.label, f.amount?.let { Fmt.amountWithSymbol(it, f.symbol ?: "") } ?: "ustalana przy podpisie", strong = false) }
                Text(if (left > 0) "Wycena ważna jeszcze $left s" else "Wycena wygasła", style = Spectra.type.captionStrong, color = if (left > 0) col.textSecondary else col.negative, modifier = Modifier.padding(top = 6.dp))
            }
            q.approval?.let { a ->
                Spacer(Modifier.height(10.dp))
                NoticeCard("Wymagana zgoda na token", "Kontrakt ${Fmt.shortAddress(a.spender, 6, 4)} potrzebuje pozwolenia na wydanie ${Fmt.amountWithSymbol(a.required, vm.fromToken?.symbol ?: "")}. Spectra prosi o zgodę tylko na dokładną kwotę (nie bez limitu).", Tone.WARNING, SpectraIcons.ShieldAlert)
            }
            vm.reviewed?.second?.warnings?.forEach { Spacer(Modifier.height(10.dp)); NoticeCard("Sprawdź przed podpisem", it, Tone.WARNING, SpectraIcons.Alert) }
            val allowed = TransactionRepository.signingAllowed(vm.network(vm.fromChain))
            if (!allowed) { Spacer(Modifier.height(10.dp)); NoticeCard("Wykonanie wyłączone", "Dostawcy wymian działają tylko w sieci głównej, a podpisywanie w sieci głównej jest zablokowane w tej eksperymentalnej kompilacji. Wycena ma charakter informacyjny.", Tone.NEGATIVE, SpectraIcons.ShieldAlert) }
            Spacer(Modifier.height(12.dp))
            when {
                left <= 0 -> PrimaryButton("Pobierz nową wycenę", vm::getQuote, loading = vm.busy)
                q.approval != null -> PrimaryButton("Zatwierdź limit tokena", vm::approve, enabled = allowed, loading = vm.busy)
                vm.reviewed == null -> PrimaryButton("Przejrzyj transakcję", vm::review, loading = vm.busy)
                else -> PrimaryButton("Podpisz i wymień", vm::execute, enabled = allowed, loading = vm.busy)
            }
        } else {
            Spacer(Modifier.height(14.dp))
            PrimaryButton("Pobierz wycenę", vm::getQuote, enabled = route is RouteSupport.Supported && vm.toToken != null && vm.amountText.isNotBlank(), loading = vm.busy)
        }
        if (swaps.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            SectionLabel("Twoje wymiany")
            GlassCard(Modifier.fillMaxWidth()) {
                swaps.take(10).forEachIndexed { i, s ->
                    if (i > 0) Hairline()
                    val st = runCatching { SwapStatus.valueOf(s.status) }.getOrDefault(SwapStatus.UNKNOWN)
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${Fmt.amountWithSymbol(Amount(BigInteger(s.amountUnits), s.fromDecimals), s.fromSymbol)} → ${s.toSymbol}", style = Spectra.type.label, color = col.text)
                            Text((s.message ?: s.provider).take(80), style = Spectra.type.caption, color = col.textSecondary)
                        }
                        StatusPill(swapStatusLabel(st), when (st) { SwapStatus.COMPLETED -> Tone.POSITIVE; SwapStatus.FAILED, SwapStatus.EXPIRED -> Tone.NEGATIVE; SwapStatus.PARTIAL, SwapStatus.REFUNDED, SwapStatus.UNKNOWN -> Tone.WARNING; else -> Tone.PRIMARY }, dot = false)
                    }
                }
                Text("Spectra nigdy nie powtarza automatycznie wymiany o niepewnym statusie.", style = Spectra.type.caption, color = col.textSecondary, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
    pickFor?.let { from ->
        val ch = if (from) vm.fromChain else vm.toChain
        val n = vm.network(ch)
        var custom by remember { mutableStateOf("") }
        var customErr by remember { mutableStateOf<String?>(null) }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        SpectraSheet({ pickFor = null }) {
            Text("Wybierz aktywo · ${n.displayName}", style = Spectra.type.headline, color = col.text)
            Spacer(Modifier.height(12.dp))
            val list = listOf(TokenInfo.native(n)) + tokens.filter { it.networkId == n.id && !it.hidden }.map { it.toInfo() }
            list.distinctBy { it.id }.forEach { t ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) {
                    if (from) vm.fromToken = t else vm.toToken = t; vm.invalidate(); pickFor = null
                }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AssetAvatar(t.symbol, ch, t.id.isNative, 36.dp); Spacer(Modifier.width(12.dp))
                    Column { Text(t.symbol, style = Spectra.type.bodyStrong, color = col.text); Text(t.name, style = Spectra.type.caption, color = col.textSecondary) }
                }
            }
            if (ch != Chain.LITECOIN) {
                Spacer(Modifier.height(10.dp))
                GlassTextField(custom, { custom = it.trim() }, if (ch == Chain.SOLANA) "Adres mint tokena" else "Adres kontraktu ERC-20", sensitive = true)
                customErr?.let { Text(it, style = Spectra.type.caption, color = col.negative) }
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Dodaj i wybierz", {
                    scope.launch { runCatching { c.portfolio.addCustomToken(n, custom) }.onSuccess { t -> if (from) vm.fromToken = t else vm.toToken = t; vm.invalidate(); pickFor = null }.onFailure { customErr = it.humanMessage() } }
                }, enabled = custom.length > 30)
            }
        }
    }
}

@Composable
private fun SwapSide(label: String, chain: Chain, token: TokenInfo?, onChain: (Chain) -> Unit, onPick: () -> Unit, body: @Composable () -> Unit) {
    val col = Spectra.colors
    GlassCard(Modifier.fillMaxWidth()) {
        Text(label, style = Spectra.type.captionStrong, color = col.textSecondary)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Chain.entries.forEach { c -> Chip(c.symbol, c == chain, { onChain(c) }) } }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button, onClick = onPick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (token != null) { AssetAvatar(token.symbol, chain, token.id.isNative, 30.dp); Spacer(Modifier.width(8.dp)); Text(token.symbol, style = Spectra.type.bodyStrong, color = col.text) }
            else Text("Wybierz aktywo", style = Spectra.type.bodyStrong, color = col.link)
            Spacer(Modifier.width(4.dp)); Icon(SpectraIcons.ChevronDown, null, Modifier.size(16.dp), tint = col.textSecondary)
        }
        Spacer(Modifier.height(8.dp))
        body()
    }
}

