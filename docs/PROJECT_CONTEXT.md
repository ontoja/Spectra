# Spectra Wallet — pełny kontekst projektu i rozmowy

> Ten plik zastępuje historię rozmowy z Claude. Nowa sesja powinna przeczytać go w całości (oraz `README.md`)
> przed jakąkolwiek pracą. Stan na: 2026-11-03, gałąź `claude/zealous-lamport-18abto`, ostatni commit `fb8a5d8`
> (+ commit z tym plikiem).

---

## 1. Kim jest użytkownik i jak z nim pracować

- Pisze po polsku (krótko, często bez polskich znaków). **Odpowiadaj po polsku.** UI aplikacji też jest po polsku.
- Repozytorium: `ontoja/Spectra` (GitHub), gałąź robocza: **`claude/zealous-lamport-18abto`**. Pushuj tylko tam.
  Nie twórz PR, dopóki użytkownik o to nie poprosi.
- Commity kończ liniami:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01JjP6zjQA2tJwcXvxW7W1U6
  ```
  (nowa sesja dostanie własny link w przypomnieniu systemowym — wtedy użyj nowego).
- Użytkownik chce dostawać gotowy plik **APK** (wysyłany narzędziem SendUserFile). APK nie jest w gicie
  (`*.apk` w `.gitignore`), buduje się go lokalnie.

## 2. Historia rozmowy (chronologicznie)

1. **Główne zlecenie** (bardzo długi prompt, streszczony w sekcji 3): zbudować kompletną aplikację Android
   „Spectra Wallet v0.1” — niekustodialny, prywatny portfel multi-chain (Solana, Ethereum, Litecoin) w Kotlinie +
   Jetpack Compose, Clean Architecture + MVVM, design „Aurora Glass” wg załączonego pliku HTML
   `Portfel krypto — redesign.html` (12 ekranów referencyjnych). Fazy 1–10. Repo było puste.
2. Zbudowałem całość (szczegóły niżej). Wynik: 66 testów zielonych, APK debug + release (R8) się budują, lint czysty.
   Podpisywanie transakcji w **mainnecie jest domyślnie wyłączone** (wersja eksperymentalna bez audytu).
3. **„gdzie jest apk?”** → wysłałem `spectra-0.1.0-debug.apk`, wyjaśniłem instalację i że mainnet jest zablokowany,
   a testnety (Profil → Sieci testowe) działają.
4. **„Nie ma żadnych animacji, dodaj animacje wszędzie gdzie powinny być”** → dodałem system animacji w całej
   aplikacji (sekcja 8), wysłałem `spectra-0.1.1-debug.apk`. Użytkownik może prosić o dostrojenie konkretnych animacji.
5. **„Spisz dokładnie wszystkie informacje… do pliku md”** → ten plik.
6. Użytkownik przetestował na **prawdziwym telefonie** (screenshot ekranu Odbierz) i poprosił: „dodaj ikonki krypto,
   na środku kodu QR ikonkę krypto w zależności od sieci i ogólnie popraw to UI”. Zrobione:
   - `ui/components/CryptoLogos.kt`: wektorowe logo SOL/ETH/LTC (`ChainLogo`), `AssetAvatar` = logo sieci dla monet
     natywnych, dla tokenów litera + mała plakietka sieci; logo także w chipach (Wymiana, Historia, Tokeny).
   - `ui/receive/Receive.kt` przepisany: przełącznik sieci z logo na całą szerokość, karta z logo/nazwą konta/
     Mainnet-Testnet, QR z zaokrąglonymi modułami i logo w środku (korekcja błędów H, dziura ~22%), adres z
     pogrubionymi pierwszymi/ostatnimi 6 znakami, dotknięcie QR/adresu kopiuje, lżejsze ostrzeżenie, przyciski
     przypięte nad paskiem nawigacji (Kopiuj + ikona Udostępnij), LTC pokazuje adres od razu i dopiero potem
     podmienia na nowy nieużywany.
   - Test `ScreenshotTest.receive*QrWithLogoScans` renderuje ekran i dekoduje QR ZXingiem — 3/3 zgodne z adresem.
   - Wysłano `spectra-0.1.2-debug.apk`.
7. Screenshot ekranu Wymiana (ze starego APK) → „zrób 3 mockupy z prawdziwymi ikonkami krypto”. Przygotowałem
   płótno Design (artefakt claude.ai: A „Szklane karty”, B „Trasa między sieciami”, C „Kwota i klawiatura”).
   Użytkownik wybrał **A + cross-chain**. Zrobione w `ui/swap/Swap.kt`:
   - usunięte rzędy chipów sieci; każda strona ma pigułkę tokena (logo + symbol), a sieć wybiera się w arkuszu
     „Co wysyłasz? / Co chcesz otrzymać?” (zakładki SOL/ETH/LTC z logo + lista aktywów z saldami) → różne sieci po
     obu stronach = wymiana międzysieciowa (LI.FI), pokazywana paskiem „Wymiana międzysieciowa · A → B · most LI.FI”;
   - duża kwota, „≈ X zł” tylko gdy znana cena (nigdy zmyślona), etykieta sieci pod kwotą, okrągły przycisk zamiany
     nachodzący na obie karty, linia kursu „1 X ≈ Y Z” + „przez Jupiter/LI.FI · N s”, karta szczegółów
     (poślizg w `SegmentedTabs`, minimum, wpływ na cenę, czas, trasa, opłaty), pigułka Mainnet/Testnet;
   - `SwapViewModel.select(from, chain, token)` (wybór tego samego aktywa co po drugiej stronie zamienia strony);
   - `CryptoLogos.kt`: logo USDC/USDT rozpoznawane **wyłącznie po adresie** mintu/kontraktu w mainnecie (nie po
     symbolu — spamowy „USDC” nie dostaje logo); `AssetAvatar(token: TokenInfo)` używany w Home/Send/Swap.
   - Screenshoty: `14_swap`, `14b_swap_crosschain`, `14c_swap_stablecoins`. Wysłano `spectra-0.1.3-debug.apk`.

## 3. Pierwotna specyfikacja (streszczenie wymagań użytkownika)

Produkt: Spectra Wallet 0.1, Android, Kotlin, Compose, Clean Architecture + MVVM, design Aurora Glass,
non-custodial, PIN + opcjonalna biometria, wiele kont HD i wiele fraz, sieci Solana/Ethereum/Litecoin,
mainnet + testnety.

**Bez**: rejestracji, e-maila, telefonu, „Spectra ID”, kont centralnych, obowiązkowej chmury, analityki, reklam.
Uczciwie informować, że dostawcy RPC widzą adresy publiczne i IP (brak obietnicy anonimowości).

**Zasady**: sprawdzone biblioteki kryptograficzne, żadnych własnych prymitywów; nigdy nie wymyślać odpowiedzi RPC,
hashy, sald, tras swapów, wyników stakingu ani statusów sukcesu; brakujące integracje oznaczać jako niedostępne;
nie udostępniać produkcyjnych transakcji, dopóki testy bezpieczeństwa/integracji nie przejdą.

**Design**: wg screenshotów (jasny pastelowy gradient, lawenda/fiolet, glass karty, duże zaokrąglenia), tokeny
zamiast hardkodu, jasny i ciemny motyw (domyślnie jasny), animacje 180–350 ms, respektowanie „reduce motion”,
TalkBack, skalowanie czcionek, kontrast, cele dotyku.
Kolory sugerowane: #6445EF, #D9D1FA, #D9E9FF, #F8DDD9, #D8F4E9 (faktycznie użyte wartości z HTML — sekcja 6).

**Nawigacja**: Home, Swap, Activity, Staking, Settings (zgodnie z referencją: pigułka 4 zakładek + okrągły przycisk
Wymiana). Po zablokowaniu ekrany wrażliwe niedostępne przez back stack.

**Onboarding**: Witaj (Utwórz / Importuj) → PIN 6 cyfr + potwierdzenie (własna klawiatura, nie plaintext, ochrona
przed brute force, PIN nie jest samodzielnym sekretem kryptograficznym) → Biometria (BiometricPrompt; stany:
dostępna, brak sprzętu, brak zapisanej, odmowa, anulowanie, lockout, zmiana enrollmentu) → Fraza 12 słów BIP-39
(tylko w chronionym flow, ćwiczenie weryfikacji, bez schowka, bez logów/backupów, ochrona przed screenshotami,
ostrzeżenie o utracie) → generowanie kont (prawdziwy postęp) → Portfel gotowy (adresy skrócone, odznaki tylko dla
faktycznie włączonych funkcji).

**Wallet core**: wiele fraz, wiele kont z jednej frazy, rozróżnienie vault/fraza/konto HD/adres/sieć, zapisana
ścieżka; ETH BIP-44; LTC natywny SegWit BIP-84 + wykrywanie typowych ścieżek przy imporcie; Solana — wiele
popularnych ścieżek; testy wektorami i niezależnymi implementacjami; nigdy nie zmieniać ścieżki po cichu.

**Security engine**: AES-256-GCM, Android Keystore (sprzętowo gdzie możliwe), separacja sekretów od metadanych,
autoryzacja każdej operacji podpisu (świeży PIN/biometria), brak sekretów w logach/backupach/crash reportach,
obsługa uszkodzonego vaulta, bezpieczne usuwanie. Auto-lock: natychmiast/30 s/1/5/15 min, domyślnie 1 min.
Odsłonięcie frazy zawsze wymaga świeżego uwierzytelnienia. Root: tylko ostrzeżenie. Backup: tylko ręczny.

**Adaptery blockchain** za stabilnym interfejsem (derywacja, walidacja adresów, salda, tokeny, historia, opłaty,
przygotowanie, podpis, broadcast, potwierdzenia, normalizacja błędów).
- **Solana**: SOL, SPL, Token-2022, transfery, metadane, historia, statusy, opłaty, priority fees, tworzenie kont
  tokenów, rent-exempt, wygasanie blockhash, Devnet + Mainnet, podpis lokalny.
- **Ethereum**: ETH, ERC-20, metadane, EIP-1559, nonce, wykrywanie zastąpienia, historia, approvals, ostrzeżenia
  o interakcjach z kontraktami, Mainnet + Sepolia, weryfikacja chain id.
- **Litecoin**: salda, SegWit, walidacja, UTXO, wybór UTXO, sat/vB, reszta, podpis, broadcast, potwierdzenia,
  historia, testnet; dust, niepotwierdzone, wydane wejścia, gap limit, prefiksy sieci.

**RPC Manager**: primary/fallback/custom, walidacja, weryfikacja tożsamości sieci, timeouty, rate-limit, backoff,
health, cache; custom RPC nie zmienia źródła cen/indeksera; brak automatycznego rebroadcastu przy niepewnym wyniku.

**Portfel**: wartość całkowita, wybór konta, opcjonalne saldo łączne, ukrywanie sald, lista aktywów, ikony, fiat,
wykres, Wyślij/Odbierz/Wymień, szczegóły aktywa; domyślnie PLN (+USD, EUR…); cache offline z etykietą „nieaktualne”;
brak ceny ≠ zero. Odkrywanie tokenów SPL/ERC-20, ręczne dodawanie, walidacja, ukrywanie, filtr spamu, metadane
jako niezaufane.

**Send/Receive**: pełny flow (konto → aktywo → odbiorca → walidacja → kwota → opłata → saldo → przegląd →
autoryzacja → podpis → broadcast → monitoring), MAX z uwzględnieniem opłat/rezerw; Receive z QR, kopiowanie,
udostępnianie, ostrzeżenie o sieci, rotacja adresów LTC. Sukces ≠ przyjęcie przez RPC.

**Cykl życia transakcji**: Draft, Estimated, Awaiting Authorization, Signed, Broadcasting, Pending, Confirmed,
Finalized, Failed, Expired, Replaced, Dropped, Unknown; trwałe metadane; reorgi; timeout ≠ porażka.

**Swapy**: same-chain (Jupiter dla Solany, 0x lub inny dla ETH), cross-chain (np. LI.FI), LTC wymaga weryfikacji;
UX: sieci, aktywa, kwota, szacunek, minimum, opłaty, slippage, czas, trasa, wygasanie, autoryzacja, postęp;
trwały stan; nigdy nie powtarzać swapu o niepewnym statusie.

**Staking**: natywny SOL (walidatory, prowizje, konto stake, delegacja, aktywacja, pozycje, nagrody,
dezaktywacja, wypłata), epoki, bez obiecywania zysków; użytkownik zachowuje stake i withdraw authority.
Bez stakingu ETH i LTC w v0.1.

**Activity**: filtry (konto, sieć, aktywo, typ, status), linki do eksploratorów, rozróżnienie testnet/mainnet.

**Settings**: konta, dodaj/importuj/usuń portfel (ostrzeżenia przy ostatnim), waluta, motyw, widoczność sald,
agregacja, auto-lock, zmiana PIN, biometria, fraza, RPC, testnety, prywatność, o aplikacji.

**Testy, release requirements, jakość**: testy krypto/blockchain/swap/staking/Android; bez prawdziwych środków;
przed jakimkolwiek „production-ready”: audyt itd. — inaczej oznaczyć jako eksperymentalne. Bez fałszywych
sukcesów, bez telemetrii, bez zbędnych uprawnień, przypięte wersje zależności, bez nadmiaru dokumentacji.

## 4. Środowisko (kontener chmurowy — trzeba odtworzyć po restarcie!)

Kontener jest efemeryczny. Jeśli `/opt/android-sdk` nie istnieje, odtwórz:

```bash
mkdir -p /opt/android-sdk && cd /opt/android-sdk
curl -sS -o clt.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
unzip -q clt.zip && mkdir -p cmdline-tools/latest && mv cmdline-tools/bin cmdline-tools/lib cmdline-tools/NOTICE.txt cmdline-tools/source.properties cmdline-tools/latest/
yes | cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null
cmdline-tools/latest/bin/sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
rm clt.zip
```

`local.properties` (nie w gicie): `sdk.dir=/opt/android-sdk`.

**Maven Central zwraca 429** z tego kontenera → lokalny init script (nie w repo) przekierowuje na mirror Google:

```bash
mkdir -p ~/.gradle/init.d && cat > ~/.gradle/init.d/mirror.gradle <<'EOF'
def mirror = "https://maven-central.storage-download.googleapis.com/maven2/"
def fix = { repos -> repos.withType(MavenArtifactRepository).configureEach { r -> if (r.url.toString().contains("repo.maven.apache.org")) r.url = mirror } }
beforeSettings { s -> fix(s.pluginManagement.repositories); fix(s.dependencyResolutionManagement.repositories) }
allprojects { fix(repositories); fix(buildscript.repositories) }
EOF
echo "robolectric.repoUrl=https://maven-central.storage-download.googleapis.com/maven2" >> ~/.gradle/gradle.properties
```

- JDK 21 jest w systemie (projekt kompiluje do JVM 17). Gradle wrapper 8.14.3 (z sumą SHA-256).
- Brak `/dev/kvm` → **nie da się uruchomić emulatora**. Wizualna weryfikacja: Robolectric (`ScreenshotTest`) →
  `app/build/screenshots/*.png`.
- Narzędzia pomocnicze (scratchpad, giną po restarcie): venv Pythona z `bip_utils`, `eth-account`, `solders`,
  `fonttools`, `pillow` — użyte do wygenerowania wektorów referencyjnych (już zapisane w repo) i statycznych
  wag fontu. PyPI działa bez proxy.

**Komendy**:

```bash
./gradlew :wallet-core:test :app:testDebugUnitTest
./gradlew :app:assembleDebug          # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease        # R8, niepodpisany
./gradlew :app:lintDebug
./gradlew :app:assembleDebug -Pspectra.allowMainnetSigning=true   # włącza podpis w mainnecie (tylko dev!)
```

## 5. Wersje i zależności (przypięte w `gradle/libs.versions.toml`)

AGP 8.13.2, Kotlin 2.2.21, KSP 2.3.2, Compose BOM 2025.12.01, activity-compose 1.11.0, lifecycle 2.9.4,
navigation 2.9.6, Room 2.8.4, DataStore 1.1.7, biometric 1.1.0, core-ktx 1.17.0, coroutines 1.10.2,
serialization 1.9.0, BouncyCastle `bcprov-jdk18on` 1.84, OkHttp 4.12.0, ZXing core 3.5.3, JUnit 4.13.2,
Robolectric 4.16.1, androidx.test core 1.7.0. compileSdk/targetSdk 36, **minSdk 30**.
`applicationId = app.spectra.wallet`, `versionName = 0.1.0-experimental`.
Flaga `spectra.allowMainnetSigning=false` w `gradle.properties` → `BuildConfig.ALLOW_MAINNET_SIGNING`.

Dlaczego bez web3j/bitcoinj/sol4k: web3j 4.14 wymaga JVM 21 i ciągnie natywną bibliotekę KZG; bitcoinj nie ma
Litecoina i jest ciężki. Decyzja: prymitywy z BouncyCastle, kodowanie protokołów własne, ale weryfikowane bajt
po bajcie wektorami i niezależnymi implementacjami.

## 6. Design referencyjny

Źródło: plik użytkownika `Portfel krypto — redesign.html` (bundle z zagnieżdżonymi bundlami). Rozpakowane ekrany
są zapisane w repo: **`docs/design-reference/screens/*.png`** (render 390×844) i
**`docs/design-reference/html/*.html`** (HTML/CSS bez fontów). Ekrany: 01 Witaj, 02 Ustaw PIN, 03 Biometria,
03b Biometria brak, 04 Fraza odzyskiwania (+ krok weryfikacji 3 pytania × 3 słowa w HTML), 06 Przygotowanie
portfela, 07 Portfel gotowy, PIN odblokowanie, Start (Home), Solana szczegóły, Wyślij, Staking (Solana/Ethereum).

Tokeny wyciągnięte z HTML (zaimplementowane w `ui/theme/Tokens.kt`):
- tło `#F5F3FA`, tekst `#17151F`, tekst drugorzędny `#5E5A6E`, wyłączony `#8A8699`
- primary `#6046E8`, link/pressed `#4A33C9`, positive `#12814F`, negative `#B42A40` / mocny `#C8324A`
- glass: `rgba(255,255,255,0.55)`, border `0.8`, strong `0.95`, cień `rgba(48,36,110,0.07–0.14)`
- aurora: lawenda `#AB9FF2`, brzoskwinia `#FFBCA0`, niebieski `#96C4FF`, mięta `#A0E6C8` (radialne, blur 48–52px)
- awatary: Solana `#8B6CFF→#3DDBB0`, Ethereum `#9AAEFF→#5A6CF0`, Litecoin `#C3CCDA→#6E7F99`,
  awatar konta: conic `#AB9FF2,#FFBCA0,#96C4FF`
- font **Instrument Sans** (OFL) 400/500/600/700 — statyczne instancje w `app/src/main/res/font/`,
  licencja `app/FONT_LICENSE_OFL.txt`
- rozmiary: saldo hero 64sp + część dziesiętna 34sp, tytuły 28sp, klawisze PIN 76dp szklane koła, przyciski 56dp
  r=28, karty r=26–28, nav pill 64dp + okrągły przycisk wymiany 64dp
- ikony: obrysowe SVG z referencji przepisane do `ui/components/Icons.kt` (`SpectraIcons`)

Zmiany względem referencji (świadome): „Skarbce” na Home → zakładka „Ukryte” (tokeny ukryte/spam);
„[APY]%” → inflacja sieci z RPC z dopiskiem, że to nie gwarantowany zwrot; „Kup” otwiera wyjaśnienie, że on-ramp
nie jest zintegrowany; „Regulamin/Polityka” na powitaniu → info o wersji eksperymentalnej + „Prywatność
i ograniczenia”; zakładka Ethereum w Stakingu pokazuje „niedostępny w v0.1”.

## 7. Architektura i mapa plików

```
settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml, gradle/wrapper/*
wallet-core/   (czysty Kotlin/JVM, testowalny bez Androida)
  crypto/   Hashes (BC), SecureMemory, Secp256k1 (RFC6979, low-S, recovery), Ed25519Keys, Bip39,
            DerivationPath, Bip32 (Bip32Key, Bip32PublicNode = CKDpub, Slip10Ed25519Key)
  encoding/ Hex, Base58(+Check), Bech32 (BIP-173/350, segwit), Rlp
  model/    Chain, NetworkEnv, Network (chainId, genesis, eksploratory), Amount, DerivationScheme,
            DerivedAddress, AssetId/TokenInfo (sanitize, spam), TxState + TxStateMachine, TxType, HistoryItem,
            StatusObservation, WalletError (znormalizowane błędy)
  wallet/   Keyring (krótkożyjący seed; derywacja adresów, kluczy, xpub LTC)
  chain/    Addresses (Solana/Ethereum/Litecoin walidacja), ChainAdapter (+ TransferRequest, PreparedTransaction,
            SignedTransaction, BroadcastOutcome Accepted/Rejected/Unknown, TrackedTransaction)
    solana/   SolanaPrimitives (PublicKey, PDA, createWithSeed, programy), SolanaMessage (legacy compile,
              parser v0, signExternal), SolanaInstructions (System, ComputeBudget, Token transferChecked,
              ATA createIdempotent, Stake), SolanaRpc, SolanaAdapter
    ethereum/ EthereumTx (EIP-1559, Erc20Abi, CallDescription), EthereumAdapter (+ Blockscout indexer)
    litecoin/ LitecoinTx (BIP-143, CoinSelector, dust 10 000 lit), LitecoinAdapter (Esplora)
  rpc/      Http (HttpTransport, tylko HTTPS), RpcManager (+TtlCache, health, IdentityProbe), JsonRpcClient
  staking/  SolanaStaking (walidatory, pozycje, nagrody, delegate/deactivate/withdraw)
  market/   PriceService (CoinGecko, opcjonalny klucz demo)
  swap/     Swap (modele, SwapRouter), JupiterProvider, LifiProvider
  test resources: bip39-vectors.json (Trezor), reference-addresses.txt (bip_utils),
                  reference-transactions.txt (eth-account, solders)

app/  (Android)
  SpectraApp (ProcessLifecycle → auto-lock, pętla monitoringu tx), MainActivity (FragmentActivity)
  data/      Database (Room v1: vaults, accounts, chain_addresses, tokens, balances, prices, transactions,
             swaps, rpc_endpoints; schemat w app/schemas), Preferences (DataStore; AutoLock, UserPreferences)
  domain/    AppContainer (ręczne DI), Mappers + DefaultEndpoints, WalletRepository, PortfolioRepository,
             TransactionRepository (bramka mainnet), SwapRepository, StakingRepository
  security/  VaultCrypto (+ LockoutPolicy), KeystoreBackend, SecurityManager, Authorization
             (AuthorizationCoordinator.withKeyring / revealMnemonic), DeviceIntegrity (root)
  ui/        SpectraRoot (maszyna stanów: onboarding/lock/keys-lost/main), MainNavigation (NavHost + zakładki),
             MainViewModel (sesja, portfel, odświeżanie, online), theme/ (Tokens, Theme), components/
             (Glass, Controls, Keypad, Inputs, BottomBar, Sheet, States, Icons, Motion), format/Format (PL),
             common/Common (SecureScreen FLAG_SECURE, biometria, humanMessage), onboarding/, lock/, auth/,
             home/ (Home, AssetDetail), send/, receive/, activity/, tx/, swap/, staking/, settings/
  test: VaultCryptoTest (+ LockoutPolicyTest), FormatTest, ScreenshotTest (Robolectric)
docs/PROJECT_CONTEXT.md (ten plik), docs/design-reference/
README.md (instrukcja budowania, bezpieczeństwo, macierz funkcji)
```

## 8. Kluczowe decyzje implementacyjne

**Bezpieczeństwo**
- Losowy DEK 256-bit szyfruje każdą frazę AES-256-GCM (AAD = id vaulta). DEK opakowany:
  `Argon2id(HMAC_Keystore(PIN), salt)` (m=19456 KiB, t=2, p=1), a potem jeszcze kluczem AES-GCM z Keystore
  (StrongBox jeśli jest). Pliki w `noBackupFilesDir/vault/` (`keys.json`, `vault_<id>.bin`, AtomicFile).
- Lockout: 5. błąd → 30 s, 6 → 1 min, 7 → 5 min, 8 → 15 min, ≥9 → 1 h (persist przez `commit()` celowo).
- Biometria: osobny klucz Keystore `BIOMETRIC_STRONG`, `setInvalidatedByBiometricEnrollment(true)`, CryptoObject;
  unieważnienie → automatyczne wyłączenie i komunikat.
- DEK nigdy nie jest cachowany: odblokowanie tylko weryfikuje PIN; podpis/fraza/dodanie konta/usunięcie portfela
  wymagają świeżej autoryzacji (arkusz PIN/biometria w `AuthorizationHost`).
- Blokada → cały graf nawigacji wypada z kompozycji. FLAG_SECURE na ekranach PIN/fraza/import/autoryzacja
  (opcjonalnie globalnie). Brak backupu (`allowBackup=false`, data extraction rules), brak cleartext,
  R8 usuwa `android.util.Log` w release. Root: tylko ostrzeżenie.
- Zgubione klucze Keystore → ekran KEYS_LOST → odtworzenie z frazy.

**Ścieżki derywacji** (wybór jawny przy imporcie z podglądem sald):
SOL `m/44'/501'/n'/0'` (domyślna), `m/44'/501'/n'`, seed[0..32] (solana-keygen); ETH `m/44'/60'/0'/0/n`
(domyślna), Ledger Live `m/44'/60'/n'/0/0`; LTC BIP-84 (domyślna, jedyna do wydawania), BIP-49 i BIP-44
tylko podgląd. Testnet LTC: coin type 1, `tltc`.

**Sieci i dostawcy** (zweryfikowane na żywo przy budowie):
- Solana mainnet: api.mainnet-beta.solana.com (+ solana-rpc.publicnode.com); Devnet: api.devnet.solana.com
- Ethereum: ethereum-rpc.publicnode.com (+ eth.drpc.org); Sepolia: ethereum-sepolia-rpc.publicnode.com
  (+ sepolia.gateway.tenderly.co); indeksery Blockscout (eth / eth-sepolia)
- Litecoin: litecoinspace.org/api i /testnet/api (Esplora, testnet4)
- Ceny: CoinGecko; metadane SPL: Jupiter tokens API; swapy: Jupiter lite-api (Solana), LI.FI (ETH i ETH↔SOL).
  **LI.FI nie obsługuje Litecoina** → swapy LTC niedostępne. Swapy tylko w mainnecie.
- Tożsamość sieci: genesis hash Solany/Litecoina, `eth_chainId`; niezgodny endpoint jest wykluczany.

**Bramka mainnetu**: `TransactionRepository.signingAllowed()` — testnet zawsze, mainnet tylko z
`ALLOW_MAINNET_SIGNING`. Dotyczy wysyłki, swapów, approvals, stakingu.

**Animacje** (`ui/components/Motion.kt` + edycje ekranów): `pressScale`, `enterAnimation` (stagger),
`shake` (zły PIN), `floating`, `pulse`, `Reveal`, `FadeAwayOverlay`, `shimmer`/`SkeletonRow`,
`animatedNumber`; dryfujące tło aurory (transformacja warstwy), przesuwany wskaźnik w `SegmentedTabs`
i dolnym pasku, obrót przycisku Wymiana, przejścia root/zakładki/onboarding (kierunkowe)/Wyślij↔Przegląd,
rysowanie wykresu, „pop” ✓ na ekranie gotowości, licznik salda, animowane cyfry kwoty, przenikanie QR.
Wszystko wyłącza się, gdy systemowa skala animacji = 0 (`Spectra.reducedMotion`).

## 9. Stan weryfikacji

- **71 testów, 0 porażek**: wallet-core 48 (BIP-39 Trezor, BIP-32 v1, SLIP-10 v1, CKDpub, 58 adresów vs
  bip_utils, EIP-1559/ERC-20 vs eth-account, Solana tx/ATA/stake-seed vs solders, BIP-143 oficjalny wektor,
  RFC6979, Bech32/Base58/RLP/EIP-55, RpcManager, adaptery na MockWebServer, modele) + app 18 (VaultCrypto,
  Lockout, Format, 11 screenshotów w tym 3 testy dekodowania QR).
- `assembleDebug`, `assembleRelease` (R8), `lintDebug` — OK.
- **Niezweryfikowane**: brak testów na prawdziwym telefonie (Keystore/StrongBox, BiometricPrompt, śmierć procesu,
  rotacja), brak migracji Room (tylko schemat v1), brak przebiegów na żywych testnetach (brak środków testowych),
  animacje oglądane tylko jako stan końcowy (nie na żywo).

## 10. Znane ograniczenia / pomysły na dalszą pracę

- Mainnet signing wyłączony do czasu audytu i testów (decyzja użytkownika potrzebna, jeśli chce włączyć).
- Brak: skanera QR (wymaga uprawnienia aparatu), on-rampu „Kup”, stakingu ETH/LTC, wydawania z LTC BIP-44/49,
  MWEB, wykresu portfela na Home (są wykresy per aktywo), nazw walidatorów, logo tokenów (prywatność).
- Swapy działają tylko jako wyceny w domyślnym buildzie (mainnet-only + bramka).
- Stany aktywacji stake'u liczone z epok (warm-up/cool-down może wydłużać).
- Możliwe kolejne kroki: testy instrumentalne na urządzeniu, testy migracji Room, wersja angielska stringów
  (teksty są w Kotlinie, nie w `strings.xml`), WorkManager do monitoringu w tle, dostrajanie animacji
  wg uwag użytkownika, podpisany release (keystore — nie commitować).

## 11. Wydane pliki dla użytkownika

- `spectra-0.1.0-debug.apk` — pierwsza wersja (bez animacji).
- `spectra-0.1.1-debug.apk` — z animacjami.
- `spectra-0.1.2-debug.apk` — logo kryptowalut, QR z logo, nowy ekran Odbierz (lokalnie w katalogu repo, ignorowany przez git).
- `spectra-0.1.3-debug.apk` — nowy ekran Wymiana (wariant A, wybór sieci w arkuszu aktywów, cross-chain), logo USDC/USDT.
Instalacja: Android 11+, zgoda na nieznane źródła; podpis debug; mainnet tylko odczyt, testnety pełne
(Profil → Sieci testowe → przełącznik w arkuszu kont / ustawieniach).
