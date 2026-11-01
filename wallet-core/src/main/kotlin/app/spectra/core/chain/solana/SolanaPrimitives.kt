package app.spectra.core.chain.solana

import app.spectra.core.crypto.Ed25519Keys
import app.spectra.core.crypto.Hashes
import app.spectra.core.encoding.Base58
import java.io.ByteArrayOutputStream

class PublicKey(val bytes: ByteArray) {
    init { require(bytes.size == 32) { "Solana public key must be 32 bytes" } }
    val base58: String get() = Base58.encode(bytes)
    override fun equals(other: Any?) = other is PublicKey && other.bytes.contentEquals(bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = base58

    companion object {
        fun of(base58: String): PublicKey = PublicKey(Base58.decode(base58))

        /** Program-derived address search per Solana runtime `find_program_address`. */
        fun findProgramAddress(seeds: List<ByteArray>, programId: PublicKey): Pair<PublicKey, Int> {
            for (bump in 255 downTo 0) {
                val candidate = createProgramAddress(seeds + byteArrayOf(bump.toByte()), programId)
                if (candidate != null) return candidate to bump
            }
            error("Unable to find a viable program address bump seed")
        }

        fun createProgramAddress(seeds: List<ByteArray>, programId: PublicKey): PublicKey? {
            require(seeds.size <= 16 && seeds.all { it.size <= 32 })
            val hash = Hashes.sha256(*(seeds + listOf(programId.bytes, "ProgramDerivedAddress".toByteArray())).toTypedArray())
            return if (Ed25519Keys.isOnCurve(hash)) null else PublicKey(hash)
        }

        fun createWithSeed(base: PublicKey, seed: String, owner: PublicKey): PublicKey {
            require(seed.length <= 32)
            return PublicKey(Hashes.sha256(base.bytes, seed.toByteArray(), owner.bytes))
        }
    }
}

data class AccountMeta(val pubkey: PublicKey, val isSigner: Boolean, val isWritable: Boolean)

class Instruction(val programId: PublicKey, val accounts: List<AccountMeta>, val data: ByteArray)

/** Little-endian binary writer for instruction data and compact-u16 ("shortvec") lengths. */
class SolWriter {
    private val out = ByteArrayOutputStream()
    fun u8(v: Int) = apply { out.write(v and 0xff) }
    fun u32(v: Long) = apply { for (i in 0 until 4) out.write(((v ushr (8 * i)) and 0xff).toInt()) }
    fun u64(v: Long) = apply { for (i in 0 until 8) out.write(((v ushr (8 * i)) and 0xff).toInt()) }
    fun i64(v: Long) = u64(v)
    fun bytes(b: ByteArray) = apply { out.write(b) }
    fun shortVec(len: Int) = apply {
        var rem = len
        while (true) {
            var elem = rem and 0x7f
            rem = rem ushr 7
            if (rem == 0) { out.write(elem); break }
            elem = elem or 0x80
            out.write(elem)
        }
    }
    fun toByteArray(): ByteArray = out.toByteArray()
}

class SolReader(private val b: ByteArray, var pos: Int = 0) {
    fun u8(): Int = b[pos++].toInt() and 0xff
    fun bytes(n: Int): ByteArray = b.copyOfRange(pos, pos + n).also { pos += n }
    fun shortVec(): Int {
        var len = 0
        var size = 0
        while (true) {
            val elem = u8()
            len = len or ((elem and 0x7f) shl (size * 7))
            size++
            if (elem and 0x80 == 0) break
            require(size < 3) { "Invalid shortvec" }
        }
        return len
    }
    val remaining get() = b.size - pos
}

object SolanaPrograms {
    val SYSTEM = PublicKey.of("11111111111111111111111111111111")
    val TOKEN = PublicKey.of("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
    val TOKEN_2022 = PublicKey.of("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb")
    val ASSOCIATED_TOKEN = PublicKey.of("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL")
    val COMPUTE_BUDGET = PublicKey.of("ComputeBudget111111111111111111111111111111")
    val STAKE = PublicKey.of("Stake11111111111111111111111111111111111111")
    val STAKE_CONFIG = PublicKey.of("StakeConfig11111111111111111111111111111111")
    val SYSVAR_RENT = PublicKey.of("SysvarRent111111111111111111111111111111111")
    val SYSVAR_CLOCK = PublicKey.of("SysvarC1ock11111111111111111111111111111111")
    val SYSVAR_STAKE_HISTORY = PublicKey.of("SysvarStakeHistory1111111111111111111111111")
    val WRAPPED_SOL_MINT = PublicKey.of("So11111111111111111111111111111111111111112")

    fun isTokenProgram(p: PublicKey) = p == TOKEN || p == TOKEN_2022

    fun associatedTokenAddress(owner: PublicKey, mint: PublicKey, tokenProgram: PublicKey): PublicKey =
        PublicKey.findProgramAddress(listOf(owner.bytes, tokenProgram.bytes, mint.bytes), ASSOCIATED_TOKEN).first
}
