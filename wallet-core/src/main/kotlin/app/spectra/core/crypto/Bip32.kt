package app.spectra.core.crypto

import app.spectra.core.encoding.Base58
import java.math.BigInteger

/** BIP-32 hierarchical deterministic keys on secp256k1 (private derivation only). */
class Bip32Key private constructor(
    val privateKey: ByteArray,
    val chainCode: ByteArray,
    val depth: Int,
    val parentFingerprint: Int,
    val childNumber: Long,
) {
    val publicKey: ByteArray by lazy { Secp256k1.publicKey(privateKey, compressed = true) }
    val fingerprint: Int get() = Hashes.hash160(publicKey).let {
        ((it[0].toInt() and 0xff) shl 24) or ((it[1].toInt() and 0xff) shl 16) or ((it[2].toInt() and 0xff) shl 8) or (it[3].toInt() and 0xff)
    }

    fun deriveChild(index: Long): Bip32Key {
        require(index in 0..0xffffffffL)
        val hardened = index >= DerivationPath.HARDENED
        val data = if (hardened) byteArrayOf(0) + privateKey + ser32(index) else publicKey + ser32(index)
        val i = Hashes.hmacSha512(chainCode, data)
        val il = BigInteger(1, i.copyOfRange(0, 32))
        val ki = il.add(BigInteger(1, privateKey)).mod(Secp256k1.n)
        SecureMemory.wipe(data)
        if (il >= Secp256k1.n || ki.signum() == 0) {
            SecureMemory.wipe(i)
            throw IllegalStateException("Invalid child key at index $index; BIP-32 requires skipping this index")
        }
        val child = Bip32Key(Secp256k1.to32(ki), i.copyOfRange(32, 64), depth + 1, fingerprint, index)
        SecureMemory.wipe(i)
        return child
    }

    fun derive(path: DerivationPath): Bip32Key {
        var k = this
        for (idx in path.indices) {
            val next = k.deriveChild(idx)
            if (k !== this) k.wipe()
            k = next
        }
        return k
    }

    fun wipe() = SecureMemory.wipe(privateKey, chainCode)

    /** Serialises as xprv (mainnet version bytes). Used for test-vector verification only. */
    fun toXprv(): String = serialize(0x0488ADE4, byteArrayOf(0) + privateKey)
    fun toXpub(): String = serialize(0x0488B21E, publicKey)

    private fun serialize(version: Int, key: ByteArray): String =
        Base58.encodeCheck(ser32(version.toLong() and 0xffffffffL) + byteArrayOf(depth.toByte()) +
            ser32(parentFingerprint.toLong() and 0xffffffffL) + ser32(childNumber) + chainCode + key)

    companion object {
        fun fromSeed(seed: ByteArray): Bip32Key {
            val i = Hashes.hmacSha512("Bitcoin seed".toByteArray(), seed)
            val il = i.copyOfRange(0, 32)
            require(Secp256k1.isValidPrivateKey(il)) { "Seed produced an invalid master key" }
            return Bip32Key(il, i.copyOfRange(32, 64), 0, 0, 0).also { SecureMemory.wipe(i) }
        }

        internal fun ser32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    }
}

/** SLIP-0010 derivation for Ed25519 (hardened-only), as used by Solana wallets. */
class Slip10Ed25519Key private constructor(val key: ByteArray, val chainCode: ByteArray) {
    val publicKey: ByteArray by lazy { Ed25519Keys.publicKey(key) }

    fun deriveChild(index: Long): Slip10Ed25519Key {
        require(index >= DerivationPath.HARDENED) { "Ed25519 SLIP-10 supports hardened derivation only" }
        val data = byteArrayOf(0) + key + Bip32Key.ser32(index)
        val i = Hashes.hmacSha512(chainCode, data)
        SecureMemory.wipe(data)
        return Slip10Ed25519Key(i.copyOfRange(0, 32), i.copyOfRange(32, 64)).also { SecureMemory.wipe(i) }
    }

    fun derive(path: DerivationPath): Slip10Ed25519Key {
        require(path.allHardened) { "Ed25519 SLIP-10 path must be fully hardened" }
        var k = this
        for (idx in path.indices) {
            val next = k.deriveChild(idx)
            if (k !== this) k.wipe()
            k = next
        }
        return k
    }

    fun wipe() = SecureMemory.wipe(key, chainCode)

    companion object {
        fun fromSeed(seed: ByteArray): Slip10Ed25519Key {
            val i = Hashes.hmacSha512("ed25519 seed".toByteArray(), seed)
            return Slip10Ed25519Key(i.copyOfRange(0, 32), i.copyOfRange(32, 64)).also { SecureMemory.wipe(i) }
        }

        /** Wraps a raw 32-byte Ed25519 seed (e.g. `solana-keygen` style seed[0..32]); chain code is unused. */
        fun fromRawSeed(seed32: ByteArray): Slip10Ed25519Key = Slip10Ed25519Key(seed32.copyOf(), ByteArray(32))
    }
}
