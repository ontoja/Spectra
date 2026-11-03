package app.spectra.wallet.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import app.spectra.core.crypto.Bip39
import app.spectra.core.model.AssetId
import app.spectra.core.model.AssetKind
import app.spectra.core.model.Chain
import app.spectra.core.model.DerivationScheme
import app.spectra.core.model.Network
import app.spectra.core.model.NetworkEnv
import app.spectra.core.model.TokenInfo
import app.spectra.core.wallet.Keyring
import app.spectra.wallet.SpectraApp
import app.spectra.wallet.data.AccountEntity
import app.spectra.wallet.data.BalanceEntity
import app.spectra.wallet.data.ChainAddressEntity
import app.spectra.wallet.data.PriceEntity
import app.spectra.wallet.data.VaultEntity
import app.spectra.wallet.domain.toEntity
import app.spectra.wallet.ui.common.LocalContainer
import app.spectra.wallet.ui.onboarding.OnboardingFlow
import app.spectra.wallet.ui.onboarding.OnboardingStep
import app.spectra.wallet.ui.onboarding.OnboardingViewModel
import app.spectra.wallet.ui.theme.SpectraTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import app.spectra.wallet.ui.components.AuroraBackground
import app.spectra.wallet.ui.components.AuroraPreset
import app.spectra.wallet.ui.send.SendScreen
import app.spectra.wallet.ui.staking.StakingScreen
import app.spectra.wallet.ui.settings.SettingsScreen
import app.spectra.wallet.ui.swap.SwapScreen
import app.spectra.wallet.ui.receive.ReceiveScreen

/**
 * Renders key screens on the JVM (no emulator) to PNGs under app/build/screenshots for visual comparison with the
 * reference designs. Balances below are synthetic fixtures for rendering only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xxhdpi", application = SpectraApp::class)
class ScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<SpectraApp>()
    private val out = File("build/screenshots").apply { mkdirs() }

    private fun shot(name: String, content: @Composable () -> Unit): Bitmap {
        rule.mainClock.autoAdvance = false
        rule.setContent { SpectraTheme { CompositionLocalProvider(LocalContainer provides app.container) { content() } } }
        repeat(15) {
            Thread.sleep(150)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            rule.mainClock.advanceTimeBy(200)
        }
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }

    /** Decodes any QR code visible in [bmp] with ZXing (independent of how we drew it). */
    private fun decodeQr(bmp: Bitmap): String {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val src = com.google.zxing.RGBLuminanceSource(bmp.width, bmp.height, px)
        val hints = mapOf(com.google.zxing.DecodeHintType.TRY_HARDER to true, com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE))
        return com.google.zxing.MultiFormatReader().decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(src)), hints).text
    }

    @Test fun welcome() {
        val vm = OnboardingViewModel(app.container, false)
        shot("01_welcome") { OnboardingFlow(vm, {}) }
    }

    @Test fun pinCreate() {
        val vm = OnboardingViewModel(app.container, false).apply { startCreate(); pinDigit('4'); pinDigit('8') }
        shot("02_pin") { OnboardingFlow(vm, {}) }
    }

    @Test fun phrase() {
        val vm = OnboardingViewModel(app.container, false)
        vm.startCreate()
        // Jump to the phrase step without creating credentials (Keystore is not available under Robolectric).
        val f = OnboardingViewModel::class.java.getDeclaredField("step\$delegate").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") (f.get(vm) as androidx.compose.runtime.MutableState<OnboardingStep>).value = OnboardingStep.PHRASE_SHOW
        vm.afterBiometrics()
        shot("04_phrase_hidden") { OnboardingFlow(vm, {}) }
    }

    private fun seed(): String = runBlocking {
        val c = app.container
        val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
        val vaultId = "00000000-test-vault-0001"
        val accId = "$vaultId:0"
        c.db.wallet().upsertVault(VaultEntity(vaultId, "Portfel główny", 0, false, true, "SOL_BIP44_CHANGE", "ETH_BIP44", "LTC_BIP84"))
        c.db.wallet().upsertAccount(AccountEntity(accId, vaultId, 0, "Konto 1", 0))
        val rows = Keyring.fromMnemonic(Bip39.phraseBytes(words)).use { k ->
            Chain.entries.flatMap { ch -> NetworkEnv.entries.map { env ->
                val n = Network(ch, env); val s = DerivationScheme.defaultFor(ch); val d = k.deriveAddress(n, s, 0)
                ChainAddressEntity("$accId:${n.id}", accId, n.id, s.name, d.address, d.path, d.publicKeyHex, if (ch == Chain.LITECOIN) k.accountPublicNode(n, s, 0).serialize() else null)
            } }
        }
        c.db.wallet().upsertAddresses(rows)
        val sol = TokenInfo.native(Network(Chain.SOLANA, NetworkEnv.MAINNET))
        val eth = TokenInfo.native(Network(Chain.ETHEREUM, NetworkEnv.MAINNET))
        c.db.portfolio().insertTokensIfAbsent(listOf(sol.toEntity(false, false), eth.toEntity(false, false)))
        val now = System.currentTimeMillis()
        c.db.portfolio().upsertBalances(listOf(
            BalanceEntity(accId, sol.id.key, "solana:mainnet", "149900000", 9, null, null, now),
            BalanceEntity(accId, eth.id.key, "ethereum:mainnet", "200000000000000", 18, null, null, now)))
        c.db.portfolio().upsertPrices(listOf(PriceEntity("cg:solana", "PLN", "114.28", -1.45, now), PriceEntity("cg:ethereum", "PLN", "2650.00", -1.04, now)))
        c.prefs.setActiveAccount(accId)
        c.security.markUnlocked()
        accId
    }

    @Test fun home() {
        seed()
        val main = MainViewModel(app.container, app, autoRefresh = false)
        shot("10_home") { MainNavigation(app.container, main) }
    }

    @Test fun send() {
        seed()
        val main = MainViewModel(app.container, app, autoRefresh = false)
        shot("11_send") { AuroraBackground(AuroraPreset.DETAIL) {
            SendScreen(main, null, {}, {}) } }
    }

    @Test fun staking() {
        seed()
        val main = MainViewModel(app.container, app, autoRefresh = false)
        shot("12_staking") { AuroraBackground(AuroraPreset.DETAIL) { StakingScreen(main) } }
    }

    @Test fun settings() {
        seed()
        val main = MainViewModel(app.container, app, autoRefresh = false)
        shot("13_settings") { AuroraBackground(AuroraPreset.DETAIL) { SettingsScreen(main, { _, _ -> }, {}) } }
    }

    @Test fun swap() {
        seed()
        val main = MainViewModel(app.container, app, autoRefresh = false)
        shot("14_swap") { AuroraBackground(AuroraPreset.DETAIL) { SwapScreen(main) } }
    }

    @Test fun swapCrossChain() {
        seed()
        val main = MainViewModel(app.container, app, autoRefresh = false)
        val eth = TokenInfo.native(Network(Chain.ETHEREUM, NetworkEnv.MAINNET))
        shot("14b_swap_crosschain") { AuroraBackground(AuroraPreset.DETAIL) { SwapScreen(main) { amountText = "1,25"; select(false, Chain.ETHEREUM, eth) } } }
    }

    @Test fun swapStablecoinLogo() {
        seed()
        val main = MainViewModel(app.container, app, autoRefresh = false)
        val sol = Network(Chain.SOLANA, NetworkEnv.MAINNET)
        val usdc = TokenInfo(AssetId(sol, AssetKind.SPL_TOKEN, "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"), "USDC", "USD Coin", 6)
        val eth = Network(Chain.ETHEREUM, NetworkEnv.MAINNET)
        val usdt = TokenInfo(AssetId(eth, AssetKind.ERC20, "0xdAC17F958D2ee523a2206206994597C13D831ec7"), "USDT", "Tether USD", 6)
        shot("14c_swap_stablecoins") { AuroraBackground(AuroraPreset.DETAIL) { SwapScreen(main) { select(true, Chain.ETHEREUM, usdt); select(false, Chain.SOLANA, usdc); amountText = "250" } } }
    }

    private fun receiveScans(chain: Chain) {
        val acc = seed()
        val expected = runBlocking { app.container.wallets.account(acc)!!.view(Network(chain, NetworkEnv.MAINNET))!!.primaryAddress }
        val bmp = shot("15_receive_${chain.name.lowercase()}") { ReceiveScreen(acc, false, chain) {} }
        org.junit.Assert.assertEquals(expected, decodeQr(bmp))
    }

    @Test fun receiveSolanaQrWithLogoScans() = receiveScans(Chain.SOLANA)
    @Test fun receiveEthereumQrWithLogoScans() = receiveScans(Chain.ETHEREUM)
    @Test fun receiveLitecoinQrWithLogoScans() = receiveScans(Chain.LITECOIN)
}
