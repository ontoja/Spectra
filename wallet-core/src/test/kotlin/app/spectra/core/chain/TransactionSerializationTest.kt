package app.spectra.core.chain

import app.spectra.core.chain.ethereum.CallDescription
import app.spectra.core.chain.ethereum.Eip1559Transaction
import app.spectra.core.chain.ethereum.Erc20Abi
import app.spectra.core.chain.litecoin.CoinSelectionException
import app.spectra.core.chain.litecoin.CoinSelectionError
import app.spectra.core.chain.litecoin.CoinSelector
import app.spectra.core.chain.litecoin.LitecoinTransactions
import app.spectra.core.chain.litecoin.SigningInput
import app.spectra.core.chain.litecoin.TxOutput
import app.spectra.core.chain.litecoin.Utxo
import app.spectra.core.chain.solana.LegacyMessage
import app.spectra.core.chain.solana.ParsedSolanaTransaction
import app.spectra.core.chain.solana.PublicKey
import app.spectra.core.chain.solana.SolanaPrograms
import app.spectra.core.chain.solana.SolanaTransactions
import app.spectra.core.chain.solana.SystemInstructions
import app.spectra.core.crypto.Ed25519Keys
import app.spectra.core.crypto.Hashes
import app.spectra.core.crypto.Secp256k1
import app.spectra.core.encoding.Hex
import app.spectra.core.model.NetworkEnv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/** Byte-exact comparison with transactions produced by eth-account and solders (reference-transactions.txt). */
class TransactionSerializationTest {
    private val ref = javaClass.getResourceAsStream("/reference-transactions.txt")!!.bufferedReader().readLines()
        .filter { it.isNotBlank() }.associate { it.substringBefore(' ') to it.substringAfter(' ') }

    @Test fun ethereumEip1559TransferMatchesEthAccount() {
        val tx = Eip1559Transaction(11155111, BigInteger.valueOf(7), BigInteger.valueOf(1_500_000_000), BigInteger.valueOf(30_000_000_000),
            BigInteger.valueOf(21000), "0x3535353535353535353535353535353535353535", BigInteger.TEN.pow(16))
        val signed = tx.sign(Hex.decode(ref.getValue("ETH_KEY")))
        assertEquals(ref.getValue("ETH_RAW"), Hex.encode(signed.raw))
        assertEquals("0x" + ref.getValue("ETH_HASH"), signed.hash)
    }

    @Test fun ethereumErc20TransferMatchesEthAccount() {
        val data = Erc20Abi.transfer("0x3535353535353535353535353535353535353535", BigInteger.valueOf(2_500_000))
        val tx = Eip1559Transaction(1, BigInteger.ZERO, BigInteger.valueOf(1_500_000_000), BigInteger.valueOf(30_000_000_000),
            BigInteger.valueOf(65000), "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", BigInteger.ZERO, data)
        val signed = tx.sign(Hex.decode(ref.getValue("ETH_KEY")))
        assertEquals(ref.getValue("ETH_RAW_ERC20"), Hex.encode(signed.raw))
        assertEquals("0x" + ref.getValue("ETH_HASH_ERC20"), signed.hash)
        val d = Erc20Abi.describe(data)
        assertTrue(d is CallDescription.TokenTransfer && d.amount == BigInteger.valueOf(2_500_000))
        assertTrue(Erc20Abi.describe(Hex.decode("deadbeef00")) is CallDescription.Unknown)
        val approval = Erc20Abi.describe(Erc20Abi.approve("0x3535353535353535353535353535353535353535", Erc20Abi.UNLIMITED))
        assertTrue(approval is CallDescription.TokenApproval && approval.isUnlimited)
    }

    @Test fun abiStringDecoding() {
        val enc = "0000000000000000000000000000000000000000000000000000000000000020" +
            "0000000000000000000000000000000000000000000000000000000000000004" +
            "5553444300000000000000000000000000000000000000000000000000000000"
        assertEquals("USDC", Erc20Abi.decodeString(enc))
        assertEquals("MKR", Erc20Abi.decodeString("4d4b520000000000000000000000000000000000000000000000000000000000"))
        assertEquals(null, Erc20Abi.decodeString("0x1234"))
    }

    @Test fun solanaTransferMatchesSolders() {
        val seed = Hex.decode(ref.getValue("SOL_SEED"))
        val from = PublicKey(Ed25519Keys.publicKey(seed))
        assertEquals(ref.getValue("SOL_PUB"), from.base58)
        val msg = LegacyMessage.compile(from, listOf(SystemInstructions.transfer(from, PublicKey.of("HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk"), 1_234_567)),
            "EETubP5AKHgjPAhzPAFcb8BAY1hMH639CWCFTqi3hq1k")
        val signed = SolanaTransactions.signLegacy(msg, seed)
        assertEquals(ref.getValue("SOL_WIRE"), Hex.encode(signed.wire))
        assertEquals(ref.getValue("SOL_SIG"), signed.signature)
        val parsed = ParsedSolanaTransaction.parse(signed.wire)
        assertEquals(from, parsed.staticKeys[0])
        assertEquals(listOf(SolanaPrograms.SYSTEM), parsed.programIds)
    }

    @Test fun solanaExternalSigningFillsFeePayerSlot() {
        val seed = Hex.decode(ref.getValue("SOL_SEED"))
        val from = PublicKey(Ed25519Keys.publicKey(seed))
        val msg = LegacyMessage.compile(from, listOf(SystemInstructions.transfer(from, from, 1)), "EETubP5AKHgjPAhzPAFcb8BAY1hMH639CWCFTqi3hq1k")
        val unsigned = byteArrayOf(1) + ByteArray(64) + msg.serialize()
        val (_, signed) = SolanaTransactions.signExternal(unsigned, seed)
        assertEquals(Hex.encode(SolanaTransactions.signLegacy(msg, seed).wire), Hex.encode(signed.wire))
        val otherSeed = Hashes.sha256("other".toByteArray())
        val r = runCatching { SolanaTransactions.signExternal(unsigned, otherSeed) }
        assertTrue(r.isFailure)
    }

    @Test fun solanaDerivedAddressesMatchSolders() {
        val owner = PublicKey.of("HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk")
        val ata = SolanaPrograms.associatedTokenAddress(owner, PublicKey.of("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"), SolanaPrograms.TOKEN)
        assertEquals(ref.getValue("SOL_ATA"), ata.base58)
        assertTrue(!Ed25519Keys.isOnCurve(ata.bytes))
        assertEquals(ref.getValue("SOL_STAKE_SEED_ADDR"), PublicKey.createWithSeed(owner, "stake:0", SolanaPrograms.STAKE).base58)
        val check = AddressValidator.validate(app.spectra.core.model.Network(app.spectra.core.model.Chain.SOLANA, NetworkEnv.MAINNET), ata.base58)
        assertTrue(check is AddressCheck.Valid && check.warning == AddressWarning.OFF_CURVE_PROGRAM_ADDRESS)
    }

    @Test fun bip143NativeP2wpkhOfficialVector() {
        val outputs = listOf(
            TxOutput(Hex.decode("76a9148280b37df378db99f66f85c95a783a76ac7a6d5988ac"), 112340000),
            TxOutput(Hex.decode("76a9143bde42dbee7e4dbe6a21b2d50ce2f0167faa815988ac"), 223450000),
        )
        // Outpoints given in BIP-143 as little-endian bytes; convert to display (big-endian) txids.
        val op1 = Hex.encode(Hex.decode("fff7f7881a8099afa6940d42d1e7f6362bec38171ea3edf433541db4e4ad969f").reversedArray())
        val op2 = Hex.encode(Hex.decode("ef51e1b804cc89d182d279655c3aa89e815b1b309fe287d9b2b55d57b90ec68a").reversedArray())
        val pub = Hex.decode("025476c2e83188368da1ff3e292e7acafcdb3566bb0ad253f62fc70f07aeee6357")
        val sighash = LitecoinTransactions.bip143Sighash(listOf(op1 to 0, op2 to 1), listOf(0xffffffeeL, 0xffffffffL), outputs, 1, pub,
            600_000_000L, version = 1, lockTime = 0x11)
        assertEquals("c37af31116d1b27caf68aae9e3ac82f1477929014d5b917657d0eb49478cb670", Hex.encode(sighash))
        val priv = Hex.decode("619c335025c7f4012e556c2a58b2506e30b8511b53ade95ea316fd8c3286feb9")
        assertEquals("304402203609e17b84f6a7d30c80bfa610b5b4542f32a8a0d5447a12fb1366d7f01cc44a0220573a954c4518331561406f90300e8f3358f51928d43c212a8caed02de67eebee",
            Hex.encode(Secp256k1.sign(sighash, priv).toDer()))
    }

    @Test fun litecoinSignedTransactionIsWellFormedAndVerifies() {
        val priv = Hashes.sha256("ltc test key".toByteArray())
        val pub = Secp256k1.publicKey(priv)
        val utxo = Utxo("aa".repeat(32), 1, 1_000_000, true, "x", "m/84'/1'/0'/0/0")
        val out = TxOutput(LitecoinTransactions.p2wpkhScript(Hashes.hash160(pub)), 900_000)
        val signed = LitecoinTransactions.sign(listOf(SigningInput(utxo, pub)), listOf(out)) { priv.copyOf() }
        val raw = Hex.encode(signed.raw)
        assertTrue(raw.startsWith("02000000" + "0001" + "01" + "aa".repeat(32) + "01000000" + "00" + "feffffff"))
        assertEquals(64, signed.txid.length)
        assertTrue(signed.vsize <= LitecoinTransactions.estimateVsize(1, listOf(out.scriptPubKey)))
        // Witness signature verifies against the BIP-143 sighash.
        val sighash = LitecoinTransactions.bip143Sighash(listOf(utxo.txid to 1), listOf(LitecoinTransactions.SEQUENCE_RBF_DISABLED), listOf(out), 0, pub, utxo.value)
        val der = Secp256k1.sign(sighash, priv).toDer() + byteArrayOf(1)
        // RFC 6979 is deterministic, so the witness must contain exactly this signature followed by the public key.
        assertTrue(raw.endsWith("02" + Hex.encode(byteArrayOf(der.size.toByte())) + Hex.encode(der) + "21" + Hex.encode(pub) + "00000000"))
        val sig = Secp256k1.sign(sighash, priv)
        assertTrue(Secp256k1.verify(sighash, sig.r, sig.s, pub))
    }

    @Test fun coinSelection() {
        val script = ByteArray(22)
        fun u(v: Long, c: Boolean = true) = Utxo("bb".repeat(32), v.toInt() % 7, v, c, "a", "p")
        val sel = CoinSelector.select(listOf(u(50_000), u(300_000), u(20_000)), 200_000, 2, script, script, false).getOrThrow()
        assertEquals(1, sel.inputs.size)
        assertEquals(300_000 - 200_000 - sel.fee, sel.change)
        val fail = CoinSelector.select(listOf(u(50_000), u(500_000, false)), 200_000, 2, script, script, false)
        val e = (fail.exceptionOrNull() as CoinSelectionException).error
        assertTrue(e is CoinSelectionError.InsufficientConfirmedFunds && e.unconfirmed == 500_000L)
        val max = CoinSelector.select(listOf(u(50_000), u(30_000)), 0, 3, script, script, true).getOrThrow()
        assertEquals(80_000 - max.fee, max.recipientValue)
        assertTrue((CoinSelector.select(listOf(u(50_000)), 500, 1, script, script, false).exceptionOrNull() as CoinSelectionException).error is CoinSelectionError.DustOutput)
    }
}
