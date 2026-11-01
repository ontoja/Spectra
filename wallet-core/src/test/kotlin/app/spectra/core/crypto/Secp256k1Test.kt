package app.spectra.core.crypto

import app.spectra.core.encoding.Hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class Secp256k1Test {
    @Test fun signaturesAreLowSVerifiableAndRecoverable() {
        repeat(20) { i ->
            val priv = Hashes.sha256(byteArrayOf(i.toByte(), 7))
            val hash = Hashes.sha256("message $i".toByteArray())
            val sig = Secp256k1.sign(hash, priv)
            assertTrue(sig.s <= Secp256k1.n.shiftRight(1))
            val pub = Secp256k1.publicKey(priv, compressed = true)
            assertTrue(Secp256k1.verify(hash, sig.r, sig.s, pub))
            assertArrayEquals(Secp256k1.publicKey(priv, false), Secp256k1.recover(hash, sig.r, sig.s, sig.recId)!!.getEncoded(false))
        }
    }

    @Test fun deterministicNoncesRfc6979() {
        // Widely published secp256k1/RFC6979 vector: key = 1, message "Satoshi Nakamoto" (SHA-256).
        val priv = Secp256k1.to32(BigInteger.ONE)
        val sig = Secp256k1.sign(Hashes.sha256("Satoshi Nakamoto".toByteArray()), priv)
        assertEquals("934b1ea10a4b3c1757e2b0c017d0b6143ce3c9a7e6a4a49860d7a6ab210ee3d8", Hex.encode(sig.r32()))
        assertEquals("2442ce9d2b916064108014783e923ec36b49743e2ffa1c4496f01a512aafd9e5", Hex.encode(sig.s32()))
    }
}
