package app.spectra.wallet.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.spectra.core.model.Chain
import app.spectra.wallet.ui.theme.AssetGradients
import app.spectra.wallet.ui.theme.Spectra

/**
 * Network logos drawn as vectors (no network fetches). Glyph geometry follows the widely used public
 * cryptocurrency-icons set (CC0) and Solana's published mark.
 */
private object LogoPaths {
    private fun p(d: String): Path = PathParser().parsePathString(d).toPath()

    // Solana mark, viewBox 397.7 × 311.7
    val solana = listOf(
        p("M64.6 237.9c2.4-2.4 5.7-3.8 9.2-3.8h317.4c5.8 0 8.7 7 4.6 11.1l-62.7 62.7c-2.4 2.4-5.7 3.8-9.2 3.8H6.5c-5.8 0-8.7-7-4.6-11.1l62.7-62.7z"),
        p("M64.6 3.8C67.1 1.4 70.4 0 73.8 0h317.4c5.8 0 8.7 7 4.6 11.1l-62.7 62.7c-2.4 2.4-5.7 3.8-9.2 3.8H6.5c-5.8 0-8.7-7-4.6-11.1L64.6 3.8z"),
        p("M333.1 120.1c-2.4-2.4-5.7-3.8-9.2-3.8H6.5c-5.8 0-8.7 7-4.6 11.1l62.7 62.7c2.4 2.4 5.7 3.8 9.2 3.8h317.4c5.8 0 8.7-7 4.6-11.1l-62.7-62.7z"),
    )

    // Ethereum glyph on a 32 × 32 grid (path to alpha)
    val ethereum = listOf(
        p("M16.498 4v8.87l7.497 3.35z") to 0.602f,
        p("M16.498 4L9 16.22l7.498-3.35z") to 1f,
        p("M16.498 21.968v6.027L24 17.616z") to 0.602f,
        p("M16.498 27.995v-6.028L9 17.616z") to 1f,
        p("M16.498 20.573l7.497-4.353-7.497-3.348z") to 0.2f,
        p("M9 16.22l7.498 4.353v-7.701z") to 0.602f,
    )

    // Litecoin "Ł" on a 32 × 32 grid
    val litecoin = p("M10.427 19.214L9 19.768l.688-2.759 1.444-.58L13.213 8h5.129l-1.519 6.196 1.41-.571-.68 2.75-1.427.571-.848 3.483H23L22.127 24H9.252z")
}

private val SolanaBg = Color(0xFF14111F)
private val SolanaGradient = listOf(Color(0xFF00FFA3), Color(0xFF7B61FF), Color(0xFFDC1FFF))
private val EthereumBg = Color(0xFF627EEA)
private val LitecoinBg = Color(0xFF345D9D)

private fun DrawScope.drawSolana() {
    val vbW = 397.7f; val vbH = 311.7f
    val inner = size.minDimension * 0.54f
    val s = inner / vbW
    translate((size.width - vbW * s) / 2f, (size.height - vbH * s) / 2f) {
        scale(s, s, Offset.Zero) {
            val brush = Brush.linearGradient(SolanaGradient, start = Offset(360f, -37f), end = Offset(40f, 340f))
            LogoPaths.solana.forEach { drawPath(it, brush) }
        }
    }
}

private fun DrawScope.drawGlyph32(block: DrawScope.() -> Unit) {
    val s = size.minDimension / 32f
    scale(s, s, Offset.Zero) { block() }
}

/** Circular network logo (SOL / ETH / LTC). */
@Composable
fun ChainLogo(chain: Chain, size: Dp, modifier: Modifier = Modifier) {
    val bg = when (chain) { Chain.SOLANA -> SolanaBg; Chain.ETHEREUM -> EthereumBg; Chain.LITECOIN -> LitecoinBg }
    Canvas(modifier.size(size).clip(CircleShape).background(bg).semantics { contentDescription = chain.displayName }) {
        when (chain) {
            Chain.SOLANA -> drawSolana()
            Chain.ETHEREUM -> drawGlyph32 {
                // Glyph sits slightly right of centre in the source grid; nudge it to the optical centre.
                translate(-0.75f, 0f) { LogoPaths.ethereum.forEach { (path, a) -> drawPath(path, Color.White.copy(alpha = a)) } }
            }
            Chain.LITECOIN -> drawGlyph32 { drawPath(LogoPaths.litecoin, Color.White) }
        }
    }
}

/**
 * Asset avatar: network logo for native coins; for tokens a lettered disc with a small network badge so the user
 * always sees which chain a token lives on.
 */
@Composable
fun AssetAvatar(symbol: String, chain: Chain?, isNative: Boolean, size: Dp = 42.dp, modifier: Modifier = Modifier) {
    if (isNative && chain != null) { ChainLogo(chain, size, modifier); return }
    val letter = symbol.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
    Box(modifier.size(size)) {
        Box(Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(AssetGradients.token)), contentAlignment = Alignment.Center) {
            Text(letter, style = Spectra.type.bodyStrong.copy(fontSize = Spectra.type.bodyStrong.fontSize * (size.value / 42f)), color = Color.White)
        }
        if (chain != null) {
            val badge = size * 0.42f
            ChainLogo(chain, badge, Modifier.align(Alignment.BottomEnd).offset(x = badge * 0.18f, y = badge * 0.18f)
                .border(1.5.dp, Spectra.colors.background, CircleShape))
        }
    }
}
