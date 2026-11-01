package app.spectra.core.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.math.ec.rfc8032.Ed25519

/** Ed25519 (RFC 8032) via BouncyCastle. */
object Ed25519Keys {
    fun publicKey(seed32: ByteArray): ByteArray {
        require(seed32.size == 32) { "Ed25519 seed must be 32 bytes" }
        return Ed25519PrivateKeyParameters(seed32, 0).generatePublicKey().encoded
    }

    fun sign(seed32: ByteArray, message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seed32, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        signer.update(message, 0, message.size)
        return signer.verifySignature(signature)
    }

    /** True if the 32 bytes decode to a point on the curve (Solana's PDA "on curve" test). */
    fun isOnCurve(publicKey: ByteArray): Boolean =
        publicKey.size == 32 && Ed25519.validatePublicKeyPartial(publicKey, 0)
}
