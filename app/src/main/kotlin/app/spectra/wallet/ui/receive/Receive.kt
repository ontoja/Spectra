package app.spectra.wallet.ui.receive

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.spectra.core.chain.AccountView
import app.spectra.core.model.AddressRole
import app.spectra.core.model.Chain
import app.spectra.core.model.Network
import app.spectra.core.model.NetworkEnv
import app.spectra.wallet.ui.common.LocalContainer
import app.spectra.wallet.ui.common.humanMessage
import app.spectra.wallet.ui.components.AuroraBackground
import app.spectra.wallet.ui.components.AuroraPreset
import app.spectra.wallet.ui.components.ChainLogo
import app.spectra.wallet.ui.components.GlassIconButton
import app.spectra.wallet.ui.components.PrimaryButton
import app.spectra.wallet.ui.components.SecondaryButton
import app.spectra.wallet.ui.components.SpectraIcons
import app.spectra.wallet.ui.components.SpectraSheet
import app.spectra.wallet.ui.components.StatusPill
import app.spectra.wallet.ui.components.Tone
import app.spectra.wallet.ui.components.TopBar
import app.spectra.wallet.ui.components.enterAnimation
import app.spectra.wallet.ui.components.glassSurface
import app.spectra.wallet.ui.components.pressScale
import app.spectra.wallet.ui.theme.Motion
import app.spectra.wallet.ui.theme.Spectra
import app.spectra.wallet.ui.theme.spectraTween
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay

private val QrInk = Color(0xFF17151F)

/**
 * QR code with rounded modules and rounded finder patterns. When [center] is set, the network logo is placed in the
 * middle; error correction is raised to H (≈30 % recoverable) and the covered area is kept well below that.
 */
@Composable
fun QrCode(text: String, size: Dp, modifier: Modifier = Modifier, center: Chain? = null) {
    val matrix = remember(text, center) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to if (center != null) ErrorCorrectionLevel.H else ErrorCorrectionLevel.M))
    }
    val n = matrix.width
    // Logo hole in modules (odd count, ~22 % of the width) — far under the 30 % recovery budget of level H.
    val hole = if (center != null) ((n * 0.22f).toInt() or 1) else 0
    val holeStart = (n - hole) / 2
    Box(modifier.size(size).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(16.dp)
        .semantics { contentDescription = "Kod QR adresu" + (center?.let { " ${it.displayName}" } ?: "") }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = this.size.width / n
            fun inFinder(x: Int, y: Int) = (x < 7 && y < 7) || (x >= n - 7 && y < 7) || (x < 7 && y >= n - 7)
            fun inHole(x: Int, y: Int) = hole > 0 && x in holeStart until holeStart + hole && y in holeStart until holeStart + hole
            for (y in 0 until n) for (x in 0 until n) {
                if (!matrix[x, y] || inFinder(x, y) || inHole(x, y)) continue
                val inset = cell * 0.08f
                drawRoundRect(QrInk, Offset(x * cell + inset, y * cell + inset), Size(cell - 2 * inset, cell - 2 * inset), CornerRadius(cell * 0.38f))
            }
            listOf(0 to 0, n - 7 to 0, 0 to n - 7).forEach { (fx, fy) ->
                val o = Offset(fx * cell, fy * cell)
                val stroke = cell
                drawRoundRect(QrInk, o + Offset(stroke / 2, stroke / 2), Size(7 * cell - stroke, 7 * cell - stroke), CornerRadius(cell * 2f), style = Stroke(stroke))
                drawRoundRect(QrInk, o + Offset(2 * cell, 2 * cell), Size(3 * cell, 3 * cell), CornerRadius(cell * 1.1f))
            }
        }
        if (center != null) {
            val logoBox = size * 0.2f
            Box(Modifier.size(logoBox).clip(RoundedCornerShape(logoBox * 0.3f)).background(Color.White), contentAlignment = Alignment.Center) {
                ChainLogo(center, logoBox * 0.82f)
            }
        }
    }
}

/** Copies a public address to the clipboard (Android 13+ shows its own confirmation). */
fun copyAddress(context: Context, label: String, address: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newPlainText(label, address)
    if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", false) }
    cm.setPrimaryClip(clip)
}

fun shareAddress(context: Context, network: String, address: String) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, address)
        .putExtra(Intent.EXTRA_SUBJECT, "Mój adres $network"), "Udostępnij adres"))
}

/** Address with the first and last 6 characters emphasised — the parts people actually compare. */
@Composable
fun AddressText(address: String, modifier: Modifier = Modifier) {
    val c = Spectra.colors
    val head = 6.coerceAtMost(address.length / 2)
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = c.text, fontWeight = FontWeight.Bold)) { append(address.take(head)) }
        withStyle(SpanStyle(color = c.textSecondary)) { append(address.substring(head, (address.length - head).coerceAtLeast(head))) }
        withStyle(SpanStyle(color = c.text, fontWeight = FontWeight.Bold)) { append(address.takeLast(head)) }
    }
    Text(text, style = Spectra.type.mono.copy(fontSize = Spectra.type.body.fontSize, lineHeight = Spectra.type.bodyStrong.lineHeight),
        textAlign = TextAlign.Center, modifier = modifier)
}

@Composable
fun AddressQrSheet(networkName: String, address: String, chain: Chain? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }
    SpectraSheet(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            chain?.let { ChainLogo(it, 28.dp); Spacer(Modifier.width(10.dp)) }
            Text(networkName, style = Spectra.type.headline, color = Spectra.colors.text)
        }
        Spacer(Modifier.height(16.dp))
        QrCode(address, 248.dp, Modifier.align(Alignment.CenterHorizontally), center = chain)
        Spacer(Modifier.height(16.dp))
        AddressText(address, Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        PrimaryButton(if (copied) "Skopiowano" else "Kopiuj adres", { copyAddress(context, networkName, address); copied = true }, icon = if (copied) SpectraIcons.Check else SpectraIcons.Copy)
        Spacer(Modifier.height(10.dp))
        SecondaryButton("Udostępnij", { shareAddress(context, networkName, address) }, icon = SpectraIcons.Share)
    }
}

/** Full-width network selector with logos and a sliding selection. */
@Composable
private fun ChainSelector(selected: Chain, onSelect: (Chain) -> Unit) {
    val c = Spectra.colors
    Row(Modifier.fillMaxWidth().glassSurface(RoundedCornerShape(28.dp)).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Chain.entries.forEach { ch ->
            val sel = ch == selected
            val bg by animateColorAsState(if (sel) c.inverse else Color.Transparent, spectraTween(Motion.STANDARD), label = "chainBg")
            val fg by animateColorAsState(if (sel) c.onInverse else c.text, spectraTween(Motion.STANDARD), label = "chainFg")
            val interaction = remember { MutableInteractionSource() }
            Row(Modifier.weight(1f).height(46.dp).pressScale(interaction, 0.95f).clip(RoundedCornerShape(23.dp)).background(bg)
                .clickable(interaction, ripple(), role = Role.Tab) { onSelect(ch) }.semantics { stateDescription = if (sel) "Wybrano" else "" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                ChainLogo(ch, 22.dp)
                Spacer(Modifier.width(7.dp))
                Text(ch.symbol, style = Spectra.type.label, color = fg, maxLines = 1)
            }
        }
    }
}

/** Receive: choose chain, show address + QR, copy/share, network warning. Litecoin rotates to the next unused address. */
@Composable
fun ReceiveScreen(accountId: String, testnet: Boolean, initialChain: Chain?, onBack: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val col = Spectra.colors
    var chain by remember { mutableStateOf(initialChain ?: Chain.SOLANA) }
    val env = if (testnet) NetworkEnv.TESTNET else NetworkEnv.MAINNET
    val network = Network(chain, env)
    var address by remember { mutableStateOf<String?>(null) }
    var accountName by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }
    LaunchedEffect(chain, accountId, testnet) {
        address = null; note = null; copied = false
        val acc = c.wallets.account(accountId)
        accountName = acc?.account?.name ?: ""
        val view: AccountView? = acc?.view(network)
        if (view == null) { note = "Brak adresu dla tej sieci."; return@LaunchedEffect }
        // Show a valid address immediately; for Litecoin, then look for the next unused one (privacy) without blocking.
        address = view.primaryAddress
        if (chain == Chain.LITECOIN) {
            note = "Sprawdzamy, czy jest nowy, nieużywany adres… Ten adres też należy do Ciebie."
            runCatching { c.litecoin.discover(view, force = true).nextUnused(AddressRole.RECEIVE) }
                .onSuccess { a ->
                    if (a != null && a.address != address) address = a.address
                    note = if (a != null && a.index > 0) "Nowy adres (#${a.index}). Litecoin używa nowego adresu dla każdej płatności, aby chronić prywatność. Poprzednie adresy nadal działają." else null
                }
                .onFailure { note = "Nie udało się sprawdzić użytych adresów (${it.humanMessage()}). Pokazujemy pierwszy adres konta — jest poprawny." }
        }
    }
    AuroraBackground(AuroraPreset.DETAIL) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 12.dp)) {
                TopBar("Odbierz", onClose = onBack)
                Spacer(Modifier.height(18.dp))
                ChainSelector(chain) { chain = it }
                Spacer(Modifier.height(16.dp))
                Column(Modifier.fillMaxWidth().enterAnimation(1).glassSurface(RoundedCornerShape(32.dp)).padding(horizontal = 20.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ChainLogo(chain, 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(network.displayName, style = Spectra.type.bodyStrong, color = col.text)
                            if (accountName.isNotEmpty()) Text(accountName, style = Spectra.type.caption, color = col.textSecondary)
                        }
                        StatusPill(if (testnet) "Testnet" else "Mainnet", if (testnet) Tone.WARNING else Tone.POSITIVE)
                    }
                    Spacer(Modifier.height(18.dp))
                    val qd = if (Spectra.reducedMotion) 0 else 320
                    AnimatedContent(address to chain, transitionSpec = { (fadeIn(tween(qd)) + scaleIn(tween(qd), 0.92f)) togetherWith fadeOut(tween(qd / 2)) }, label = "qr") { (a, ch) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (a == null) Box(Modifier.size(248.dp), contentAlignment = Alignment.Center) { app.spectra.wallet.ui.components.LoadingState("Ustalanie adresu…") }
                            else {
                                val interaction = remember { MutableInteractionSource() }
                                QrCode(a, 248.dp, Modifier.pressScale(interaction, 0.97f).clip(RoundedCornerShape(24.dp))
                                    .clickable(interaction, null, role = Role.Button) { copyAddress(context, network.displayName, a); copied = true }, center = ch)
                                Spacer(Modifier.height(16.dp))
                                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(col.glassHighlight)
                                    .clickable(role = Role.Button) { copyAddress(context, network.displayName, a); copied = true }.padding(horizontal = 14.dp, vertical = 12.dp),
                                    contentAlignment = Alignment.Center) { AddressText(a) }
                                Text("Dotknij kodu lub adresu, aby skopiować", style = Spectra.type.caption, color = col.textSecondary, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                }
                note?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, style = Spectra.type.caption, color = col.textSecondary, modifier = Modifier.padding(horizontal = 6.dp))
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().enterAnimation(2).clip(RoundedCornerShape(18.dp)).background(if (testnet) col.warningSoft else col.glass)
                    .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(SpectraIcons.Alert, null, Modifier.size(18.dp), tint = if (testnet) col.warning else col.textSecondary)
                    Spacer(Modifier.width(10.dp))
                    Text("Wysyłaj tu tylko ${chain.symbol} i tokeny sieci ${network.displayName}. Środki z innej sieci mogą przepaść." +
                        if (testnet) " To sieć testowa — tokeny nie mają wartości." else "",
                        style = Spectra.type.caption, color = col.textSecondary)
                }
            }
            // Actions stay pinned above the navigation bar so nothing is cut off.
            val a = address
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 6.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    PrimaryButton(if (copied) "Skopiowano" else "Kopiuj adres", { if (a != null) { copyAddress(context, network.displayName, a); copied = true } },
                        enabled = a != null, icon = if (copied) SpectraIcons.Check else SpectraIcons.Copy)
                }
                Spacer(Modifier.width(10.dp))
                GlassIconButton(SpectraIcons.Share, "Udostępnij adres", { if (a != null) shareAddress(context, network.displayName, a) }, size = 56.dp, enabled = a != null)
            }
        }
    }
}
