package app.spectra.wallet.security

import app.spectra.core.crypto.Bip39
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Software stand-in for the Android Keystore with the same contract (keys never leave the object). */
class FakeBackend : HardwareKeyBackend {
    private var pepperKey: ByteArray? = ByteArray(32) { 7 }
    private var wrapKey: ByteArray? = ByteArray(32) { 9 }
    var pepperCalls = 0

    override fun pepper(input: ByteArray): ByteArray { pepperCalls++; return Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(pepperKey ?: error("gone"), "HmacSHA256")); doFinal(input) } }
    override fun wrap(plain: ByteArray): ByteArray {
        val iv = ByteArray(12) { it.toByte() }
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(wrapKey ?: error("gone"), "AES"), GCMParameterSpec(128, iv))
        return iv + c.doFinal(plain)
    }
    override fun unwrap(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, SecretKeySpec(wrapKey ?: error("gone"), "AES"), GCMParameterSpec(128, blob, 0, 12))
        return c.doFinal(blob, 12, blob.size - 12)
    }
    override fun deleteKeys() { pepperKey = null; wrapKey = null }
}

class VaultCryptoTest {
    private val backend = FakeBackend()
    private val crypto = VaultCrypto(backend)
    private val pin = "482915".toCharArray()

    @Test fun createUnlockAndEncryptRoundTrip() {
        val (file, dek) = crypto.create(pin)
        assertEquals(32, dek.size)
        assertEquals("argon2id", file.kdf.alg)
        assertArrayEquals(dek, crypto.unlock(pin, file))
        val phrase = Bip39.phraseBytes(Bip39.generate())
        val blob = crypto.encryptVault(dek, "vault-0001", phrase)
        assertArrayEquals(phrase, crypto.decryptVault(dek, "vault-0001", blob))
    }

    @Test fun wrongPinIsRejected() {
        val (file, _) = crypto.create(pin)
        try { crypto.unlock("482916".toCharArray(), file); fail() } catch (e: VaultException.WrongPin) { }
    }

    @Test fun pinGuessesRequireTheHardwarePepper() {
        val (file, _) = crypto.create(pin)
        val before = backend.pepperCalls
        crypto.unlock(pin, file)
        assertTrue(backend.pepperCalls > before)
        backend.deleteKeys()
        try { crypto.unlock(pin, file); fail() } catch (e: VaultException.HardwareKeyUnavailable) { }
    }

    @Test fun vaultCiphertextIsBoundToVaultIdAndTamperEvident() {
        val (_, dek) = crypto.create(pin)
        val blob = crypto.encryptVault(dek, "vault-aaaa", "secret".toByteArray())
        try { crypto.decryptVault(dek, "vault-bbbb", blob); fail() } catch (e: VaultException.Corrupted) { }
        val tampered = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        try { crypto.decryptVault(dek, "vault-aaaa", tampered); fail() } catch (e: VaultException.Corrupted) { }
        try { crypto.decryptVault(dek, "vault-aaaa", ByteArray(5)); fail() } catch (e: VaultException.Corrupted) { }
    }

    @Test fun changePinKeepsDekAndInvalidatesOldPin() {
        val (file, dek) = crypto.create(pin)
        val newPin = "730164".toCharArray()
        val changed = crypto.changePin(pin, newPin, file)
        assertNotEquals(file.kdf.salt, changed.kdf.salt)
        assertArrayEquals(dek, crypto.unlock(newPin, changed))
        try { crypto.unlock(pin, changed); fail() } catch (e: VaultException.WrongPin) { }
    }

    @Test fun nonNumericPinRejected() {
        try { crypto.create("12ab56".toCharArray()); fail() } catch (e: IllegalArgumentException) { }
    }
}

class LockoutPolicyTest {
    private class MemStore : LockoutPolicy.Store { override var failures = 0; override var lockedUntil = 0L }

    @Test fun escalatingLockout() {
        var now = 0L
        val store = MemStore()
        val p = LockoutPolicy(store) { now }
        repeat(4) { assertEquals(LockoutPolicy.Decision.Allowed, p.onFailure()) }
        val d = p.onFailure()
        assertTrue(d is LockoutPolicy.Decision.Locked && d.untilMillis == 30_000L)
        now = 30_001
        assertEquals(LockoutPolicy.Decision.Allowed, p.check())
        val d6 = p.onFailure()
        assertTrue(d6 is LockoutPolicy.Decision.Locked && d6.untilMillis == now + 60_000)
        p.onSuccess()
        assertEquals(0, store.failures)
        assertEquals(60 * 60_000L, LockoutPolicy.delayFor(12))
    }

    @Test fun lockoutSurvivesNewPolicyInstance() {
        val store = MemStore()
        val p = LockoutPolicy(store) { 1000 }
        repeat(5) { p.onFailure() }
        assertTrue(LockoutPolicy(store) { 2000 }.check() is LockoutPolicy.Decision.Locked)
    }
}
