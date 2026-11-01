package app.spectra.core.chain.solana

import app.spectra.core.crypto.Ed25519Keys
import app.spectra.core.encoding.Base58

/** Legacy Solana message compilation (https://solana.com/docs/core/transactions). */
class LegacyMessage private constructor(
    val accountKeys: List<PublicKey>,
    val numRequiredSignatures: Int,
    val numReadonlySigned: Int,
    val numReadonlyUnsigned: Int,
    val recentBlockhash: String,
    val compiled: List<Triple<Int, List<Int>, ByteArray>>,
) {
    fun serialize(): ByteArray {
        val w = SolWriter().u8(numRequiredSignatures).u8(numReadonlySigned).u8(numReadonlyUnsigned)
        w.shortVec(accountKeys.size)
        accountKeys.forEach { w.bytes(it.bytes) }
        val bh = Base58.decode(recentBlockhash)
        require(bh.size == 32) { "Invalid blockhash" }
        w.bytes(bh)
        w.shortVec(compiled.size)
        compiled.forEach { (prog, accs, data) ->
            w.u8(prog)
            w.shortVec(accs.size)
            accs.forEach { w.u8(it) }
            w.shortVec(data.size)
            w.bytes(data)
        }
        return w.toByteArray()
    }

    val signers: List<PublicKey> get() = accountKeys.take(numRequiredSignatures)

    companion object {
        fun compile(feePayer: PublicKey, instructions: List<Instruction>, recentBlockhash: String): LegacyMessage {
            data class Flags(var signer: Boolean, var writable: Boolean)
            val order = LinkedHashMap<PublicKey, Flags>()
            order[feePayer] = Flags(signer = true, writable = true)
            for (ix in instructions) {
                for (m in ix.accounts) {
                    val f = order.getOrPut(m.pubkey) { Flags(false, false) }
                    f.signer = f.signer || m.isSigner
                    f.writable = f.writable || m.isWritable
                }
                order.getOrPut(ix.programId) { Flags(false, false) }
            }
            val entries = order.entries.toList()
            val payer = entries.first()
            // Within each group keys are ordered by raw bytes, matching solana-sdk's CompiledKeys (BTreeMap<Pubkey>).
            val rest = entries.drop(1).sortedWith { a, b -> compareUnsigned(a.key.bytes, b.key.bytes) }
            val sorted = listOf(payer) +
                rest.filter { it.value.signer && it.value.writable } +
                rest.filter { it.value.signer && !it.value.writable } +
                rest.filter { !it.value.signer && it.value.writable } +
                rest.filter { !it.value.signer && !it.value.writable }
            val keys = sorted.map { it.key }
            val numSigners = sorted.count { it.value.signer }
            require(numSigners <= 255 && keys.size <= 256)
            val compiled = instructions.map { ix ->
                Triple(keys.indexOf(ix.programId), ix.accounts.map { keys.indexOf(it.pubkey) }, ix.data)
            }
            return LegacyMessage(
                accountKeys = keys,
                numRequiredSignatures = numSigners,
                numReadonlySigned = sorted.count { it.value.signer && !it.value.writable },
                numReadonlyUnsigned = sorted.count { !it.value.signer && !it.value.writable },
                recentBlockhash = recentBlockhash,
                compiled = compiled,
            )
        }
    }
}

private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
    for (i in a.indices) {
        val c = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
        if (c != 0) return c
    }
    return 0
}

/** A serialised transaction ready to broadcast; [signature] (base58) is the Solana transaction id. */
class SignedSolanaTransaction(val wire: ByteArray, val signature: String)

object SolanaTransactions {
    /** Signs a legacy message whose only required signer is [signerSeed]'s public key. */
    fun signLegacy(message: LegacyMessage, signerSeed: ByteArray): SignedSolanaTransaction {
        val pub = PublicKey(Ed25519Keys.publicKey(signerSeed))
        require(message.signers == listOf(pub)) { "Message requires signers this wallet does not control" }
        val msg = message.serialize()
        val sig = Ed25519Keys.sign(signerSeed, msg)
        val wire = SolWriter().shortVec(1).bytes(sig).bytes(msg).toByteArray()
        return SignedSolanaTransaction(wire, Base58.encode(sig))
    }

    /**
     * Parses and signs an externally built (legacy or v0) transaction, e.g. from a swap provider.
     * The wallet signs only if it is the fee payer and the sole required signer slot it is asked to fill.
     */
    fun signExternal(wire: ByteArray, signerSeed: ByteArray): Pair<ParsedSolanaTransaction, SignedSolanaTransaction> {
        val parsed = ParsedSolanaTransaction.parse(wire)
        val pub = PublicKey(Ed25519Keys.publicKey(signerSeed))
        require(parsed.staticKeys.isNotEmpty() && parsed.staticKeys[0] == pub) { "Wallet is not the fee payer of this transaction" }
        val slot = parsed.staticKeys.take(parsed.numRequiredSignatures).indexOf(pub)
        require(slot >= 0) { "Wallet is not a required signer" }
        val unsignedOthers = parsed.signatures.withIndex().filter { it.index != slot && it.value.all { b -> b.toInt() == 0 } }
        require(unsignedOthers.isEmpty()) { "Transaction requires additional signatures that the provider did not supply" }
        val sig = Ed25519Keys.sign(signerSeed, parsed.messageBytes)
        val sigs = parsed.signatures.toMutableList().also { it[slot] = sig }
        val w = SolWriter().shortVec(sigs.size)
        sigs.forEach { w.bytes(it) }
        w.bytes(parsed.messageBytes)
        return parsed to SignedSolanaTransaction(w.toByteArray(), Base58.encode(sigs[0]))
    }
}

/** Minimal parser for legacy and v0 transactions, used to inspect provider-built transactions before signing. */
class ParsedSolanaTransaction(
    val signatures: List<ByteArray>,
    val messageBytes: ByteArray,
    val version: Int?,
    val numRequiredSignatures: Int,
    val staticKeys: List<PublicKey>,
    val recentBlockhash: String,
    val programIds: List<PublicKey>,
    val usesLookupTables: Boolean,
) {
    companion object {
        fun parse(wire: ByteArray): ParsedSolanaTransaction {
            val r = SolReader(wire)
            val nSigs = r.shortVec()
            val sigs = (0 until nSigs).map { r.bytes(64) }
            val msgStart = r.pos
            val first = wire[r.pos].toInt() and 0xff
            val version = if (first and 0x80 != 0) { r.u8(); first and 0x7f } else null
            require(version == null || version == 0) { "Unsupported transaction version $version" }
            val numReq = r.u8(); r.u8(); r.u8()
            val keys = (0 until r.shortVec()).map { PublicKey(r.bytes(32)) }
            val blockhash = Base58.encode(r.bytes(32))
            val programs = ArrayList<PublicKey>()
            repeat(r.shortVec()) {
                val p = r.u8()
                repeat(r.shortVec()) { r.u8() }
                r.bytes(r.shortVec())
                // Program ids must be static keys (runtime rule).
                programs.add(keys.getOrNull(p) ?: throw IllegalArgumentException("Program id not in static keys"))
            }
            var lookups = false
            if (version == 0) {
                val n = r.shortVec()
                lookups = n > 0
                repeat(n) { r.bytes(32); r.bytes(r.shortVec()); r.bytes(r.shortVec()) }
            }
            require(r.remaining == 0) { "Trailing bytes in transaction" }
            require(numReq == nSigs) { "Signature count mismatch" }
            return ParsedSolanaTransaction(sigs, wire.copyOfRange(msgStart, wire.size), version, numReq, keys, blockhash, programs.distinct(), lookups)
        }
    }
}
