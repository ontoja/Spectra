package app.spectra.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class Chain(val symbol: String, val displayName: String, val nativeDecimals: Int) {
    SOLANA("SOL", "Solana", 9),
    ETHEREUM("ETH", "Ethereum", 18),
    LITECOIN("LTC", "Litecoin", 8),
}

@Serializable
enum class NetworkEnv { MAINNET, TESTNET }

/**
 * A concrete network: chain + environment. Testnets: Solana Devnet, Ethereum Sepolia, Litecoin testnet4.
 * Identity values below are used to verify RPC endpoints before use.
 */
@Serializable
data class Network(val chain: Chain, val env: NetworkEnv) {
    val id: String get() = "${chain.name.lowercase()}:${env.name.lowercase()}"
    val isTestnet: Boolean get() = env == NetworkEnv.TESTNET

    val displayName: String get() = when (chain) {
        Chain.SOLANA -> if (isTestnet) "Solana Devnet" else "Solana"
        Chain.ETHEREUM -> if (isTestnet) "Ethereum Sepolia" else "Ethereum"
        Chain.LITECOIN -> if (isTestnet) "Litecoin Testnet" else "Litecoin"
    }

    /** EVM chain id (EIP-155). */
    val evmChainId: Long? get() = if (chain == Chain.ETHEREUM) (if (isTestnet) 11155111L else 1L) else null

    /** Solana genesis hash or Litecoin genesis block hash used for chain identity verification. */
    val genesisHash: String? get() = when (chain) {
        Chain.SOLANA -> if (isTestnet) "EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG" else "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d"
        Chain.LITECOIN -> if (isTestnet) "4966625a4b2851d9fdee139e56211a0d88575f59ed816ff5e6a63deb4e3e29a0" else "12a765e31ffd4059bada1e25190f6e98c99d9714d334efa41a195a7e7e04bfe2"
        Chain.ETHEREUM -> null
    }

    fun explorerTxUrl(txId: String): String = when (chain) {
        Chain.SOLANA -> "https://explorer.solana.com/tx/$txId" + if (isTestnet) "?cluster=devnet" else ""
        Chain.ETHEREUM -> (if (isTestnet) "https://sepolia.etherscan.io/tx/" else "https://etherscan.io/tx/") + txId
        Chain.LITECOIN -> (if (isTestnet) "https://litecoinspace.org/testnet/tx/" else "https://litecoinspace.org/tx/") + txId
    }

    fun explorerAddressUrl(address: String): String = when (chain) {
        Chain.SOLANA -> "https://explorer.solana.com/address/$address" + if (isTestnet) "?cluster=devnet" else ""
        Chain.ETHEREUM -> (if (isTestnet) "https://sepolia.etherscan.io/address/" else "https://etherscan.io/address/") + address
        Chain.LITECOIN -> (if (isTestnet) "https://litecoinspace.org/testnet/address/" else "https://litecoinspace.org/address/") + address
    }

    companion object {
        fun all(includeTestnets: Boolean): List<Network> = Chain.entries.flatMap { c ->
            listOfNotNull(Network(c, NetworkEnv.MAINNET), if (includeTestnets) Network(c, NetworkEnv.TESTNET) else null)
        }
        fun fromId(id: String): Network {
            val (c, e) = id.split(':')
            return Network(Chain.valueOf(c.uppercase()), NetworkEnv.valueOf(e.uppercase()))
        }
    }
}
