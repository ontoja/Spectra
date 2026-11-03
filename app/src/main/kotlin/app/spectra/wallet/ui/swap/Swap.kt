package app.spectra.wallet.ui.swap

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.spectra.core.model.Amount
import app.spectra.core.model.Chain
import app.spectra.core.model.Network
import app.spectra.core.model.NetworkEnv
import app.spectra.core.model.TokenInfo
import app.spectra.core.swap.PopularTokens
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
import app.spectra.wallet.ui.components.ChainLogo
import app.spectra.wallet.ui.components.Chip
import app.spectra.wallet.ui.components.GlassCard
import app.spectra.wallet.ui.components.GlassTextField
import app.spectra.wallet.ui.components.Hairline
import app.spectra.wallet.ui.components.NoticeCard
import app.spectra.wallet.ui.components.PrimaryButton
import app.spectra.wallet.ui.components.Reveal
import app.spectra.wallet.ui.components.SecondaryButton
import app.spectra.wallet.ui.components.SectionLabel
import app.spectra.wallet.ui.components.SegmentedTabs
import app.spectra.wallet.ui.components.SpectraIcons
import app.spectra.wallet.ui.components.SpectraSheet
import app.spectra.wallet.ui.components.StatusPill
import app.spectra.wallet.ui.components.Tone
import app.spectra.wallet.ui.components.enterAnimation
import app.spectra.wallet.ui.components.glass
import app.spectra.wallet.ui.components.pressScale
import app.spectra.wallet.ui.format.Fmt
import app.spectra.wallet.ui.theme.Radii
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
    fun routeFor(from: Network, to: Network): RouteSupport = c.swaps.route(from, to)

    init { reset() }

    fun reset() {
        fromToken = TokenInfo.native(network(fromChain)); toToken = if (fromChain == toChain) PopularTokens.forNetwork(network(toChain)).firstOrNull() else TokenInfo.native(network(toChain))
        quote = null; reviewed = null; error = null
    }

    /** Picks network and asset together, so each side may live on a different chain (cross-chain via LI.FI). */
    fun select(from: Boolean, chain: Chain, token: TokenInfo) {
        if (from) {
            fromChain = chain; fromToken = token
            if (toToken?.id == token.id) toToken = null
        } else {
            // Picking the asset already on the "from" side swaps the sides instead of quoting X → X.
            if (fromToken?.id == token.id) { fromChain = toChain; fromToken = toToken }
            toChain = chain; toToken = token
        }
        if (fromToken == null) fromToken = TokenInfo.native(network(fromChain)).takeIf { it.id != toToken?.id }
        invalidate()
    }
    fun flip() { val f = fromChain; val ft = fromToken; fromChain = toChain; fromToken = toToken ?: TokenInfo.native(network(toChain)); toChain = f; toToken = ft; invalidate() }
    fun invalidate() { quote = null; reviewed = null; error = null; info = null }

    val isCrossChain get() = fromChain != toChain
    val currency get() = main.session.value?.prefs?.currency ?: "PLN"
    fun parsedAmount(): Amount? = fromToken?.decimals?.let { Amount.parse(amountText, it) }

    fun balanceOf(t: TokenInfo?): Amount? {
        t ?: return null
        val active = main.session.value?.active?.account?.id
        return main.portfolio.value?.rows?.firstOrNull { it.token.id == t.id && it.accountId == active }?.amount
    }
    fun available(): Amount? = balanceOf(fromToken)

    /** Unit price from the cached portfolio; null when unknown (never assumed zero). */
    fun unitPrice(t: TokenInfo?): java.math.BigDecimal? = t?.let { tok -> main.portfolio.value?.rows?.firstOrNull { it.token.id == tok.id && it.unitPrice != null }?.unitPrice }
    fun fiatOf(a: Amount?, t: TokenInfo?): java.math.BigDecimal? {
        if (a == null || a.isZero) return null
        return unitPrice(t)?.multiply(a.toDecimal())
    }

    fun getQuote() {
        val s = main.session.value ?: return
        val acc = s.active ?: return
        val ft = fromToken ?: run { error = "Wybierz aktywo do wysłania"; return }; val tt = toToken ?: run { error = "Wybierz aktywo docelowe"; return }
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
fun SwapScreen(main: MainViewModel, setup: (SwapViewModel.() -> Unit)? = null) {
    val c = LocalContainer.current
    val vm: SwapViewModel = viewModel(factory = factory { SwapViewModel(c, main) })
    if (setup != null) LaunchedEffect(Unit) { vm.setup() }
    val swaps by c.swaps.swaps.collectAsState(initial = emptyList())
    val tokens by c.db.portfolio().tokens().collectAsState(initial = emptyList())
    @Suppress("UNUSED_VARIABLE") val portfolioState by main.portfolio.collectAsState() // recompose when balances change
    val session by main.session.collectAsState()
    val col = Spectra.colors
    var pickFor by remember { mutableStateOf<Boolean?>(null) } // true = from, false = to
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val route = vm.route
    val quote = vm.quote
    val secondsLeft = quote?.let { ((it.expiresAtMillis - now) / 1000).coerceAtLeast(0) } ?: 0L
    val testnet = session?.env == NetworkEnv.TESTNET
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 120.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Wymiana", style = Spectra.type.title, color = col.text, modifier = Modifier.weight(1f).semantics { heading() })
            StatusPill(if (testnet) "Testnet" else "Mainnet", if (testnet) Tone.WARNING else Tone.POSITIVE)
        }
        Spacer(Modifier.height(16.dp))

        // ── Wysyłasz ──
        SwapCard {
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Wysyłasz", style = Spectra.type.label, color = col.textSecondary, modifier = Modifier.weight(1f))
                vm.available()?.let { a ->
                    Text("Saldo ${Fmt.amountWithSymbol(a, vm.fromToken?.symbol ?: "")}", style = Spectra.type.caption, color = col.textSecondary, maxLines = 1)
                    Spacer(Modifier.width(8.dp))
                    SmallPill("Max", "Wpisz całe saldo") { vm.amountText = Fmt.exact(a); vm.invalidate() }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (vm.amountText.isEmpty()) Text("0", style = amountStyle(), color = col.textDisabled)
                    BasicTextField(vm.amountText, { vm.amountText = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' }.take(24); vm.invalidate() },
                        Modifier.fillMaxWidth().semantics { contentDescription = "Kwota do wymiany" }, textStyle = amountStyle().copy(color = col.text), singleLine = true,
                        cursorBrush = SolidColor(col.primary), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                Spacer(Modifier.width(12.dp))
                TokenPill(vm.fromToken, vm.fromChain, "Wysyłane aktywo: ${vm.fromToken?.symbol ?: "nie wybrano"}, ${vm.network(vm.fromChain).displayName}. Zmień") { pickFor = true }
            }
            Spacer(Modifier.height(6.dp))
            FiatAndNetwork(vm.fiatOf(vm.parsedAmount(), vm.fromToken), vm.currency, vm.network(vm.fromChain))
        }

        FlipButton { vm.flip() }

        // ── Otrzymujesz ──
        SwapCard {
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Otrzymujesz", style = Spectra.type.label, color = col.textSecondary, modifier = Modifier.weight(1f))
                if (quote != null) Text("szacunek", style = Spectra.type.caption, color = col.textSecondary)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(quote?.let { Fmt.amount(it.estimatedOut) } ?: "—", Modifier.weight(1f), transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 2 }) togetherWith (fadeOut(tween(120)) + slideOutVertically(tween(160)) { -it / 2 })
                }, label = "estOut") { txt -> Text(txt, style = amountStyle(), color = if (txt == "—") col.textDisabled else col.text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Spacer(Modifier.width(12.dp))
                TokenPill(vm.toToken, vm.toChain, "Otrzymywane aktywo: ${vm.toToken?.symbol ?: "nie wybrano"}, ${vm.network(vm.toChain).displayName}. Zmień") { pickFor = false }
            }
            Spacer(Modifier.height(6.dp))
            FiatAndNetwork(vm.fiatOf(quote?.estimatedOut, vm.toToken), vm.currency, vm.network(vm.toChain))
        }

        Spacer(Modifier.height(14.dp))
        if (route is RouteSupport.Unsupported) NoticeCard("Trasa niedostępna", routeReasonPl(route.reason), Tone.WARNING, SpectraIcons.Info)
        else {
            val provider = (route as RouteSupport.Supported).provider.displayName
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val rate = quote?.let { q -> vm.parsedAmount()?.let { rateText(it, q.estimatedOut, vm.fromToken?.symbol ?: "", vm.toToken?.symbol ?: "") } }
                Text(rate ?: "Kurs pojawi się po wycenie", style = if (rate != null) Spectra.type.label else Spectra.type.caption,
                    color = if (rate != null) col.text else col.textSecondary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(buildAnnotatedString {
                    append("przez "); withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = col.text)) { append(provider) }
                    if (quote != null) append(if (secondsLeft > 0) " · $secondsLeft s" else " · wygasła")
                }, style = Spectra.type.caption, color = if (quote != null && secondsLeft <= 0) col.negative else col.textSecondary)
            }
            Reveal(vm.isCrossChain) {
                Spacer(Modifier.height(10.dp))
                CrossChainStrip(vm.network(vm.fromChain), vm.network(vm.toChain), provider, quote?.estimatedDurationSec)
            }
            Spacer(Modifier.height(12.dp))
            GlassCard(Modifier.fillMaxWidth(), radius = 22.dp, padding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Poślizg", style = Spectra.type.body, color = col.textSecondary, modifier = Modifier.weight(1f))
                    val options = listOf(50, 100, 300)
                    SegmentedTabs(options.map { "${it / 100.0}%".replace(".0%", "%").replace('.', ',') }, options.indexOf(vm.slippageBps).coerceAtLeast(0),
                        { vm.slippageBps = options[it]; vm.invalidate() })
                }
                Reveal(quote != null) {
                    val q = vm.quote ?: return@Reveal
                    val toSym = vm.toToken?.symbol ?: ""
                    Hairline()
                    DetailRow("Minimum do otrzymania", Fmt.amountWithSymbol(q.minimumOut, toSym))
                    q.priceImpactPct?.let { DetailRow("Wpływ na cenę", String.format(java.util.Locale.ROOT, "%.2f%%", it).replace('.', ','), if (it > 1) col.warning else col.positive) }
                    q.estimatedDurationSec?.let { DetailRow("Szacowany czas", if (it < 90) "$it s" else "${it / 60} min") }
                    if (q.route.isNotEmpty()) DetailRow("Trasa", q.route.take(4).joinToString(" → "))
                    q.providerFees.forEach { f -> DetailRow(f.label, f.amount?.let { Fmt.amountWithSymbol(it, f.symbol ?: "") } ?: (if (f.included) "wliczone w kurs" else "—")) }
                    q.networkFees.forEach { f -> DetailRow(f.label, f.amount?.let { "≈ " + Fmt.amountWithSymbol(it, f.symbol ?: "") } ?: "ustalana przy podpisie") }
                }
            }
        }
        Reveal(vm.error != null) { Spacer(Modifier.height(10.dp)); NoticeCard("Nie udało się", vm.error ?: "", Tone.NEGATIVE, SpectraIcons.Alert) }
        Reveal(vm.info != null) { Spacer(Modifier.height(10.dp)); NoticeCard("Informacja", vm.info ?: "", Tone.PRIMARY, SpectraIcons.Info) }
        if (quote != null) {
            quote.approval?.let { a ->
                Spacer(Modifier.height(10.dp))
                NoticeCard("Wymagana zgoda na token", "Kontrakt ${Fmt.shortAddress(a.spender, 6, 4)} potrzebuje pozwolenia na wydanie ${Fmt.amountWithSymbol(a.required, vm.fromToken?.symbol ?: "")}. Spectra prosi o zgodę tylko na dokładną kwotę (nie bez limitu).", Tone.WARNING, SpectraIcons.ShieldAlert)
            }
            vm.reviewed?.second?.warnings?.forEach { Spacer(Modifier.height(10.dp)); NoticeCard("Sprawdź przed podpisem", it, Tone.WARNING, SpectraIcons.Alert) }
            if (!TransactionRepository.signingAllowed(vm.network(vm.fromChain))) {
                Spacer(Modifier.height(10.dp))
                NoticeCard("Wykonanie wyłączone", "Dostawcy wymian działają tylko w sieci głównej, a podpisywanie w sieci głównej jest zablokowane w tej eksperymentalnej kompilacji. Wycena ma charakter informacyjny.", Tone.NEGATIVE, SpectraIcons.ShieldAlert)
            }
        }
        Spacer(Modifier.height(16.dp))
        val allowed = TransactionRepository.signingAllowed(vm.network(vm.fromChain))
        val ctaMs = if (Spectra.reducedMotion) 0 else 200
        AnimatedContent(when {
            quote == null -> 0; secondsLeft <= 0 -> 1; quote.approval != null -> 2; vm.reviewed == null -> 3; else -> 4
        }, transitionSpec = { fadeIn(tween(ctaMs)) togetherWith fadeOut(tween(ctaMs / 2)) }, label = "cta") { step ->
            when (step) {
                0 -> PrimaryButton("Pobierz wycenę", vm::getQuote, enabled = route is RouteSupport.Supported && vm.fromToken != null && vm.toToken != null && vm.amountText.isNotBlank(), loading = vm.busy)
                1 -> PrimaryButton("Pobierz nową wycenę", vm::getQuote, loading = vm.busy)
                2 -> PrimaryButton("Zatwierdź limit tokena", vm::approve, enabled = allowed, loading = vm.busy)
                3 -> PrimaryButton("Przejrzyj wymianę", vm::review, loading = vm.busy)
                else -> PrimaryButton("Podpisz i wymień", vm::execute, enabled = allowed, loading = vm.busy)
            }
        }

        if (swaps.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            SectionLabel("Twoje wymiany")
            GlassCard(Modifier.fillMaxWidth()) {
                swaps.take(10).forEachIndexed { i, s ->
                    if (i > 0) Hairline()
                    val st = runCatching { SwapStatus.valueOf(s.status) }.getOrDefault(SwapStatus.UNKNOWN)
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).enterAnimation(i), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${Fmt.amountWithSymbol(Amount(BigInteger(s.amountUnits), s.fromDecimals), s.fromSymbol)} → ${s.toSymbol}", style = Spectra.type.label, color = col.text)
                            Text((s.message ?: s.provider).take(80), style = Spectra.type.caption, color = col.textSecondary)
                        }
                        StatusPill(swapStatusLabel(st), pulsing = !st.isTerminal, dot = false, tone = when (st) { SwapStatus.COMPLETED -> Tone.POSITIVE; SwapStatus.FAILED, SwapStatus.EXPIRED -> Tone.NEGATIVE; SwapStatus.PARTIAL, SwapStatus.REFUNDED, SwapStatus.UNKNOWN -> Tone.WARNING; else -> Tone.PRIMARY })
                    }
                }
                Text("Spectra nigdy nie powtarza automatycznie wymiany o niepewnym statusie.", style = Spectra.type.caption, color = col.textSecondary, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }

    pickFor?.let { from -> AssetPickerSheet(vm, from, tokens.map { it to it.toInfo() }, onDismiss = { pickFor = null }) }
}

/** Network + asset picker. Each side picks its own network, which is what makes cross-chain swaps possible. */
@Composable
private fun AssetPickerSheet(vm: SwapViewModel, from: Boolean, tokens: List<Pair<app.spectra.wallet.data.TokenEntity, TokenInfo>>, onDismiss: () -> Unit) =
    SpectraSheet(onDismiss) { AssetPickerContent(vm, from, tokens, onDismiss) }

@Composable
internal fun AssetPickerContent(vm: SwapViewModel, from: Boolean, tokens: List<Pair<app.spectra.wallet.data.TokenEntity, TokenInfo>>, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val col = Spectra.colors
    var chain by remember { mutableStateOf(if (from) vm.fromChain else vm.toChain) }
    var custom by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var customErr by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val current = if (from) vm.fromToken else vm.toToken
    Column {
        Text(if (from) "Co wysyłasz?" else "Co chcesz otrzymać?", style = Spectra.type.headline, color = col.text, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(4.dp))
        Text("Wybierz sieć i aktywo. Różne sieci po obu stronach = wymiana międzysieciowa.", style = Spectra.type.caption, color = col.textSecondary)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chain.entries.forEach { ch ->
                Chip(ch.symbol, ch == chain, { chain = ch; custom = ""; customErr = null; query = "" }, Modifier.weight(1f), leading = { ChainLogo(ch, 22.dp) })
            }
        }
        val n = vm.network(chain)
        val otherSide = vm.network(if (from) vm.toChain else vm.fromChain)
        val routeHere = if (from) vm.routeFor(n, otherSide) else vm.routeFor(otherSide, n)
        if (routeHere is RouteSupport.Unsupported) {
            Spacer(Modifier.height(10.dp))
            NoticeCard("Brak trasy", routeReasonPl(routeHere.reason), Tone.WARNING, SpectraIcons.Info)
        }
        Spacer(Modifier.height(10.dp))
        val owned = (listOf(TokenInfo.native(n)) + tokens.filter { it.first.networkId == n.id && !it.first.hidden }.map { it.second }).distinctBy { it.id }
        val popular = PopularTokens.forNetwork(n).filter { p -> owned.none { it.id == p.id } }
        val q = query.trim()
        fun TokenInfo.matches() = q.isEmpty() || symbol.contains(q, ignoreCase = true) || name.contains(q, ignoreCase = true) ||
            (id.address?.equals(q, ignoreCase = true) == true)
        if (owned.size + popular.size > 6) {
            GlassTextField(query, { query = it }, "Szukaj: symbol lub nazwa")
            Spacer(Modifier.height(8.dp))
        }
        val ownedShown = owned.filter { it.matches() }
        val popularShown = popular.filter { it.matches() }
        if (ownedShown.isNotEmpty()) {
            SectionLabel("Twoje aktywa", Modifier.padding(top = 6.dp))
            ownedShown.forEachIndexed { i, t -> PickerRow(t, n, current?.id == t.id, vm.balanceOf(t), Modifier.enterAnimation(i)) { vm.select(from, chain, t); onDismiss() } }
        }
        if (popularShown.isNotEmpty()) {
            SectionLabel("Popularne", Modifier.padding(top = 10.dp))
            popularShown.forEachIndexed { i, t -> PickerRow(t, n, current?.id == t.id, null, Modifier.enterAnimation(ownedShown.size + i)) { vm.select(from, chain, t); onDismiss() } }
            Text("Lista wbudowana w aplikację; adresy kontraktów sprawdzone w sieci i u dostawców tras.", style = Spectra.type.caption, color = col.textSecondary, modifier = Modifier.padding(start = 10.dp, top = 4.dp))
        }
        if (ownedShown.isEmpty() && popularShown.isEmpty()) Text("Brak wyników. Możesz dodać token po adresie poniżej.", style = Spectra.type.caption, color = col.textSecondary, modifier = Modifier.padding(vertical = 12.dp))
        if (chain != Chain.LITECOIN) {
            Spacer(Modifier.height(12.dp))
            GlassTextField(custom, { custom = it.trim(); customErr = null }, if (chain == Chain.SOLANA) "Adres mint tokena" else "Adres kontraktu ERC-20", sensitive = true)
            customErr?.let { Text(it, style = Spectra.type.caption, color = col.negative) }
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Dodaj i wybierz", {
                scope.launch { runCatching { c.portfolio.addCustomToken(n, custom) }.onSuccess { t -> vm.select(from, chain, t); onDismiss() }.onFailure { customErr = it.humanMessage() } }
            }, enabled = custom.length > 30)
        }
    }
}

@Composable
private fun PickerRow(t: TokenInfo, n: Network, selected: Boolean, balance: Amount?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val col = Spectra.colors
    Row(modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(18.dp))
        .background(if (selected) col.primarySoft else androidx.compose.ui.graphics.Color.Transparent)
        .clickable(role = Role.Button, onClick = onClick)
        .semantics { stateDescription = if (selected) "Wybrano" else "" }.padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        AssetAvatar(t, 40.dp); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.symbol, style = Spectra.type.bodyStrong, color = col.text)
            Text(if (t.id.isNative) n.displayName else "${t.name} · ${n.displayName}", style = Spectra.type.caption, color = col.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (balance != null && !balance.isZero) Text(Fmt.amount(balance), style = Spectra.type.label, color = col.text)
        if (selected) { Spacer(Modifier.width(8.dp)); Icon(SpectraIcons.Check, null, Modifier.size(18.dp), tint = col.primary) }
    }
}

private fun routeReasonPl(reason: String): String = when {
    reason.contains("mainnet only", ignoreCase = true) -> "Dostawcy wymian działają tylko w sieci głównej — w sieciach testowych nie ma tras."
    reason.contains("Litecoin", ignoreCase = true) -> "Żaden zweryfikowany dostawca nie obsługuje wymian Litecoina w Spectra 0.1 (LI.FI nie wspiera LTC)."
    else -> reason
}

private fun rateText(amountIn: Amount, out: Amount, fromSym: String, toSym: String): String? {
    if (amountIn.isZero) return null
    val r = out.toDecimal().divide(amountIn.toDecimal(), 10, java.math.RoundingMode.HALF_UP)
    return "1 $fromSym ≈ ${Fmt.amount(Amount(r.movePointRight(10).toBigInteger(), 10), 6)} $toSym"
}

@Composable
private fun amountStyle() = Spectra.type.display.copy(fontSize = 40.sp, lineHeight = 46.sp)

@Composable
private fun SwapCard(content: @Composable ColumnScope.() -> Unit) =
    GlassCard(Modifier.fillMaxWidth(), radius = Radii.cardLarge, padding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), content = content)

@Composable
private fun SmallPill(text: String, description: String, onClick: () -> Unit) {
    val col = Spectra.colors
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.height(32.dp).pressScale(interaction, 0.92f).clip(RoundedCornerShape(16.dp)).background(col.primarySoft)
        .clickable(interaction, ripple(), role = Role.Button, onClick = onClick).semantics { contentDescription = description }.padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center) { Text(text, style = Spectra.type.captionStrong, color = col.link) }
}

@Composable
private fun TokenPill(token: TokenInfo?, chain: Chain, description: String, onClick: () -> Unit) {
    val col = Spectra.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(25.dp)
    val ms = if (Spectra.reducedMotion) 0 else 200
    Row(Modifier.height(50.dp).pressScale(interaction, 0.95f).glass(shape, col.glassStrong, col.glassBorder, col.shadow, 6.dp).clip(shape)
        .clickable(interaction, ripple(), role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) { contentDescription = description }
        .padding(start = if (token != null) 9.dp else 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        AnimatedContent(token, transitionSpec = {
            (fadeIn(tween(ms)) + scaleIn(tween(ms), initialScale = .85f)) togetherWith fadeOut(tween(ms / 2)) using SizeTransform(clip = false)
        }, contentKey = { it?.id }, label = "tokenPill") { t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (t != null) {
                    AssetAvatar(t, 32.dp); Spacer(Modifier.width(8.dp))
                    Text(t.symbol.take(10), style = Spectra.type.bodyStrong.copy(fontSize = 18.sp), color = col.text, maxLines = 1)
                } else Text("Wybierz", style = Spectra.type.bodyStrong, color = col.link)
            }
        }
        Spacer(Modifier.width(4.dp))
        Icon(SpectraIcons.ChevronDown, null, Modifier.size(16.dp), tint = col.textSecondary)
    }
}

@Composable
private fun FiatAndNetwork(fiat: java.math.BigDecimal?, currency: String, network: Network) {
    val col = Spectra.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(fiat?.let { "≈ " + Fmt.fiat(it, currency) } ?: "", style = Spectra.type.caption, color = col.textSecondary, modifier = Modifier.weight(1f))
        ChainLogo(network.chain, 16.dp)
        Spacer(Modifier.width(6.dp))
        Text(network.displayName, style = Spectra.type.caption, color = col.textSecondary)
    }
}

@Composable
private fun FlipButton(onFlip: () -> Unit) {
    val col = Spectra.colors
    var turns by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val rot by animateFloatAsState(turns * 180f, if (Spectra.reducedMotion) snap() else spring(dampingRatio = 0.6f, stiffness = 260f), label = "flip")
    val interaction = remember { MutableInteractionSource() }
    // Zero-height row so the button straddles both cards, drawn above them.
    Box(Modifier.fillMaxWidth().height(8.dp).zIndex(1f), contentAlignment = Alignment.Center) {
        Box(Modifier.requiredSize(48.dp).pressScale(interaction, 0.9f)
            .shadow(10.dp, CircleShape, clip = false, ambientColor = col.shadow.copy(alpha = .14f), spotColor = col.shadow.copy(alpha = .22f))
            .background(col.background, CircleShape).border(1.dp, col.glassBorder, CircleShape).clip(CircleShape)
            .clickable(interaction, ripple(), role = Role.Button) { turns++; onFlip() }.semantics { contentDescription = "Zamień kierunek" },
            contentAlignment = Alignment.Center) {
            Icon(SpectraIcons.Swap, null, Modifier.size(22.dp).graphicsLayer { rotationZ = rot + 90f }, tint = col.primary)
        }
    }
}

@Composable
private fun CrossChainStrip(from: Network, to: Network, provider: String, durationSec: Long?) {
    val col = Spectra.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(col.primarySoft).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ChainLogo(from.chain, 22.dp)
        Box(Modifier.width(22.dp).height(2.dp).background(col.primary.copy(alpha = .35f)))
        Icon(SpectraIcons.Chevron, null, Modifier.size(14.dp), tint = col.primary)
        ChainLogo(to.chain, 22.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Wymiana międzysieciowa", style = Spectra.type.captionStrong, color = col.link)
            Text("${from.displayName} → ${to.displayName} · most $provider" + (durationSec?.let { " · ~" + if (it < 90) "$it s" else "${it / 60} min" } ?: ""),
                style = Spectra.type.caption, color = col.textSecondary, maxLines = 2)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = Spectra.colors.text) {
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Spectra.type.body.copy(fontSize = 14.sp), color = Spectra.colors.textSecondary, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = Spectra.type.label, color = valueColor, textAlign = TextAlign.End)
    }
}
