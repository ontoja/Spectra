package app.spectra.core.crypto

import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.math.ec.ECAlgorithms
import org.bouncycastle.math.ec.ECPoint
import org.bouncycastle.math.ec.FixedPointCombMultiplier
import java.math.BigInteger

/**
 * secp256k1 ECDSA via BouncyCastle: deterministic nonces (RFC 6979), low-S normalisation (BIP-62/EIP-2)
 * and public key recovery (SEC 1 v2 §4.1.6) to derive the Ethereum recovery id.
 */
object Secp256k1 {
    private val params = CustomNamedCurves.getByName("secp256k1")
    val domain = ECDomainParameters(params.curve, params.g, params.n, params.h)
    val n: BigInteger = params.n
    private val halfN: BigInteger = n.shiftRight(1)

    data class Signature(val r: BigInteger, val s: BigInteger, val recId: Int) {
        fun toDer(): ByteArray = DERSequence(arrayOf(ASN1Integer(r), ASN1Integer(s))).encoded
        fun r32(): ByteArray = to32(r)
        fun s32(): ByteArray = to32(s)
    }

    fun isValidPrivateKey(key: ByteArray): Boolean {
        if (key.size != 32) return false
        val d = BigInteger(1, key)
        return d.signum() > 0 && d < n
    }

    fun publicPoint(privateKey: ByteArray): ECPoint {
        require(isValidPrivateKey(privateKey)) { "Invalid secp256k1 private key" }
        return FixedPointCombMultiplier().multiply(domain.g, BigInteger(1, privateKey)).normalize()
    }

    fun publicKey(privateKey: ByteArray, compressed: Boolean = true): ByteArray = publicPoint(privateKey).getEncoded(compressed)

    fun decodePoint(encoded: ByteArray): ECPoint = domain.curve.decodePoint(encoded)

    fun sign(hash32: ByteArray, privateKey: ByteArray): Signature {
        require(hash32.size == 32) { "Expected 32-byte digest" }
        val d = BigInteger(1, privateKey)
        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(d, domain))
        val (r, rawS) = signer.generateSignature(hash32).let { it[0] to it[1] }
        val s = if (rawS > halfN) n.subtract(rawS) else rawS
        val pub = publicPoint(privateKey)
        val recId = (0..3).firstOrNull { recover(hash32, r, s, it)?.equals(pub) == true }
            ?: throw IllegalStateException("Could not compute recovery id")
        return Signature(r, s, recId)
    }

    fun verify(hash32: ByteArray, r: BigInteger, s: BigInteger, publicKey: ByteArray): Boolean {
        val signer = ECDSASigner()
        signer.init(false, ECPublicKeyParameters(decodePoint(publicKey), domain))
        return signer.verifySignature(hash32, r, s)
    }

    fun recover(hash32: ByteArray, r: BigInteger, s: BigInteger, recId: Int): ECPoint? {
        require(recId in 0..3)
        if (r.signum() <= 0 || s.signum() <= 0 || r >= n || s >= n) return null
        val i = BigInteger.valueOf((recId / 2).toLong())
        val x = r.add(i.multiply(n))
        val prime = domain.curve.field.characteristic
        if (x >= prime) return null
        val encoded = byteArrayOf(if (recId and 1 == 1) 0x03 else 0x02) + to32(x)
        val bigR = try { domain.curve.decodePoint(encoded) } catch (e: IllegalArgumentException) { return null }
        if (!bigR.multiply(n).isInfinity) return null
        val e = BigInteger(1, hash32)
        val eInv = BigInteger.ZERO.subtract(e).mod(n)
        val rInv = r.modInverse(n)
        val q = ECAlgorithms.sumOfTwoMultiplies(domain.g, rInv.multiply(eInv).mod(n), bigR, rInv.multiply(s).mod(n))
        return q.normalize()
    }

    fun to32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size == 33 && raw[0].toInt() == 0 -> raw.copyOfRange(1, 33)
            raw.size < 32 -> ByteArray(32 - raw.size) + raw
            else -> throw IllegalArgumentException("Integer too large")
        }
    }
}
