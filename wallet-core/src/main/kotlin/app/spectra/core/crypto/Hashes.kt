package app.spectra.core.crypto

import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.KeccakDigest
import org.bouncycastle.crypto.digests.RIPEMD160Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter

/** Thin wrappers over BouncyCastle digests. No primitives are implemented here. */
object Hashes {
    private fun run(d: Digest, vararg parts: ByteArray): ByteArray {
        parts.forEach { d.update(it, 0, it.size) }
        return ByteArray(d.digestSize).also { d.doFinal(it, 0) }
    }

    fun sha256(vararg parts: ByteArray): ByteArray = run(SHA256Digest(), *parts)
    fun sha512(vararg parts: ByteArray): ByteArray = run(SHA512Digest(), *parts)
    fun doubleSha256(data: ByteArray): ByteArray = sha256(sha256(data))
    fun ripemd160(data: ByteArray): ByteArray = run(RIPEMD160Digest(), data)
    fun hash160(data: ByteArray): ByteArray = ripemd160(sha256(data))
    fun keccak256(vararg parts: ByteArray): ByteArray = run(KeccakDigest(256), *parts)

    fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        val mac = HMac(SHA512Digest())
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        return ByteArray(mac.macSize).also { mac.doFinal(it, 0) }
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = HMac(SHA256Digest())
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        return ByteArray(mac.macSize).also { mac.doFinal(it, 0) }
    }

    fun pbkdf2HmacSha512(password: ByteArray, salt: ByteArray, iterations: Int, lengthBytes: Int): ByteArray {
        val gen = PKCS5S2ParametersGenerator(SHA512Digest())
        gen.init(password, salt, iterations)
        return (gen.generateDerivedParameters(lengthBytes * 8) as KeyParameter).key
    }
}
