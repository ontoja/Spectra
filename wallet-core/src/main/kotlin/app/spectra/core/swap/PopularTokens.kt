package app.spectra.core.swap

import app.spectra.core.model.AssetId
import app.spectra.core.model.AssetKind
import app.spectra.core.model.Chain
import app.spectra.core.model.Network
import app.spectra.core.model.NetworkEnv
import app.spectra.core.model.TokenInfo

/**
 * Curated, widely used mainnet tokens offered in the swap picker without the user pasting an address.
 *
 * Every entry was checked against three independent sources (2026-11-03): the chain itself (ERC-20 `symbol()` /
 * `decimals()` via RPC; SPL mint account owner + decimals), the Jupiter verified token list (Solana) and LI.FI's
 * token registry. Addresses are fixed here on purpose — the list is never fetched or extended from the network,
 * so a remote source cannot inject a look-alike token. Testnets get no entries (swap providers are mainnet-only).
 */
object PopularTokens {
    private fun sol(mint: String, symbol: String, name: String, decimals: Int) =
        TokenInfo(AssetId(Network(Chain.SOLANA, NetworkEnv.MAINNET), AssetKind.SPL_TOKEN, mint), symbol, name, decimals, verified = true)

    private fun eth(address: String, symbol: String, name: String, decimals: Int) =
        TokenInfo(AssetId(Network(Chain.ETHEREUM, NetworkEnv.MAINNET), AssetKind.ERC20, address), symbol, name, decimals, verified = true)

    val solana: List<TokenInfo> = listOf(
        sol("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", "USDC", "USD Coin", 6),
        sol("Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB", "USDT", "Tether USD", 6),
        sol("JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN", "JUP", "Jupiter", 6),
        sol("mSoLzYCxHdYgdzU16g5QSh3i5K3z3KZK7ytfqcJm7So", "mSOL", "Marinade staked SOL", 9),
        sol("J1toso1uCk3RLmjorhTtrVwY9HJ7X8V9yYac6Y7kGCPn", "JitoSOL", "Jito Staked SOL", 9),
        sol("4k3Dyjzvzp8eMZWUXbBCjEvwSkkk59S5iCNLY3QrkX6R", "RAY", "Raydium", 6),
        sol("DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263", "BONK", "Bonk", 5),
        sol("EKpQGSJtjMFqKZ9KQanSqYXRcF8fBopzLHYxdM65zcjm", "WIF", "dogwifhat", 6),
    )

    val ethereum: List<TokenInfo> = listOf(
        eth("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", "USDC", "USD Coin", 6),
        eth("0xdAC17F958D2ee523a2206206994597C13D831ec7", "USDT", "Tether USD", 6),
        eth("0x6B175474E89094C44Da98b954EedeAC495271d0F", "DAI", "Dai Stablecoin", 18),
        eth("0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2", "WETH", "Wrapped Ether", 18),
        eth("0x2260FAC5E5542a773Aa44fBCfeDf7C193bc2C599", "WBTC", "Wrapped Bitcoin", 8),
        eth("0x514910771AF9Ca656af840dff83E8264EcF986CA", "LINK", "Chainlink", 18),
        eth("0x1f9840a85d5aF5bf1D1762F925BDADdC4201F984", "UNI", "Uniswap", 18),
        eth("0x7Fc66500c84A76Ad7e9c93437bFc5Ac33E2DDaE9", "AAVE", "Aave", 18),
        eth("0x6982508145454Ce325dDbE47a25d4ec3d2311933", "PEPE", "Pepe", 18),
    )

    fun forNetwork(network: Network): List<TokenInfo> = when {
        network.isTestnet -> emptyList()
        network.chain == Chain.SOLANA -> solana
        network.chain == Chain.ETHEREUM -> ethereum
        else -> emptyList()
    }
}
