package app.spectra.core.crypto

import app.spectra.core.encoding.Hex
import org.junit.Assert.assertEquals
import org.junit.Test

class HdDerivationTest {
    @Test fun bip32TestVector1() {
        val master = Bip32Key.fromSeed(Hex.decode("000102030405060708090a0b0c0d0e0f"))
        val expected = mapOf(
            "m" to "xprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPPqjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi",
            "m/0'" to "xprv9uHRZZhk6KAJC1avXpDAp4MDc3sQKNxDiPvvkX8Br5ngLNv1TxvUxt4cV1rGL5hj6KCesnDYUhd7oWgT11eZG7XnxHrnYeSvkzY7d2bhkJ7",
            "m/0'/1" to "xprv9wTYmMFdV23N2TdNG573QoEsfRrWKQgWeibmLntzniatZvR9BmLnvSxqu53Kw1UmYPxLgboyZQaXwTCg8MSY3H2EU4pWcQDnRnrVA1xe8fs",
            "m/0'/1/2'" to "xprv9z4pot5VBttmtdRTWfWQmoH1taj2axGVzFqSb8C9xaxKymcFzXBDptWmT7FwuEzG3ryjH4ktypQSAewRiNMjANTtpgP4mLTj34bhnZX7UiM",
            "m/0'/1/2'/2" to "xprvA2JDeKCSNNZky6uBCviVfJSKyQ1mDYahRjijr5idH2WwLsEd4Hsb2Tyh8RfQMuPh7f7RtyzTtdrbdqqsunu5Mm3wDvUAKRHSC34sJ7in334",
            "m/0'/1/2'/2/1000000000" to "xprvA41z7zogVVwxVSgdKUHDy1SKmdb533PjDz7J6N6mV6uS3ze1ai8FHa8kmHScGpWmj4WggLyQjgPie1rFSruoUihUZREPSL39UNdE3BBDu76",
        )
        for ((path, xprv) in expected) assertEquals(path, xprv, master.derive(DerivationPath.parse(path)).toXprv())
    }

    @Test fun slip10Ed25519TestVector1() {
        val master = Slip10Ed25519Key.fromSeed(Hex.decode("000102030405060708090a0b0c0d0e0f"))
        val expected = listOf(
            Triple("m/0'", "68e0fe46dfb67e368c75379acec591dad19df3cde26e63b93a8e704f1dade7a3", "8c8a13df77a28f3445213a0f432fde644acaa215fc72dcdf300d5efaa85d350c"),
            Triple("m/0'/1'", "b1d0bad404bf35da785a64ca1ac54b2617211d2777696fbffaf208f746ae84f2", "1932a5270f335bed617d5b935c80aedb1a35bd9fc1e31acafd5372c30f5c1187"),
            Triple("m/0'/1'/2'/2'/1000000000'", "8f94d394a8e8fd6b1bc2f3f49f5c47e385281d5c17e65324b0f62483e37e8793", "3c24da049451555d51a7014a37337aa4e12d41e485abccfa46b47dfb2af54b7a"),
        )
        assertEquals("2b4be7f19ee27bbf30c667b642d5f4aa69fd169872f8fc3059c08ebae2eb19e7", Hex.encode(master.key))
        assertEquals("90046a93de5380a72b5e45010748567d5ea02bbf6522f979e05c0d8d8ca9fffb", Hex.encode(master.chainCode))
        for ((path, priv, pub) in expected) {
            val k = master.derive(DerivationPath.parse(path))
            assertEquals(priv, Hex.encode(k.key))
            assertEquals(pub, Hex.encode(k.publicKey))
        }
    }

    @Test fun pathParsing() {
        assertEquals("m/84'/2'/0'/0/5", DerivationPath.parse("m/84h/2'/0'/0/5").toString())
    }
}

class PublicDerivationTest {
    @Test fun publicDerivationMatchesPrivate() {
        val master = Bip32Key.fromSeed(app.spectra.core.encoding.Hex.decode("000102030405060708090a0b0c0d0e0f"))
        val account = master.derive(DerivationPath.parse("m/84'/2'/0'"))
        val pub = account.publicNode()
        for (role in 0L..1L) for (i in 0L..3L) {
            assertEquals(
                app.spectra.core.encoding.Hex.encode(account.deriveChild(role).deriveChild(i).publicKey),
                app.spectra.core.encoding.Hex.encode(pub.deriveChild(role).deriveChild(i).publicKey),
            )
        }
        assertEquals(pub.serialize(), Bip32PublicNode.deserialize(pub.serialize()).serialize())
    }
}
