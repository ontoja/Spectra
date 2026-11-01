package app.spectra.core.chain.ethereum

import app.spectra.core.chain.EthereumAddress
import app.spectra.core.crypto.Hashes
import app.spectra.core.crypto.Secp256k1
import app.spectra.core.encoding.Hex
import app.spectra.core.encoding.Rlp
import java.math.BigInteger

/** EIP-1559 (type 2) transaction. Access lists are always empty in Spectra v0.1. */
data class Eip1559Transaction(
    val chainId: Long,
    val nonce: BigInteger,
    val maxPriorityFeePerGas: BigInteger,
    val maxFeePerGas: BigInteger,
    val gasLimit: BigInteger,
    val to: String,
    val value: BigInteger,
    val data: ByteArray = ByteArray(0),
) {
    init {
        require(chainId > 0)
        require(maxPriorityFeePerGas <= maxFeePerGas) { "Priority fee cannot exceed max fee" }
        require(gasLimit.signum() > 0 && value.signum() >= 0 && nonce.signum() >= 0)
    }

    private fun fields(): List<Rlp.Item> = listOf(
        Rlp.uint(BigInteger.valueOf(chainId)), Rlp.uint(nonce), Rlp.uint(maxPriorityFeePerGas), Rlp.uint(maxFeePerGas),
        Rlp.uint(gasLimit), Rlp.bytes(EthereumAddress.toBytes(to)), Rlp.uint(value), Rlp.bytes(data), Rlp.list(),
    )

    fun signingPayload(): ByteArray = byteArrayOf(0x02) + Rlp.encode(Rlp.list(fields()))
    fun signingHash(): ByteArray = Hashes.keccak256(signingPayload())

    fun sign(privateKey: ByteArray): SignedEthereumTransaction {
        val sig = Secp256k1.sign(signingHash(), privateKey)
        val yParity = (sig.recId and 1).toLong()
        val raw = byteArrayOf(0x02) + Rlp.encode(Rlp.list(fields() + listOf(Rlp.uint(yParity), Rlp.uint(sig.r), Rlp.uint(sig.s))))
        return SignedEthereumTransaction(raw, "0x" + Hex.encode(Hashes.keccak256(raw)))
    }

    /** Upper bound of the fee the sender may pay (maxFeePerGas * gasLimit). */
    val maxFee: BigInteger get() = maxFeePerGas * gasLimit
}

class SignedEthereumTransaction(val raw: ByteArray, val hash: String) {
    val rawHex: String get() = "0x" + Hex.encode(raw)
}

/** Minimal ABI helpers for the ERC-20 calls Spectra uses. */
object Erc20Abi {
    const val TRANSFER = "a9059cbb"
    const val APPROVE = "095ea7b3"
    const val BALANCE_OF = "70a08231"
    const val ALLOWANCE = "dd62ed3e"
    const val DECIMALS = "313ce567"
    const val SYMBOL = "95d89b41"
    const val NAME = "06fdde03"

    fun word(address: String) = ByteArray(12) + EthereumAddress.toBytes(address)
    fun word(v: BigInteger): ByteArray {
        require(v.signum() >= 0 && v.bitLength() <= 256)
        return Secp256k1.to32(v)
    }

    fun transfer(to: String, amount: BigInteger) = Hex.decode(TRANSFER) + word(to) + word(amount)
    fun approve(spender: String, amount: BigInteger) = Hex.decode(APPROVE) + word(spender) + word(amount)
    fun balanceOf(owner: String) = Hex.decode(BALANCE_OF) + word(owner)
    fun allowance(owner: String, spender: String) = Hex.decode(ALLOWANCE) + word(owner) + word(spender)

    fun decodeUint(hex: String): BigInteger? {
        val b = runCatching { Hex.decode(hex) }.getOrNull() ?: return null
        if (b.size < 32) return null
        return BigInteger(1, b.copyOfRange(0, 32))
    }

    /** Decodes an ABI `string`, falling back to legacy `bytes32` tokens (e.g. MKR). Returns null for malformed data. */
    fun decodeString(hex: String): String? {
        val b = runCatching { Hex.decode(hex) }.getOrNull() ?: return null
        if (b.size == 32) return String(b.takeWhile { it.toInt() != 0 }.toByteArray(), Charsets.UTF_8).ifBlank { null }
        if (b.size < 64) return null
        val offset = BigInteger(1, b.copyOfRange(0, 32))
        if (offset.bitLength() > 16) return null
        val off = offset.toInt()
        if (off + 32 > b.size) return null
        val len = BigInteger(1, b.copyOfRange(off, off + 32))
        if (len.bitLength() > 16 || off + 32 + len.toInt() > b.size) return null
        return String(b.copyOfRange(off + 32, off + 32 + len.toInt()), Charsets.UTF_8)
    }

    /** Describes calldata for display. Unknown selectors are reported as unknown — never guessed. */
    fun describe(data: ByteArray): CallDescription {
        if (data.isEmpty()) return CallDescription.PlainTransfer
        if (data.size < 4) return CallDescription.Unknown(Hex.encode(data))
        val sel = Hex.encode(data.copyOfRange(0, 4))
        return when {
            sel == TRANSFER && data.size == 68 -> CallDescription.TokenTransfer(
                EthereumAddress.checksum(Hex.encode(data.copyOfRange(16, 36))), BigInteger(1, data.copyOfRange(36, 68)))
            sel == APPROVE && data.size == 68 -> CallDescription.TokenApproval(
                EthereumAddress.checksum(Hex.encode(data.copyOfRange(16, 36))), BigInteger(1, data.copyOfRange(36, 68)))
            else -> CallDescription.Unknown(sel)
        }
    }

    val UNLIMITED: BigInteger = BigInteger.ONE.shiftLeft(256) - BigInteger.ONE
}

sealed interface CallDescription {
    data object PlainTransfer : CallDescription
    data class TokenTransfer(val to: String, val amount: BigInteger) : CallDescription
    data class TokenApproval(val spender: String, val amount: BigInteger) : CallDescription {
        val isUnlimited get() = amount >= BigInteger.ONE.shiftLeft(255)
    }
    /** Contract interaction whose effects cannot be determined locally. */
    data class Unknown(val selector: String) : CallDescription
}
