package app.spectra.wallet.ui.home

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.spectra.core.model.Chain
import app.spectra.core.model.Network
import app.spectra.core.model.NetworkEnv
import app.spectra.core.model.TxState
import app.spectra.wallet.domain.AssetRow
import app.spectra.wallet.domain.PortfolioRepository
import app.spectra.wallet.ui.MainViewModel
import app.spectra.wallet.ui.common.LocalContainer
import app.spectra.wallet.ui.components.AssetAvatar
import app.spectra.wallet.ui.components.EmptyState
import app.spectra.wallet.ui.components.GlassCard
import app.spectra.wallet.ui.components.GlassIconButton
import app.spectra.wallet.ui.components.Hairline
import app.spectra.wallet.ui.components.LinkButton
import app.spectra.wallet.ui.components.NoticeCard
import app.spectra.wallet.ui.components.PrimaryButton
import app.spectra.wallet.ui.components.SegmentedTabs
import app.spectra.wallet.ui.components.SpectraIcons
import app.spectra.wallet.ui.components.SpectraSheet
import app.spectra.wallet.ui.components.SpectraSwitch
import app.spectra.wallet.ui.components.StatusPill
import app.spectra.wallet.ui.components.Tone
import app.spectra.wallet.ui.components.glassSurface
import app.spectra.wallet.ui.format.Fmt
import app.spectra.wallet.ui.lock.AccountAvatar
import app.spectra.wallet.ui.settings.SettingsPage
import app.spectra.wallet.ui.theme.Elevation
import app.spectra.wallet.ui.theme.Radii
import app.spectra.wallet.ui.theme.Spectra
import app.spectra.wallet.ui.components.enterAnimation
import app.spectra.wallet.ui.components.pressScale
import app.spectra.wallet.ui.components.pulse
import app.spectra.wallet.ui.components.Reveal
import app.spectra.wallet.ui.components.SkeletonRow
import app.spectra.wallet.ui.components.animatedNumber
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import java.math.BigDecimal

@Composable
fun ChangeLine(abs: BigDecimal?, pct: Double?, currency: String, hidden: Boolean) {
    if (pct == null) return
    val c = Spectra.colors
    val color = if (pct < 0) c.negative else c.positive
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (pct < 0) SpectraIcons.TrendDown else SpectraIcons.TrendUpSmall, null, Modifier.size(14.dp), tint = color)
        Spacer(Modifier.width(6.dp))
        val absText = abs?.let { if (hidden) Fmt.MASK + " " + Fmt.currencySymbol(currency) else Fmt.signedFiat(it, currency) }
        Text(listOfNotNull(absText, Fmt.percent(pct).removePrefix("+").removePrefix("−") + " dziś").joinToString(" · "), style = Spectra.type.label, color = color)
    }
}

@Composable
fun AssetRowItem(r: AssetRow, currency: String, hidden: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Spectra.colors
    val interaction = remember { MutableInteractionSource() }
    Row(modifier.fillMaxWidth().heightIn(min = 60.dp).pressScale(interaction, 0.98f).clip(RoundedCornerShape(Radii.tile)).clickable(interaction, ripple(), role = Role.Button, onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AssetAvatar(r.token)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.token.name, style = Spectra.type.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (!r.token.id.isNative) { Spacer(Modifier.width(6.dp)); Text(r.token.id.network.chain.symbol, style = Spectra.type.micro, color = c.textSecondary) }
            }
            Text(if (hidden) "${Fmt.MASK} ${r.token.symbol}" else Fmt.amountWithSymbol(r.amount, r.token.symbol) + (r.pending?.let { " (+${Fmt.amount(it)} oczek.)" } ?: ""),
                style = Spectra.type.caption, color = c.textSecondary, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(when { hidden -> Fmt.MASK; r.fiatValue != null -> Fmt.fiat(r.fiatValue, currency); r.token.id.network.isTestnet -> "testnet"; else -> "brak ceny" },
                style = Spectra.type.bodyStrong, color = if (r.fiatValue == null) c.textSecondary else c.text)
            r.change24h?.let { Text(Fmt.percent(it), style = Spectra.type.captionStrong, color = if (it < 0) c.negative else c.positive) }
        }
    }
}

@Composable
private fun QuickAction(label: String, icon: ImageVector, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Spectra.colors
    val interaction = remember { MutableInteractionSource() }
    Column(modifier.pressScale(interaction, 0.9f).clip(RoundedCornerShape(16.dp)).clickable(interaction, null, role = Role.Button, onClick = onClick).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(56.dp).then(if (primary) Modifier.glassSurface(CircleShape).clip(CircleShape).background(c.primary) else Modifier.glassSurface(CircleShape)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(22.dp), tint = if (primary) c.onPrimary else c.text)
        }
        Text(label, style = Spectra.type.captionStrong, color = c.text)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(main: MainViewModel, onAsset: (String) -> Unit, onSend: () -> Unit, onReceive: () -> Unit, onSwap: () -> Unit, onStake: () -> Unit,
               onTx: (String) -> Unit, onImport: () -> Unit, onSettings: (SettingsPage) -> Unit) {
    val container = LocalContainer.current
    val session by main.session.collectAsState()
    val portfolio by main.portfolio.collectAsState()
    val online by main.online.collectAsState()
    val txs by container.transactions.all.collectAsState(initial = emptyList())
    val s = session ?: return
    val c = Spectra.colors
    val currency = s.prefs.currency
    val hidden = s.prefs.hideBalances
    var tab by rememberSaveable { mutableStateOf(0) }
    var switcher by remember { mutableStateOf(false) }
    var notifications by remember { mutableStateOf(false) }
    var buyInfo by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(false) }
    val inFlight = txs.filter { TxState.valueOf(it.state).isInFlight && it.source == "LOCAL" && it.accountId in s.scopeIds }
    val p = portfolio

    PullToRefreshBox(main.refreshing, main::refresh, Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 120.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.clip(CircleShape).clickable(role = Role.Button) { switcher = true }.semantics { contentDescription = "${s.active?.account?.name}, zmień konto" }) { AccountAvatar(44.dp) }
                Row(Modifier.weight(1f).height(44.dp).glassSurface(RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).clickable(role = Role.Button) { search = true }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(SpectraIcons.Search, null, Modifier.size(18.dp), tint = c.textSecondary)
                    Spacer(Modifier.width(10.dp))
                    Text("Szukaj", style = Spectra.type.bodyMedium, color = c.textSecondary)
                }
                Box {
                    GlassIconButton(SpectraIcons.Bell, if (inFlight.isNotEmpty()) "Powiadomienia, ${inFlight.size} nowe" else "Powiadomienia", { notifications = true })
                    if (inFlight.isNotEmpty()) Box(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 11.dp).size(8.dp).pulse(true, 0.4f, 1100).clip(CircleShape).background(c.primary))
                }
            }

            if (s.env == NetworkEnv.TESTNET) { Spacer(Modifier.height(14.dp)); StatusPill("Sieć testowa — środki bez wartości", Tone.WARNING, Modifier.align(Alignment.CenterHorizontally)) }
            if (!online) { Spacer(Modifier.height(10.dp)); StatusPill("Offline — pokazujemy zapisane dane", Tone.NEUTRAL, Modifier.align(Alignment.CenterHorizontally)) }

            // Hero balance
            Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) { main.toggleHide() }
                    .semantics { contentDescription = if (hidden) "Pokaż saldo" else "Ukryj saldo" }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text((if (s.prefs.aggregatePortfolio) "Wszystkie konta" else s.active?.account?.name ?: "") + " · " + currency, style = Spectra.type.label, color = c.textSecondary)
                    Spacer(Modifier.width(6.dp))
                    AnimatedContent(hidden, label = "eye") { h -> Icon(if (h) SpectraIcons.EyeOff else SpectraIcons.Eye, null, Modifier.size(16.dp), tint = c.textSecondary) }
                }
                Spacer(Modifier.height(6.dp))
                if (s.env == NetworkEnv.TESTNET) {
                    Text("—", style = Spectra.type.balanceHero, color = c.text)
                } else {
                    val shown = animatedNumber(p?.totalFiat?.toDouble() ?: 0.0)
                    val (int, dec) = Fmt.fiatParts(BigDecimal.valueOf(shown).setScale(2, java.math.RoundingMode.HALF_UP), currency)
                    val hd = if (Spectra.reducedMotion) 0 else 240
                    AnimatedContent(hidden, transitionSpec = {
                        (fadeIn(tween(hd)) + slideInVertically(tween(hd)) { it / 3 }) togetherWith (fadeOut(tween(hd / 2)) + slideOutVertically(tween(hd)) { -it / 3 })
                    }, label = "hero", modifier = Modifier.enterAnimation(1)) { isHidden ->
                        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.semantics(mergeDescendants = true) { }) {
                            Text(if (isHidden) Fmt.MASK else int, style = Spectra.type.balanceHero, color = c.text)
                            Text(if (isHidden) " " + Fmt.currencySymbol(currency) else dec, style = Spectra.type.balanceHeroDecimals, color = c.text, modifier = Modifier.padding(bottom = 4.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    val total = p?.totalFiat
                    val change = p?.change24hFiat
                    if (change != null && total != null && total.signum() > 0) {
                        val prev = total - change
                        ChangeLine(change, if (prev.signum() != 0) change.toDouble() / prev.toDouble() * 100 else 0.0, currency, hidden)
                    }
                    if ((p?.unpricedAssets ?: 0) > 0) Text("+ ${p!!.unpricedAssets} aktyw. bez ceny (nie wliczone)", style = Spectra.type.caption, color = c.textSecondary, modifier = Modifier.padding(top = 4.dp))
                    val stale = p?.oldestBalanceUpdate?.let { System.currentTimeMillis() - it > PortfolioRepository.STALE_AFTER_MS } == true
                    if (stale || main.priceError != null) Text("Dane nieaktualne · ${p?.oldestBalanceUpdate?.let { Fmt.relativeTime(it) } ?: ""}", style = Spectra.type.caption, color = c.warning, modifier = Modifier.padding(top = 4.dp))
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 32.dp)) {
                QuickAction("Wyślij", SpectraIcons.Send, true, onSend, Modifier.weight(1f).enterAnimation(2))
                QuickAction("Odbierz", SpectraIcons.Receive, false, onReceive, Modifier.weight(1f).enterAnimation(3))
                QuickAction("Wymień", SpectraIcons.Swap, false, onSwap, Modifier.weight(1f).enterAnimation(4))
                QuickAction("Kup", SpectraIcons.Plus, false, { buyInfo = true }, Modifier.weight(1f).enterAnimation(5))
            }

            Reveal(main.refreshErrors.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                NoticeCard("Nie wszystkie sieci odpowiedziały", main.refreshErrors.entries.joinToString("\n") { "${it.key.displayName}: ${it.value}" } + "\nPokazujemy ostatnie zapisane salda.", Tone.WARNING, SpectraIcons.Alert)
            }

            // Assets card
            Column(Modifier.fillMaxWidth().padding(top = 28.dp).enterAnimation(6, distance = 26f).glassSurface(RoundedCornerShape(Radii.cardLarge), elevation = Elevation.card)
                .animateContentSize(app.spectra.wallet.ui.theme.spectraTween(app.spectra.wallet.ui.theme.Motion.STANDARD)).padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 6.dp)) {
                SegmentedTabs(listOf("Aktywa", "Rynek", "Ukryte"), tab, { tab = it })
                Spacer(Modifier.height(4.dp))
                val td = if (Spectra.reducedMotion) 0 else 220
                AnimatedContent(tab, transitionSpec = { fadeIn(tween(td)) togetherWith fadeOut(tween(td / 2)) }, label = "homeTab") { t -> Column {
                when (t) {
                    0 -> {
                        val rows = p?.rows.orEmpty()
                        if (p == null || (rows.isEmpty() && main.refreshing)) { SkeletonRow(); SkeletonRow() }
                        else if (rows.isEmpty()) EmptyState("Brak aktywów", "Odbierz środki, aby zobaczyć je tutaj.", SpectraIcons.Vault)
                        else rows.forEachIndexed { i, r -> AssetRowItem(r, currency, hidden, Modifier.enterAnimation(i, key = r.token.id.key)) { onAsset(r.token.id.key) } }
                    }
                    1 -> MarketTab(main)
                    else -> HiddenTab(main, onAsset)
                }
                } }
            }

            val stakeInteraction = remember { MutableInteractionSource() }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp).enterAnimation(7, distance = 26f).pressScale(stakeInteraction, 0.98f).glassSurface(RoundedCornerShape(24.dp), elevation = Elevation.card).clip(RoundedCornerShape(24.dp)).clickable(stakeInteraction, ripple(), role = Role.Button, onClick = onStake)
                .padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(c.positiveSoft), contentAlignment = Alignment.Center) { Icon(SpectraIcons.Earn, null, Modifier.size(20.dp), tint = c.positive) }
                Column(Modifier.weight(1f)) {
                    Text("Stakuj SOL", style = Spectra.type.bodyStrong.copy(fontSize = Spectra.type.body.fontSize), color = c.text)
                    Text("Natywny staking · bez gwarancji nagród", style = Spectra.type.caption, color = c.textSecondary)
                }
                Icon(SpectraIcons.Chevron, null, Modifier.size(18.dp), tint = c.textSecondary)
            }
        }
    }

    if (switcher) AccountSwitcherSheet(main, { switcher = false }, onImport, onSettings)
    if (buyInfo) SpectraSheet({ buyInfo = false }) {
        Text("Zakup kryptowalut", style = Spectra.type.headline, color = c.text)
        Spacer(Modifier.height(10.dp))
        Text("Spectra 0.1 nie integruje żadnego dostawcy płatności (on-ramp). Kupno wymaga zewnętrznej usługi z weryfikacją tożsamości, co jest sprzeczne z założeniem braku kont. Możesz kupić środki na giełdzie i wypłacić je na swój adres w zakładce Odbierz.",
            style = Spectra.type.body, color = c.textSecondary)
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Pokaż mój adres", { buyInfo = false; onReceive() })
    }
    if (notifications) SpectraSheet({ notifications = false }) {
        Text("Powiadomienia", style = Spectra.type.headline, color = c.text)
        Spacer(Modifier.height(12.dp))
        if (inFlight.isEmpty()) EmptyState("Nic nowego", "Tu pojawią się transakcje w toku i ważne alerty.", SpectraIcons.Bell)
        inFlight.forEach { t ->
            app.spectra.wallet.ui.components.SettingRow("${t.symbol ?: ""} · ${app.spectra.wallet.ui.activity.stateLabel(TxState.valueOf(t.state))}", Network.fromId(t.networkId).displayName,
                SpectraIcons.History, { notifications = false; onTx(t.id) })
        }
    }
    if (search) AssetSearchSheet(p?.rows.orEmpty(), currency, hidden, { search = false }) { search = false; onAsset(it) }
}

@Composable
private fun MarketTab(main: MainViewModel) {
    val container = LocalContainer.current
    val session by main.session.collectAsState()
    val currency = session?.prefs?.currency ?: "PLN"
    val prices by container.db.portfolio().prices(currency).collectAsState(initial = emptyList())
    val c = Spectra.colors
    Chain.entries.forEachIndexed { idx, ch ->
        val id = app.spectra.core.model.TokenInfo.native(Network(ch, NetworkEnv.MAINNET)).coingeckoId
        val p = prices.firstOrNull { it.priceKey == "cg:$id" }
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).enterAnimation(idx).padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AssetAvatar(ch.symbol, ch, true)
            Column(Modifier.weight(1f)) {
                Text(ch.displayName, style = Spectra.type.bodyStrong, color = c.text)
                Text(ch.symbol, style = Spectra.type.caption, color = c.textSecondary)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(p?.price?.toBigDecimalOrNull()?.let { Fmt.fiat(it, currency) } ?: "brak danych", style = Spectra.type.bodyStrong, color = c.text)
                p?.change24h?.let { Text(Fmt.percent(it), style = Spectra.type.captionStrong, color = if (it < 0) c.negative else c.positive) }
            }
        }
    }
    Text("Ceny orientacyjne z CoinGecko — nie są to kursy wykonania wymiany.", style = Spectra.type.caption, color = c.textSecondary, modifier = Modifier.padding(10.dp))
}

@Composable
private fun HiddenTab(main: MainViewModel, onAsset: (String) -> Unit) {
    val container = LocalContainer.current
    val tokens by container.db.portfolio().tokens().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val session by main.session.collectAsState()
    val env = session?.env ?: NetworkEnv.MAINNET
    val hidden = tokens.filter { it.hidden && Network.fromId(it.networkId).env == env }
    val c = Spectra.colors
    if (hidden.isEmpty()) EmptyState("Brak ukrytych tokenów", "Tokeny oznaczone jako możliwy spam trafiają tutaj automatycznie.", SpectraIcons.EyeOff)
    hidden.forEach { t ->
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.symbol, style = Spectra.type.bodyStrong, color = c.text)
                Text((if (t.possibleSpam) "Możliwy spam · " else "") + Fmt.shortAddress(t.address ?: "", 6, 4), style = Spectra.type.caption, color = c.textSecondary)
            }
            LinkButton("Pokaż", { scope.launch { container.portfolio.setHidden(t.assetKey, false) } })
        }
    }
}

@Composable
private fun AssetSearchSheet(rows: List<AssetRow>, currency: String, hidden: Boolean, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    SpectraSheet(onDismiss) {
        app.spectra.wallet.ui.components.GlassTextField(q, { q = it }, "Szukaj aktywa", label = "Szukaj aktywa")
        Spacer(Modifier.height(12.dp))
        rows.filter { q.isBlank() || it.token.symbol.contains(q, true) || it.token.name.contains(q, true) }.forEach { r ->
            AssetRowItem(r, currency, hidden) { onPick(r.token.id.key) }
        }
    }
}

@Composable
private fun AccountSwitcherSheet(main: MainViewModel, onDismiss: () -> Unit, onImport: () -> Unit, onSettings: (SettingsPage) -> Unit) {
    val session by main.session.collectAsState()
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val s = session ?: return
    val c = Spectra.colors
    var error by remember { mutableStateOf<String?>(null) }
    SpectraSheet(onDismiss) {
        Text("Konta", style = Spectra.type.headline, color = c.text, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(12.dp))
        GlassCard(Modifier.fillMaxWidth(), padding = PaddingValues(6.dp)) {
            s.accounts.forEachIndexed { i, a ->
                if (i > 0) Hairline()
                val active = a.account.id == s.active?.account?.id
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(role = Role.Button) { main.setActive(a.account.id); onDismiss() }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AccountAvatar(40.dp)
                    Column(Modifier.weight(1f)) {
                        Text(a.account.name, style = Spectra.type.bodyStrong, color = c.text)
                        Text("${a.vault.name} · #${a.account.accountIndex} · ${Fmt.shortAddress(a.view(Network(Chain.ETHEREUM, NetworkEnv.MAINNET))?.primaryAddress ?: "", 6, 4)}",
                            style = Spectra.type.caption, color = c.textSecondary)
                    }
                    if (active) Icon(SpectraIcons.Check, "Aktywne", Modifier.size(20.dp), tint = c.primary)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Saldo łączne", style = Spectra.type.bodyStrong, color = c.text)
                Text("Sumuj wszystkie konta na ekranie głównym", style = Spectra.type.caption, color = c.textSecondary)
            }
            SpectraSwitch(s.prefs.aggregatePortfolio, { main.setAggregate(it) }, description = "Saldo łączne")
        }
        if (s.prefs.testnetsEnabled) Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Sieci testowe", style = Spectra.type.bodyStrong, color = c.text)
                Text("Devnet / Sepolia / Litecoin testnet", style = Spectra.type.caption, color = c.textSecondary)
            }
            SpectraSwitch(s.prefs.viewTestnet, { main.setViewTestnet(it) }, description = "Pokaż sieci testowe")
        }
        error?.let { Text(it, style = Spectra.type.caption, color = c.negative, modifier = Modifier.padding(6.dp)) }
        Spacer(Modifier.height(12.dp))
        PrimaryButton("Dodaj konto", {
            scope.launch {
                runCatching { s.active?.vault?.id?.let { container.wallets.addAccount(it) } }
                    .onSuccess { id -> if (id != null) { main.setActive(id); onDismiss() } }
                    .onFailure { error = it.message }
            }
        }, icon = SpectraIcons.Plus)
        Spacer(Modifier.height(8.dp))
        app.spectra.wallet.ui.components.SecondaryButton("Importuj lub utwórz portfel", { onDismiss(); onImport() })
        LinkButton("Zarządzaj kontami", { onDismiss(); onSettings(SettingsPage.ACCOUNTS) }, Modifier.fillMaxWidth())
    }
}
