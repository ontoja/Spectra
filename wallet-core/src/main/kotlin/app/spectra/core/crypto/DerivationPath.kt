package app.spectra.core.crypto

/** A BIP-32 style path such as m/44'/60'/0'/0/0. Hardened indices carry the 0x80000000 bit. */
data class DerivationPath(val indices: List<Long>) {
    override fun toString(): String = "m" + indices.joinToString("") { i ->
        if (i >= HARDENED) "/${i - HARDENED}'" else "/$i"
    }

    fun child(index: Long, hardened: Boolean = false) = DerivationPath(indices + (if (hardened) index + HARDENED else index))

    val allHardened: Boolean get() = indices.all { it >= HARDENED }

    companion object {
        const val HARDENED = 0x80000000L

        fun parse(path: String): DerivationPath {
            val parts = path.trim().split('/')
            require(parts.isNotEmpty() && parts[0] == "m") { "Path must start with m" }
            return DerivationPath(parts.drop(1).map { p ->
                val hardened = p.endsWith("'") || p.endsWith("h") || p.endsWith("H")
                val num = (if (hardened) p.dropLast(1) else p).toLong()
                require(num in 0 until HARDENED) { "Index out of range" }
                if (hardened) num + HARDENED else num
            })
        }
    }
}
