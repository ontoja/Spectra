package app.spectra.wallet.security

import android.os.Build
import java.io.File

/**
 * Heuristic root indicators. This is NOT a security guarantee: a determined attacker can hide root.
 * Spectra only warns; it never blocks the user.
 */
object DeviceIntegrity {
    private val suPaths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/su", "/su/bin/su", "/data/local/xbin/su",
        "/data/local/bin/su", "/system/sd/xbin/su", "/data/adb/magisk", "/system/app/Superuser.apk")

    fun rootIndicators(): List<String> {
        val found = mutableListOf<String>()
        if (Build.TAGS?.contains("test-keys") == true) found += "test-keys build"
        suPaths.filter { runCatching { File(it).exists() }.getOrDefault(false) }.forEach { found += it }
        return found
    }
}
