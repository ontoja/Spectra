package app.spectra.wallet

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import app.spectra.wallet.data.UserPreferences
import app.spectra.wallet.ui.SpectraRoot
import app.spectra.wallet.ui.theme.SpectraTheme

/** FragmentActivity is required by androidx.biometric's BiometricPrompt. */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as SpectraApp).container
        setContent {
            val prefs by container.prefs.flow.collectAsState(initial = UserPreferences())
            LaunchedEffect(prefs.blockScreenshotsEverywhere) {
                if (prefs.blockScreenshotsEverywhere) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            SpectraTheme(prefs.theme) { SpectraRoot(container, prefs) }
        }
    }
}
