package app.spectra.core.encoding

object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0f])
        }
        return sb.toString()
    }

    fun decode(input: String): ByteArray {
        val s = if (input.startsWith("0x") || input.startsWith("0X")) input.substring(2) else input
        require(s.length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(s.length / 2) { i ->
            val hi = Character.digit(s[2 * i], 16)
            val lo = Character.digit(s[2 * i + 1], 16)
            require(hi >= 0 && lo >= 0) { "Invalid hex character" }
            ((hi shl 4) or lo).toByte()
        }
    }

    fun isHex(s: String): Boolean {
        val body = s.removePrefix("0x").removePrefix("0X")
        return body.all { Character.digit(it, 16) >= 0 }
    }
}

fun ByteArray.toHex(): String = Hex.encode(this)
fun String.hexToBytes(): ByteArray = Hex.decode(this)
