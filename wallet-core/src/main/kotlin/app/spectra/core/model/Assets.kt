package app.spectra.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class AssetKind { NATIVE, SPL_TOKEN, SPL_TOKEN_2022, ERC20 }

/** Stable asset identifier: network + kind + contract/mint address (null for native). */
@Serializable
data class AssetId(val network: Network, val kind: AssetKind, val address: String? = null) {
    val key: String get() = "${network.id}:${kind.name}:${address ?: "native"}"
    val isNative get() = kind == AssetKind.NATIVE

    companion object {
        fun native(network: Network) = AssetId(network, AssetKind.NATIVE)
        fun fromKey(key: String): AssetId {
            val p = key.split(':')
            return AssetId(Network.fromId("${p[0]}:${p[1]}"), AssetKind.valueOf(p[2]), p[3].takeIf { it != "native" })
        }
    }
}

/** Token support verdict for sending. Metadata is untrusted and only ever displayed as plain text. */
@Serializable
enum class TokenSupport { SUPPORTED, WARN, UNSUPPORTED }

@Serializable
data class TokenInfo(
    val id: AssetId,
    val symbol: String,
    val name: String,
    val decimals: Int?,
    val logoUrl: String? = null,
    val coingeckoId: String? = null,
    val verified: Boolean = false,
    val possibleSpam: Boolean = false,
    val support: TokenSupport = TokenSupport.SUPPORTED,
    val supportNote: String? = null,
) {
    companion object {
        fun native(network: Network) = TokenInfo(
            AssetId.native(network), network.chain.symbol, network.chain.displayName, network.chain.nativeDecimals,
            coingeckoId = if (network.isTestnet) null else when (network.chain) {
                Chain.SOLANA -> "solana"; Chain.ETHEREUM -> "ethereum"; Chain.LITECOIN -> "litecoin"
            },
            verified = true,
        )

        private val CONTROL = Regex("[\\p{Cc}\\p{Cf}]")

        /** Neutralises untrusted metadata: strips control/format characters (incl. bidi overrides), clamps length. */
        fun sanitize(text: String?, max: Int): String =
            (text ?: "").replace(CONTROL, "").trim().take(max).ifEmpty { "?" }

        private val SPAM_HINTS = listOf("http", "www.", ".com", ".io", ".xyz", ".org", "claim", "visit", "reward", "airdrop", "t.me", "voucher")

        fun looksLikeSpam(symbol: String, name: String): Boolean {
            val s = (symbol + " " + name).lowercase()
            return SPAM_HINTS.any { s.contains(it) } || s.any { it.code > 0x2000 && !it.isLetterOrDigit() }
        }
    }
}

/** [amount] is the spendable/confirmed balance; [pending] is incoming or unconfirmed value not yet spendable. */
data class AssetBalance(val token: TokenInfo, val amount: Amount, val accountAddress: String? = null, val pending: Amount? = null)
