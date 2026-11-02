package app.spectra.wallet.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.spectra.core.model.AssetId
import app.spectra.core.model.Chain
import app.spectra.wallet.domain.AppContainer
import app.spectra.wallet.ui.activity.ActivityScreen
import app.spectra.wallet.ui.common.factory
import app.spectra.wallet.ui.components.AuroraBackground
import app.spectra.wallet.ui.components.AuroraPreset
import app.spectra.wallet.ui.components.MainTab
import app.spectra.wallet.ui.components.SpectraBottomBar
import app.spectra.wallet.ui.home.AssetDetailScreen
import app.spectra.wallet.ui.home.HomeScreen
import app.spectra.wallet.ui.onboarding.OnboardingFlow
import app.spectra.wallet.ui.onboarding.OnboardingViewModel
import app.spectra.wallet.ui.receive.ReceiveScreen
import app.spectra.wallet.ui.send.SendScreen
import app.spectra.wallet.ui.settings.SettingsPage
import app.spectra.wallet.ui.settings.SettingsScreen
import app.spectra.wallet.ui.settings.SettingsSubScreen
import app.spectra.wallet.ui.staking.StakingScreen
import app.spectra.wallet.ui.swap.SwapScreen
import app.spectra.wallet.ui.tx.TransactionDetailScreen
import app.spectra.wallet.ui.theme.Spectra

object Routes {
    const val MAIN = "main"
    fun asset(key: String) = "asset/${Uri.encode(key)}"
    fun send(key: String?) = "send?asset=${Uri.encode(key ?: "")}"
    fun receive(chain: Chain?) = "receive?chain=${chain?.name ?: ""}"
    fun tx(id: String) = "tx/$id"
    fun settings(page: SettingsPage, arg: String = "") = "settings/${page.name}?arg=${Uri.encode(arg)}"
    const val IMPORT = "import"
}

@Composable
fun MainNavigation(c: AppContainer, main: MainViewModel) {
    val nav = rememberNavController()
    val session by main.session.collectAsState()
    val s = session ?: return
    val accountId = s.active?.account?.id ?: return
    val testnet = s.prefs.viewTestnet
    val anim = !Spectra.reducedMotion
    NavHost(nav, Routes.MAIN,
        enterTransition = { if (anim) slideInHorizontally(tween(260)) { it / 6 } + fadeIn(tween(260)) else fadeIn(tween(0)) },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { fadeIn(tween(200)) },
        popExitTransition = { if (anim) slideOutHorizontally(tween(220)) { it / 6 } + fadeOut(tween(220)) else fadeOut(tween(0)) },
    ) {
        composable(Routes.MAIN) {
            var tab by rememberSaveable { mutableStateOf(MainTab.HOME) }
            AuroraBackground(if (tab == MainTab.HOME) AuroraPreset.HOME else AuroraPreset.DETAIL) {
                val d = if (anim) 260 else 0
                AnimatedContent(tab, transitionSpec = {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (fadeIn(tween(d)) + slideInHorizontally(tween(d)) { dir * it / 12 }) togetherWith (fadeOut(tween(d / 2)) + slideOutHorizontally(tween(d)) { -dir * it / 12 })
                }, label = "tabs") { current ->
                when (current) {
                    MainTab.HOME -> HomeScreen(main, onAsset = { nav.navigate(Routes.asset(it)) }, onSend = { nav.navigate(Routes.send(null)) },
                        onReceive = { nav.navigate(Routes.receive(null)) }, onSwap = { tab = MainTab.SWAP }, onStake = { tab = MainTab.STAKING },
                        onTx = { nav.navigate(Routes.tx(it)) }, onImport = { nav.navigate(Routes.IMPORT) }, onSettings = { p -> nav.navigate(Routes.settings(p)) })
                    MainTab.SWAP -> SwapScreen(main)
                    MainTab.ACTIVITY -> ActivityScreen(main) { nav.navigate(Routes.tx(it)) }
                    MainTab.STAKING -> StakingScreen(main)
                    MainTab.SETTINGS -> SettingsScreen(main, onOpen = { p, arg -> nav.navigate(Routes.settings(p, arg)) }, onImport = { nav.navigate(Routes.IMPORT) })
                }
                }
                Box(Modifier.fillMaxSize().navigationBarsPadding(), contentAlignment = Alignment.BottomCenter) {
                    SpectraBottomBar(tab, { tab = it })
                }
            }
        }
        composable("asset/{key}", listOf(navArgument("key") { type = NavType.StringType })) { e ->
            val key = Uri.decode(e.arguments?.getString("key") ?: "")
            AssetDetailScreen(main, AssetId.fromKey(key), onBack = { nav.popBackStack() }, onSend = { nav.navigate(Routes.send(key)) },
                onReceive = { nav.navigate(Routes.receive(AssetId.fromKey(key).network.chain)) }, onTx = { nav.navigate(Routes.tx(it)) })
        }
        composable("send?asset={asset}", listOf(navArgument("asset") { type = NavType.StringType; defaultValue = "" })) { e ->
            val key = Uri.decode(e.arguments?.getString("asset") ?: "").ifEmpty { null }
            SendScreen(main, key, onClose = { nav.popBackStack() }, onSubmitted = { id -> nav.navigate(Routes.tx(id)) { popUpTo(Routes.MAIN) } })
        }
        composable("receive?chain={chain}", listOf(navArgument("chain") { type = NavType.StringType; defaultValue = "" })) { e ->
            val chain = e.arguments?.getString("chain")?.takeIf { it.isNotEmpty() }?.let { Chain.valueOf(it) }
            ReceiveScreen(accountId, testnet, chain) { nav.popBackStack() }
        }
        composable("tx/{id}") { e -> TransactionDetailScreen(e.arguments?.getString("id") ?: "") { nav.popBackStack() } }
        composable("settings/{page}?arg={arg}", listOf(navArgument("arg") { type = NavType.StringType; defaultValue = "" })) { e ->
            val page = SettingsPage.valueOf(e.arguments?.getString("page") ?: SettingsPage.ABOUT.name)
            SettingsSubScreen(main, page, Uri.decode(e.arguments?.getString("arg") ?: ""), onBack = { nav.popBackStack() },
                onOpen = { p, arg -> nav.navigate(Routes.settings(p, arg)) })
        }
        composable(Routes.IMPORT) {
            val vm: OnboardingViewModel = viewModel(key = "import-${it.id}", factory = factory { OnboardingViewModel(c, addingWallet = true) })
            OnboardingFlow(vm, onFinished = { nav.popBackStack(Routes.MAIN, false) }, onCancel = { nav.popBackStack() })
        }
    }
}
