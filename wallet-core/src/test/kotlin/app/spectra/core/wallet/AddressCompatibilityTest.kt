package app.spectra.core.wallet

import app.spectra.core.chain.AddressCheck
import app.spectra.core.chain.AddressError
import app.spectra.core.chain.AddressValidator
import app.spectra.core.chain.EthereumAddress
import app.spectra.core.crypto.Bip39
import app.spectra.core.model.AddressRole
import app.spectra.core.model.Chain
import app.spectra.core.model.DerivationScheme
import app.spectra.core.model.Network
import app.spectra.core.model.NetworkEnv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compares Spectra derivation against vectors produced by an independent implementation
 * (bip_utils 2.x, see reference-addresses.txt) for every supported scheme.
 */
class AddressCompatibilityTest {
    private val reference: Map<String, Map<String, String>> = run {
        val out = LinkedHashMap<String, MutableMap<String, String>>()
        var current = ""
        javaClass.getResourceAsStream("/reference-addresses.txt")!!.bufferedReader().readLines().forEach { line ->
            if (line.startsWith("MNEMONIC ")) { current = line.removePrefix("MNEMONIC "); out[current] = LinkedHashMap() }
            else if (line.isNotBlank()) { val i = line.lastIndexOf(' '); out.getValue(current)[line.substring(0, i)] = line.substring(i + 1) }
        }
        out
    }

    private val main = NetworkEnv.MAINNET
    private val test = NetworkEnv.TESTNET

    @Test fun allSchemesMatchReference() {
        assertEquals(2, reference.size)
        for ((mnemonic, expected) in reference) {
            Keyring.fromMnemonic(Bip39.phraseBytes(mnemonic.split(" "))).use { k ->
                var checked = 0
                for ((key, address) in expected) {
                    val parts = key.split(" ")
                    val label = parts[0]
                    val account = parts[1].toInt()
                    val actual = when (label) {
                        "LTC_BIP84" -> k.deriveAddress(Network(Chain.LITECOIN, main), DerivationScheme.LTC_BIP84, account, AddressRole.entries[parts[2].toInt()], parts[3].toInt())
                        "LTC_BIP84_TEST" -> k.deriveAddress(Network(Chain.LITECOIN, test), DerivationScheme.LTC_BIP84, account, AddressRole.entries[parts[2].toInt()], parts[3].toInt())
                        else -> {
                            val scheme = DerivationScheme.valueOf(label)
                            k.deriveAddress(Network(scheme.chain, main), scheme, account)
                        }
                    }.address
                    assertEquals("$label $key", address, actual)
                    checked++
                }
                assertEquals(29, checked)
            }
        }
    }

    @Test fun eip55ChecksumExamples() {
        // EIP-55 specification examples.
        listOf("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
            "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB", "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb").forEach {
            assertEquals(it, EthereumAddress.checksum(it.lowercase()))
            assertTrue(EthereumAddress.validate(it) is AddressCheck.Valid)
        }
        assertEquals(AddressCheck.Invalid(AddressError.BAD_CHECKSUM), EthereumAddress.validate("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAeD"))
    }

    @Test fun networkMismatchDetection() {
        val ltcMain = Network(Chain.LITECOIN, main)
        val ltcTest = Network(Chain.LITECOIN, test)
        assertTrue(AddressValidator.validate(ltcMain, "ltc1qjmxnz78nmc8nq77wuxh25n2es7rzm5c2rkk4wh") is AddressCheck.Valid)
        assertEquals(AddressCheck.Invalid(AddressError.WRONG_NETWORK), AddressValidator.validate(ltcTest, "ltc1qjmxnz78nmc8nq77wuxh25n2es7rzm5c2rkk4wh"))
        assertEquals(AddressCheck.Invalid(AddressError.WRONG_NETWORK), AddressValidator.validate(ltcMain, "tltc1q6rz28mcfaxtmd6v789l9rrlrusdprr9pesrjxk"))
        assertEquals(AddressCheck.Invalid(AddressError.WRONG_NETWORK), AddressValidator.validate(ltcMain, "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"))
        assertTrue(AddressValidator.validate(ltcMain, "LUWPbpM43E2p7ZSh8cyTBEkvpHmr3cB8Ez") is AddressCheck.Valid)
        assertTrue(AddressValidator.validate(ltcMain, "M7wtsL7wSHDBJVMWWhtQfTMSYYkyooAAXM") is AddressCheck.Valid)
        assertEquals(AddressCheck.Invalid(AddressError.BAD_FORMAT), AddressValidator.validate(Network(Chain.SOLANA, main), "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"))
        assertEquals(AddressCheck.Invalid(AddressError.BAD_FORMAT), AddressValidator.validate(Network(Chain.ETHEREUM, main), "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk"))
    }

    @Test fun solanaWalletAddressIsOnCurve() {
        val r = AddressValidator.validate(Network(Chain.SOLANA, main), "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk")
        assertTrue(r is AddressCheck.Valid && r.warning == null)
    }
}
