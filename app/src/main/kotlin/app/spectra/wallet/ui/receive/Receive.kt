package app.spectra.wallet.ui.receive

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.spectra.core.chain.AccountView
import app.spectra.core.model.AddressRole
import app.spectra.core.model.Chain
import app.spectra.core.model.Network
import app.spectra.wallet.ui.common.LocalContainer
import app.spectra.wallet.ui.common.humanMessage
import app.spectra.wallet.ui.components.AuroraBackground
import app.spectra.wallet.ui.components.AuroraPreset
import app.spectra.wallet.ui.components.Chip
import app.spectra.wallet.ui.components.LoadingState
import app.spectra.wallet.ui.components.NoticeCard
import app.spectra.wallet.ui.components.PrimaryButton
import app.spectra.wallet.ui.components.SecondaryButton
import app.spectra.wallet.ui.components.SpectraIcons
import app.spectra.wallet.ui.components.SpectraSheet
import app.spectra.wallet.ui.components.StatusPill
import app.spectra.wallet.ui.components.Tone
import app.spectra.wallet.ui.components.TopBar
import app.spectra.wallet.ui.components.glassSurface
import app.spectra.wallet.ui.theme.Spectra
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

@Composable
fun QrCode(text: String, size: Dp, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    }
    Box(modifier.size(size).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(14.dp).semantics { contentDescription = "Kod QR adresu" }) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = this.size.width / matrix.width
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                if (matrix[x, y]) drawRect(Color(0xFF17151F), Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

/** Copies a public address. Marked as non-sensitive public data; still auto-clears from the preview on Android 13+. */
fun copyAddress(context: Context, label: String, address: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newPlainText(label, address)
    cm.setPrimaryClip(clip)
}

fun shareAddress(context: Context, network: String, address: String) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, address)
        .putExtra(Intent.EXTRA_SUBJECT, "Mój adres $network"), "Udostępnij adres"))
}

@Composable
fun AddressQrSheet(networkName: String, address: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    SpectraSheet(onDismiss) {
        Text(networkName, style = Spectra.type.headline, color = Spectra.colors.text)
        Spacer(Modifier.height(16.dp))
        QrCode(address, 240.dp, Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(16.dp))
        Text(address, style = Spectra.type.mono, color = Spectra.colors.text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Kopiuj adres", { copyAddress(context, networkName, address) }, icon = SpectraIcons.Copy)
        Spacer(Modifier.height(10.dp))
        SecondaryButton("Udostępnij", { shareAddress(context, networkName, address) }, icon = SpectraIcons.Share)
    }
}

/** Receive: choose chain, show address + QR, copy/share, network warning. Litecoin rotates to the next unused address. */
@Composable
fun ReceiveScreen(accountId: String, testnet: Boolean, initialChain: Chain?, onBack: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    var chain by remember { mutableStateOf(initialChain ?: Chain.SOLANA) }
    val env = if (testnet) app.spectra.core.model.NetworkEnv.TESTNET else app.spectra.core.model.NetworkEnv.MAINNET
    val network = Network(chain, env)
    var address by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(chain, accountId, testnet) {
        address = null; note = null; copied = false
        val view: AccountView? = c.wallets.account(accountId)?.view(network)
        if (view == null) { note = "Brak adresu dla tej sieci."; return@LaunchedEffect }
        if (chain == Chain.LITECOIN) {
            runCatching { c.litecoin.discover(view, force = true).nextUnused(AddressRole.RECEIVE) }
                .onSuccess { a -> address = a?.address ?: view.primaryAddress; if (a != null && a.index > 0) note = "Nowy adres (#${a.index}) — Litecoin używa nowego adresu dla każdej płatności, aby chronić prywatność." }
                .onFailure { address = view.primaryAddress; note = "Nie udało się sprawdzić użytych adresów (${it.humanMessage()}). Pokazujemy pierwszy adres konta." }
        } else address = view.primaryAddress
    }
    AuroraBackground(AuroraPreset.DETAIL) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
            TopBar("Odbierz", onClose = onBack)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Chain.entries.forEach { ch -> Chip(ch.displayName, ch == chain, { chain = ch }) } }
            Spacer(Modifier.height(20.dp))
            Column(Modifier.fillMaxWidth().glassSurface(RoundedCornerShape(28.dp)).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                StatusPill(network.displayName, if (testnet) Tone.WARNING else Tone.POSITIVE)
                Spacer(Modifier.height(16.dp))
                val a = address
                if (a == null) LoadingState("Ustalanie adresu…") else {
                    QrCode(a, 232.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(a, style = Spectra.type.mono.copy(fontSize = Spectra.type.body.fontSize), color = Spectra.colors.text, textAlign = TextAlign.Center)
                }
            }
            note?.let { Spacer(Modifier.height(12.dp)); NoticeCard("Adres odbioru", it, Tone.PRIMARY, SpectraIcons.Info) }
            Spacer(Modifier.height(12.dp))
            NoticeCard("Wysyłaj tylko w sieci ${network.displayName}",
                "Środki wysłane z innej sieci mogą zostać utracone." + if (testnet) " To sieć testowa — tokeny nie mają wartości." else "", if (testnet) Tone.WARNING else Tone.NEUTRAL, SpectraIcons.Alert)
            Spacer(Modifier.height(16.dp))
            val a = address
            PrimaryButton(if (copied) "Skopiowano" else "Kopiuj adres", { if (a != null) { copyAddress(context, network.displayName, a); copied = true } }, enabled = a != null, icon = SpectraIcons.Copy)
            Spacer(Modifier.height(10.dp))
            SecondaryButton("Udostępnij", { if (a != null) shareAddress(context, network.displayName, a) }, enabled = a != null, icon = SpectraIcons.Share)
        }
    }
}

