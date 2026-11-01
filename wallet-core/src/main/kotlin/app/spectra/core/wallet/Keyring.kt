package app.spectra.core.wallet

import app.spectra.core.chain.EthereumAddress
import app.spectra.core.chain.LitecoinAddress
import app.spectra.core.chain.SolanaAddress
import app.spectra.core.crypto.Bip32Key
import app.spectra.core.crypto.Bip39
import app.spectra.core.crypto.DerivationPath
import app.spectra.core.crypto.SecureMemory
import app.spectra.core.crypto.Slip10Ed25519Key
import app.spectra.core.encoding.Hex
import app.spectra.core.model.AddressRole
import app.spectra.core.model.Chain
import app.spectra.core.model.DerivationScheme
import app.spectra.core.model.DerivedAddress
import app.spectra.core.model.Network
import app.spectra.core.model.NetworkEnv

/**
 * Short-lived holder of a BIP-39 seed. Created only after fresh user authorisation and closed (wiped) immediately
 * after the requested derivation or signature. Never persisted, logged or sent anywhere.
 */
class Keyring private constructor(private val seed: ByteArray) : AutoCloseable {
    private var closed = false

    /** Ed25519 32-byte private seed for a Solana account. Caller must wipe. */
    fun solanaPrivateKey(scheme: DerivationScheme, account: Int): ByteArray {
        check(!closed)
        require(scheme.chain == Chain.SOLANA)
        if (scheme == DerivationScheme.SOL_KEYGEN_SEED) {
            require(account == 0) { "solana-keygen seed scheme has a single account" }
            return seed.copyOfRange(0, 32)
        }
        val master = Slip10Ed25519Key.fromSeed(seed)
        val node = master.derive(scheme.path(account, NetworkEnv.MAINNET)!!)
        master.wipe()
        return node.key.copyOf().also { node.wipe() }
    }

    /** secp256k1 node at the full [path]. Caller must wipe the returned key. */
    fun secpNode(path: DerivationPath): Bip32Key {
        check(!closed)
        val master = Bip32Key.fromSeed(seed)
        return master.derive(path).also { master.wipe() }
    }

    /** Full path of a single address inside a scheme. */
    fun addressPath(network: Network, scheme: DerivationScheme, account: Int, role: AddressRole, index: Int): String {
        require(scheme.chain == network.chain)
        return when (network.chain) {
            Chain.LITECOIN -> scheme.path(account, network.env)!!.child(role.chainIndex.toLong()).child(index.toLong()).toString()
            else -> scheme.path(account, network.env)?.toString() ?: "seed[0..32]"
        }
    }

    fun deriveAddress(network: Network, scheme: DerivationScheme, account: Int, role: AddressRole = AddressRole.RECEIVE, index: Int = 0): DerivedAddress {
        require(scheme.chain == network.chain) { "Scheme does not match chain" }
        val path = addressPath(network, scheme, account, role, index)
        return when (network.chain) {
            Chain.SOLANA -> {
                val sk = solanaPrivateKey(scheme, account)
                val pub = app.spectra.core.crypto.Ed25519Keys.publicKey(sk)
                SecureMemory.wipe(sk)
                DerivedAddress(network, scheme, account, SolanaAddress.encode(pub), path, publicKeyHex = Hex.encode(pub))
            }
            Chain.ETHEREUM -> {
                val node = secpNode(DerivationPath.parse(path))
                val pub = node.publicKey
                node.wipe()
                DerivedAddress(network, scheme, account, EthereumAddress.fromPublicKey(pub), path, publicKeyHex = Hex.encode(pub))
            }
            Chain.LITECOIN -> {
                val node = secpNode(DerivationPath.parse(path))
                val pub = node.publicKey
                node.wipe()
                val address = when (scheme) {
                    DerivationScheme.LTC_BIP84 -> LitecoinAddress.p2wpkh(pub, network.env)
                    DerivationScheme.LTC_BIP49 -> LitecoinAddress.p2shP2wpkh(pub, network.env)
                    DerivationScheme.LTC_BIP44 -> LitecoinAddress.p2pkh(pub, network.env)
                    else -> error("Unsupported Litecoin scheme")
                }
                DerivedAddress(network, scheme, account, address, path, role, index, Hex.encode(pub))
            }
        }
    }

    override fun close() {
        if (!closed) { SecureMemory.wipe(seed); closed = true }
    }

    companion object {
        /** Builds a keyring from mnemonic bytes (NFKD UTF-8). The mnemonic bytes are not retained. */
        fun fromMnemonic(mnemonicUtf8: ByteArray, passphrase: CharArray = CharArray(0)): Keyring =
            Keyring(Bip39.toSeed(mnemonicUtf8, passphrase))

        /** For tests that start from a raw seed. */
        fun fromSeed(seed: ByteArray): Keyring = Keyring(seed.copyOf())
    }
}
