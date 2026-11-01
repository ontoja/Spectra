package app.spectra.wallet.ui.onboarding

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import app.spectra.core.crypto.Bip39
import app.spectra.core.model.Amount
import app.spectra.core.model.Chain
import app.spectra.core.model.Network
import app.spectra.core.model.NetworkEnv
import app.spectra.wallet.ui.common.BiometricAvailability
import app.spectra.wallet.ui.common.BiometricResult
import app.spectra.wallet.ui.common.SecureScreen
import app.spectra.wallet.ui.common.authenticateBiometric
import app.spectra.wallet.ui.common.biometricAvailability
import app.spectra.wallet.ui.common.findActivity
import app.spectra.wallet.ui.components.AssetAvatar
import app.spectra.wallet.ui.components.AuroraBackground
import app.spectra.wallet.ui.components.AuroraPreset
import app.spectra.wallet.ui.components.CheckRow
import app.spectra.wallet.ui.components.Chip
import app.spectra.wallet.ui.components.GlassCard
import app.spectra.wallet.ui.components.GlassIconButton
import app.spectra.wallet.ui.components.GlassTextField
import app.spectra.wallet.ui.components.Hairline
import app.spectra.wallet.ui.components.KeypadKey
import app.spectra.wallet.ui.components.LinkButton
import app.spectra.wallet.ui.components.LoadingState
import app.spectra.wallet.ui.components.NoticeCard
import app.spectra.wallet.ui.components.NumericKeypad
import app.spectra.wallet.ui.components.PinDots
import app.spectra.wallet.ui.components.PinState
import app.spectra.wallet.ui.components.PrimaryButton
import app.spectra.wallet.ui.components.SecondaryButton
import app.spectra.wallet.ui.components.SpectraIcons
import app.spectra.wallet.ui.components.StatusPill
import app.spectra.wallet.ui.components.StepProgress
import app.spectra.wallet.ui.components.Tone
import app.spectra.wallet.ui.components.glassSurface
import app.spectra.wallet.ui.format.Fmt
import app.spectra.wallet.ui.theme.Spectra
import kotlinx.coroutines.launch

@Composable
fun OnboardingFlow(vm: OnboardingViewModel, onFinished: () -> Unit, onCancel: (() -> Unit)? = null) {
    AnimatedContent(vm.step, transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(180)) }, label = "onboarding") { step ->
        when (step) {
            OnboardingStep.WELCOME -> WelcomeScreen(vm::startCreate, vm::startImport)
            OnboardingStep.PIN_CREATE, OnboardingStep.PIN_CONFIRM -> PinCreateScreen(vm)
            OnboardingStep.BIOMETRICS -> BiometricsScreen(vm)
            OnboardingStep.PHRASE_SHOW -> PhraseScreen(vm, onCancel)
            OnboardingStep.PHRASE_VERIFY -> VerifyScreen(vm)
            OnboardingStep.IMPORT_PHRASE -> ImportPhraseScreen(vm, onCancel)
            OnboardingStep.IMPORT_SCHEMES -> SchemeSelectionScreen(vm)
            OnboardingStep.PREPARING -> PreparingScreen(vm)
            OnboardingStep.READY -> ReadyScreen(vm) { vm.finish(); onFinished() }
        }
    }
}

@Composable
private fun Screen(preset: AuroraPreset = AuroraPreset.ONBOARDING, scroll: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    AuroraBackground(preset) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 16.dp)
                .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            content = content,
        )
    }
}

@Composable
fun SpectraLogo(size: Dp = 104.dp) {
    Box(Modifier.size(size).glassSurface(RoundedCornerShape(size * 0.29f), strong = true), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size * 0.62f)) {
            val s = this.size.width / 64f
            drawCircle(Color(0xFF8B6CFF).copy(alpha = .78f), 15 * s, Offset(24 * s, 25 * s))
            drawCircle(Color(0xFFFF9A76).copy(alpha = .72f), 15 * s, Offset(40 * s, 25 * s))
            drawCircle(Color(0xFF4FA3FF).copy(alpha = .70f), 15 * s, Offset(32 * s, 39 * s))
            drawCircle(Color.White.copy(alpha = .9f), 5 * s, Offset(32 * s, 30 * s))
        }
    }
}

@Composable
private fun Title(text: String, center: Boolean = true) {
    Text(text, style = Spectra.type.title, color = Spectra.colors.text, textAlign = if (center) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.fillMaxWidth().semantics { heading() })
}

@Composable
private fun Subtitle(text: String, center: Boolean = true) {
    Text(text, style = Spectra.type.body, color = Spectra.colors.textSecondary, textAlign = if (center) TextAlign.Center else TextAlign.Start, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun IconTile(icon: androidx.compose.ui.graphics.vector.ImageVector, size: Dp = 56.dp, tint: Color = Spectra.colors.primary) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(Spectra.colors.primarySoft.copy(alpha = .22f)).border(1.dp, Spectra.colors.glassBorder, RoundedCornerShape(size * .32f)),
        contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(size * 0.42f), tint = tint) }
}

// ---------------- Welcome ----------------

@Composable
fun WelcomeScreen(onCreate: () -> Unit, onImport: () -> Unit) {
    var showPrivacy by remember { mutableStateOf(false) }
    Screen {
        Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            SpectraLogo()
            Spacer(Modifier.height(24.dp))
            Text("Spectra", style = Spectra.type.display, color = Spectra.colors.text, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(10.dp))
            Text("Twój portfel krypto. Prosty,\nprzejrzysty i tylko twój.", style = Spectra.type.bodyMedium.copy(fontSize = Spectra.type.bodyStrong.fontSize), color = Spectra.colors.textSecondary, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.weight(1.2f))
        PrimaryButton("Utwórz portfel", onCreate)
        Spacer(Modifier.height(12.dp))
        SecondaryButton("Mam już portfel — importuj", onImport)
        Spacer(Modifier.height(14.dp))
        Text("Wersja eksperymentalna 0.1 — nie przechowuj tu znaczących środków. Bez konta, e-maila i telemetrii.",
            style = Spectra.type.caption, color = Spectra.colors.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        LinkButton("Prywatność i ograniczenia", { showPrivacy = true }, Modifier.align(Alignment.CenterHorizontally))
    }
    if (showPrivacy) app.spectra.wallet.ui.settings.PrivacySheet { showPrivacy = false }
}

// ---------------- PIN ----------------

@Composable
private fun PinCreateScreen(vm: OnboardingViewModel) {
    val confirming = vm.step == OnboardingStep.PIN_CONFIRM
    Screen(AuroraPreset.PIN) {
        SecureScreen()
        StepProgress(1, 3, vm::back)
        Spacer(Modifier.height(40.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            IconTile(SpectraIcons.Lock)
            Spacer(Modifier.height(18.dp))
            Title(if (confirming) "Powtórz PIN" else "Ustaw PIN")
            Spacer(Modifier.height(8.dp))
            Subtitle(if (confirming) "Wpisz ten sam sześciocyfrowy PIN jeszcze raz." else "Będziesz go używać do odblokowania portfela i potwierdzania przelewów.")
            Spacer(Modifier.height(24.dp))
            PinDots(vm.pinEntry.length, if (vm.pinError != null) PinState.ERROR else PinState.IDLE)
            Box(Modifier.heightIn(min = 40.dp).padding(top = 12.dp)) {
                vm.pinError?.let { Text(it, style = Spectra.type.label, color = Spectra.colors.negative, textAlign = TextAlign.Center) }
            }
        }
        Spacer(Modifier.weight(1f))
        NumericKeypad({ k -> when (k) { is KeypadKey.Digit -> vm.pinDigit(k.d); KeypadKey.Delete -> vm.pinDelete(); else -> Unit } },
            modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(8.dp))
    }
}

// ---------------- Biometrics ----------------

@Composable
private fun BiometricsScreen(vm: OnboardingViewModel) {
    val context = LocalContext.current
    val availability = remember { biometricAvailability(context) }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val activity = context.findActivity() as? FragmentActivity
    Screen {
        StepProgress(2, 3, null)
        Spacer(Modifier.weight(0.6f))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(130.dp).glassSurface(RoundedCornerShape(38.dp), strong = true), contentAlignment = Alignment.Center) {
                Icon(SpectraIcons.Fingerprint, null, Modifier.size(56.dp), tint = if (availability == BiometricAvailability.AVAILABLE) Spectra.colors.primary else Spectra.colors.textDisabled)
                if (availability != BiometricAvailability.AVAILABLE) Canvas(Modifier.size(64.dp)) {
                    drawLine(Color(0xFFB42A40), Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = 5f, cap = StrokeCap.Round)
                }
            }
            Spacer(Modifier.height(24.dp))
            when (availability) {
                BiometricAvailability.AVAILABLE -> {
                    Title("Odblokowuj szybciej")
                    Spacer(Modifier.height(8.dp))
                    Subtitle("Użyj odcisku palca lub rozpoznawania twarzy zamiast PIN-u. PIN zawsze zostaje jako zapasowa metoda.")
                    Spacer(Modifier.height(20.dp))
                    GlassCard(Modifier.fillMaxWidth()) {
                        listOf(SpectraIcons.Bolt to "Odblokowanie jednym dotknięciem", SpectraIcons.Send to "Szybkie potwierdzanie przelewów",
                            SpectraIcons.Shield to "Odcisk zostaje w telefonie — Spectra go nie widzi").forEachIndexed { i, (ic, t) ->
                            if (i > 0) Hairline()
                            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(ic, null, Modifier.size(20.dp), tint = Spectra.colors.primary)
                                Spacer(Modifier.width(14.dp))
                                Text(t, style = Spectra.type.bodyMedium, color = Spectra.colors.text)
                            }
                        }
                    }
                }
                BiometricAvailability.NONE_ENROLLED -> {
                    Title("Brak zapisanej biometrii")
                    Spacer(Modifier.height(8.dp))
                    Subtitle("Na tym telefonie nie dodano jeszcze odcisku palca ani twarzy. Portfel zabezpiecza PIN, który właśnie ustawiłeś.")
                }
                else -> {
                    Title("Biometria niedostępna")
                    Spacer(Modifier.height(8.dp))
                    Subtitle(when (availability) {
                        BiometricAvailability.NO_HARDWARE -> "Ten telefon nie ma czytnika biometrycznego klasy Strong. Portfel zabezpiecza PIN."
                        BiometricAvailability.SECURITY_UPDATE_REQUIRED -> "Czujnik wymaga aktualizacji zabezpieczeń systemu. Do tego czasu używaj PIN-u."
                        else -> "Czujnik jest chwilowo niedostępny. Możesz włączyć biometrię później w Profil → Bezpieczeństwo."
                    })
                }
            }
            error?.let { Spacer(Modifier.height(12.dp)); Text(it, style = Spectra.type.label, color = Spectra.colors.negative, textAlign = TextAlign.Center) }
        }
        Spacer(Modifier.weight(1f))
        if (availability == BiometricAvailability.AVAILABLE && activity != null) {
            PrimaryButton("Włącz biometrię", {
                scope.launch {
                    val cipher = vm.prepareBiometricCipher()
                    if (cipher == null) { error = "Nie udało się utworzyć klucza biometrycznego."; return@launch }
                    when (val r = activity.authenticateBiometric("Włącz biometrię", "Potwierdź, aby powiązać biometrię z portfelem", cipher)) {
                        is BiometricResult.Success -> if (vm.completeBiometric(r.cipher)) vm.afterBiometrics() else error = "Nie udało się zapisać klucza."
                        is BiometricResult.Cancelled -> error = null
                        is BiometricResult.LockedOut -> error = if (r.permanent) "Biometria zablokowana — odblokuj telefon kodem urządzenia." else "Zbyt wiele prób. Spróbuj za chwilę."
                        is BiometricResult.Error -> error = r.message
                    }
                }
            })
            LinkButton("Pomiń — włączę później", vm::afterBiometrics, Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
            Text("Włączysz to w każdej chwili w Profil → Bezpieczeństwo", style = Spectra.type.caption, color = Spectra.colors.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        } else {
            PrimaryButton("Kontynuuj z PIN-em", vm::afterBiometrics)
            if (availability == BiometricAvailability.NONE_ENROLLED) {
                LinkButton("Dodaj odcisk w ustawieniach telefonu", {
                    runCatching { context.startActivity(Intent(Settings.ACTION_BIOMETRIC_ENROLL)) }.onFailure { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                }, Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp), icon = SpectraIcons.External)
                Text("Po dodaniu odcisku włączysz biometrię w Profil → Bezpieczeństwo", style = Spectra.type.caption, color = Spectra.colors.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// ---------------- Recovery phrase ----------------

@Composable
private fun PhraseGrid(words: List<String>, revealed: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        words.chunked(3).forEachIndexed { row, chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chunk.forEachIndexed { col, w ->
                    val n = row * 3 + col + 1
                    Row(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(14.dp)).background(Spectra.colors.glassHighlight).padding(horizontal = 10.dp)
                        .semantics { contentDescription = if (revealed) "Słowo $n: $w" else "Słowo $n ukryte" }, verticalAlignment = Alignment.CenterVertically) {
                        Text("$n", style = Spectra.type.micro.copy(fontSize = Spectra.type.captionStrong.fontSize * 0.92f), color = Spectra.colors.textSecondary, modifier = Modifier.width(18.dp))
                        Text(if (revealed) w else "•••••", style = Spectra.type.label.copy(fontSize = Spectra.type.body.fontSize), color = if (revealed) Spectra.colors.text else Spectra.colors.textDisabled, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun PhraseScreen(vm: OnboardingViewModel, onCancel: (() -> Unit)?) {
    Screen(AuroraPreset.PHRASE, scroll = true) {
        SecureScreen()
        StepProgress(3, 3, onCancel ?: vm::back)
        Spacer(Modifier.height(28.dp))
        Title("Twoja fraza odzyskiwania", center = false)
        Spacer(Modifier.height(8.dp))
        Subtitle("Te 12 słów to jedyny sposób, by odzyskać portfel. Zapisz je na papierze, w tej kolejności.", center = false)
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().glassSurface(RoundedCornerShape(26.dp)).padding(12.dp)) {
            PhraseGrid(vm.words, vm.revealed)
            if (!vm.revealed) {
                Column(Modifier.matchParentSize().clip(RoundedCornerShape(20.dp)).background(Spectra.colors.background.copy(alpha = .55f)),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Row(Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(Spectra.colors.inverse).clickable(role = Role.Button) { vm.revealed = true }.padding(horizontal = 22.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(SpectraIcons.Eye, null, Modifier.size(18.dp), tint = Spectra.colors.onInverse)
                        Spacer(Modifier.width(8.dp))
                        Text("Pokaż frazę", style = Spectra.type.bodyStrong.copy(fontSize = Spectra.type.body.fontSize), color = Spectra.colors.onInverse)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Upewnij się, że nikt nie patrzy na ekran", style = Spectra.type.captionStrong, color = Spectra.colors.text)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        NoticeCard("Nigdy nikomu jej nie podawaj", "Spectra nigdy nie zapyta o frazę. Kto ją zna, może zabrać wszystkie twoje środki. Nie rób zrzutu ekranu ani nie kopiuj jej do schowka.", Tone.NEGATIVE)
        Spacer(Modifier.height(10.dp))
        Text("Jeśli stracisz telefon i frazę, odzyskanie portfela może być niemożliwe.", style = Spectra.type.caption, color = Spectra.colors.textSecondary)
        Spacer(Modifier.height(10.dp))
        CheckRow(vm.acknowledged, { vm.acknowledged = it }, "Zapisałem frazę w bezpiecznym miejscu")
        Spacer(Modifier.weight(1f).heightIn(min = 16.dp))
        PrimaryButton("Dalej", vm::toVerify, enabled = vm.revealed && vm.acknowledged)
    }
}

@Composable
private fun VerifyScreen(vm: OnboardingViewModel) {
    Screen(AuroraPreset.PHRASE, scroll = true) {
        SecureScreen()
        StepProgress(3, 3, vm::back)
        Spacer(Modifier.height(28.dp))
        Title("Sprawdźmy zapis", center = false)
        Spacer(Modifier.height(8.dp))
        Subtitle("Wybierz właściwe słowo dla każdej pozycji.", center = false)
        Spacer(Modifier.height(20.dp))
        vm.questions.forEach { q ->
            val picked = vm.picks[q.position]
            GlassCard(Modifier.fillMaxWidth().padding(bottom = 12.dp), radius = 24.dp, padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
                    Text("Słowo nr ${q.position}", style = Spectra.type.label, color = Spectra.colors.text, modifier = Modifier.weight(1f))
                    if (picked != null) Text(if (vm.isCorrect(q.position)) "Zgadza się" else "To nie to słowo", style = Spectra.type.captionStrong,
                        color = if (vm.isCorrect(q.position)) Spectra.colors.positive else Spectra.colors.negative)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    q.options.forEach { w ->
                        val sel = picked == w
                        val correct = w == vm.words[q.position - 1]
                        Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp))
                            .background(if (sel) (if (correct) Spectra.colors.positive else Spectra.colors.negativeStrong) else Spectra.colors.chip)
                            .clickable(role = Role.Button) { vm.pick(q.position, w) }.semantics { stateDescription = if (sel) "Wybrano" else "" }, contentAlignment = Alignment.Center) {
                            Text(w, style = Spectra.type.label.copy(fontSize = Spectra.type.body.fontSize), color = if (sel) Color.White else Spectra.colors.text)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f).heightIn(min = 16.dp))
        PrimaryButton("Utwórz portfel", vm::prepare, enabled = vm.allCorrect)
    }
}

// ---------------- Import ----------------

@Composable
private fun ImportPhraseScreen(vm: OnboardingViewModel, onCancel: (() -> Unit)?) {
    val words = vm.importWords
    val validation = vm.importValidation
    Screen(AuroraPreset.PHRASE, scroll = true) {
        SecureScreen()
        if (onCancel != null || vm.pinExists) app.spectra.wallet.ui.components.TopBar("Importuj portfel", onBack = onCancel ?: {}) else StepProgress(3, 3, vm::back)
        Spacer(Modifier.height(24.dp))
        Title("Wpisz frazę odzyskiwania", center = false)
        Spacer(Modifier.height(8.dp))
        Subtitle("12 lub 24 słowa oddzielone spacjami, w tej samej kolejności. Fraza nie opuszcza telefonu.", center = false)
        Spacer(Modifier.height(18.dp))
        GlassTextField(vm.importText, { vm.importText = it }, "np. velvet harbor lunar …", label = "Fraza odzyskiwania", singleLine = false, minLines = 4, sensitive = true)
        Spacer(Modifier.height(10.dp))
        val status = when (validation) {
            Bip39.Validation.Valid -> "Fraza poprawna (${words.size} słów)" to Spectra.colors.positive
            is Bip39.Validation.InvalidLength -> "Słów: ${validation.count} (wymagane 12, 15, 18, 21 lub 24)" to Spectra.colors.textSecondary
            is Bip39.Validation.UnknownWords -> "Nieznane słowa na pozycjach: ${validation.positions.joinToString { (it + 1).toString() }}" to Spectra.colors.negative
            Bip39.Validation.InvalidChecksum -> "Suma kontrolna się nie zgadza — sprawdź kolejność i pisownię słów." to Spectra.colors.negative
        }
        Text(status.first, style = Spectra.type.captionStrong, color = status.second)
        val last = words.lastOrNull()
        if (last != null && !vm.importText.endsWith(" ") && last !in Bip39.wordlist) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Bip39.suggestions(last).forEach { s -> Chip(s, false, { vm.importText = (words.dropLast(1) + s).joinToString(" ") + " " }) }
            }
        }
        Spacer(Modifier.height(14.dp))
        NoticeCard("Sprawdzamy, nie zgadujemy", "Po weryfikacji pokażemy adresy dla popularnych ścieżek wyprowadzania kluczy. Wybierzesz je sam — Spectra nie zmienia ścieżki po cichu.", Tone.PRIMARY, SpectraIcons.Info)
        Spacer(Modifier.weight(1f).heightIn(min = 16.dp))
        PrimaryButton("Dalej", vm::discover, enabled = validation == Bip39.Validation.Valid)
    }
}

@Composable
private fun SchemeSelectionScreen(vm: OnboardingViewModel) {
    Screen(AuroraPreset.PHRASE, scroll = true) {
        SecureScreen()
        app.spectra.wallet.ui.components.TopBar("Wybierz konta", onBack = vm::back)
        Spacer(Modifier.height(16.dp))
        Subtitle("Różne portfele używają różnych ścieżek. Pokazujemy salda w sieci głównej, aby ułatwić wybór.", center = false)
        Spacer(Modifier.height(16.dp))
        when {
            vm.discovering -> LoadingState("Sprawdzanie adresów w sieciach…")
            else -> {
                vm.discoveryError?.let { NoticeCard("Nie udało się sprawdzić sald", it, Tone.WARNING, SpectraIcons.Alert); Spacer(Modifier.height(12.dp)) }
                Chain.entries.forEach { chain ->
                    val list = vm.candidates[chain] ?: app.spectra.core.model.DerivationScheme.forChain(chain).map { app.spectra.wallet.domain.SchemeCandidate(it, "—", null, "brak danych") }
                    val selected = vm.selectedSchemes[chain]
                    val defaultEmpty = list.firstOrNull { it.scheme == app.spectra.core.model.DerivationScheme.defaultFor(chain) }?.balanceUnits == 0L
                    val otherFunded = list.any { it.scheme != app.spectra.core.model.DerivationScheme.defaultFor(chain) && (it.balanceUnits ?: 0) > 0 }
                    Text(chain.displayName, style = Spectra.type.bodyStrong, color = Spectra.colors.text, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp, top = 6.dp))
                    if (defaultEmpty && otherFunded) { NoticeCard("Środki na innej ścieżce", "Znaleźliśmy saldo na niestandardowej ścieżce. Wybierz ją, jeśli to twój poprzedni portfel.", Tone.WARNING, SpectraIcons.Info); Spacer(Modifier.height(8.dp)) }
                    GlassCard(Modifier.fillMaxWidth().padding(bottom = 10.dp), padding = androidx.compose.foundation.layout.PaddingValues(6.dp)) {
                        list.forEachIndexed { i, cand ->
                            if (i > 0) Hairline()
                            val sel = cand.scheme == selected
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(role = Role.RadioButton) { vm.selectScheme(cand.scheme) }
                                .semantics { stateDescription = if (sel) "Wybrano" else "Nie wybrano" }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(22.dp).clip(CircleShape).border(2.dp, if (sel) Spectra.colors.primary else Spectra.colors.text.copy(alpha = .3f), CircleShape), contentAlignment = Alignment.Center) {
                                    if (sel) Box(Modifier.size(12.dp).clip(CircleShape).background(Spectra.colors.primary))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(cand.scheme.label, style = Spectra.type.mono, color = Spectra.colors.text)
                                    Text(Fmt.shortAddress(cand.address, 6, 6) + if (!cand.scheme.canSpend) " · tylko podgląd w v0.1" else "", style = Spectra.type.caption, color = Spectra.colors.textSecondary)
                                }
                                val bal = cand.balanceUnits
                                when {
                                    cand.error != null -> StatusPill("błąd", Tone.WARNING, dot = false)
                                    bal != null && bal > 0 -> StatusPill(if (cand.scheme.chain == Chain.ETHEREUM && bal == 1L) "tokeny" else Fmt.amount(Amount.of(bal, chain.nativeDecimals)) + " " + chain.symbol, Tone.POSITIVE)
                                    else -> StatusPill("puste", Tone.NEUTRAL, dot = false)
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f).heightIn(min = 16.dp))
        PrimaryButton("Importuj portfel", vm::prepare, enabled = !vm.discovering)
    }
}

// ---------------- Preparing ----------------

@Composable
private fun PreparingScreen(vm: OnboardingViewModel) {
    val c = Spectra.colors
    val rotation = if (Spectra.reducedMotion) 0f else rememberInfiniteTransition(label = "orbit").animateFloat(0f, 360f, infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart), label = "rot").value
    Screen(AuroraPreset.ONBOARDING) {
        Spacer(Modifier.height(56.dp))
        Box(Modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(180.dp).rotate(rotation)) {
                drawCircle(c.primary.copy(alpha = .25f), size.width / 2, style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 10f))))
                drawArc(c.primary, -150f, 60f, false, style = Stroke(6f, cap = StrokeCap.Round))
                val r = size.width / 2
                drawCircle(Color(0xFF6E7F99), 7.dp.toPx(), center + Offset(-r * .66f, -r * .74f))
                drawCircle(c.primary, 7.dp.toPx(), center + Offset(r * .96f, -r * .25f))
                drawCircle(Color(0xFF5A6CF0), 7.dp.toPx(), center + Offset(-r * .3f, r * .95f))
            }
            SpectraLogo(124.dp)
        }
        Spacer(Modifier.height(24.dp))
        Title("Przygotowujemy portfel")
        Spacer(Modifier.height(8.dp))
        Subtitle("Generujemy klucze z twojej frazy i zapisujemy je zaszyfrowane na telefonie. Nic nie wychodzi do sieci.")
        Spacer(Modifier.height(24.dp))
        if (vm.prepareError != null) {
            NoticeCard("Nie udało się utworzyć portfela", vm.prepareError, Tone.NEGATIVE, SpectraIcons.Alert)
            Spacer(Modifier.height(12.dp))
            PrimaryButton("Spróbuj ponownie", vm::prepare)
            return@Screen
        }
        Row(Modifier.fillMaxWidth()) {
            Text(vm.progressLabel, style = Spectra.type.label, color = c.text, modifier = Modifier.weight(1f))
            Text("${vm.progressStep} z ${vm.progressTotal} kroków", style = Spectra.type.label, color = c.text)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.text.copy(alpha = .08f))) {
            Box(Modifier.fillMaxWidth(vm.progressStep / vm.progressTotal.toFloat()).height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.primary))
        }
        Spacer(Modifier.height(16.dp))
        GlassCard(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
            listOf(Chain.SOLANA to 3, Chain.ETHEREUM to 4, Chain.LITECOIN to 5).forEachIndexed { i, (chain, doneAt) ->
                if (i > 0) Hairline()
                val scheme = vm.selectedSchemes.getValue(chain)
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                    AssetAvatar(chain.symbol, chain, true)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(chain.displayName, style = Spectra.type.bodyStrong, color = c.text)
                        Text(scheme.path(0, NetworkEnv.MAINNET)?.toString() ?: scheme.label, style = Spectra.type.mono, color = c.textSecondary)
                    }
                    when {
                        vm.progressStep >= doneAt -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(24.dp).clip(CircleShape).background(c.positive), contentAlignment = Alignment.Center) { Icon(SpectraIcons.Check, null, Modifier.size(14.dp), tint = Color.White) }
                            Spacer(Modifier.width(6.dp)); Text("Gotowe", style = Spectra.type.label, color = c.positive)
                        }
                        vm.progressStep == doneAt - 1 -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = c.primary, strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp)); Text("Generowanie", style = Spectra.type.label, color = c.link)
                        }
                        else -> Text("Oczekuje", style = Spectra.type.label, color = c.textSecondary)
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text("Nie zamykaj aplikacji", style = Spectra.type.bodyMedium, color = c.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
    LaunchedEffect(Unit) { }
}

// ---------------- Ready ----------------

@Composable
private fun ReadyScreen(vm: OnboardingViewModel, onDone: () -> Unit) {
    val c = Spectra.colors
    val acc = vm.created
    var qrFor by remember { mutableStateOf<Pair<Chain, String>?>(null) }
    Screen(AuroraPreset.SUCCESS, scroll = true) {
        Spacer(Modifier.height(40.dp))
        Box(Modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(180.dp).clip(CircleShape).border(2.dp, c.positive.copy(alpha = .18f), CircleShape))
            Box(Modifier.size(124.dp).glassSurface(RoundedCornerShape(36.dp), strong = true), contentAlignment = Alignment.Center) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(c.positive), contentAlignment = Alignment.Center) { Icon(SpectraIcons.Check, null, Modifier.size(30.dp), tint = Color.White) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Title("Portfel gotowy")
        Spacer(Modifier.height(8.dp))
        Subtitle("${acc?.account?.name ?: "Konto"} działa w 3 sieciach. Dotknij sieci, żeby zobaczyć pełny adres i kod QR.")
        Spacer(Modifier.height(20.dp))
        GlassCard(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
            Chain.entries.forEachIndexed { i, chain ->
                if (i > 0) Hairline()
                val addr = acc?.view(Network(chain, NetworkEnv.MAINNET))?.primaryAddress ?: ""
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button) { qrFor = chain to addr }, verticalAlignment = Alignment.CenterVertically) {
                    AssetAvatar(chain.symbol, chain, true)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(chain.displayName, style = Spectra.type.bodyStrong, color = c.text)
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.size(6.dp).clip(CircleShape).background(c.positive)); Spacer(Modifier.width(4.dp))
                            Text("Aktywne", style = Spectra.type.captionStrong, color = c.positive)
                        }
                        Text(Fmt.shortAddress(addr, 6, 4), style = Spectra.type.mono, color = c.textSecondary)
                    }
                    GlassIconButton(SpectraIcons.Qr, "Pokaż kod QR ${chain.displayName}", { qrFor = chain to addr }, size = 40.dp, tint = c.primary)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOfNotNull("PIN", if (vm.biometricsEnabled) "Biometria" else null, if (vm.mode == OnboardingMode.CREATE) "Kopia potwierdzona" else "Fraza zaimportowana").forEach {
                Row(Modifier.clip(RoundedCornerShape(16.dp)).background(c.glassStrong).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(SpectraIcons.Check, null, Modifier.size(14.dp), tint = c.positive); Spacer(Modifier.width(6.dp))
                    Text(it, style = Spectra.type.captionStrong, color = c.text)
                }
            }
        }
        Spacer(Modifier.weight(1f).heightIn(min = 24.dp))
        PrimaryButton("Przejdź do portfela", onDone)
    }
    qrFor?.let { (chain, addr) -> app.spectra.wallet.ui.receive.AddressQrSheet(chain.displayName, addr) { qrFor = null } }
}

