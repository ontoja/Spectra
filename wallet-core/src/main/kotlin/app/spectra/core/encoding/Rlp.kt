package app.spectra.core.encoding

import java.math.BigInteger

/** Recursive Length Prefix encoding (Ethereum Yellow Paper, Appendix B). Encoding only. */
object Rlp {
    sealed interface Item
    class Bytes(val value: ByteArray) : Item
    class ListItem(val items: List<Item>) : Item

    fun bytes(b: ByteArray): Item = Bytes(b)
    fun uint(v: BigInteger): Item {
        require(v.signum() >= 0) { "RLP integers must be non-negative" }
        return Bytes(if (v.signum() == 0) ByteArray(0) else v.toByteArray().dropLeadingZeros())
    }
    fun uint(v: Long): Item = uint(BigInteger.valueOf(v))
    fun list(vararg items: Item): Item = ListItem(items.toList())
    fun list(items: List<Item>): Item = ListItem(items)

    fun encode(item: Item): ByteArray = when (item) {
        is Bytes -> {
            val b = item.value
            if (b.size == 1 && (b[0].toInt() and 0xff) < 0x80) b else lengthPrefix(b.size, 0x80) + b
        }
        is ListItem -> {
            val payload = item.items.fold(ByteArray(0)) { acc, it -> acc + encode(it) }
            lengthPrefix(payload.size, 0xc0) + payload
        }
    }

    private fun lengthPrefix(len: Int, offset: Int): ByteArray =
        if (len < 56) byteArrayOf((offset + len).toByte())
        else {
            val lenBytes = BigInteger.valueOf(len.toLong()).toByteArray().dropLeadingZeros()
            byteArrayOf((offset + 55 + lenBytes.size).toByte()) + lenBytes
        }
}

internal fun ByteArray.dropLeadingZeros(): ByteArray {
    var i = 0
    while (i < size - 1 && this[i].toInt() == 0) i++
    return copyOfRange(i, size)
}
