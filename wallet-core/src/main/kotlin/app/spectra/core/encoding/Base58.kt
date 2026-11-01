package app.spectra.core.encoding

import app.spectra.core.crypto.Hashes
import java.math.BigInteger

/** Bitcoin-alphabet Base58 / Base58Check (used by Solana addresses and Litecoin legacy addresses). */
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val INDEXES = IntArray(128) { -1 }.also { idx -> ALPHABET.forEachIndexed { i, c -> idx[c.code] = i } }
    private val BASE = BigInteger.valueOf(58)

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        var zeros = 0
        while (zeros < input.size && input[zeros].toInt() == 0) zeros++
        var num = BigInteger(1, input)
        val sb = StringBuilder()
        while (num.signum() > 0) {
            val qr = num.divideAndRemainder(BASE)
            sb.append(ALPHABET[qr[1].toInt()])
            num = qr[0]
        }
        repeat(zeros) { sb.append('1') }
        return sb.reverse().toString()
    }

    fun decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        var num = BigInteger.ZERO
        for (c in input) {
            val digit = if (c.code < 128) INDEXES[c.code] else -1
            require(digit >= 0) { "Invalid Base58 character" }
            num = num.multiply(BASE).add(BigInteger.valueOf(digit.toLong()))
        }
        var zeros = 0
        while (zeros < input.length && input[zeros] == '1') zeros++
        val raw = num.toByteArray().let { if (it.size > 1 && it[0].toInt() == 0) it.copyOfRange(1, it.size) else it }
        val body = if (num.signum() == 0) ByteArray(0) else raw
        return ByteArray(zeros) + body
    }

    fun encodeCheck(payload: ByteArray): String = encode(payload + Hashes.doubleSha256(payload).copyOfRange(0, 4))

    fun decodeCheck(input: String): ByteArray {
        val data = decode(input)
        require(data.size >= 4) { "Base58Check payload too short" }
        val payload = data.copyOfRange(0, data.size - 4)
        val checksum = data.copyOfRange(data.size - 4, data.size)
        require(Hashes.doubleSha256(payload).copyOfRange(0, 4).contentEquals(checksum)) { "Invalid Base58Check checksum" }
        return payload
    }
}
