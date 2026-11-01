package app.spectra.core.chain

import app.spectra.core.crypto.Ed25519Keys
import app.spectra.core.crypto.Hashes
import app.spectra.core.crypto.Secp256k1
import app.spectra.core.encoding.Base58
import app.spectra.core.encoding.Bech32
import app.spectra.core.encoding.Hex
import app.spectra.core.model.Chain
import app.spectra.core.model.Network
import app.spectra.core.model.NetworkEnv

sealed interface AddressCheck {
    data class Valid(val normalized: String, val warning: AddressWarning? = null) : AddressCheck
    data class Invalid(val reason: AddressError) : AddressCheck
}

enum class AddressError { EMPTY, BAD_FORMAT, BAD_CHECKSUM, WRONG_NETWORK, UNSUPPORTED_TYPE }
enum class AddressWarning { OFF_CURVE_PROGRAM_ADDRESS, NO_CHECKSUM, SELF_TRANSFER }

object SolanaAddress {
    fun encode(pubkey: ByteArray): String = Base58.encode(pubkey)
    fun decode(address: String): ByteArray? = try {
        Base58.decode(address).takeIf { it.size == 32 }
    } catch (e: IllegalArgumentException) { null }

    fun validate(input: String): AddressCheck {
        val a = input.trim()
        if (a.isEmpty()) return AddressCheck.Invalid(AddressError.EMPTY)
        if (a.length !in 32..44) return AddressCheck.Invalid(AddressError.BAD_FORMAT)
        val bytes = decode(a) ?: return AddressCheck.Invalid(AddressError.BAD_FORMAT)
        val warning = if (!Ed25519Keys.isOnCurve(bytes)) AddressWarning.OFF_CURVE_PROGRAM_ADDRESS else null
        return AddressCheck.Valid(a, warning)
    }
}

object EthereumAddress {
    fun fromPublicKey(publicKey: ByteArray): String {
        val uncompressed = if (publicKey.size == 65) publicKey else Secp256k1.decodePoint(publicKey).getEncoded(false)
        val hash = Hashes.keccak256(uncompressed.copyOfRange(1, 65))
        return checksum(Hex.encode(hash.copyOfRange(12, 32)))
    }

    /** EIP-55 mixed-case checksum encoding. */
    fun checksum(address: String): String {
        val lower = address.removePrefix("0x").removePrefix("0X").lowercase()
        require(lower.length == 40 && Hex.isHex(lower))
        val hash = Hex.encode(Hashes.keccak256(lower.toByteArray(Charsets.US_ASCII)))
        return "0x" + lower.mapIndexed { i, c -> if (c.isLetter() && Character.digit(hash[i], 16) >= 8) c.uppercaseChar() else c }.joinToString("")
    }

    fun validate(input: String): AddressCheck {
        val a = input.trim()
        if (a.isEmpty()) return AddressCheck.Invalid(AddressError.EMPTY)
        if (!a.startsWith("0x") || a.length != 42 || !Hex.isHex(a.substring(2))) return AddressCheck.Invalid(AddressError.BAD_FORMAT)
        val body = a.substring(2)
        val allLower = body == body.lowercase()
        val allUpper = body == body.uppercase()
        if (allLower || allUpper) return AddressCheck.Valid(checksum(a), AddressWarning.NO_CHECKSUM)
        return if (checksum(a) == a) AddressCheck.Valid(a) else AddressCheck.Invalid(AddressError.BAD_CHECKSUM)
    }

    fun toBytes(address: String): ByteArray = Hex.decode(address.removePrefix("0x")).also { require(it.size == 20) }
}

object LitecoinAddress {
    data class Params(val hrp: String, val p2pkh: Int, val p2shVersions: Set<Int>, val otherHrps: Set<String>, val otherVersions: Set<Int>)

    fun params(env: NetworkEnv) = when (env) {
        NetworkEnv.MAINNET -> Params("ltc", 0x30, setOf(0x32, 0x05), setOf("tltc", "rltc", "bc", "tb"), setOf(0x6f, 0x3a, 0xc4, 0x00))
        NetworkEnv.TESTNET -> Params("tltc", 0x6f, setOf(0x3a, 0xc4), setOf("ltc", "rltc", "bc", "tb"), setOf(0x30, 0x32, 0x05, 0x00))
    }

    fun p2wpkh(compressedPubKey: ByteArray, env: NetworkEnv): String =
        Bech32.encodeSegwit(params(env).hrp, 0, Hashes.hash160(compressedPubKey))

    fun p2pkh(compressedPubKey: ByteArray, env: NetworkEnv): String =
        Base58.encodeCheck(byteArrayOf(params(env).p2pkh.toByte()) + Hashes.hash160(compressedPubKey))

    fun p2shP2wpkh(compressedPubKey: ByteArray, env: NetworkEnv): String {
        val redeem = byteArrayOf(0x00, 0x14) + Hashes.hash160(compressedPubKey)
        val version = if (env == NetworkEnv.MAINNET) 0x32 else 0x3a
        return Base58.encodeCheck(byteArrayOf(version.toByte()) + Hashes.hash160(redeem))
    }

    /** Returns the output script for a valid address on [env], or null. */
    fun scriptPubKey(address: String, env: NetworkEnv): ByteArray? {
        val p = params(env)
        val a = address.trim()
        Bech32.decodeSegwit(p.hrp, a)?.let { sw ->
            val op = if (sw.witnessVersion == 0) 0x00 else 0x50 + sw.witnessVersion
            return byteArrayOf(op.toByte(), sw.program.size.toByte()) + sw.program
        }
        val payload = try { Base58.decodeCheck(a) } catch (e: IllegalArgumentException) { return null }
        if (payload.size != 21) return null
        val version = payload[0].toInt() and 0xff
        val hash = payload.copyOfRange(1, 21)
        return when (version) {
            p.p2pkh -> byteArrayOf(0x76, 0xa9.toByte(), 0x14) + hash + byteArrayOf(0x88.toByte(), 0xac.toByte())
            in p.p2shVersions -> byteArrayOf(0xa9.toByte(), 0x14) + hash + byteArrayOf(0x87.toByte())
            else -> null
        }
    }

    fun validate(input: String, env: NetworkEnv): AddressCheck {
        val a = input.trim()
        if (a.isEmpty()) return AddressCheck.Invalid(AddressError.EMPTY)
        if (scriptPubKey(a, env) != null) {
            return AddressCheck.Valid(if (a.lowercase().startsWith(params(env).hrp + "1")) a.lowercase() else a)
        }
        val p = params(env)
        val lower = a.lowercase()
        if (lower.startsWith("ltcmweb1") || lower.startsWith("tmweb1")) return AddressCheck.Invalid(AddressError.UNSUPPORTED_TYPE)
        if (p.otherHrps.any { lower.startsWith(it + "1") && Bech32.decodeSegwit(it, lower) != null }) return AddressCheck.Invalid(AddressError.WRONG_NETWORK)
        val payload = try { Base58.decodeCheck(a) } catch (e: IllegalArgumentException) {
            return AddressCheck.Invalid(if (a.length in 26..35) AddressError.BAD_CHECKSUM else AddressError.BAD_FORMAT)
        }
        return if (payload.size == 21 && (payload[0].toInt() and 0xff) in p.otherVersions) AddressCheck.Invalid(AddressError.WRONG_NETWORK)
        else AddressCheck.Invalid(AddressError.BAD_FORMAT)
    }
}

object AddressValidator {
    fun validate(network: Network, input: String): AddressCheck = when (network.chain) {
        Chain.SOLANA -> SolanaAddress.validate(input)
        Chain.ETHEREUM -> EthereumAddress.validate(input)
        Chain.LITECOIN -> LitecoinAddress.validate(input, network.env)
    }
}
