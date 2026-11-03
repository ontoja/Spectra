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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
    // USDC: "$" stroke and the two side arcs of Circle's mark, on a 32 × 32 grid
    val usdcDollar = p("M19.6 12.4C19.2 11 17.9 10.2 16 10.2C13.9 10.2 12.6 11.2 12.6 12.8C12.6 14.5 14.2 15.1 16 15.6C18 16.1 19.6 16.8 19.6 18.7C19.6 20.3 18.1 21.4 16 21.4C14 21.4 12.6 20.6 12.3 19.1M16 8.2V10.2M16 21.4V23.6")
    val usdcArcs = p("M12.6 7.4A9.4 9.4 0 0 0 12.6 24.6M19.4 7.4A9.4 9.4 0 0 1 19.4 24.6")

    // Tether "T" with its ring, on a 32 × 32 grid
    val tether = p("M17.922 17.383v-.002c-.11.008-.677.042-1.942.042-1.01 0-1.721-.03-1.971-.042v.003c-3.888-.171-6.79-.848-6.79-1.658 0-.809 2.902-1.486 6.79-1.66v2.644c.254.018.982.061 1.988.061 1.207 0 1.812-.05 1.925-.06v-2.643c3.88.173 6.775.85 6.775 1.658 0 .81-2.895 1.485-6.775 1.657m0-3.59v-2.366h5.414V7.819H8.595v3.608h5.414v2.365c-4.4.202-7.709 1.074-7.709 2.118 0 1.044 3.309 1.915 7.709 2.118v7.582h3.913v-7.584c4.393-.202 7.694-1.073 7.694-2.116 0-1.043-3.301-1.914-7.694-2.117")

    val litecoin = p("M10.427 19.214L9 19.768l.688-2.759 1.444-.58L13.213 8h5.129l-1.519 6.196 1.41-.571-.68 2.75-1.427.571-.848 3.483H23L22.127 24H9.252z")
}

private val SolanaBg = Color(0xFF14111F)
private val SolanaGradient = listOf(Color(0xFF00FFA3), Color(0xFF7B61FF), Color(0xFFDC1FFF))
private val EthereumBg = Color(0xFF627EEA)
private val LitecoinBg = Color(0xFF345D9D)
private val UsdcBg = Color(0xFF2775CA)
private val TetherBg = Color(0xFF26A17B)

private enum class KnownToken { USDC, USDT }

/**
 * Bundled logos for a few major stablecoins, matched strictly by mainnet contract / mint address — never by symbol,
 * so a spam token calling itself "USDC" keeps the generic avatar.
 */
private val knownTokens: Map<String, KnownToken> = mapOf(
    "solana:mainnet:EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v" to KnownToken.USDC,
    "solana:mainnet:Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB" to KnownToken.USDT,
    "ethereum:mainnet:0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48" to KnownToken.USDC,
    "ethereum:mainnet:0xdac17f958d2ee523a2206206994597c13d831ec7" to KnownToken.USDT,
)

private fun knownToken(chain: Chain, testnet: Boolean, address: String?): KnownToken? {
    if (testnet || address == null) return null
    val a = if (chain == Chain.ETHEREUM) address.lowercase() else address
    return knownTokens["${chain.name.lowercase()}:mainnet:$a"]
}

@Composable
private fun KnownTokenLogo(token: KnownToken, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size).clip(CircleShape).background(if (token == KnownToken.USDC) UsdcBg else TetherBg).semantics { contentDescription = token.name }) {
        drawGlyph32 {
            when (token) {
                KnownToken.USDC -> {
                    drawPath(LogoPaths.usdcArcs, Color.White, style = Stroke(width = 1.7f, cap = StrokeCap.Round))
                    drawPath(LogoPaths.usdcDollar, Color.White, style = Stroke(width = 2.1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                KnownToken.USDT -> drawPath(LogoPaths.tether, Color.White)
            }
        }
    }
}

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
fun AssetAvatar(symbol: String, chain: Chain?, isNative: Boolean, size: Dp = 42.dp, modifier: Modifier = Modifier, address: String? = null, testnet: Boolean = false) {
    if (isNative && chain != null) { ChainLogo(chain, size, modifier); return }
    val letter = symbol.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
    val known = chain?.let { knownToken(it, testnet, address) }
    Box(modifier.size(size)) {
        if (known != null) KnownTokenLogo(known, size)
        else Box(Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(AssetGradients.token)), contentAlignment = Alignment.Center) {
            Text(letter, style = Spectra.type.bodyStrong.copy(fontSize = Spectra.type.bodyStrong.fontSize * (size.value / 42f)), color = Color.White)
        }
        if (chain != null) {
            val badge = size * 0.42f
            ChainLogo(chain, badge, Modifier.align(Alignment.BottomEnd).offset(x = badge * 0.18f, y = badge * 0.18f)
                .border(1.5.dp, Spectra.colors.background, CircleShape))
        }
    }
}

/** Avatar for a concrete asset: network logo for native coins, bundled logo for known stablecoins, else lettered disc. */
@Composable
fun AssetAvatar(token: app.spectra.core.model.TokenInfo, size: Dp = 42.dp, modifier: Modifier = Modifier) =
    AssetAvatar(token.symbol, token.id.network.chain, token.id.isNative, size, modifier, token.id.address, token.id.network.isTestnet)
