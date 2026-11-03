package app.spectra.wallet.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.spectra.core.model.Amount
import app.spectra.core.model.AssetId
import app.spectra.core.model.Chain
import app.spectra.core.model.Network
import app.spectra.core.model.NetworkEnv
import app.spectra.core.model.TxDirection
import app.spectra.core.model.TxState
import app.spectra.core.model.TxType
import app.spectra.wallet.data.SwapEntity
import app.spectra.wallet.data.TransactionEntity
import app.spectra.wallet.ui.MainViewModel
import app.spectra.wallet.ui.common.LocalContainer
import app.spectra.wallet.ui.components.AssetAvatar
import app.spectra.wallet.ui.components.ChainLogo
import app.spectra.wallet.ui.components.Chip
import app.spectra.wallet.ui.components.EmptyState
import app.spectra.wallet.ui.components.GlassCard
import app.spectra.wallet.ui.components.GlassIconButton
import app.spectra.wallet.ui.components.GlassTextField
import app.spectra.wallet.ui.components.Hairline
import app.spectra.wallet.ui.components.PrimaryButton
import app.spectra.wallet.ui.components.SecondaryButton
import app.spectra.wallet.ui.components.SectionLabel
import app.spectra.wallet.ui.components.SegmentedTabs
import app.spectra.wallet.ui.components.SpectraIcons
import app.spectra.wallet.ui.components.SpectraSheet
import app.spectra.wallet.ui.components.StatusPill
import app.spectra.wallet.ui.components.Tone
import app.spectra.wallet.ui.components.enterAnimation
import app.spectra.wallet.ui.components.pressScale
import app.spectra.wallet.ui.format.Fmt
import app.spectra.wallet.ui.theme.Spectra
import java.math.BigDecimal
import java.math.BigInteger

fun stateLabel(s: TxState) = when (s) {
    TxState.DRAFT -> "Szkic"; TxState.ESTIMATED -> "Wyceniona"; TxState.AWAITING_AUTHORIZATION -> "Czeka na potwierdzenie"
    TxState.SIGNED -> "Podpisana (niewysłana)"; TxState.BROADCASTING -> "Wysyłanie"; TxState.PENDING -> "Oczekuje"
    TxState.CONFIRMED -> "Potwierdzona"; TxState.FINALIZED -> "Ostateczna"; TxState.FAILED -> "Nieudana"; TxState.EXPIRED -> "Wygasła"
    TxState.REPLACED -> "Zastąpiona"; TxState.DROPPED -> "Odrzucona"; TxState.UNKNOWN -> "Status nieznany"
}

fun stateTone(s: TxState) = when (s) {
    TxState.CONFIRMED, TxState.FINALIZED -> Tone.POSITIVE
    TxState.FAILED, TxState.EXPIRED, TxState.DROPPED, TxState.REPLACED -> Tone.NEGATIVE
    TxState.UNKNOWN, TxState.SIGNED -> Tone.WARNING
    else -> Tone.PRIMARY
}

fun typeLabel(t: TxType, d: TxDirection) = when (t) {
    TxType.TRANSFER -> if (d == TxDirection.IN) "Otrzymano" else "Wysłano"
    TxType.RECEIVE -> "Otrzymano"
    TxType.TOKEN_TRANSFER -> if (d == TxDirection.IN) "Otrzymano token" else "Wysłano token"
    TxType.SWAP -> "Wymiana"; TxType.APPROVAL -> "Zgoda na token"; TxType.STAKE_DELEGATE -> "Delegacja stake"
    TxType.STAKE_DEACTIVATE -> "Dezaktywacja stake"; TxType.STAKE_WITHDRAW -> "Wypłata stake"; TxType.CONTRACT_CALL -> "Interakcja z kontraktem"; TxType.UNKNOWN -> "Transakcja"
}


private val TxState.done get() = this == TxState.CONFIRMED || this == TxState.FINALIZED
private fun TransactionEntity.epochSec(): Long = timestamp ?: (createdAt / 1000)
private fun TransactionEntity.amount(): Amount? { val u = amountUnits ?: return null; val d = decimals ?: return null; return runCatching { Amount(BigInteger(u), d) }.getOrNull() }
private fun TransactionEntity.assetId(): AssetId? = assetKey?.let { runCatching { AssetId.fromKey(it) }.getOrNull() }

/** Fiat value of a transaction's amount from cached prices, or null when no price is known (never assumed zero). */
private fun fiatOf(t: TransactionEntity, prices: Map<String, BigDecimal>): BigDecimal? {
    val a = t.amount() ?: return null
    val p = t.assetKey?.let { prices[it] } ?: return null
    return a.toDecimal().multiply(p)
}

@Composable
fun TxRow(t: TransactionEntity, hidden: Boolean, swap: SwapEntity? = null, fiat: String? = null, onClick: () -> Unit) {
    val c = Spectra.colors
    val interaction = remember { MutableInteractionSource() }
    val type = runCatching { TxType.valueOf(t.type) }.getOrDefault(TxType.UNKNOWN)
    val dir = runCatching { TxDirection.valueOf(t.direction) }.getOrDefault(TxDirection.SELF)
    val state = runCatching { TxState.valueOf(t.state) }.getOrDefault(TxState.UNKNOWN)
    val n = Network.fromId(t.networkId)
    val isStake = type == TxType.STAKE_DELEGATE || type == TxType.STAKE_DEACTIVATE || type == TxType.STAKE_WITHDRAW
    val title = when {
        type == TxType.SWAP && swap != null -> "Wymiana ${swap.fromSymbol} → ${swap.toSymbol}"
        type == TxType.TRANSFER || type == TxType.RECEIVE || type == TxType.TOKEN_TRANSFER ->
            (if (dir == TxDirection.IN || type == TxType.RECEIVE) "Otrzymano" else "Wysłano") + (t.symbol?.let { " $it" } ?: "")
        isStake -> typeLabel(type, dir).replace(" stake", "") + (t.symbol?.let { " · $it" } ?: "")
        else -> typeLabel(type, dir)
    }
    val who = t.counterparty?.let { Fmt.shortAddress(it, 4, 4) }
    val subtitle = listOfNotNull(
        when {
            type == TxType.SWAP -> swap?.provider?.let { p -> p.lowercase().replaceFirstChar { it.uppercase() }.replace("Lifi", "LI.FI") }
            who != null && dir == TxDirection.IN -> "od $who"
            who != null && dir == TxDirection.OUT -> "do $who"
            isStake -> n.displayName
            else -> n.displayName
        },
        Fmt.time(t.epochSec()),
    ).joinToString(" · ")
    Row(Modifier.fillMaxWidth().heightIn(min = 66.dp).pressScale(interaction, 0.98f).clip(RoundedCornerShape(18.dp))
        .clickable(interaction, ripple(), role = Role.Button, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        TxAvatar(t, type, dir, isStake, swap, n)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = Spectra.type.bodyStrong.copy(fontSize = 15.sp), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = Spectra.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val amt = t.amount()?.let { Fmt.amountWithSymbol(it, t.symbol ?: "") }
            // Stake operations keep funds in the user's control: show the amount without a gain/loss sign.
            val sign = if (isStake) "" else if (dir == TxDirection.IN) "+" else if (dir == TxDirection.OUT) "−" else ""
            if (amt != null) Text(sign + (if (hidden) Fmt.MASK else amt),
                style = Spectra.type.label.copy(fontSize = 15.sp), color = if (dir == TxDirection.IN && !isStake) c.positive else c.text, maxLines = 1)
            when {
                !state.done -> StatusPill(stateLabel(state), stateTone(state), pulsing = state == TxState.PENDING || state == TxState.BROADCASTING || state == TxState.UNKNOWN)
                type == TxType.SWAP && swap != null && !hidden -> Text("≈ ${Fmt.amountWithSymbol(Amount(BigInteger(swap.estimatedOutUnits), swap.toDecimals), swap.toSymbol)}", style = Spectra.type.caption, color = c.textSecondary, maxLines = 1)
                n.isTestnet -> Text("testnet", style = Spectra.type.micro, color = c.warning)
                fiat != null -> Text(if (hidden) Fmt.MASK else fiat, style = Spectra.type.caption, color = c.textSecondary)
            }
        }
    }
}

@Composable
private fun TxAvatar(t: TransactionEntity, type: TxType, dir: TxDirection, isStake: Boolean, swap: SwapEntity?, n: Network) {
    val c = Spectra.colors
    val ring = c.background
    if (type == TxType.SWAP && swap != null) {
        val from = runCatching { AssetId.fromKey(swap.fromAssetKey) }.getOrNull()
        val to = runCatching { AssetId.fromKey(swap.toAssetKey) }.getOrNull()
        Box(Modifier.size(40.dp)) {
            AssetAvatar(swap.fromSymbol, from?.network?.chain ?: n.chain, from?.isNative ?: true, 28.dp, address = from?.address, testnet = from?.network?.isTestnet ?: n.isTestnet, showNetworkBadge = false)
            AssetAvatar(swap.toSymbol, to?.network?.chain, to?.isNative ?: true, 28.dp, Modifier.align(Alignment.BottomEnd).border(2.dp, ring, CircleShape),
                address = to?.address, testnet = to?.network?.isTestnet ?: n.isTestnet, showNetworkBadge = false)
        }
        return
    }
    val id = t.assetId()
    Box(Modifier.size(40.dp)) {
        if (id != null) AssetAvatar(t.symbol ?: id.network.chain.symbol, id.network.chain, id.isNative, 40.dp, address = id.address, testnet = id.network.isTestnet)
        else ChainLogo(n.chain, 40.dp)
        val badge: Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.vector.ImageVector>? = when {
            isStake -> c.primary to SpectraIcons.Earn
            dir == TxDirection.IN -> c.positive to SpectraIcons.Receive
            dir == TxDirection.OUT -> c.inverse to SpectraIcons.Send
            else -> null
        }
        if (badge != null) Box(Modifier.size(19.dp).align(Alignment.BottomEnd).offset(3.dp, 3.dp).border(2.dp, ring, CircleShape).padding(2.dp).clip(CircleShape).background(badge.first),
            contentAlignment = Alignment.Center) {
            Icon(badge.second, null, Modifier.size(9.dp), tint = if (badge.first == c.inverse) c.onInverse else androidx.compose.ui.graphics.Color.White)
        }
    }
}

private data class DayGroup(val key: Long, val label: String, val items: List<TransactionEntity>, val net: BigDecimal?)

@Composable
fun ActivityScreen(main: MainViewModel, onTx: (String) -> Unit) {
    val container = LocalContainer.current
    val session by main.session.collectAsState()
    val portfolio by main.portfolio.collectAsState()
    val all by container.transactions.all.collectAsState(initial = emptyList())
    val swaps by container.swaps.swaps.collectAsState(initial = emptyList())
    val s = session ?: return
    val col = Spectra.colors
    var allAccounts by rememberSaveable { mutableStateOf(false) }
    var chain by rememberSaveable { mutableStateOf<String?>(null) }
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    var asset by rememberSaveable { mutableStateOf("") }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    val activeFilters = listOf(allAccounts, chain != null, status != null, asset.isNotBlank()).count { it }
    val filtered = all.filter { t ->
        val n = Network.fromId(t.networkId)
        n.env == s.env && (allAccounts || t.accountId == s.active?.account?.id) &&
            (chain == null || n.chain.name == chain) &&
            (type == null || when (type) { "TRANSFER" -> t.type in setOf("TRANSFER", "RECEIVE", "TOKEN_TRANSFER"); "SWAP" -> t.type in setOf("SWAP", "APPROVAL"); "STAKE" -> t.type.startsWith("STAKE"); else -> true }) &&
            (status == null || runCatching { TxState.valueOf(t.state) }.getOrDefault(TxState.UNKNOWN).let { st -> when (status) { "PENDING" -> !st.done && !st.isTerminal; "DONE" -> st.done; "FAILED" -> st.isTerminal && !st.done; else -> true } }) &&
            (asset.isBlank() || (t.symbol ?: "").contains(asset.trim(), true))
    }.sortedByDescending { it.epochSec() }
    val prices = remember(portfolio) { portfolio?.rows?.mapNotNull { r -> r.unitPrice?.let { r.token.id.key to it } }?.toMap() ?: emptyMap() }
    val swapById = remember(swaps) { swaps.associateBy { it.id } }
    val zone = remember { java.time.ZoneId.systemDefault() }
    val groups = remember(filtered, prices) {
        filtered.groupBy { java.time.Instant.ofEpochSecond(it.epochSec()).atZone(zone).toLocalDate() }.map { (day, txs) ->
            // Net fiat for the day only when every amount-bearing, settled transfer has a known price.
            // Staking and swaps move value without it leaving the wallet, so only plain transfers count.
            val counted = txs.filter { it.amount() != null && it.direction != "SELF" && it.type in setOf("TRANSFER", "RECEIVE", "TOKEN_TRANSFER") && runCatching { TxState.valueOf(it.state) }.getOrNull()?.done == true }
            val values = counted.map { t -> fiatOf(t, prices)?.let { if (t.direction == "IN") it else it.negate() } }
            val net = if (counted.isNotEmpty() && values.all { it != null }) values.fold(BigDecimal.ZERO) { a, v -> a + v!! } else null
            DayGroup(day.toEpochDay(), Fmt.dayHeader(txs.first().epochSec(), zone = zone).uppercase(java.util.Locale.forLanguageTag("pl-PL")), txs, net)
        }
    }
    val hidden = s.prefs.hideBalances
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp), contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp)) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Historia", style = Spectra.type.title, color = col.text, modifier = Modifier.weight(1f).semantics { heading() })
                if (s.env == NetworkEnv.TESTNET) { StatusPill("Testnet", Tone.WARNING); Spacer(Modifier.width(8.dp)) }
                Box {
                    GlassIconButton(SpectraIcons.Filter, if (activeFilters > 0) "Filtry, aktywne: $activeFilters" else "Filtry", { showFilters = true })
                    if (activeFilters > 0) Box(Modifier.align(Alignment.TopEnd).size(17.dp).clip(CircleShape).background(col.primary), contentAlignment = Alignment.Center) {
                        Text("$activeFilters", style = Spectra.type.micro.copy(fontSize = 10.sp), color = col.onPrimary)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            val tabs = listOf(null to "Wszystko", "TRANSFER" to "Przelewy", "SWAP" to "Wymiany", "STAKE" to "Staking")
            GlassCard(Modifier.fillMaxWidth(), radius = 24.dp, padding = PaddingValues(2.dp)) {
                SegmentedTabs(tabs.map { it.second }, tabs.indexOfFirst { it.first == type }.coerceAtLeast(0), { type = tabs[it].first }, Modifier.fillMaxWidth(), fill = true)
            }
        }
        if (groups.isEmpty()) item(key = "empty") {
            Spacer(Modifier.height(24.dp))
            if (activeFilters > 0 || type != null) EmptyState("Brak wyników", "Żadna transakcja nie pasuje do wybranych filtrów.", SpectraIcons.Filter) {
                Spacer(Modifier.height(6.dp))
                SecondaryButton("Wyczyść filtry", { allAccounts = false; chain = null; status = null; asset = ""; type = null })
            } else EmptyState("Brak transakcji", "Historia pobiera się z sieci dla aktywnego konta. Nowe przelewy, wymiany i staking pojawią się tutaj.", SpectraIcons.History) {
                Spacer(Modifier.height(6.dp))
                SecondaryButton(if (main.refreshing) "Odświeżanie…" else "Odśwież", { main.refresh() }, enabled = !main.refreshing, icon = SpectraIcons.Refresh)
            }
        }
        groups.forEachIndexed { gi, g ->
            item(key = "day-${g.key}") { Column(Modifier.animateItem().enterAnimation(gi)) {
                Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(g.label, style = Spectra.type.captionStrong.copy(letterSpacing = 0.5.sp), color = col.textSecondary, modifier = Modifier.weight(1f).semantics { heading() })
                    g.net?.takeIf { it.signum() != 0 }?.let { Text(if (hidden) Fmt.MASK else Fmt.signedFiat(it, s.prefs.currency), style = Spectra.type.caption, color = col.textSecondary) }
                }
                GlassCard(Modifier.fillMaxWidth(), radius = 26.dp, padding = PaddingValues(horizontal = 16.dp, vertical = 2.dp)) {
                    g.items.forEachIndexed { i, t ->
                        if (i > 0) Box(Modifier.padding(start = 52.dp)) { Hairline() }
                        TxRow(t, hidden, t.swapId?.let { swapById[it] }, fiatOf(t, prices)?.let { Fmt.fiat(it, s.prefs.currency) }) { onTx(t.id) }
                    }
                }
            } }
        }
    }

    if (showFilters) SpectraSheet({ showFilters = false }) {
        Text("Filtry", style = Spectra.type.headline, color = col.text, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(14.dp))
        SectionLabel("Konto")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(s.active?.account?.name ?: "Aktywne konto", !allAccounts, { allAccounts = false })
            Chip("Wszystkie konta", allAccounts, { allAccounts = true })
        }
        Spacer(Modifier.height(14.dp))
        SectionLabel("Sieć")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Wszystkie", chain == null, { chain = null })
            Chain.entries.forEach { ch -> Chip(ch.symbol, chain == ch.name, { chain = ch.name }, leading = { ChainLogo(ch, 22.dp) }) }
        }
        Spacer(Modifier.height(14.dp))
        SectionLabel("Status")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(null to "Wszystkie", "PENDING" to "W toku", "DONE" to "Potwierdzone", "FAILED" to "Nieudane").forEach { (k, l) -> Chip(l, status == k, { status = k }) }
        }
        Spacer(Modifier.height(14.dp))
        SectionLabel("Aktywo")
        GlassTextField(asset, { asset = it }, "Symbol, np. SOL, USDC", label = "Filtr aktywa")
        Spacer(Modifier.height(18.dp))
        PrimaryButton("Pokaż wyniki (${filtered.size})", { showFilters = false })
        Spacer(Modifier.height(8.dp))
        SecondaryButton("Wyczyść filtry", { allAccounts = false; chain = null; status = null; asset = "" }, enabled = activeFilters > 0)
    }
}
