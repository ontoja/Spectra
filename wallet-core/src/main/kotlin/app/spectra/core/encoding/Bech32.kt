package app.spectra.core.encoding

/** BIP-173 (bech32) and BIP-350 (bech32m) encoding with SegWit address helpers. */
object Bech32 {
    enum class Encoding(val const: Int) { BECH32(1), BECH32M(0x2bc830a3) }

    data class Decoded(val hrp: String, val data: ByteArray, val encoding: Encoding)
    data class SegwitAddress(val witnessVersion: Int, val program: ByteArray)

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GEN = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    private fun polymod(values: ByteArray): Int {
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor (v.toInt() and 0xff)
            for (i in 0 until 5) if ((top ushr i) and 1 == 1) chk = chk xor GEN[i]
        }
        return chk
    }

    private fun hrpExpand(hrp: String): ByteArray {
        val out = ByteArray(hrp.length * 2 + 1)
        hrp.forEachIndexed { i, c -> out[i] = (c.code ushr 5).toByte(); out[i + hrp.length + 1] = (c.code and 31).toByte() }
        return out
    }

    private fun checksum(hrp: String, data: ByteArray, enc: Encoding): ByteArray {
        val values = hrpExpand(hrp) + data + ByteArray(6)
        val mod = polymod(values) xor enc.const
        return ByteArray(6) { i -> ((mod ushr (5 * (5 - i))) and 31).toByte() }
    }

    fun encode(hrp: String, data: ByteArray, enc: Encoding): String {
        val combined = data + checksum(hrp, data, enc)
        val sb = StringBuilder(hrp).append('1')
        for (b in combined) sb.append(CHARSET[b.toInt()])
        return sb.toString()
    }

    fun decode(str: String): Decoded {
        require(str.length in 8..90) { "Invalid bech32 length" }
        val hasLower = str.any { it.isLowerCase() }
        val hasUpper = str.any { it.isUpperCase() }
        require(!(hasLower && hasUpper)) { "Mixed-case bech32 string" }
        val s = str.lowercase()
        val pos = s.lastIndexOf('1')
        require(pos >= 1 && pos + 7 <= s.length) { "Invalid bech32 separator position" }
        val hrp = s.substring(0, pos)
        require(hrp.all { it.code in 33..126 }) { "Invalid HRP character" }
        val data = ByteArray(s.length - pos - 1) { i ->
            val idx = CHARSET.indexOf(s[pos + 1 + i])
            require(idx >= 0) { "Invalid bech32 character" }
            idx.toByte()
        }
        val pm = polymod(hrpExpand(hrp) + data)
        val enc = when (pm) {
            Encoding.BECH32.const -> Encoding.BECH32
            Encoding.BECH32M.const -> Encoding.BECH32M
            else -> throw IllegalArgumentException("Invalid bech32 checksum")
        }
        return Decoded(hrp, data.copyOfRange(0, data.size - 6), enc)
    }

    fun convertBits(data: ByteArray, from: Int, to: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val out = ArrayList<Byte>()
        val maxv = (1 shl to) - 1
        for (b in data) {
            val value = b.toInt() and 0xff
            require(value ushr from == 0) { "Invalid data range" }
            acc = (acc shl from) or value
            bits += from
            while (bits >= to) {
                bits -= to
                out.add(((acc ushr bits) and maxv).toByte())
            }
        }
        if (pad) {
            if (bits > 0) out.add(((acc shl (to - bits)) and maxv).toByte())
        } else {
            require(bits < from && ((acc shl (to - bits)) and maxv) == 0) { "Invalid padding" }
        }
        return out.toByteArray()
    }

    fun encodeSegwit(hrp: String, version: Int, program: ByteArray): String {
        val enc = if (version == 0) Encoding.BECH32 else Encoding.BECH32M
        val data = byteArrayOf(version.toByte()) + convertBits(program, 8, 5, true)
        return encode(hrp, data, enc).also { require(decodeSegwit(hrp, it) != null) }
    }

    /** Returns null when the address is not a valid SegWit address for [expectedHrp]. */
    fun decodeSegwit(expectedHrp: String, address: String): SegwitAddress? = try {
        val d = decode(address)
        if (d.hrp != expectedHrp || d.data.isEmpty()) null else {
            val version = d.data[0].toInt()
            val program = convertBits(d.data.copyOfRange(1, d.data.size), 5, 8, false)
            when {
                version > 16 -> null
                program.size < 2 || program.size > 40 -> null
                version == 0 && program.size != 20 && program.size != 32 -> null
                version == 0 && d.encoding != Encoding.BECH32 -> null
                version != 0 && d.encoding != Encoding.BECH32M -> null
                else -> SegwitAddress(version, program)
            }
        }
    } catch (e: IllegalArgumentException) {
        null
    }
}
