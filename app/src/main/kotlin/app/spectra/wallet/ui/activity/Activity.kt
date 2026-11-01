package app.spectra.wallet.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import app.spectra.core.model.Amount
import app.spectra.core.model.Chain
import app.spectra.core.model.Network
import app.spectra.core.model.TxDirection
import app.spectra.core.model.TxState
import app.spectra.core.model.TxType
import app.spectra.wallet.data.TransactionEntity
import app.spectra.wallet.ui.MainViewModel
import app.spectra.wallet.ui.common.LocalContainer
import app.spectra.wallet.ui.components.Chip
import app.spectra.wallet.ui.components.EmptyState
import app.spectra.wallet.ui.components.GlassTextField
import app.spectra.wallet.ui.components.SpectraIcons
import app.spectra.wallet.ui.components.StatusPill
import app.spectra.wallet.ui.components.Tone
import app.spectra.wallet.ui.format.Fmt
import app.spectra.wallet.ui.theme.Spectra
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

@Composable
fun TxRow(t: TransactionEntity, hidden: Boolean, onClick: () -> Unit) {
    val c = Spectra.colors
    val type = runCatching { TxType.valueOf(t.type) }.getOrDefault(TxType.UNKNOWN)
    val dir = runCatching { TxDirection.valueOf(t.direction) }.getOrDefault(TxDirection.SELF)
    val state = TxState.valueOf(t.state)
    val n = Network.fromId(t.networkId)
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(18.dp)).clickable(role = Role.Button, onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(if (dir == TxDirection.IN) c.positiveSoft else c.primarySoft), contentAlignment = Alignment.Center) {
            Icon(when (type) { TxType.SWAP -> SpectraIcons.Swap; TxType.STAKE_DELEGATE, TxType.STAKE_DEACTIVATE, TxType.STAKE_WITHDRAW -> SpectraIcons.Earn
                else -> if (dir == TxDirection.IN) SpectraIcons.Receive else SpectraIcons.Send }, null, Modifier.size(18.dp), tint = if (dir == TxDirection.IN) c.positive else c.primary)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(typeLabel(type, dir), style = Spectra.type.bodyStrong.copy(fontSize = Spectra.type.body.fontSize), color = c.text)
            Text(n.displayName + (t.timestamp?.let { " · " + Fmt.dateTime(it) } ?: ""), style = Spectra.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            val amt = if (t.amountUnits != null && t.decimals != null) Fmt.amountWithSymbol(Amount(BigInteger(t.amountUnits), t.decimals), t.symbol ?: "") else ""
            if (amt.isNotEmpty()) Text((if (dir == TxDirection.IN) "+" else if (dir == TxDirection.OUT) "−" else "") + (if (hidden) Fmt.MASK else amt),
                style = Spectra.type.label, color = if (dir == TxDirection.IN) c.positive else c.text)
            if (state != TxState.CONFIRMED && state != TxState.FINALIZED) StatusPill(stateLabel(state), stateTone(state), dot = false)
            else if (n.isTestnet) Text("testnet", style = Spectra.type.micro, color = c.warning)
        }
    }
}

@Composable
fun ActivityScreen(main: MainViewModel, onTx: (String) -> Unit) {
    val container = LocalContainer.current
    val session by main.session.collectAsState()
    val all by container.transactions.all.collectAsState(initial = emptyList())
    val s = session ?: return
    var allAccounts by rememberSaveable { mutableStateOf(false) }
    var chain by rememberSaveable { mutableStateOf<String?>(null) }
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    var asset by rememberSaveable { mutableStateOf("") }
    val filtered = all.filter { t ->
        val n = Network.fromId(t.networkId)
        n.env == s.env && (allAccounts || t.accountId == s.active?.account?.id) &&
            (chain == null || n.chain.name == chain) &&
            (type == null || when (type) { "TRANSFER" -> t.type in setOf("TRANSFER", "RECEIVE", "TOKEN_TRANSFER"); "SWAP" -> t.type in setOf("SWAP", "APPROVAL"); "STAKE" -> t.type.startsWith("STAKE"); else -> true }) &&
            (status == null || when (status) { "PENDING" -> TxState.valueOf(t.state).isInFlight && t.state != "CONFIRMED"; "DONE" -> t.state in setOf("CONFIRMED", "FINALIZED"); "FAILED" -> TxState.valueOf(t.state).isTerminal && t.state != "FINALIZED"; else -> true }) &&
            (asset.isBlank() || (t.symbol ?: "").contains(asset.trim(), true))
    }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 16.dp, bottom = 120.dp)) {
        item {
            Text("Historia", style = Spectra.type.title, color = Spectra.colors.text, modifier = Modifier.semantics { heading() })
            if (s.env == app.spectra.core.model.NetworkEnv.TESTNET) StatusPill("Sieci testowe", Tone.WARNING, Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(14.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(if (allAccounts) "Wszystkie konta" else (s.active?.account?.name ?: "Konto"), allAccounts, { allAccounts = !allAccounts })
                Chain.entries.forEach { ch -> Chip(ch.displayName, chain == ch.name, { chain = if (chain == ch.name) null else ch.name }) }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("TRANSFER" to "Przelewy", "SWAP" to "Wymiany", "STAKE" to "Staking").forEach { (k, l) -> Chip(l, type == k, { type = if (type == k) null else k }) }
                listOf("PENDING" to "W toku", "DONE" to "Potwierdzone", "FAILED" to "Nieudane").forEach { (k, l) -> Chip(l, status == k, { status = if (status == k) null else k }) }
            }
            Spacer(Modifier.height(8.dp))
            GlassTextField(asset, { asset = it }, "Filtruj po aktywie (np. SOL, USDC)", label = "Filtr aktywa")
            Spacer(Modifier.height(10.dp))
            if (filtered.isEmpty()) EmptyState("Brak transakcji", "Pociągnij ekran główny, aby odświeżyć historię z sieci.", SpectraIcons.History)
        }
        items(filtered, key = { it.id }) { TxRow(it, s.prefs.hideBalances) { onTx(it.id) } }
    }
}
