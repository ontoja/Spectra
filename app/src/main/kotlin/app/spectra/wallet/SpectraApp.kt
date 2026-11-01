package app.spectra.wallet

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.spectra.wallet.data.AutoLock
import app.spectra.wallet.domain.AppContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class SpectraApp : Application() {
    lateinit var container: AppContainer
        private set

    @Volatile private var autoLock: AutoLock = AutoLock.M1
    private var monitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.appScope.launch { container.prefs.flow.collect { autoLock = it.autoLock } }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                container.security.onForeground(autoLock.millis)
                // Reconcile persisted in-flight transactions and swaps while the app is visible (also after process death).
                monitorJob = container.appScope.launch { container.transactions.monitorLoop() }
            }

            override fun onStop(owner: LifecycleOwner) {
                monitorJob?.cancel()
                container.security.onBackground(autoLock.millis)
            }
        })
    }
}
