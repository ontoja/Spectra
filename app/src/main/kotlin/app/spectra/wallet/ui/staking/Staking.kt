package app.spectra.wallet.ui.staking

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.spectra.core.model.Amount
import app.spectra.core.model.Chain
import app.spectra.core.model.NetworkEnv
import app.spectra.core.staking.EpochStatus
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
import app.spectra.wallet.ui.components.ChainLogo
import app.spectra.wallet.ui.components.Chip
import app.spectra.wallet.ui.components.EmptyState
import app.spectra.wallet.ui.components.ErrorState
import app.spectra.wallet.ui.components.GlassCard
import app.spectra.wallet.ui.components.GlassIconButton
import app.spectra.wallet.ui.components.GlassTextField
import app.spectra.wallet.ui.components.Hairline
import app.spectra.wallet.ui.components.KeyValueRow
import app.spectra.wallet.ui.components.LoadingState
import app.spectra.wallet.ui.components.NoticeCard
import app.spectra.wallet.ui.components.PrimaryButton
import app.spectra.wallet.ui.components.Reveal
import app.spectra.wallet.ui.components.SecondaryButton
import app.spectra.wallet.ui.components.SpectraIcons
import app.spectra.wallet.ui.components.SpectraSheet
import app.spectra.wallet.ui.components.StatusPill
import app.spectra.wallet.ui.components.Tone
import app.spectra.wallet.ui.components.enterAnimation
import app.spectra.wallet.ui.components.glassSurface
import app.spectra.wallet.ui.components.pressScale
import app.spectra.wallet.ui.format.Fmt
import app.spectra.wallet.ui.theme.Radii
import app.spectra.wallet.ui.theme.Spectra
import kotlinx.coroutines.launch
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
    /** Stake flow (amount → validator → "what happens") shown over the overview. */
    var flowOpen by mutableStateOf(false)

    /** Test/preview hook: show fixed data without touching the network. */
    internal fun previewWith(o: StakingOverview, v: List<Validator>, rent: Long?) { overview = o; validators = v; rentReserve = rent; selected = v.firstOrNull { !it.delinquent } }

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
                is ExecuteResult.Submitted -> { info = "Transakcja wysłana. Zmiany stake'u są widoczne po potwierdzeniu i zależą od granic epok."; amountText = ""; prepared = null; flowOpen = false; load() }
                is ExecuteResult.Failed -> error = r.error.humanMessage()
                ExecuteResult.Cancelled -> Unit
            }
            busy = false
        }
    }
}

fun stakeStateLabel(s: StakeState) = when (s) {
    StakeState.INITIALIZED -> "Niedelegowany"; StakeState.ACTIVATING -> "Aktywacja"; StakeState.ACTIVE -> "Aktywny"
    StakeState.DEACTIVATING -> "Dezaktywacja"; StakeState.INACTIVE -> "Do wypłaty"
}

private fun stakeTone(s: StakeState) = when (s) {
    StakeState.ACTIVE -> Tone.POSITIVE; StakeState.ACTIVATING -> Tone.PRIMARY; StakeState.INACTIVE, StakeState.INITIALIZED -> Tone.PRIMARY; StakeState.DEACTIVATING -> Tone.WARNING
}

private const val LAMPORTS = 1_000_000_000L
private fun sol(lamports: Long) = Amount.of(lamports, 9)

/** Rough time left in the epoch from slots (nominal ~400 ms per slot); always shown with "~". */
internal fun epochTimeLeft(e: EpochStatus): String {
    val secs = ((e.slotsInEpoch - e.slotIndex).coerceAtLeast(0) * 0.4).toLong()
    val d = secs / 86_400; val h = (secs % 86_400) / 3600; val m = (secs % 3600) / 60
    return "~" + when { d > 0 -> "$d d $h h"; h > 0 -> "$h h $m min"; else -> "$m min" }
}

/** "1,24 mln SOL", "860 tys. SOL", "512 SOL". */
internal fun compactSol(lamports: Long): String {
    val s = lamports.toDouble() / LAMPORTS
    fun f(v: Double) = String.format(java.util.Locale.ROOT, if (v >= 100) "%.0f" else "%.2f", v).replace('.', ',')
    return when { s >= 1_000_000 -> f(s / 1_000_000) + " mln SOL"; s >= 1_000 -> f(s / 1_000) + " tys. SOL"; else -> String.format(java.util.Locale.ROOT, "%.0f", s) + " SOL" }
}

@Composable
private fun ValidatorAvatar(vote: String?, size: androidx.compose.ui.unit.Dp = 42.dp, dim: Boolean = false) {
    val c = Spectra.colors
    val palettes = listOf(listOf(c.auroraLavender, c.primary), listOf(c.auroraBlue, c.link), listOf(c.auroraMint, c.positive), listOf(c.auroraPeach, c.primaryPressed))
    val pal = palettes[((vote?.hashCode() ?: 0) and 0x7fffffff) % palettes.size]
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.33f)).background(if (dim) androidx.compose.ui.graphics.Brush.linearGradient(listOf(c.textDisabled, c.textDisabled)) else androidx.compose.ui.graphics.Brush.linearGradient(pal)),
        contentAlignment = Alignment.Center) {
        Text(vote?.take(2) ?: "—", style = Spectra.type.captionStrong.copy(fontSize = (size.value * 0.32f).sp), color = androidx.compose.ui.graphics.Color.White)
    }
}

@Composable
private fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 8.dp) {
    val c = Spectra.colors
    val f by androidx.compose.animation.core.animateFloatAsState(fraction.coerceIn(0f, 1f), if (Spectra.reducedMotion) androidx.compose.animation.core.snap() else tween(600), label = "progress")
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(c.text.copy(alpha = .08f))) {
        Box(Modifier.fillMaxWidth(f).height(height).clip(RoundedCornerShape(height / 2)).background(c.primary))
    }
}

@Composable
fun StakingScreen(main: MainViewModel, autoLoad: Boolean = true, setup: (StakingViewModel.() -> Unit)? = null) {
    val c = LocalContainer.current
    val vm: StakingViewModel = viewModel(factory = factory { StakingViewModel(c, main) })
    val session by main.session.collectAsState()
    if (setup != null) LaunchedEffect(Unit) { vm.setup() }
    if (autoLoad) LaunchedEffect(session?.active?.account?.id, session?.env) { vm.load() }
    var pickValidator by remember { mutableStateOf(false) }
    var openPosition by remember { mutableStateOf<StakePosition?>(null) }
    androidx.activity.compose.BackHandler(enabled = vm.flowOpen) { vm.flowOpen = false }
    val d = if (Spectra.reducedMotion) 0 else 280
    AnimatedContent(vm.flowOpen, transitionSpec = {
        if (targetState) (slideInHorizontally(tween(d)) { it / 3 } + fadeIn(tween(d))) togetherWith fadeOut(tween(d / 2))
        else (slideInHorizontally(tween(d)) { -it / 3 } + fadeIn(tween(d))) togetherWith fadeOut(tween(d / 2))
    }, label = "stakeFlow") { flow ->
        if (flow) StakeFlow(vm, main, onPickValidator = { pickValidator = true }) else StakingOverviewScreen(vm, main, onPosition = { openPosition = it })
    }
    if (pickValidator) ValidatorSheet(vm.validators, vm.selected, { vm.selected = it; pickValidator = false }) { pickValidator = false }
    openPosition?.let { p -> PositionSheet(vm, p, onDismiss = { openPosition = null }) }
    vm.prepared?.let { p -> StakeConfirmSheet(vm, p) }
}

@Composable
private fun StakingOverviewScreen(vm: StakingViewModel, main: MainViewModel, onPosition: (StakePosition) -> Unit) {
    val col = Spectra.colors
    val session by main.session.collectAsState()
    val portfolio by main.portfolio.collectAsState()
    val currency = session?.prefs?.currency ?: "PLN"
    val hidden = session?.prefs?.hideBalances == true
    val solPrice = portfolio?.rows?.firstOrNull { it.token.id.isNative && it.token.id.network.chain == Chain.SOLANA }?.unitPrice
    var chainSheet by remember { mutableStateOf(false) }
    var ethInfo by rememberSaveable { mutableStateOf(false) }
    var inflationInfo by remember { mutableStateOf(false) }
    val o = vm.overview
    val byVote = remember(vm.validators) { vm.validators.associateBy { it.votePubkey } }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 120.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Staking", style = Spectra.type.title, color = col.text, modifier = Modifier.weight(1f).semantics { heading() })
            if (vm.env == NetworkEnv.TESTNET) { StatusPill("Devnet", Tone.WARNING); Spacer(Modifier.width(8.dp)) }
            val interaction = remember { MutableInteractionSource() }
            Row(Modifier.height(44.dp).pressScale(interaction, 0.95f).glassSurface(RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp))
                .clickable(interaction, ripple(), role = Role.Button) { chainSheet = true }
                .semantics(mergeDescendants = true) { contentDescription = "Sieć: ${if (ethInfo) "Ethereum" else "Solana"}. Zmień" }
                .padding(start = 7.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                ChainLogo(if (ethInfo) Chain.ETHEREUM else Chain.SOLANA, 28.dp); Spacer(Modifier.width(7.dp))
                Text(if (ethInfo) "Ethereum" else "Solana", style = Spectra.type.label, color = col.text); Spacer(Modifier.width(4.dp))
                Icon(SpectraIcons.ChevronDown, null, Modifier.size(14.dp), tint = col.textSecondary)
            }
        }
        Spacer(Modifier.height(16.dp))
        if (ethInfo) {
            NoticeCard("Staking ETH niedostępny w v0.1", "Natywny staking Ethereum wymaga 32 ETH i infrastruktury walidatora, a staking płynny opiera się na kontraktach zewnętrznych. Spectra 0.1 nie oferuje żadnej z tych opcji.", Tone.NEUTRAL, SpectraIcons.Info,
                Modifier.enterAnimation(0, key = "eth"))
            return@Column
        }
        when {
            o == null && vm.loading -> { LoadingState("Wczytywanie danych stakingu…"); return@Column }
            o == null && vm.loadError != null -> { ErrorState(vm.loadError!!, vm::load); return@Column }
        }
        val staked = o?.positions?.sumOf { it.delegatedLamports } ?: 0L
        val earned = o?.rewards?.sumOf { it.lamports } ?: 0L
        GlassCard(Modifier.fillMaxWidth().enterAnimation(0), radius = Radii.cardLarge, padding = PaddingValues(20.dp)) {
            Text("W stakingu", style = Spectra.type.label, color = col.textSecondary)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(if (hidden) Fmt.MASK else Fmt.amount(sol(staked)), style = Spectra.type.display, color = col.text)
                Spacer(Modifier.width(8.dp))
                Text("SOL", style = Spectra.type.headline, color = col.textSecondary, modifier = Modifier.padding(bottom = 6.dp))
            }
            val fiat = solPrice?.let { sol(staked).toDecimal() * it }
            if (fiat != null && staked > 0) Text(if (hidden) Fmt.MASK else "≈ " + Fmt.fiat(fiat.setScale(2, RoundingMode.HALF_UP), currency), style = Spectra.type.body, color = col.textSecondary)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f).glassSurface(RoundedCornerShape(18.dp), strong = true).padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text("Nagrody (3 epoki)", style = Spectra.type.caption, color = col.textSecondary, maxLines = 1)
                    Text(if (hidden) Fmt.MASK else (if (earned > 0) "+" else "") + Fmt.amountWithSymbol(sol(earned), "SOL"), style = Spectra.type.bodyStrong, color = if (earned > 0) col.positive else col.text, maxLines = 1)
                }
                val interaction = remember { MutableInteractionSource() }
                Column(Modifier.weight(1f).glassSurface(RoundedCornerShape(18.dp), strong = true).clip(RoundedCornerShape(18.dp))
                    .clickable(interaction, ripple(), role = Role.Button) { inflationInfo = !inflationInfo }
                    .semantics(mergeDescendants = true) { contentDescription = "Inflacja sieci, wyjaśnienie" }.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Inflacja sieci", style = Spectra.type.caption, color = col.textSecondary, maxLines = 1)
                        Spacer(Modifier.width(4.dp)); Icon(SpectraIcons.Info, null, Modifier.size(13.dp), tint = col.textSecondary)
                    }
                    Text(o?.inflationPct?.let { String.format(java.util.Locale.ROOT, "%.2f", it).replace('.', ',') + "% / rok" } ?: "—", style = Spectra.type.bodyStrong, color = col.text, maxLines = 1)
                }
            }
            Reveal(inflationInfo) {
                Text("Wskaźnik inflacji dla walidatorów z sieci Solana — nie jest gwarantowaną stopą zwrotu. Rzeczywiste nagrody zależą od łącznego stake'u w sieci, prowizji i działania walidatora.",
                    style = Spectra.type.caption, color = col.textSecondary, modifier = Modifier.padding(top = 10.dp))
            }
            o?.epoch?.let { e ->
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("Epoka ${e.epoch}", style = Spectra.type.captionStrong, color = col.text, modifier = Modifier.weight(1f))
                    Text("${(e.progress * 100).toInt()}% · ${epochTimeLeft(e)} do końca", style = Spectra.type.caption, color = col.textSecondary)
                }
                Spacer(Modifier.height(6.dp))
                ProgressBar(e.progress.toFloat())
            }
        }
        Spacer(Modifier.height(20.dp))
        val positions = o?.positions.orEmpty()
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Twoje pozycje", style = Spectra.type.bodyStrong.copy(fontSize = 17.sp), color = col.text, modifier = Modifier.weight(1f).semantics { heading() })
            if (positions.isNotEmpty()) Text(if (positions.size == 1) "1 konto stake" else "${positions.size} konta stake", style = Spectra.type.caption, color = col.textSecondary)
        }
        Spacer(Modifier.height(10.dp))
        if (positions.isEmpty()) GlassCard(Modifier.fillMaxWidth().enterAnimation(1)) {
            EmptyState("Nie masz jeszcze stake'u", "Delegujesz SOL do walidatora, a środki zostają na Twoim koncie stake. Pozycje pojawią się tutaj ze stanem aktywacji.", SpectraIcons.Earn)
        } else GlassCard(Modifier.fillMaxWidth().enterAnimation(1), padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            positions.forEachIndexed { i, p ->
                if (i > 0) Box(Modifier.padding(start = 54.dp)) { Hairline() }
                PositionRow(p, p.voter?.let { byVote[it] }, o?.epoch, hidden) { onPosition(p) }
            }
        }
        Text("Konta stake pozostają Twoje — Ty masz uprawnienia do stakingu i wypłaty. Inflacja to nie gwarantowany zysk.",
            style = Spectra.type.caption, color = col.textSecondary, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp))
        Reveal(vm.error != null && vm.prepared == null) { Spacer(Modifier.height(10.dp)); NoticeCard("Nie udało się", vm.error ?: "", Tone.NEGATIVE, SpectraIcons.Alert) }
        Reveal(vm.info != null) { Spacer(Modifier.height(10.dp)); NoticeCard("Wysłano", vm.info ?: "", Tone.PRIMARY, SpectraIcons.Info) }
        Spacer(Modifier.height(16.dp))
        val withdrawable = positions.filter { it.canWithdraw }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Wypłać", { withdrawable.singleOrNull()?.let { vm.prepare(StakeOp.WITHDRAW, it) } ?: withdrawable.firstOrNull()?.let(onPosition) },
                Modifier.weight(1f), enabled = withdrawable.isNotEmpty())
            PrimaryButton("Stakuj SOL", { vm.error = null; vm.info = null; vm.flowOpen = true }, Modifier.weight(1f), enabled = o != null)
        }
        if (withdrawable.isEmpty() && positions.any { it.state == StakeState.DEACTIVATING })
            Text("Wypłata będzie możliwa po zakończeniu epoki, w której dezaktywowano stake.", style = Spectra.type.caption, color = col.textSecondary, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
    }
    if (chainSheet) SpectraSheet({ chainSheet = false }) {
        Text("Sieć stakingu", style = Spectra.type.headline, color = col.text)
        Spacer(Modifier.height(12.dp))
        listOf(Chain.SOLANA to "Natywny staking SOL", Chain.ETHEREUM to "Niedostępny w v0.1").forEach { (ch, sub) ->
            val sel = (ch == Chain.ETHEREUM) == ethInfo
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(18.dp)).background(if (sel) col.primarySoft else androidx.compose.ui.graphics.Color.Transparent)
                .clickable(role = Role.Button) { ethInfo = ch == Chain.ETHEREUM; chainSheet = false }.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ChainLogo(ch, 40.dp); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { Text(ch.displayName, style = Spectra.type.bodyStrong, color = col.text); Text(sub, style = Spectra.type.caption, color = col.textSecondary) }
                if (sel) Icon(SpectraIcons.Check, null, Modifier.size(18.dp), tint = col.primary)
            }
        }
    }
}

@Composable
private fun PositionRow(p: StakePosition, v: Validator?, epoch: EpochStatus?, hidden: Boolean, onClick: () -> Unit) {
    val col = Spectra.colors
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).pressScale(interaction, 0.98f).clip(RoundedCornerShape(18.dp))
        .clickable(interaction, ripple(), role = Role.Button, onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        ValidatorAvatar(p.voter)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(p.voter?.let { Fmt.shortAddress(it, 6, 4) } ?: "Bez delegacji", style = Spectra.type.bodyStrong.copy(fontSize = 15.sp), color = col.text, maxLines = 1)
            val sub = listOfNotNull(v?.let { "prowizja ${it.commission}%" },
                when (p.state) {
                    StakeState.ACTIVATING -> epoch?.let { "od epoki ${it.epoch + 1}" }
                    StakeState.DEACTIVATING -> epoch?.let { "wypłata od ep. ${it.epoch + 1}" }
                    else -> null
                }).joinToString(" · ").ifEmpty { "konto ${Fmt.shortAddress(p.stakeAccount)}" }
            Text(sub, style = Spectra.type.caption, color = col.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (hidden) Fmt.MASK else Fmt.amountWithSymbol(sol(if (p.delegatedLamports > 0) p.delegatedLamports else p.lamports), "SOL"), style = Spectra.type.label.copy(fontSize = 15.sp), color = col.text, maxLines = 1)
            StatusPill(stakeStateLabel(p.state), stakeTone(p.state), pulsing = p.state == StakeState.ACTIVATING || p.state == StakeState.DEACTIVATING)
        }
    }
}

@Composable
private fun StakeFlow(vm: StakingViewModel, main: MainViewModel, onPickValidator: () -> Unit) {
    val col = Spectra.colors
    val session by main.session.collectAsState()
    val portfolio by main.portfolio.collectAsState()
    val currency = session?.prefs?.currency ?: "PLN"
    val solPrice = portfolio?.rows?.firstOrNull { it.token.id.isNative && it.token.id.network.chain == Chain.SOLANA }?.unitPrice
    val o = vm.overview
    val amount = Amount.parse(vm.amountText, 9)
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 120.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(SpectraIcons.Back, "Wstecz", { vm.flowOpen = false })
            Spacer(Modifier.width(12.dp))
            Text("Stakuj SOL", style = Spectra.type.headline, color = col.text, modifier = Modifier.weight(1f).semantics { heading() })
            if (vm.env == NetworkEnv.TESTNET) StatusPill("Devnet", Tone.WARNING)
        }
        Spacer(Modifier.height(16.dp))
        GlassCard(Modifier.fillMaxWidth().enterAnimation(0), radius = Radii.cardLarge, padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ile stakujesz", style = Spectra.type.label, color = col.textSecondary, modifier = Modifier.weight(1f))
                Text("Dostępne " + Fmt.amountWithSymbol(sol(o?.liquidLamports ?: 0), "SOL"), style = Spectra.type.caption, color = col.textSecondary)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    val style = Spectra.type.display.copy(fontSize = 40.sp, lineHeight = 46.sp)
                    if (vm.amountText.isEmpty()) Text("0", style = style, color = col.textDisabled)
                    BasicTextField(vm.amountText, { vm.amountText = it.filter { ch -> ch.isDigit() || ch == ',' || ch == '.' }.take(18); vm.error = null },
                        Modifier.fillMaxWidth().semantics { contentDescription = "Kwota stakingu w SOL" }, textStyle = style.copy(color = col.text), singleLine = true,
                        cursorBrush = SolidColor(col.primary), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                ChainLogo(Chain.SOLANA, 28.dp); Spacer(Modifier.width(6.dp))
                Text("SOL", style = Spectra.type.bodyStrong.copy(fontSize = 17.sp), color = col.text)
            }
            val fiat = amount?.takeIf { !it.isZero }?.let { a -> solPrice?.let { a.toDecimal() * it } }
            Text(fiat?.let { "≈ " + Fmt.fiat(it.setScale(2, RoundingMode.HALF_UP), currency) } ?: " ", style = Spectra.type.caption, color = col.textSecondary)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("25%" to .25, "50%" to .5, "75%" to .75, "Max" to 1.0).forEach { (l, f) -> Chip(l, false, { vm.setFraction(f) }, Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(10.dp))
            Hairline()
            val v = vm.selected
            val interaction = remember { MutableInteractionSource() }
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp)).clickable(interaction, ripple(), role = Role.Button, onClick = onPickValidator)
                .semantics(mergeDescendants = true) { contentDescription = "Walidator: ${v?.votePubkey?.let { Fmt.shortAddress(it, 6, 4) } ?: "nie wybrano"}. Zmień" }
                .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ValidatorAvatar(v?.votePubkey, 34.dp)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text("Walidator", style = Spectra.type.caption, color = col.textSecondary)
                    Text(v?.let { "${Fmt.shortAddress(it.votePubkey, 6, 4)} · prowizja ${it.commission}%" } ?: "Wybierz walidatora", style = Spectra.type.label, color = if (v == null) col.link else col.text, maxLines = 1)
                }
                Icon(SpectraIcons.Chevron, null, Modifier.size(16.dp), tint = col.textSecondary)
            }
        }
        Spacer(Modifier.height(12.dp))
        GlassCard(Modifier.fillMaxWidth().enterAnimation(1), radius = Radii.cardLarge, padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
            Text("Co się stanie", style = Spectra.type.bodyStrong, color = col.text, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(12.dp))
            val e = o?.epoch
            LifecycleStep(1, StepState.CURRENT, "Teraz — delegacja",
                "Nowe konto stake" + (vm.rentReserve?.let { " + rezerwa ${Fmt.amountWithSymbol(sol(it), "SOL")} (zwracana przy wypłacie)" } ?: ""))
            LifecycleStep(2, StepState.NEXT, "Aktywacja na granicy epoki",
                e?.let { "Epoka ${it.epoch} kończy się za ${epochTimeLeft(it)}" } ?: "Na koniec bieżącej epoki", progress = e?.progress?.toFloat())
            LifecycleStep(3, StepState.LATER, "Aktywny — nagrody co epokę", "Wysokość zależy od sieci i prowizji, nie jest gwarantowana")
            LifecycleStep(4, StepState.LATER, "Kiedy zechcesz — wypłata", "Dezaktywacja do końca epoki, potem wypłacasz SOL na swój adres", last = true)
        }
        Reveal(vm.error != null && vm.prepared == null) { Spacer(Modifier.height(10.dp)); NoticeCard("Nie udało się", vm.error ?: "", Tone.NEGATIVE, SpectraIcons.Alert) }
        Spacer(Modifier.height(16.dp))
        val label = amount?.takeIf { !it.isZero }?.let { "Stakuj ${Fmt.amountWithSymbol(it, "SOL")}" } ?: "Stakuj SOL"
        PrimaryButton(label, vm::prepareDelegate, enabled = amount != null && !amount.isZero && vm.selected != null, loading = vm.busy)
    }
}

private enum class StepState { CURRENT, NEXT, LATER }

@Composable
private fun LifecycleStep(n: Int, state: StepState, title: String, body: String, progress: Float? = null, last: Boolean = false) {
    val col = Spectra.colors
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val (bg, fg, border) = when (state) {
                StepState.CURRENT -> Triple(col.primary, col.onPrimary, col.primary)
                StepState.NEXT -> Triple(col.background, col.link, col.primary)
                StepState.LATER -> Triple(col.background, col.textSecondary, col.text.copy(alpha = .2f))
            }
            Box(Modifier.size(26.dp).clip(CircleShape).background(bg).border(2.dp, border, CircleShape), contentAlignment = Alignment.Center) {
                Text("$n", style = Spectra.type.captionStrong.copy(fontSize = 12.sp), color = fg)
            }
            if (!last) Box(Modifier.width(2.dp).weight(1f).background(if (state == StepState.LATER) col.text.copy(alpha = .12f) else col.primary.copy(alpha = .3f)))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, bottom = if (last) 0.dp else 14.dp)) {
            Text(title, style = Spectra.type.label, color = col.text)
            Text(body, style = Spectra.type.caption, color = col.textSecondary)
            if (progress != null) { Spacer(Modifier.height(6.dp)); ProgressBar(progress, height = 6.dp) }
        }
    }
}

@Composable
private fun ValidatorSheet(validators: List<Validator>, selected: Validator?, onPick: (Validator) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var byStake by rememberSaveable { mutableStateOf(false) }
    val col = Spectra.colors
    SpectraSheet(onDismiss) {
        Text("Wybierz walidatora", style = Spectra.type.headline, color = col.text, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(4.dp))
        Text("Prowizja i stake pochodzą z sieci Solana. Spectra nie ocenia walidatorów i nie pobiera ich nazw — klucz głosowania możesz sprawdzić w eksploratorze.", style = Spectra.type.caption, color = col.textSecondary)
        Spacer(Modifier.height(12.dp))
        GlassTextField(q, { q = it.trim() }, "Szukaj po kluczu głosowania", sensitive = true)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Najniższa prowizja", !byStake, { byStake = false })
            Chip("Największy stake", byStake, { byStake = true })
        }
        Spacer(Modifier.height(10.dp))
        val list = validators.filter { q.isEmpty() || it.votePubkey.startsWith(q) }
            .sortedWith(if (byStake) compareBy<Validator> { it.delinquent }.thenByDescending { it.activatedStakeLamports }
                        else compareBy<Validator> { it.delinquent }.thenBy { it.commission }.thenByDescending { it.activatedStakeLamports })
            .take(60)
        if (list.isEmpty()) Text(if (validators.isEmpty()) "Lista walidatorów jeszcze się wczytuje." else "Brak walidatora o takim kluczu.", style = Spectra.type.caption, color = col.textSecondary, modifier = Modifier.padding(vertical = 12.dp))
        list.forEach { v ->
            val sel = selected?.votePubkey == v.votePubkey
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).heightIn(min = 64.dp).clip(RoundedCornerShape(20.dp))
                .background(if (sel) col.glassStrong else col.glass).border(2.dp, if (sel) col.primary else col.glassBorder, RoundedCornerShape(20.dp))
                .clickable(enabled = !v.delinquent, role = Role.RadioButton) { onPick(v) }
                .semantics { stateDescription = if (sel) "Wybrano" else if (v.delinquent) "Niedostępny" else "" }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                ValidatorAvatar(v.votePubkey, 40.dp, dim = v.delinquent)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(Fmt.shortAddress(v.votePubkey, 6, 4), style = Spectra.type.bodyStrong.copy(fontSize = 15.sp), color = if (v.delinquent) col.textDisabled else col.text)
                    if (v.delinquent) Text("Nie głosuje — odradzane", style = Spectra.type.captionStrong, color = col.warning)
                    else Text("Prowizja ${v.commission}% · stake ${compactSol(v.activatedStakeLamports)}", style = Spectra.type.caption, color = if (v.commission >= 50) col.warning else col.textSecondary)
                }
                if (!v.delinquent) Box(Modifier.size(22.dp).clip(CircleShape).background(if (sel) col.primary else androidx.compose.ui.graphics.Color.Transparent)
                    .border(2.dp, if (sel) col.primary else col.text.copy(alpha = .25f), CircleShape), contentAlignment = Alignment.Center) {
                    if (sel) Icon(SpectraIcons.Check, null, Modifier.size(12.dp), tint = col.onPrimary)
                }
            }
        }
    }
}

@Composable
private fun PositionSheet(vm: StakingViewModel, p: StakePosition, onDismiss: () -> Unit) {
    val col = Spectra.colors
    val v = p.voter?.let { id -> vm.validators.firstOrNull { it.votePubkey == id } }
    SpectraSheet(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ValidatorAvatar(p.voter, 44.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(Fmt.amountWithSymbol(sol(p.lamports), "SOL"), style = Spectra.type.headline, color = col.text)
                Text("Konto stake ${Fmt.shortAddress(p.stakeAccount, 6, 4)}", style = Spectra.type.caption, color = col.textSecondary)
            }
            StatusPill(stakeStateLabel(p.state), stakeTone(p.state), pulsing = p.state == StakeState.ACTIVATING || p.state == StakeState.DEACTIVATING)
        }
        Spacer(Modifier.height(14.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            KeyValueRow("Walidator", p.voter?.let { Fmt.shortAddress(it, 6, 4) } ?: "—")
            v?.let { KeyValueRow("Prowizja", "${it.commission}%") }
            KeyValueRow("Delegowane", Fmt.amountWithSymbol(sol(p.delegatedLamports), "SOL"))
            KeyValueRow("Rezerwa (zwracana)", Fmt.amountWithSymbol(sol(p.rentReserve), "SOL"), strong = false)
            p.activationEpoch?.let { KeyValueRow("Aktywacja w epoce", "$it", strong = false) }
            p.deactivationEpoch?.let { KeyValueRow("Dezaktywacja w epoce", "$it", strong = false) }
        }
        Spacer(Modifier.height(10.dp))
        Text(when (p.state) {
            StakeState.ACTIVATING -> "Stake zacznie pracować na granicy epoki. Możesz go jeszcze dezaktywować."
            StakeState.ACTIVE -> "Stake przynosi nagrody co epokę. Dezaktywacja kończy się wraz z bieżącą epoką, potem możesz wypłacić SOL."
            StakeState.DEACTIVATING -> "Dezaktywacja trwa do końca bieżącej epoki. Potem pojawi się przycisk Wypłać."
            StakeState.INACTIVE, StakeState.INITIALIZED -> "Całe saldo konta stake (wraz z rezerwą) możesz wypłacić na swój adres."
        }, style = Spectra.type.body, color = col.textSecondary)
        Spacer(Modifier.height(16.dp))
        if (p.canWithdraw) PrimaryButton("Wypłać ${Fmt.amountWithSymbol(sol(p.lamports), "SOL")}", { onDismiss(); vm.prepare(StakeOp.WITHDRAW, p) }, loading = vm.busy)
        if (p.canDeactivate) SecondaryButton("Dezaktywuj", { onDismiss(); vm.prepare(StakeOp.DEACTIVATE, p) })
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
            KeyValueRow("Kwota", Fmt.amountWithSymbol(sol(p.lamports), "SOL"))
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
