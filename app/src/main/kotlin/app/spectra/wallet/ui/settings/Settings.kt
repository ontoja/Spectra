package app.spectra.wallet.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import app.spectra.core.crypto.SecureMemory
import app.spectra.core.market.PriceService
import app.spectra.core.model.Chain
import app.spectra.core.model.Network
import app.spectra.core.model.NetworkEnv
import app.spectra.core.rpc.EndpointKind
import app.spectra.core.rpc.EndpointRole
import app.spectra.core.rpc.RpcEndpoint
import app.spectra.wallet.BuildConfig
import app.spectra.wallet.data.AutoLock
import app.spectra.wallet.data.RpcEndpointEntity
import app.spectra.wallet.domain.toEndpoint
import app.spectra.wallet.security.DeviceIntegrity
import app.spectra.wallet.security.KeystoreBackend
import app.spectra.wallet.security.UnlockResult
import app.spectra.wallet.ui.MainViewModel
import app.spectra.wallet.ui.common.BiometricAvailability
import app.spectra.wallet.ui.common.BiometricResult
import app.spectra.wallet.ui.common.LocalContainer
import app.spectra.wallet.ui.common.SecureScreen
import app.spectra.wallet.ui.common.authenticateBiometric
import app.spectra.wallet.ui.common.biometricAvailability
import app.spectra.wallet.ui.common.findActivity
import app.spectra.wallet.ui.common.humanMessage
import app.spectra.wallet.ui.components.AuroraBackground
import app.spectra.wallet.ui.components.AuroraPreset
import app.spectra.wallet.ui.components.Chip
import app.spectra.wallet.ui.components.GlassCard
import app.spectra.wallet.ui.components.GlassTextField
import app.spectra.wallet.ui.components.Hairline
import app.spectra.wallet.ui.components.KeypadKey
import app.spectra.wallet.ui.components.LinkButton
import app.spectra.wallet.ui.components.NoticeCard
import app.spectra.wallet.ui.components.NumericKeypad
import app.spectra.wallet.ui.components.PinDots
import app.spectra.wallet.ui.components.PinState
import app.spectra.wallet.ui.components.PrimaryButton
import app.spectra.wallet.ui.components.SectionLabel
import app.spectra.wallet.ui.components.SettingRow
import app.spectra.wallet.ui.components.SpectraIcons
import app.spectra.wallet.ui.components.SpectraSheet
import app.spectra.wallet.ui.components.SpectraSwitch
import app.spectra.wallet.ui.components.StatusPill
import app.spectra.wallet.ui.components.Tone
import app.spectra.wallet.ui.components.TopBar
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
import app.spectra.wallet.ui.theme.ThemeMode
import kotlinx.coroutines.launch
import java.util.UUID

enum class SettingsPage(val title: String) {
    ACCOUNTS("Konta i portfele"), SECURITY("Bezpieczeństwo"), CHANGE_PIN("Zmień PIN"), PHRASE("Fraza odzyskiwania"), CURRENCY("Waluta"),
    APPEARANCE("Wygląd"), RPC("Węzły RPC"), TOKENS("Tokeny"), PRIVACY("Prywatność"), ABOUT("O Spectra Wallet")
}

@Composable
private fun Card(index: Int = 0, content: @Composable ColumnScope.() -> Unit) =
    GlassCard(Modifier.fillMaxWidth().enterAnimation(index).padding(bottom = 12.dp), padding = PaddingValues(6.dp), content = content)

@Composable
fun SettingsScreen(main: MainViewModel, onOpen: (SettingsPage, String) -> Unit, onImport: () -> Unit) {
    val session by main.session.collectAsState()
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val s = session ?: return
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 120.dp)) {
        Text("Profil", style = Spectra.type.title, color = Spectra.colors.text, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(12.dp))
        NoticeCard("Wersja eksperymentalna", "Spectra 0.1 nie przeszła niezależnego audytu bezpieczeństwa. Nie przechowuj tu znaczących środków." +
            if (!BuildConfig.ALLOW_MAINNET_SIGNING) " Podpisywanie w sieci głównej jest wyłączone." else "", Tone.WARNING, SpectraIcons.ShieldAlert)
        Spacer(Modifier.height(12.dp))
        SectionLabel("Konto")
        Card(1) {
            SettingRow("Konta i portfele", "${s.accounts.size} kont · ${s.accounts.map { it.vault.id }.distinct().size} fraz", SpectraIcons.Profile, { onOpen(SettingsPage.ACCOUNTS, "") })
            SettingRow("Importuj lub utwórz portfel", "Dodaj kolejną frazę odzyskiwania", SpectraIcons.Plus, onImport)
            SettingRow("Tokeny", "Dodaj token, ukryte i spam", SpectraIcons.Coins, { onOpen(SettingsPage.TOKENS, "") })
        }
        SectionLabel("Bezpieczeństwo")
        Card(2) {
            SettingRow("Bezpieczeństwo", "Blokada: ${s.prefs.autoLock.label} · biometria · PIN", SpectraIcons.Lock, { onOpen(SettingsPage.SECURITY, "") })
            SettingRow("Fraza odzyskiwania", "Wymaga potwierdzenia PIN-em", SpectraIcons.Key, { onOpen(SettingsPage.ACCOUNTS, "") })
        }
        SectionLabel("Preferencje")
        Card(3) {
            SettingRow("Waluta", s.prefs.currency, SpectraIcons.Coins, { onOpen(SettingsPage.CURRENCY, "") })
            SettingRow("Wygląd", when (s.prefs.theme) { ThemeMode.LIGHT -> "Jasny"; ThemeMode.DARK -> "Ciemny"; ThemeMode.SYSTEM -> "Systemowy" }, SpectraIcons.Moon, { onOpen(SettingsPage.APPEARANCE, "") })
            SettingRow("Ukryj salda", null, SpectraIcons.EyeOff, trailing = { SpectraSwitch(s.prefs.hideBalances, { main.toggleHide() }, description = "Ukryj salda") })
            SettingRow("Saldo łączne", "Sumuj wszystkie konta", SpectraIcons.Vault, trailing = { SpectraSwitch(s.prefs.aggregatePortfolio, { main.setAggregate(it) }, description = "Saldo łączne") })
        }
        SectionLabel("Sieci")
        Card(4) {
            SettingRow("Węzły RPC", "Główne, zapasowe i własne punkty końcowe", SpectraIcons.Server, { onOpen(SettingsPage.RPC, "") })
            SettingRow("Sieci testowe", "Devnet, Sepolia, Litecoin testnet", SpectraIcons.Globe, trailing = {
                SpectraSwitch(s.prefs.testnetsEnabled, { v -> scope.launch { c.prefs.setTestnetsEnabled(v) } }, description = "Sieci testowe")
            })
            if (s.prefs.testnetsEnabled) SettingRow("Pokazuj sieci testowe", "Przełącz widok portfela", SpectraIcons.Globe, trailing = {
                SpectraSwitch(s.prefs.viewTestnet, { main.setViewTestnet(it) }, description = "Pokazuj sieci testowe")
            })
        }
        SectionLabel("Informacje")
        Card(5) {
            SettingRow("Prywatność", "Co widzą dostawcy sieci", SpectraIcons.Shield, { onOpen(SettingsPage.PRIVACY, "") })
            SettingRow("O Spectra Wallet", "Wersja ${BuildConfig.VERSION_NAME}", SpectraIcons.Info, { onOpen(SettingsPage.ABOUT, "") })
        }
    }
}

@Composable
fun SettingsSubScreen(main: MainViewModel, page: SettingsPage, arg: String, onBack: () -> Unit, onOpen: (SettingsPage, String) -> Unit) {
    AuroraBackground(AuroraPreset.DETAIL) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
            TopBar(page.title, onBack = onBack)
            Spacer(Modifier.height(16.dp))
            when (page) {
                SettingsPage.ACCOUNTS -> AccountsPage(main, onOpen, onBack)
                SettingsPage.SECURITY -> SecurityPage(main, onOpen)
                SettingsPage.CHANGE_PIN -> ChangePinPage(onBack)
                SettingsPage.PHRASE -> PhrasePage(arg, onBack)
                SettingsPage.CURRENCY -> OptionsPage(PriceService.SUPPORTED_CURRENCIES, main.session.collectAsState().value?.prefs?.currency) { c, v -> c.prefs.setCurrency(v); runCatching { c.portfolio.refreshPrices() } }
                SettingsPage.APPEARANCE -> {
                    val cur = main.session.collectAsState().value?.prefs?.theme
                    OptionsPage(listOf("Jasny", "Ciemny", "Systemowy"), when (cur) { ThemeMode.LIGHT -> "Jasny"; ThemeMode.DARK -> "Ciemny"; else -> "Systemowy" }) { c, v ->
                        c.prefs.setTheme(when (v) { "Jasny" -> ThemeMode.LIGHT; "Ciemny" -> ThemeMode.DARK; else -> ThemeMode.SYSTEM })
                    }
                }
                SettingsPage.RPC -> RpcPage()
                SettingsPage.TOKENS -> TokensPage(main)
                SettingsPage.PRIVACY -> PrivacyContent()
                SettingsPage.ABOUT -> AboutContent()
            }
        }
    }
}

@Composable
private fun OptionsPage(options: List<String>, selected: String?, onSelect: suspend (app.spectra.wallet.domain.AppContainer, String) -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    Card {
        options.forEachIndexed { i, o ->
            if (i > 0) Hairline()
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(16.dp)).clickable(role = Role.RadioButton) {
                scope.launch { onSelect(c, o) }
            }.semantics { stateDescription = if (o == selected) "Wybrano" else "" }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(o, style = Spectra.type.bodyMedium, color = Spectra.colors.text, modifier = Modifier.weight(1f))
                if (o == selected) Box(Modifier.size(20.dp).clip(CircleShape).background(Spectra.colors.primary))
                else Box(Modifier.size(20.dp).clip(CircleShape).border(2.dp, Spectra.colors.text.copy(alpha = .3f), CircleShape))
            }
        }
    }
}

// ---------------- Accounts ----------------

@Composable
private fun AccountsPage(main: MainViewModel, onOpen: (SettingsPage, String) -> Unit, onBack: () -> Unit) {
    val c = LocalContainer.current
    val session by main.session.collectAsState()
    val scope = rememberCoroutineScope()
    val s = session ?: return
    var renaming by remember { mutableStateOf<Pair<String, String>?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    s.accounts.groupBy { it.vault }.forEach { (vault, accs) ->
        SectionLabel(vault.name + if (vault.imported) " · importowany" else "")
        Card {
            accs.forEach { a ->
                SettingRow(a.account.name, "#${a.account.accountIndex} · ${Fmt.shortAddress(a.view(Network(Chain.SOLANA, NetworkEnv.MAINNET))?.primaryAddress ?: "")}", SpectraIcons.Profile,
                    { renaming = a.account.id to a.account.name })
            }
            Hairline()
            SettingRow("Dodaj konto do tej frazy", null, SpectraIcons.Plus, {
                scope.launch { runCatching { c.wallets.addAccount(vault.id) }.onFailure { error = it.humanMessage() } }
            })
            SettingRow("Pokaż frazę odzyskiwania", "Wymaga potwierdzenia", SpectraIcons.Key, { onOpen(SettingsPage.PHRASE, vault.id) })
            Text("Ścieżki: SOL ${vault.schemeSolana} · ETH ${vault.schemeEthereum} · LTC ${vault.schemeLitecoin}", style = Spectra.type.caption, color = Spectra.colors.textSecondary, modifier = Modifier.padding(10.dp))
            SettingRow("Usuń ten portfel z telefonu", null, SpectraIcons.Trash, { removing = vault.id }, destructive = true)
        }
    }
    error?.let { NoticeCard("Błąd", it, Tone.NEGATIVE, SpectraIcons.Alert) }
    renaming?.let { (id, name) ->
        var text by remember(id) { mutableStateOf(name) }
        SpectraSheet({ renaming = null }) {
            Text("Zmień nazwę konta", style = Spectra.type.headline, color = Spectra.colors.text)
            Spacer(Modifier.height(12.dp))
            GlassTextField(text, { text = it.take(32) }, "Nazwa konta")
            Spacer(Modifier.height(12.dp))
            PrimaryButton("Zapisz", { scope.launch { c.wallets.rename(id, text); renaming = null } })
        }
    }
    removing?.let { vid ->
        val last = s.accounts.map { it.vault.id }.distinct().size == 1
        var typed by remember(vid) { mutableStateOf("") }
        SpectraSheet({ removing = null }) {
            Text("Usuń portfel z telefonu", style = Spectra.type.headline, color = Spectra.colors.text)
            Spacer(Modifier.height(10.dp))
            NoticeCard(if (last) "To twój ostatni portfel" else "Tej operacji nie można cofnąć",
                "Zaszyfrowana fraza zostanie usunięta z urządzenia. Środki pozostaną w sieci, ale dostęp do nich odzyskasz wyłącznie frazą odzyskiwania." +
                    if (last) " Usunięte zostaną też PIN, klucze urządzenia i wszystkie dane aplikacji." else "", Tone.NEGATIVE)
            Spacer(Modifier.height(12.dp))
            Text("Wpisz USUŃ, aby potwierdzić", style = Spectra.type.label, color = Spectra.colors.text)
            Spacer(Modifier.height(6.dp))
            GlassTextField(typed, { typed = it }, "USUŃ")
            Spacer(Modifier.height(12.dp))
            PrimaryButton("Usuń portfel", {
                scope.launch {
                    // Deleting requires fresh authorisation like other sensitive actions.
                    val dek = c.auth.authorize("Usuń portfel", "Potwierdź usunięcie portfela z tego telefonu.")
                    if (dek != null) { SecureMemory.wipe(dek); c.wallets.removeVault(vid); removing = null; if (!last) onBack() }
                }
            }, enabled = typed.trim().uppercase() == "USUŃ", destructive = true)
        }
    }
}

@Composable
private fun PhrasePage(vaultId: String, onBack: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var words by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    SecureScreen()
    DisposableEffect(Unit) { onDispose { words = null } }
    LaunchedEffect(vaultId) {
        runCatching { c.auth.revealMnemonic(vaultId) }.onSuccess { b ->
            if (b == null) onBack() else { words = String(b, Charsets.UTF_8).split(" "); SecureMemory.wipe(b) }
        }.onFailure { error = it.message ?: "Nie udało się odszyfrować frazy" }
    }
    NoticeCard("Nigdy nikomu jej nie podawaj", "Ekran jest chroniony przed zrzutami. Fraza nie jest kopiowana do schowka.", Tone.NEGATIVE)
    Spacer(Modifier.height(12.dp))
    error?.let { NoticeCard("Dane portfela są uszkodzone", "$it. Jeśli masz zapisaną frazę, usuń portfel i zaimportuj go ponownie.", Tone.NEGATIVE, SpectraIcons.Alert) }
    words?.let { w ->
        GlassCard(Modifier.fillMaxWidth()) {
            w.chunked(3).forEachIndexed { r, chunk ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    chunk.forEachIndexed { i, word ->
                        Row(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(14.dp)).background(Spectra.colors.glassHighlight).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${r * 3 + i + 1}", style = Spectra.type.micro, color = Spectra.colors.textSecondary, modifier = Modifier.width(20.dp))
                            Text(word, style = Spectra.type.label, color = Spectra.colors.text)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton("Ukryj", { words = null; onBack() })
        LaunchedEffect(Unit) { scope.launch { c.wallets.markBackupConfirmed(vaultId) } }
    }
}

// ---------------- Security ----------------

@Composable
private fun SecurityPage(main: MainViewModel, onOpen: (SettingsPage, String) -> Unit) {
    val c = LocalContainer.current
    val session by main.session.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context.findActivity() as? FragmentActivity
    val availability = remember { biometricAvailability(context) }
    var bioEnabled by remember { mutableStateOf(c.security.biometricEnabled()) }
    var message by remember { mutableStateOf<String?>(null) }
    val s = session ?: return
    SectionLabel("Automatyczna blokada")
    Card {
        AutoLock.entries.forEachIndexed { i, a ->
            if (i > 0) Hairline()
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(16.dp)).clickable(role = Role.RadioButton) { scope.launch { c.prefs.setAutoLock(a) } }
                .semantics { stateDescription = if (a == s.prefs.autoLock) "Wybrano" else "" }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(a.label + if (a == AutoLock.M1) " (domyślnie)" else "", style = Spectra.type.bodyMedium, color = Spectra.colors.text, modifier = Modifier.weight(1f))
                if (a == s.prefs.autoLock) Box(Modifier.size(20.dp).clip(CircleShape).background(Spectra.colors.primary))
            }
        }
    }
    Text("Odsłonięcie frazy i każda transakcja zawsze wymagają ponownego potwierdzenia, niezależnie od tego ustawienia.", style = Spectra.type.caption, color = Spectra.colors.textSecondary, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
    SectionLabel("Uwierzytelnianie")
    Card {
        SettingRow("Biometria", when (availability) {
            BiometricAvailability.AVAILABLE -> if (bioEnabled) "Włączona (klasa Strong)" else "Wyłączona"
            BiometricAvailability.NONE_ENROLLED -> "Brak zapisanych odcisków/twarzy w telefonie"
            else -> "Niedostępna na tym urządzeniu"
        }, SpectraIcons.Fingerprint, trailing = {
            SpectraSwitch(bioEnabled, { on ->
                scope.launch {
                    if (!on) { c.security.disableBiometrics(); bioEnabled = false; return@launch }
                    val act = activity ?: return@launch
                    val dek = c.auth.authorize("Włącz biometrię", "Najpierw potwierdź PIN-em.") ?: return@launch
                    try {
                        when (val prep = c.security.prepareBiometricEnable()) {
                            is KeystoreBackend.BioCipher.Ready -> when (val r = act.authenticateBiometric("Włącz biometrię", null, prep.cipher)) {
                                is BiometricResult.Success -> { c.security.completeBiometricEnable(r.cipher, dek); bioEnabled = true; message = null }
                                is BiometricResult.LockedOut -> message = "Biometria zablokowana — spróbuj później."
                                is BiometricResult.Error -> message = r.message
                                BiometricResult.Cancelled -> Unit
                            }
                            else -> message = "Nie udało się utworzyć klucza biometrycznego."
                        }
                    } finally { SecureMemory.wipe(dek) }
                }
            }, enabled = availability == BiometricAvailability.AVAILABLE, description = "Biometria")
        })
        SettingRow("Zmień PIN", null, SpectraIcons.Lock, { onOpen(SettingsPage.CHANGE_PIN, "") })
        SettingRow("Blokuj zrzuty ekranu wszędzie", "Ekrany z frazą są chronione zawsze", SpectraIcons.EyeOff, trailing = {
            SpectraSwitch(s.prefs.blockScreenshotsEverywhere, { v -> scope.launch { c.prefs.setBlockScreenshots(v) } }, description = "Blokuj zrzuty ekranu")
        })
    }
    message?.let { NoticeCard("Biometria", it, Tone.WARNING, SpectraIcons.Alert) }
    SectionLabel("Urządzenie")
    val root = remember { DeviceIntegrity.rootIndicators() }
    Card {
        SettingRow("Integralność systemu", if (root.isEmpty()) "Nie wykryto oznak roota (sprawdzenie heurystyczne)" else "Wykryto: ${root.joinToString()}", SpectraIcons.Shield)
        SettingRow("Kopie zapasowe", "Dane portfela są wyłączone z kopii Androida i transferu między urządzeniami", SpectraIcons.Vault)
    }
}

@Composable
private fun ChangePinPage(onBack: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf(0) }
    var entry by remember { mutableStateOf("") }
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    SecureScreen()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(listOf("Wpisz obecny PIN", "Nowy PIN", "Powtórz nowy PIN")[stage], style = Spectra.type.headline, color = Spectra.colors.text)
        Spacer(Modifier.height(20.dp))
        PinDots(entry.length, if (msg != null) PinState.ERROR else PinState.IDLE)
        Box(Modifier.heightIn(min = 40.dp).padding(top = 10.dp)) { msg?.let { Text(it, style = Spectra.type.label, color = Spectra.colors.negative) } }
        Spacer(Modifier.height(16.dp))
        NumericKeypad({ k ->
            if (busy) return@NumericKeypad
            when (k) {
                is KeypadKey.Digit -> if (entry.length < 6) { entry += k.d; msg = null }
                KeypadKey.Delete -> entry = entry.dropLast(1)
                else -> Unit
            }
            if (entry.length == 6) {
                when (stage) {
                    0 -> { old = entry; entry = ""; stage = 1 }
                    1 -> { if (entry.all { it == entry[0] } || "0123456789".contains(entry)) { msg = "Zbyt prosty PIN"; entry = "" } else { new = entry; entry = ""; stage = 2 } }
                    2 -> if (entry != new) { msg = "PIN-y nie są zgodne"; entry = ""; stage = 1 } else {
                        busy = true
                        scope.launch {
                            val o = old.toCharArray(); val n = new.toCharArray()
                            when (val r = c.security.changePin(o, n)) {
                                is UnlockResult.Success -> onBack()
                                is UnlockResult.WrongPin -> { msg = "Obecny PIN jest nieprawidłowy"; stage = 0 }
                                is UnlockResult.LockedOut -> { msg = "Zbyt wiele prób. Spróbuj później."; stage = 0 }
                                else -> { msg = "Nie udało się zmienić PIN-u"; stage = 0 }
                            }
                            SecureMemory.wipe(o); SecureMemory.wipe(n); old = ""; new = ""; entry = ""; busy = false
                        }
                    }
                }
            }
        })
    }
}

// ---------------- RPC ----------------

@Composable
private fun RpcPage() {
    val c = LocalContainer.current
    val endpoints by c.db.rpc().all().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var addFor by remember { mutableStateOf<Network?>(null) }
    var tick by remember { mutableStateOf(0) }
    NoticeCard("Co otrzymuje dostawca RPC", "Twoje publiczne adresy, zapytania o salda i adres IP. Nigdy klucze ani frazę. Własny węzeł RPC nie zmienia źródła cen (CoinGecko) ani indeksera tokenów/historii.", Tone.PRIMARY, SpectraIcons.Info)
    Spacer(Modifier.height(12.dp))
    Network.all(true).forEach { n ->
        SectionLabel(n.displayName)
        Card {
            endpoints.filter { it.networkId == n.id }.forEach { e ->
                val h = remember(tick, e.id) { c.rpc.health(e.id) }
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.url.removePrefix("https://"), style = Spectra.type.mono, color = Spectra.colors.text)
                        Text("${when (EndpointRole.valueOf(e.role)) { EndpointRole.PRIMARY -> "główny"; EndpointRole.FALLBACK -> "zapasowy"; EndpointRole.CUSTOM -> "własny" }} · ${if (e.kind == EndpointKind.INDEXER.name) "indekser" else "węzeł"}" +
                            (h.latencyMs?.let { " · ${it} ms" } ?: ""), style = Spectra.type.caption, color = Spectra.colors.textSecondary)
                    }
                    StatusPill(h.state.name.lowercase(), when (h.state.name) { "HEALTHY" -> Tone.POSITIVE; "WRONG_CHAIN", "UNREACHABLE" -> Tone.NEGATIVE; "UNKNOWN" -> Tone.NEUTRAL; else -> Tone.WARNING }, dot = false)
                    Spacer(Modifier.width(6.dp))
                    SpectraSwitch(e.enabled, { v -> scope.launch { c.db.rpc().upsert(e.copy(enabled = v)) } }, description = "Włącz ${e.url}")
                }
                if (!e.builtIn) LinkButton("Usuń", { scope.launch { c.db.rpc().deleteCustom(e.id) } })
            }
            Row(Modifier.fillMaxWidth()) {
                LinkButton("Dodaj własny", { addFor = n }, icon = SpectraIcons.Plus)
                LinkButton("Sprawdź", {
                    scope.launch { endpoints.filter { it.networkId == n.id && it.kind == EndpointKind.NODE.name }.forEach { e -> c.rpc.resetHealth(e.id); runCatching { c.rpc.ensureIdentity(e.toEndpoint()) } }; tick++ }
                }, icon = SpectraIcons.Refresh)
            }
        }
    }
    SectionLabel("Ceny")
    Card {
        val key by c.prefs.flow.collectAsState(initial = null)
        var k by remember(key?.coingeckoApiKey) { mutableStateOf(key?.coingeckoApiKey ?: "") }
        Text("Opcjonalny klucz CoinGecko Demo — zmniejsza ograniczenia publicznego API. Przechowywany lokalnie.", style = Spectra.type.caption, color = Spectra.colors.textSecondary, modifier = Modifier.padding(10.dp))
        GlassTextField(k, { k = it }, "Klucz API (opcjonalnie)", sensitive = true)
        LinkButton("Zapisz", { scope.launch { c.prefs.setCoingeckoKey(k) } })
    }
    addFor?.let { n ->
        var url by remember(n) { mutableStateOf("https://") }
        var err by remember(n) { mutableStateOf<String?>(null) }
        var busy by remember(n) { mutableStateOf(false) }
        SpectraSheet({ addFor = null }) {
            Text("Własny węzeł · ${n.displayName}", style = Spectra.type.headline, color = Spectra.colors.text)
            Spacer(Modifier.height(10.dp))
            Text(if (n.chain == Chain.LITECOIN) "Adres API zgodnego z Esplora (np. …/api)." else "Adres JSON-RPC (HTTPS).", style = Spectra.type.caption, color = Spectra.colors.textSecondary)
            Spacer(Modifier.height(8.dp))
            GlassTextField(url, { url = it.trim() }, "https://…", sensitive = true)
            err?.let { Text(it, style = Spectra.type.caption, color = Spectra.colors.negative) }
            Spacer(Modifier.height(12.dp))
            PrimaryButton("Sprawdź i dodaj", {
                if (!url.startsWith("https://") || url.length < 12) { err = "Wymagany adres HTTPS"; return@PrimaryButton }
                busy = true
                scope.launch {
                    val e = RpcEndpointEntity("custom-" + UUID.randomUUID(), n.id, url.trimEnd('/'), EndpointRole.CUSTOM.name, EndpointKind.NODE.name, true, builtIn = false)
                    // Verify chain identity (genesis hash / chain id) before saving; mismatched endpoints are refused.
                    runCatching { c.rpc.ensureIdentity(e.toEndpoint()) }
                        .onSuccess { c.db.rpc().upsert(e); addFor = null }
                        .onFailure { err = it.humanMessage() }
                    busy = false
                }
            }, loading = busy)
        }
    }
}

// ---------------- Tokens ----------------

@Composable
private fun TokensPage(main: MainViewModel) {
    val c = LocalContainer.current
    val session by main.session.collectAsState()
    val tokens by c.db.portfolio().tokens().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val env = session?.env ?: NetworkEnv.MAINNET
    var chain by remember { mutableStateOf(Chain.SOLANA) }
    var address by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var busy by remember { mutableStateOf(false) }
    SectionLabel("Dodaj token ręcznie")
    Card {
        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Chain.SOLANA, Chain.ETHEREUM).forEach { ch -> Chip(Network(ch, env).displayName, ch == chain, { chain = ch }, leading = { app.spectra.wallet.ui.components.ChainLogo(ch, 20.dp) }) }
        }
        GlassTextField(address, { address = it.trim() }, if (chain == Chain.SOLANA) "Adres mint (SPL / Token-2022)" else "Adres kontraktu ERC-20", sensitive = true)
        Spacer(Modifier.height(8.dp))
        PrimaryButton("Sprawdź i dodaj", {
            busy = true
            scope.launch {
                runCatching { c.portfolio.addCustomToken(Network(chain, env), address) }
                    .onSuccess { t -> msg = "Dodano ${t.symbol} (${t.decimals} miejsc dziesiętnych)" + (t.supportNote?.let { " — $it" } ?: "") to true; address = ""; main.refresh() }
                    .onFailure { msg = it.humanMessage() to false }
                busy = false
            }
        }, enabled = address.length > 30, loading = busy)
        msg?.let { (m, ok) -> Text(m, style = Spectra.type.caption, color = if (ok) Spectra.colors.positive else Spectra.colors.negative, modifier = Modifier.padding(8.dp)) }
        Text("Sieć, adres, liczba miejsc dziesiętnych i standard są weryfikowane w łańcuchu. Nazwy i symbole pochodzą z niezaufanych źródeł.", style = Spectra.type.caption, color = Spectra.colors.textSecondary, modifier = Modifier.padding(8.dp))
    }
    SectionLabel("Tokeny w portfelu")
    Card {
        tokens.filter { Network.fromId(it.networkId).env == env }.forEach { t ->
            SettingRow(t.symbol + if (t.possibleSpam) " · możliwy spam" else "", "${Network.fromId(t.networkId).displayName} · ${Fmt.shortAddress(t.address ?: "", 6, 4)}", null, trailing = {
                SpectraSwitch(!t.hidden, { v -> scope.launch { c.portfolio.setHidden(t.assetKey, !v) } }, description = "Pokaż ${t.symbol}")
            })
        }
    }
}

// ---------------- Privacy & About ----------------

@Composable
fun PrivacyContent() {
    val items = listOf(
        "Bez kont" to "Spectra nie wymaga rejestracji, e-maila, numeru telefonu ani identyfikatora. Nie ma serwera Spectra.",
        "Sekrety zostają w telefonie" to "Fraza i klucze są zaszyfrowane (AES-256-GCM) kluczem powiązanym z magazynem kluczy Androida i Twoim PIN-em. Nie trafiają do kopii zapasowych, logów ani raportów błędów.",
        "Co widzą dostawcy" to "Węzły RPC (Solana, Ethereum), indeksery (Blockscout, litecoinspace.org), CoinGecko, Jupiter i LI.FI widzą Twój adres IP oraz publiczne adresy, o które pytamy. Mogą je powiązać ze sobą. Spectra nie zapewnia anonimowości — rozważ VPN lub własny węzeł.",
        "Brak telemetrii" to "Aplikacja nie zawiera analityki, reklam ani SDK śledzących.",
        "Loga tokenów" to "Obrazy tokenów nie są pobierane, aby nie ujawniać dodatkowych informacji zewnętrznym serwerom.",
    )
    items.forEach { (t, b) -> NoticeCard(t, b, Tone.NEUTRAL, SpectraIcons.Shield); Spacer(Modifier.height(10.dp)) }
}

@Composable
fun PrivacySheet(onDismiss: () -> Unit) = SpectraSheet(onDismiss) {
    Text("Prywatność i ograniczenia", style = Spectra.type.headline, color = Spectra.colors.text)
    Spacer(Modifier.height(12.dp))
    PrivacyContent()
}

@Composable
private fun AboutContent() {
    GlassCard(Modifier.fillMaxWidth()) {
        Text("Spectra Wallet ${BuildConfig.VERSION_NAME}", style = Spectra.type.bodyStrong, color = Spectra.colors.text)
        Spacer(Modifier.height(6.dp))
        Text("Niekustodialny portfel dla Solany, Ethereum i Litecoina.", style = Spectra.type.body, color = Spectra.colors.textSecondary)
    }
    Spacer(Modifier.height(12.dp))
    NoticeCard("Status bezpieczeństwa", "Nie przeprowadzono niezależnego audytu bezpieczeństwa. Kryptografia opiera się na BouncyCastle, a kodowanie protokołów jest testowane wektorami BIP/SLIP/EIP i porównywane z niezależnymi implementacjami. " +
        "Podpisywanie w sieci głównej: ${if (BuildConfig.ALLOW_MAINNET_SIGNING) "WŁĄCZONE (kompilacja deweloperska)" else "wyłączone"}.", Tone.WARNING, SpectraIcons.ShieldAlert)
    Spacer(Modifier.height(12.dp))
    GlassCard(Modifier.fillMaxWidth()) {
        Text("Licencje", style = Spectra.type.bodyStrong, color = Spectra.colors.text)
        listOf("Instrument Sans — SIL Open Font License 1.1", "Bouncy Castle — MIT", "OkHttp, ZXing, AndroidX, Kotlin — Apache 2.0", "Lista słów BIP-39 — bitcoin/bips (BSD-2)").forEach {
            Text("• $it", style = Spectra.type.caption, color = Spectra.colors.textSecondary)
        }
    }
}
