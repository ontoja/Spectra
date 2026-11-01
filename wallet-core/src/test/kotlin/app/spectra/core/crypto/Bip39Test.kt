package app.spectra.core.crypto

import app.spectra.core.encoding.Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Bip39Test {
    private val vectors = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/bip39-vectors.json")!!.bufferedReader().readText()
    ).jsonObject["english"]!!.jsonArray

    @Test fun wordlistIsCanonical() {
        val bytes = Bip39::class.java.getResourceAsStream("/bip39-english.txt")!!.readBytes()
        assertEquals("2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda", Hex.encode(Hashes.sha256(bytes)))
        assertEquals(2048, Bip39.wordlist.size)
    }

    @Test fun trezorVectors() {
        assertTrue(vectors.size >= 24)
        for (v in vectors) {
            val (entropy, mnemonic, seed, xprv) = v.jsonArray.map { it.jsonPrimitive.content }
            val words = Bip39.entropyToWords(Hex.decode(entropy))
            assertEquals(mnemonic, words.joinToString(" "))
            assertEquals(Bip39.Validation.Valid, Bip39.validate(words))
            val s = Bip39.toSeed(Bip39.phraseBytes(words), "TREZOR".toCharArray())
            assertEquals(seed, Hex.encode(s))
            assertEquals(xprv, Bip32Key.fromSeed(s).toXprv())
        }
    }

    @Test fun generatesValidTwelveWordPhrases() {
        val seen = HashSet<String>()
        repeat(50) {
            val words = Bip39.generate(12)
            assertEquals(12, words.size)
            assertEquals(Bip39.Validation.Valid, Bip39.validate(words))
            assertTrue(seen.add(words.joinToString(" ")))
        }
    }

    @Test fun detectsBadChecksumUnknownWordsAndLength() {
        val good = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
        assertEquals(Bip39.Validation.InvalidChecksum, Bip39.validate(good.dropLast(1) + "abandon"))
        assertEquals(Bip39.Validation.UnknownWords(listOf(3)), Bip39.validate(good.toMutableList().also { it[3] = "bitcoinz" }))
        assertEquals(Bip39.Validation.InvalidLength(11), Bip39.validate(good.drop(1)))
    }

    @Test fun normalizesInput() {
        assertEquals(listOf("abandon", "about"), Bip39.normalizeInput("  Abandon \n  ABOUT "))
    }
}
