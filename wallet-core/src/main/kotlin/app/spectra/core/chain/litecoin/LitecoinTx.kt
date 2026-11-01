package app.spectra.core.chain.litecoin

import app.spectra.core.crypto.Hashes
import app.spectra.core.crypto.Secp256k1
import app.spectra.core.encoding.Hex
import java.io.ByteArrayOutputStream

/** A spendable output owned by one of the wallet's BIP-84 addresses. */
data class Utxo(
    val txid: String,
    val vout: Int,
    val value: Long,
    val confirmed: Boolean,
    val address: String,
    /** Path of the key owning the output, e.g. m/84'/2'/0'/0/3 */
    val path: String,
)

data class TxOutput(val scriptPubKey: ByteArray, val value: Long)

/** Input with the compressed public key of its P2WPKH owner (needed for scriptCode and witness). */
data class SigningInput(val utxo: Utxo, val publicKey: ByteArray)

class SignedLitecoinTransaction(val raw: ByteArray, val txid: String, val vsize: Int) {
    val rawHex: String get() = Hex.encode(raw)
}

/** P2WPKH transaction construction and BIP-143 signing (SIGHASH_ALL). Litecoin uses Bitcoin's segwit rules. */
object LitecoinTransactions {
    const val SIGHASH_ALL = 1
    const val VERSION = 2
    const val SEQUENCE_RBF_DISABLED = 0xfffffffeL

    /** Virtual size estimate for n P2WPKH inputs and the given output scripts (signatures assumed 72 bytes, worst case). */
    fun estimateVsize(inputs: Int, outputScripts: List<ByteArray>): Int {
        val outputs = outputScripts.sumOf { 8 + 1 + it.size }
        val base = 4 + varIntSize(inputs) + inputs * (32 + 4 + 1 + 4) + varIntSize(outputScripts.size) + outputs + 4
        val witness = 2 + inputs * (1 + 1 + 72 + 1 + 33)
        val weight = base * 4 + witness
        return (weight + 3) / 4
    }

    fun p2wpkhScript(pubKeyHash: ByteArray) = byteArrayOf(0x00, 0x14) + pubKeyHash

    /** BIP-143 signature hash for spending a P2WPKH output at [index]. */
    fun bip143Sighash(
        outpoints: List<Pair<String, Int>>, sequences: List<Long>, outputs: List<TxOutput>, index: Int,
        publicKey: ByteArray, value: Long, version: Long = VERSION.toLong(), lockTime: Long = 0, sighashType: Int = SIGHASH_ALL,
    ): ByteArray {
        val hashPrevouts = Hashes.doubleSha256(outpoints.fold(ByteArray(0)) { acc, o -> acc + outpoint(o.first, o.second) })
        val hashSequence = Hashes.doubleSha256(sequences.fold(ByteArray(0)) { acc, q -> acc + le32(q) })
        val hashOutputs = Hashes.doubleSha256(outputs.fold(ByteArray(0)) { acc, o -> acc + serializeOutput(o) })
        val pkh = Hashes.hash160(publicKey)
        val scriptCode = byteArrayOf(0x19, 0x76, 0xa9.toByte(), 0x14) + pkh + byteArrayOf(0x88.toByte(), 0xac.toByte())
        val preimage = le32(version) + hashPrevouts + hashSequence + outpoint(outpoints[index].first, outpoints[index].second) +
            scriptCode + le64(value) + le32(sequences[index]) + hashOutputs + le32(lockTime) + le32(sighashType.toLong())
        return Hashes.doubleSha256(preimage)
    }

    fun sign(inputs: List<SigningInput>, outputs: List<TxOutput>, privateKeyFor: (Utxo) -> ByteArray): SignedLitecoinTransaction {
        require(inputs.isNotEmpty() && outputs.isNotEmpty())
        val outpoints = inputs.map { it.utxo.txid to it.utxo.vout }
        val sequences = inputs.map { SEQUENCE_RBF_DISABLED }
        val outs = outputs.fold(ByteArray(0)) { acc, o -> acc + serializeOutput(o) }

        val witnesses = inputs.mapIndexed { i, input ->
            val sighash = bip143Sighash(outpoints, sequences, outputs, i, input.publicKey, input.utxo.value)
            val key = privateKeyFor(input.utxo)
            try {
                require(Secp256k1.publicKey(key, true).contentEquals(input.publicKey)) { "Key does not match input owner" }
                val der = Secp256k1.sign(sighash, key).toDer() + byteArrayOf(SIGHASH_ALL.toByte())
                listOf(der, input.publicKey)
            } finally { key.fill(0) }
        }

        val noWitness = ByteArrayOutputStream()
        val full = ByteArrayOutputStream()
        noWitness.write(le32(VERSION.toLong())); full.write(le32(VERSION.toLong()))
        full.write(byteArrayOf(0x00, 0x01))
        for (s in listOf(noWitness, full)) {
            s.write(varInt(inputs.size.toLong()))
            inputs.forEach { s.write(outpoint(it.utxo.txid, it.utxo.vout)); s.write(0); s.write(le32(SEQUENCE_RBF_DISABLED)) }
            s.write(varInt(outputs.size.toLong()))
            s.write(outs)
        }
        witnesses.forEach { items ->
            full.write(varInt(items.size.toLong()))
            items.forEach { full.write(varInt(it.size.toLong())); full.write(it) }
        }
        noWitness.write(le32(0)); full.write(le32(0))
        val base = noWitness.toByteArray()
        val raw = full.toByteArray()
        val txid = Hex.encode(Hashes.doubleSha256(base).reversedArray())
        val weight = base.size * 3 + raw.size
        return SignedLitecoinTransaction(raw, txid, (weight + 3) / 4)
    }

    private fun outpoint(txidHex: String, vout: Int): ByteArray {
        val txid = Hex.decode(txidHex)
        require(txid.size == 32)
        return txid.reversedArray() + le32(vout.toLong())
    }

    private fun serializeOutput(o: TxOutput) = le64(o.value) + varInt(o.scriptPubKey.size.toLong()) + o.scriptPubKey

    fun le32(v: Long) = ByteArray(4) { ((v ushr (8 * it)) and 0xff).toByte() }
    fun le64(v: Long) = ByteArray(8) { ((v ushr (8 * it)) and 0xff).toByte() }
    fun varIntSize(n: Int) = when { n < 0xfd -> 1; n <= 0xffff -> 3; else -> 5 }
    fun varInt(v: Long): ByteArray = when {
        v < 0xfd -> byteArrayOf(v.toByte())
        v <= 0xffff -> byteArrayOf(0xfd.toByte()) + ByteArray(2) { ((v ushr (8 * it)) and 0xff).toByte() }
        v <= 0xffffffffL -> byteArrayOf(0xfe.toByte()) + le32(v)
        else -> byteArrayOf(0xff.toByte()) + le64(v)
    }
}

/** Coin selection outcome; amounts in litoshis. */
data class CoinSelection(val inputs: List<Utxo>, val recipientValue: Long, val change: Long, val fee: Long, val vsize: Int)

sealed interface CoinSelectionError {
    data class InsufficientConfirmedFunds(val confirmedAvailable: Long, val unconfirmed: Long, val required: Long) : CoinSelectionError
    data class DustOutput(val minimum: Long) : CoinSelectionError
}

object CoinSelector {
    /**
     * Conservative dust threshold for outputs created by Spectra (litoshis). Litecoin Core's relay dust limit for
     * P2WPKH is lower; Spectra deliberately refuses to create tiny outputs that may be non-standard or uneconomic.
     */
    const val DUST_LIMIT = 10_000L

    /**
     * Largest-first selection over confirmed UTXOs only. [sendMax] spends every confirmed UTXO and deducts the fee
     * from the recipient amount. Change below [DUST_LIMIT] is added to the fee (reported to the user).
     */
    fun select(utxos: List<Utxo>, amount: Long, feeRate: Long, recipientScript: ByteArray, changeScript: ByteArray, sendMax: Boolean): Result<CoinSelection> {
        require(feeRate >= 1)
        val confirmed = utxos.filter { it.confirmed }.sortedByDescending { it.value }
        val unconfirmed = utxos.filter { !it.confirmed }.sumOf { it.value }
        val available = confirmed.sumOf { it.value }
        if (sendMax) {
            if (confirmed.isEmpty()) return failure(CoinSelectionError.InsufficientConfirmedFunds(0, unconfirmed, 1))
            val vsize = LitecoinTransactions.estimateVsize(confirmed.size, listOf(recipientScript))
            val fee = vsize * feeRate
            val value = available - fee
            if (value < DUST_LIMIT) return failure(CoinSelectionError.InsufficientConfirmedFunds(available, unconfirmed, fee + DUST_LIMIT))
            return Result.success(CoinSelection(confirmed, value, 0, fee, vsize))
        }
        if (amount < DUST_LIMIT) return failure(CoinSelectionError.DustOutput(DUST_LIMIT))
        val chosen = ArrayList<Utxo>()
        var total = 0L
        for (u in confirmed) {
            chosen.add(u)
            total += u.value
            val vsizeWithChange = LitecoinTransactions.estimateVsize(chosen.size, listOf(recipientScript, changeScript))
            val feeWithChange = vsizeWithChange * feeRate
            val change = total - amount - feeWithChange
            if (change >= DUST_LIMIT) return Result.success(CoinSelection(chosen.toList(), amount, change, feeWithChange, vsizeWithChange))
            val vsizeNoChange = LitecoinTransactions.estimateVsize(chosen.size, listOf(recipientScript))
            val feeNoChange = vsizeNoChange * feeRate
            if (total - amount >= feeNoChange) {
                return Result.success(CoinSelection(chosen.toList(), amount, 0, total - amount, vsizeNoChange))
            }
        }
        val required = amount + LitecoinTransactions.estimateVsize(maxOf(1, confirmed.size), listOf(recipientScript, changeScript)) * feeRate
        return failure(CoinSelectionError.InsufficientConfirmedFunds(available, unconfirmed, required))
    }

    private fun failure(e: CoinSelectionError) = Result.failure<CoinSelection>(CoinSelectionException(e))
}

class CoinSelectionException(val error: CoinSelectionError) : Exception(error.toString())
