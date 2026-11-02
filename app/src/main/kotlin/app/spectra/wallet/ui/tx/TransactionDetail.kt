package app.spectra.wallet.ui.tx

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.spectra.core.model.Amount
import app.spectra.core.model.Chain
import app.spectra.core.model.Network
import app.spectra.core.model.TxDirection
import app.spectra.core.model.TxState
import app.spectra.core.model.TxType
import app.spectra.wallet.domain.ExecuteResult
import app.spectra.wallet.ui.activity.stateLabel
import app.spectra.wallet.ui.activity.stateTone
import app.spectra.wallet.ui.activity.typeLabel
import app.spectra.wallet.ui.common.LocalContainer
import app.spectra.wallet.ui.common.humanMessage
import app.spectra.wallet.ui.components.AuroraBackground
import app.spectra.wallet.ui.components.AuroraPreset
import app.spectra.wallet.ui.components.GlassCard
import app.spectra.wallet.ui.components.KeyValueRow
import app.spectra.wallet.ui.components.LoadingState
import app.spectra.wallet.ui.components.NoticeCard
import app.spectra.wallet.ui.components.PrimaryButton
import app.spectra.wallet.ui.components.SecondaryButton
import app.spectra.wallet.ui.components.SpectraIcons
import app.spectra.wallet.ui.components.StatusPill
import app.spectra.wallet.ui.components.Tone
import app.spectra.wallet.ui.components.TopBar
import app.spectra.wallet.ui.format.Fmt
import app.spectra.wallet.ui.receive.copyAddress
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
import java.math.BigInteger

@Composable
fun TransactionDetailScreen(id: String, onBack: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val tx by c.transactions.byId(id).collectAsState(initial = null)
    val swaps by c.swaps.swaps.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AuroraBackground(AuroraPreset.DETAIL) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
            TopBar("Szczegóły transakcji", onBack = onBack)
            Spacer(Modifier.height(20.dp))
            val t = tx ?: run { LoadingState("Wczytywanie…"); return@Column }
            val n = Network.fromId(t.networkId)
            val state = TxState.valueOf(t.state)
            val type = runCatching { TxType.valueOf(t.type) }.getOrDefault(TxType.UNKNOWN)
            val dir = runCatching { TxDirection.valueOf(t.direction) }.getOrDefault(TxDirection.SELF)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(typeLabel(type, dir), style = Spectra.type.label, color = Spectra.colors.textSecondary)
                Spacer(Modifier.height(6.dp))
                if (t.amountUnits != null && t.decimals != null)
                    Text(Fmt.amountWithSymbol(Amount(BigInteger(t.amountUnits), t.decimals), t.symbol ?: ""), style = Spectra.type.title, color = Spectra.colors.text)
                Spacer(Modifier.height(10.dp))
                val sd = if (Spectra.reducedMotion) 0 else 260
                AnimatedContent(state, transitionSpec = { (fadeIn(tween(sd)) + scaleIn(tween(sd), 0.85f)) togetherWith fadeOut(tween(sd / 2)) }, label = "txState") { st ->
                    StatusPill(stateLabel(st), stateTone(st), pulsing = st == TxState.PENDING || st == TxState.BROADCASTING || st == TxState.UNKNOWN)
                }
            }
            Spacer(Modifier.height(18.dp))
            when (state) {
                TxState.PENDING, TxState.BROADCASTING -> NoticeCard("Transakcja w toku", "Sieć przyjęła transakcję, ale nie jest jeszcze potwierdzona. Status odświeża się automatycznie.", Tone.PRIMARY, SpectraIcons.History)
                TxState.UNKNOWN -> NoticeCard("Status nieznany", "Nie wiemy, czy transakcja dotarła do sieci (np. przekroczono czas). Spectra najpierw sprawdzi jej identyfikator; ponowne wysłanie dotyczy tylko tych samych, już podpisanych danych — nigdy nowej transakcji.", Tone.WARNING, SpectraIcons.Alert)
                TxState.SIGNED -> NoticeCard("Podpisana, ale niewysłana", "Aplikacja została przerwana po podpisaniu. Możesz wysłać tę samą podpisaną transakcję lub ją odrzucić.", Tone.WARNING, SpectraIcons.Alert)
                TxState.EXPIRED -> NoticeCard("Transakcja wygasła", "Blockhash Solany stracił ważność zanim transakcja została włączona do bloku. Środki nie zostały wydane — możesz przygotować nową transakcję.", Tone.NEGATIVE, SpectraIcons.Alert)
                TxState.REPLACED -> NoticeCard("Transakcja zastąpiona", "Nonce tej transakcji wykorzystała inna transakcja z tego adresu.", Tone.NEGATIVE, SpectraIcons.Alert)
                TxState.FAILED -> NoticeCard("Transakcja nieudana", t.error ?: "Sieć odrzuciła transakcję.", Tone.NEGATIVE, SpectraIcons.Alert)
                TxState.DROPPED -> NoticeCard("Transakcja porzucona", t.error ?: "Sieć nie ma informacji o tej transakcji.", Tone.NEGATIVE, SpectraIcons.Alert)
                else -> Unit
            }
            Spacer(Modifier.height(12.dp))
            GlassCard(Modifier.fillMaxWidth().enterAnimation(1)) {
                KeyValueRow("Sieć", n.displayName, if (n.isTestnet) Spectra.colors.warning else Spectra.colors.text)
                t.counterparty?.let { KeyValueRow(if (dir == TxDirection.IN) "Od" else "Do", Fmt.shortAddress(it, 6, 6), onClick = { copyAddress(context, "Adres", it) }, trailingIcon = SpectraIcons.Copy) }
                t.feeUnits?.let { KeyValueRow(if (state == TxState.CONFIRMED || state == TxState.FINALIZED) "Opłata" else "Opłata (maks.)",
                    Fmt.amountWithSymbol(Amount(BigInteger(it), n.chain.nativeDecimals), n.chain.symbol), strong = false) }
                t.confirmations?.let { KeyValueRow("Potwierdzenia", it.toString() + if (n.chain == Chain.LITECOIN && it >= 6) " (6+)" else "") }
                t.nonce?.let { KeyValueRow("Nonce", it.toString()) }
                t.timestamp?.let { KeyValueRow("Czas", Fmt.dateTime(it), strong = false) }
                t.txId?.let { KeyValueRow("Identyfikator", Fmt.shortAddress(it, 8, 6), onClick = { copyAddress(context, "Tx", it) }, trailingIcon = SpectraIcons.Copy) }
            }
            t.swapId?.let { sid -> swaps.firstOrNull { it.id == sid }?.let { sw ->
                Spacer(Modifier.height(12.dp))
                GlassCard(Modifier.fillMaxWidth()) {
                    KeyValueRow("Wymiana", "${sw.fromSymbol} → ${sw.toSymbol} (${sw.provider})")
                    KeyValueRow("Status wymiany", sw.status)
                    KeyValueRow("Minimum do otrzymania", Fmt.amountWithSymbol(Amount(BigInteger(sw.minimumOutUnits), sw.toDecimals), sw.toSymbol))
                    sw.message?.let { Text(it, style = Spectra.type.caption, color = Spectra.colors.textSecondary) }
                    if (sw.status == "REFUNDED") Text("Dostawca zwrócił środki na adres źródłowy. Sprawdź saldo w sieci źródłowej.", style = Spectra.type.caption, color = Spectra.colors.warning)
                    if (sw.status == "PARTIAL") Text("Wymiana zakończona częściowo — otrzymano inne aktywo lub mniejszą kwotę. Szczegóły w eksploratorze dostawcy.", style = Spectra.type.caption, color = Spectra.colors.warning)
                }
            } }
            Reveal(error != null) { Spacer(Modifier.height(12.dp)); NoticeCard("Błąd", error ?: "", Tone.NEGATIVE, SpectraIcons.Alert) }
            Spacer(Modifier.height(16.dp))
            t.txId?.let { txId -> SecondaryButton("Zobacz w eksploratorze", { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(n.explorerTxUrl(txId)))) }, icon = SpectraIcons.External) }
            if (state == TxState.UNKNOWN || (state == TxState.DROPPED && t.rawSigned != null)) {
                Spacer(Modifier.height(10.dp))
                PrimaryButton("Sprawdź i wyślij ponownie te same dane", {
                    busy = true; error = null
                    scope.launch { val r = c.transactions.reconcileAndRebroadcast(t.id); if (r is ExecuteResult.Failed) error = r.error.humanMessage(); busy = false }
                }, loading = busy)
            }
            if (state == TxState.SIGNED) {
                Spacer(Modifier.height(10.dp))
                PrimaryButton("Wyślij podpisaną transakcję", { busy = true; scope.launch { val r = c.transactions.submitStoredSigned(t.id); if (r is ExecuteResult.Failed) error = r.error.humanMessage(); busy = false } }, loading = busy)
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Odrzuć", { scope.launch { c.transactions.discardSigned(t.id) } })
            }
        }
    }
}
