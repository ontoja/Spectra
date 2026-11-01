package app.spectra.core.encoding

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EncodingTest {
    @Test fun bip173ValidAddresses() {
        // From BIP-173 test vectors.
        val bc = Bech32.decodeSegwit("bc", "BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4")
        assertNotNull(bc)
        assertEquals("751e76e8199196d454941c45d1b3a323f1433bd6", Hex.encode(bc!!.program))
        assertNotNull(Bech32.decodeSegwit("tb", "tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sl5k7"))
    }

    @Test fun bip173InvalidAddresses() {
        listOf(
            "tc1qw508d6qejxtdg4y5r3zarvary0c5xw7kg3g4ty",
            "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t5",
            "bc1rw5uspcuh",
            "BC1QR508D6QEJXTDG4Y5R3ZARVARYV98GJ9P",
            "tb1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3q0sL5k7",
            "bc1zw508d6qejxtdg4y5r3zarvaryvqyzf3du",
            "bc1gmk9yu",
        ).forEach { assertNull(it, Bech32.decodeSegwit(if (it.lowercase().startsWith("tb")) "tb" else "bc", it)) }
    }

    @Test fun bip350Bech32m() {
        assertNotNull(Bech32.decodeSegwit("bc", "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0"))
        // v1 encoded with bech32 (not bech32m) must be rejected.
        assertNull(Bech32.decodeSegwit("bc", "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqh2y7hd"))
    }

    @Test fun base58RoundTrip() {
        assertEquals("1112", Base58.encode(byteArrayOf(0, 0, 0, 1)))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1), Base58.decode("1112"))
        val payload = Hex.decode("00f54a5851e9372b87810a8e60cdd2e7cfd80b6e31")
        assertEquals("1PMycacnJaSqwwJqjawXBErnLsZ7RkXUAs", Base58.encodeCheck(payload))
    }

    @Test fun rlpYellowPaperExamples() {
        assertEquals("83646f67", Hex.encode(Rlp.encode(Rlp.bytes("dog".toByteArray()))))
        assertEquals("c88363617483646f67", Hex.encode(Rlp.encode(Rlp.list(Rlp.bytes("cat".toByteArray()), Rlp.bytes("dog".toByteArray())))))
        assertEquals("80", Hex.encode(Rlp.encode(Rlp.uint(0))))
        assertEquals("0f", Hex.encode(Rlp.encode(Rlp.uint(15))))
        assertEquals("820400", Hex.encode(Rlp.encode(Rlp.uint(1024))))
        assertEquals("c0", Hex.encode(Rlp.encode(Rlp.list())))
    }
}
