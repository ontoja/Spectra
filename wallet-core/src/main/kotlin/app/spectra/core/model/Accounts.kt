package app.spectra.core.model

import app.spectra.core.crypto.DerivationPath
import kotlinx.serialization.Serializable

/** Address/derivation schemes. A recovered wallet keeps exactly the scheme the user selected. */
@Serializable
enum class DerivationScheme(val chain: Chain, val label: String) {
    /** Phantom, Solflare, Backpack default: m/44'/501'/{account}'/0' */
    SOL_BIP44_CHANGE(Chain.SOLANA, "m/44'/501'/n'/0'"),
    /** Older Solflare / Ledger style: m/44'/501'/{account}' */
    SOL_BIP44(Chain.SOLANA, "m/44'/501'/n'"),
    /** solana-keygen default without a derivation path: first 32 bytes of the BIP-39 seed. Single account only. */
    SOL_KEYGEN_SEED(Chain.SOLANA, "solana-keygen (seed[0..32])"),
    /** MetaMask / most wallets: m/44'/60'/0'/0/{account} */
    ETH_BIP44(Chain.ETHEREUM, "m/44'/60'/0'/0/n"),
    /** Ledger Live: m/44'/60'/{account}'/0/0 */
    ETH_LEDGER_LIVE(Chain.ETHEREUM, "m/44'/60'/n'/0/0"),
    /** Native SegWit P2WPKH (BIP-84). The only Litecoin scheme Spectra can spend from in v0.1. */
    LTC_BIP84(Chain.LITECOIN, "m/84'/c'/n'/x/i (ltc1…)"),
    /** Nested SegWit P2SH-P2WPKH (BIP-49). Discovery/display only in v0.1. */
    LTC_BIP49(Chain.LITECOIN, "m/49'/c'/n'/x/i (M…)"),
    /** Legacy P2PKH (BIP-44). Discovery/display only in v0.1. */
    LTC_BIP44(Chain.LITECOIN, "m/44'/c'/n'/x/i (L…)");

    val canSpend: Boolean get() = this != LTC_BIP49 && this != LTC_BIP44

    /** Path to the key controlling the account (for Litecoin: the account-level node). */
    fun path(account: Int, env: NetworkEnv): DerivationPath? {
        val ltcCoin = if (env == NetworkEnv.TESTNET) 1 else 2
        return when (this) {
            SOL_BIP44_CHANGE -> DerivationPath.parse("m/44'/501'/$account'/0'")
            SOL_BIP44 -> DerivationPath.parse("m/44'/501'/$account'")
            SOL_KEYGEN_SEED -> null
            ETH_BIP44 -> DerivationPath.parse("m/44'/60'/0'/0/$account")
            ETH_LEDGER_LIVE -> DerivationPath.parse("m/44'/60'/$account'/0/0")
            LTC_BIP84 -> DerivationPath.parse("m/84'/$ltcCoin'/$account'")
            LTC_BIP49 -> DerivationPath.parse("m/49'/$ltcCoin'/$account'")
            LTC_BIP44 -> DerivationPath.parse("m/44'/$ltcCoin'/$account'")
        }
    }

    companion object {
        fun defaultFor(chain: Chain) = when (chain) {
            Chain.SOLANA -> SOL_BIP44_CHANGE
            Chain.ETHEREUM -> ETH_BIP44
            Chain.LITECOIN -> LTC_BIP84
        }
        fun forChain(chain: Chain) = entries.filter { it.chain == chain }
    }
}

/** Litecoin address roles inside a BIP-84 account. */
@Serializable
enum class AddressRole(val chainIndex: Int) { RECEIVE(0), CHANGE(1) }

/** Public, non-secret description of a derived address. */
@Serializable
data class DerivedAddress(
    val network: Network,
    val scheme: DerivationScheme,
    val accountIndex: Int,
    val address: String,
    val path: String,
    val role: AddressRole = AddressRole.RECEIVE,
    val addressIndex: Int = 0,
    val publicKeyHex: String,
)
