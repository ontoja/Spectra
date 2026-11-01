package app.spectra.wallet.ui.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.spectra.core.market.ChartRange
import app.spectra.core.model.AssetId
import app.spectra.core.model.Chain
import app.spectra.wallet.ui.MainViewModel
import app.spectra.wallet.ui.activity.TxRow
import app.spectra.wallet.ui.common.LocalContainer
import app.spectra.wallet.ui.common.humanMessage
import app.spectra.wallet.ui.components.AssetAvatar
import app.spectra.wallet.ui.components.AuroraBackground
import app.spectra.wallet.ui.components.AuroraPreset
import app.spectra.wallet.ui.components.EmptyState
import app.spectra.wallet.ui.components.GlassCard
import app.spectra.wallet.ui.components.Hairline
import app.spectra.wallet.ui.components.KeyValueRow
import app.spectra.wallet.ui.components.LinkButton
import app.spectra.wallet.ui.components.LoadingState
import app.spectra.wallet.ui.components.NoticeCard
import app.spectra.wallet.ui.components.PrimaryButton
import app.spectra.wallet.ui.components.SecondaryButton
import app.spectra.wallet.ui.components.SegmentedTabs
import app.spectra.wallet.ui.components.SpectraIcons
import app.spectra.wallet.ui.components.Tone
import app.spectra.wallet.ui.components.TopBar
import app.spectra.wallet.ui.components.glassSurface
import app.spectra.wallet.ui.format.Fmt
import app.spectra.wallet.ui.theme.Spectra
import kotlinx.coroutines.launch
import java.math.BigDecimal

@Composable
fun LineChart(points: List<Double>, modifier: Modifier = Modifier) {
    val c = Spectra.colors
    Canvas(modifier.semantics { contentDescription = "Wykres ceny, ${points.size} punktów" }) {
        if (points.size < 2) return@Canvas
        val min = points.min(); val max = points.max(); val span = (max - min).takeIf { it > 0 } ?: 1.0
        val stepX = size.width / (points.size - 1)
        fun y(v: Double) = (size.height * 0.9f - ((v - min) / span * size.height * 0.8f)).toFloat()
        val path = Path().apply { points.forEachIndexed { i, v -> if (i == 0) moveTo(0f, y(v)) else lineTo(i * stepX, y(v)) } }
        drawPath(path, c.primary, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val end = Offset(size.width - 2.dp.toPx(), y(points.last()))
        drawCircle(c.primary.copy(alpha = .25f), 9.dp.toPx(), end)
        drawCircle(c.background, 5.dp.toPx(), end)
        drawCircle(c.primary, 5.dp.toPx(), end, style = Stroke(2.dp.toPx()))
    }
}

@Composable
fun AssetDetailScreen(main: MainViewModel, assetId: AssetId, onBack: () -> Unit, onSend: () -> Unit, onReceive: () -> Unit, onTx: (String) -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val session by main.session.collectAsState()
    val portfolio by main.portfolio.collectAsState()
    val txs by container.transactions.all.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val s = session ?: return
    val row = portfolio?.rows?.firstOrNull { it.token.id == assetId }
    val token = row?.token ?: remember(assetId) { app.spectra.core.model.TokenInfo.native(assetId.network) }
    val currency = s.prefs.currency
    val c = Spectra.colors
    var range by remember { mutableStateOf(0) }
    var chart by remember { mutableStateOf<List<Pair<Long, Double>>?>(null) }
    var chartError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(assetId, range) {
        chart = null; chartError = null
        if (token.coingeckoId == null) { chartError = if (assetId.network.isTestnet) "Tokeny w sieci testowej nie mają ceny rynkowej." else "Brak danych rynkowych dla tego aktywa."; return@LaunchedEffect }
        runCatching { container.portfolio.chart(token, ChartRange.entries[range]) }.onSuccess { chart = it }.onFailure { chartError = it.humanMessage() }
    }
    AuroraBackground(AuroraPreset.DETAIL) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
                TopBar(null, onBack = onBack, titleContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AssetAvatar(token.symbol, assetId.network.chain, assetId.isNative, 26.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(token.name, style = Spectra.type.topBar, color = c.text)
                    }
                })
                Spacer(Modifier.height(24.dp))
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("1 ${token.symbol} · ${assetId.network.displayName}", style = Spectra.type.label, color = c.textSecondary)
                    Spacer(Modifier.height(6.dp))
                    val price = row?.unitPrice
                    if (price != null) {
                        val (i, d) = Fmt.fiatParts(price, currency)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(i, style = Spectra.type.balanceHero.copy(fontSize = Spectra.type.balanceHero.fontSize * 0.86f), color = c.text)
                            Text(d, style = Spectra.type.balanceHeroDecimals, color = c.text, modifier = Modifier.padding(bottom = 4.dp))
                        }
                        Spacer(Modifier.height(10.dp))
                        ChangeLine(row.change24h?.let { price * BigDecimal(it / (100 + it)) }, row.change24h, currency, false)
                    } else Text("Cena niedostępna", style = Spectra.type.headline, color = c.textSecondary)
                }
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) {
                    when {
                        chartError != null -> Text(chartError!!, style = Spectra.type.caption, color = c.textSecondary)
                        chart == null -> LoadingState("Wczytywanie wykresu…")
                        else -> LineChart(chart!!.map { it.second }, Modifier.fillMaxSize())
                    }
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().glassSurface(RoundedCornerShape(26.dp)).padding(4.dp)) {
                    SegmentedTabs(listOf("1D", "1T", "1M", "1R", "Wsz."), range, { range = it }, Modifier.fillMaxWidth(), fill = true)
                }
                Spacer(Modifier.height(16.dp))
                GlassCard(Modifier.fillMaxWidth(), padding = PaddingValues(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Twoje saldo", style = Spectra.type.caption, color = c.textSecondary)
                            Text(if (s.prefs.hideBalances) Fmt.MASK else row?.let { Fmt.amountWithSymbol(it.amount, token.symbol) } ?: "0 ${token.symbol}", style = Spectra.type.bodyStrong.copy(fontSize = Spectra.type.headline.fontSize * 0.82f), color = c.text)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(if (s.prefs.hideBalances) Fmt.MASK else row?.fiatValue?.let { Fmt.fiat(it, currency) } ?: "—", style = Spectra.type.bodyStrong, color = c.text)
                        }
                    }
                    row?.pending?.let { Text("Oczekujące: ${Fmt.amountWithSymbol(it, token.symbol)} (niepotwierdzone, niedostępne do wydania)", style = Spectra.type.caption, color = c.warning) }
                    if (assetId.network.chain == Chain.SOLANA && assetId.isNative) {
                        Spacer(Modifier.height(14.dp)); Hairline(); Spacer(Modifier.height(10.dp))
                        Text("Stakuj SOL w zakładce Zarabiaj — nagrody zależą od walidatora i nie są gwarantowane.", style = Spectra.type.caption, color = c.textSecondary)
                    }
                }
                if (!assetId.isNative) {
                    Spacer(Modifier.height(12.dp))
                    GlassCard(Modifier.fillMaxWidth()) {
                        KeyValueRow("Standard", when (assetId.kind) { app.spectra.core.model.AssetKind.ERC20 -> "ERC-20"; app.spectra.core.model.AssetKind.SPL_TOKEN_2022 -> "Token-2022"; else -> "SPL" })
                        KeyValueRow("Kontrakt / mint", Fmt.shortAddress(assetId.address ?: "", 6, 6), onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(assetId.network.explorerAddressUrl(assetId.address!!))))
                        }, trailingIcon = SpectraIcons.External)
                        KeyValueRow("Weryfikacja", if (token.verified) "zweryfikowany przez katalog" else "niezweryfikowany", if (token.verified) c.positive else c.warning)
                        token.supportNote?.let { Text(it, style = Spectra.type.caption, color = c.warning) }
                        LinkButton(if (row?.hidden == true) "Pokaż token" else "Ukryj token", { scope.launch { container.portfolio.setHidden(assetId.key, row?.hidden != true); if (row?.hidden != true) onBack() } })
                    }
                    if (token.possibleSpam) { Spacer(Modifier.height(12.dp)); NoticeCard("Możliwy spam", "Nazwa tokena wygląda podejrzanie. Nie odwiedzaj stron wymienionych w nazwie i nie podpisuj z nimi transakcji.", Tone.WARNING, SpectraIcons.Alert) }
                }
                Spacer(Modifier.height(16.dp))
                Text("Aktywność", style = Spectra.type.bodyStrong, color = c.text)
                val related = txs.filter { it.networkId == assetId.network.id && it.accountId in s.scopeIds && (it.assetKey == assetId.key || (assetId.isNative && it.assetKey == null && it.symbol == token.symbol)) }.take(10)
                if (related.isEmpty()) EmptyState("Brak transakcji", "Historia tego aktywa pojawi się tutaj.", SpectraIcons.History)
                related.forEach { TxRow(it, s.prefs.hideBalances) { onTx(it.id) } }
                Spacer(Modifier.height(12.dp))
            }
            Row(Modifier.fillMaxWidth().padding(16.dp).glassSurface(RoundedCornerShape(32.dp)).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.weight(1f)) { PrimaryButton("Wyślij", onSend, icon = SpectraIcons.Send) }
                Box(Modifier.weight(1f)) { SecondaryButton("Odbierz", onReceive, icon = SpectraIcons.Receive) }
            }
        }
    }
}
