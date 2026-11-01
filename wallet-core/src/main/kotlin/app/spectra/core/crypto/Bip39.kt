package app.spectra.core.crypto

import java.text.Normalizer

/**
 * BIP-39 mnemonic generation, validation and seed derivation (English wordlist).
 * The wordlist file is the canonical list from bitcoin/bips (SHA-256 verified in tests).
 */
object Bip39 {
    val wordlist: List<String> by lazy {
        val stream = Bip39::class.java.getResourceAsStream("/bip39-english.txt")
            ?: error("BIP-39 wordlist missing")
        stream.bufferedReader(Charsets.UTF_8).readLines().map { it.trim() }.filter { it.isNotEmpty() }
            .also { check(it.size == 2048) { "BIP-39 wordlist must contain 2048 words" } }
    }
    private val index: Map<String, Int> by lazy { wordlist.withIndex().associate { it.value to it.index } }

    sealed interface Validation {
        data object Valid : Validation
        data class UnknownWords(val positions: List<Int>) : Validation
        data class InvalidLength(val count: Int) : Validation
        data object InvalidChecksum : Validation
    }

    fun entropyToWords(entropy: ByteArray): List<String> {
        require(entropy.size in setOf(16, 20, 24, 28, 32)) { "Entropy must be 128-256 bits in 32-bit steps" }
        val checksumBits = entropy.size * 8 / 32
        val hash = Hashes.sha256(entropy)
        val totalBits = entropy.size * 8 + checksumBits
        val bits = BooleanArray(totalBits)
        for (i in 0 until entropy.size * 8) bits[i] = (entropy[i / 8].toInt() shr (7 - i % 8)) and 1 == 1
        for (i in 0 until checksumBits) bits[entropy.size * 8 + i] = (hash[i / 8].toInt() shr (7 - i % 8)) and 1 == 1
        return (0 until totalBits / 11).map { w ->
            var idx = 0
            for (j in 0 until 11) idx = (idx shl 1) or (if (bits[w * 11 + j]) 1 else 0)
            wordlist[idx]
        }
    }

    /** Generates a new mnemonic from the platform CSPRNG. 12 words = 128 bits of entropy. */
    fun generate(wordCount: Int = 12): List<String> {
        require(wordCount in setOf(12, 15, 18, 21, 24))
        val entropy = SecureMemory.randomBytes(wordCount * 4 / 3)
        return try { entropyToWords(entropy) } finally { SecureMemory.wipe(entropy) }
    }

    fun normalizeInput(raw: String): List<String> =
        Normalizer.normalize(raw, Normalizer.Form.NFKD).lowercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun validate(words: List<String>): Validation {
        if (words.size !in setOf(12, 15, 18, 21, 24)) return Validation.InvalidLength(words.size)
        val unknown = words.withIndex().filter { it.value !in index }.map { it.index }
        if (unknown.isNotEmpty()) return Validation.UnknownWords(unknown)
        val totalBits = words.size * 11
        val bits = BooleanArray(totalBits)
        words.forEachIndexed { w, word ->
            val idx = index.getValue(word)
            for (j in 0 until 11) bits[w * 11 + j] = (idx shr (10 - j)) and 1 == 1
        }
        val checksumBits = totalBits / 33
        val entropyBytes = (totalBits - checksumBits) / 8
        val entropy = ByteArray(entropyBytes)
        for (i in 0 until entropyBytes * 8) if (bits[i]) entropy[i / 8] = (entropy[i / 8].toInt() or (1 shl (7 - i % 8))).toByte()
        val hash = Hashes.sha256(entropy)
        SecureMemory.wipe(entropy)
        for (i in 0 until checksumBits) {
            val expected = (hash[i / 8].toInt() shr (7 - i % 8)) and 1 == 1
            if (bits[entropyBytes * 8 + i] != expected) return Validation.InvalidChecksum
        }
        return Validation.Valid
    }

    fun suggestions(prefix: String, limit: Int = 4): List<String> =
        if (prefix.isEmpty()) emptyList() else wordlist.filter { it.startsWith(prefix.lowercase()) }.take(limit)

    /**
     * Derives the 64-byte BIP-39 seed. [mnemonicUtf8] must be the NFKD-normalised, single-space-joined phrase.
     * The caller owns and should wipe both inputs and the returned seed.
     */
    fun toSeed(mnemonicUtf8: ByteArray, passphrase: CharArray = CharArray(0)): ByteArray {
        val pass = Normalizer.normalize(String(passphrase), Normalizer.Form.NFKD)
        val salt = ("mnemonic$pass").toByteArray(Charsets.UTF_8)
        return Hashes.pbkdf2HmacSha512(mnemonicUtf8, salt, 2048, 64)
    }

    fun phraseBytes(words: List<String>): ByteArray =
        Normalizer.normalize(words.joinToString(" "), Normalizer.Form.NFKD).toByteArray(Charsets.UTF_8)
}
