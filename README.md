# Spectra Wallet 0.1 (experimental)

Non-custodial, privacy-first multi-chain wallet for Android (Solana, Ethereum, Litecoin), built with Kotlin and Jetpack Compose in the "Aurora Glass" design.

> **Experimental build.** Spectra 0.1 has **not** had an independent security audit. It is unsuitable for storing
> valuable assets. Transaction signing on **mainnet is disabled by default** at build time; testnets
> (Solana Devnet, Ethereum Sepolia, Litecoin testnet4) are fully usable.

## Build

Requirements: JDK 17+ (tested with 21), Android SDK platform 36 + build-tools 36.

```bash
./gradlew :wallet-core:test :app:testDebugUnitTest
```

```bash
./gradlew :app:assembleDebug
```

Opt in to mainnet signing only for development builds you understand the risks of:

```bash
./gradlew :app:assembleDebug -Pspectra.allowMainnetSigning=true
```

Screenshot renders of key screens (Robolectric, no emulator) are written to `app/build/screenshots/` by
`ScreenshotTest`. If Maven Central rate-limits Robolectric's runtime download, pass `-Probolectric.repoUrl=<mirror>/maven2`.

## Architecture

| Module | Contents |
| --- | --- |
| `wallet-core` (pure Kotlin/JVM) | BIP-39, BIP-32, SLIP-10, address codecs, RLP, EIP-1559, Solana message/instructions, BIP-143 signing, RPC manager, chain adapters, staking, prices, swap providers |
| `app` (Android) | Compose UI, security engine (Keystore, PIN, biometrics, auto-lock), Room metadata DB, DataStore preferences, repositories |

Clean Architecture + MVVM with manual dependency injection (`AppContainer`). Chain logic lives behind
`ChainAdapter`; Compose screens never build or sign transactions themselves.

Cryptographic primitives (SHA-2, RIPEMD-160, Keccak, HMAC, PBKDF2, Argon2id, secp256k1 ECDSA with RFC 6979, Ed25519)
come from **BouncyCastle** and the platform JCE/Keystore. No primitive is implemented in this project. Protocol
encodings are implemented in `wallet-core` and verified byte-for-byte against official vectors and independent
implementations (see Tests).

## Security design

- **Vault**: a random 256-bit DEK encrypts each recovery phrase with AES-256-GCM (AAD bound to the vault id).
  The DEK is wrapped by `Argon2id(HMAC_Keystore(PIN), salt)` and then again by a non-exportable Android Keystore
  AES-GCM key (StrongBox when available). Off-device brute force of the 6-digit PIN is not possible without the
  device key; on-device guessing is slowed by Argon2id and an escalating, persisted lockout (30 s → 1 h).
  A 6-digit PIN is **not** a strong secret against an attacker with root on the unlocked device.
- **Biometrics**: a separate Keystore key (`BIOMETRIC_STRONG`, invalidated on enrollment change) unwraps a copy of
  the DEK through a `BiometricPrompt` CryptoObject. Enrollment changes disable biometrics; PIN always remains.
- **No cached secrets**: unlocking only verifies the PIN. Every signature, phrase reveal, account derivation and
  wallet removal requires fresh PIN/biometric authorisation; the DEK, mnemonic and keys are wiped after use.
  (JVM `String`s used to *display* words cannot be wiped; the UI keeps them only on protected screens.)
- **Locking**: configurable auto-lock (immediately/30 s/1/5/15 min, default 1 min). When locked, the entire main
  navigation graph leaves composition, so sensitive screens cannot be reached via the back stack.
- **Screens**: `FLAG_SECURE` + disabled recents thumbnails on PIN, phrase, import and authorisation screens
  (optional app-wide). Phrases are never copied to the clipboard.
- **Backups/logs**: `allowBackup=false`, data-extraction rules exclude everything, no analytics/ads/crash SDKs,
  release builds strip `android.util.Log`. Cleartext HTTP is disabled.
- **Root**: heuristic warning only; never blocks and makes no guarantee.
- **Corruption / lost keys**: GCM authentication failures and missing Keystore keys are detected and the user is
  directed to restore from the recovery phrase.

## Derivation paths

Chosen explicitly by the user on import (balances are shown for each candidate); never changed silently.

| Chain | Schemes |
| --- | --- |
| Solana | `m/44'/501'/n'/0'` (default; Phantom/Solflare/Backpack), `m/44'/501'/n'`, `solana-keygen` seed[0..32] |
| Ethereum | `m/44'/60'/0'/0/n` (default; MetaMask), `m/44'/60'/n'/0/0` (Ledger Live) |
| Litecoin | BIP-84 `m/84'/2'/n'` native SegWit (default, spendable); BIP-49 and BIP-44 discovered/displayed only |

Litecoin testnet uses coin type 1 and `tltc` addresses.

## Feature status

| Area | Status |
| --- | --- |
| Onboarding: PIN, biometrics (all availability states), 12-word phrase, verification, real derivation progress | Implemented |
| Import (12–24 words), multiple phrases, multiple HD accounts, account switcher, aggregate balance | Implemented |
| Solana: SOL, SPL and Token-2022 balances, transfers, ATA creation, rent checks, priority fees, simulation-based compute budget, blockhash expiry | Implemented (Token-2022 mints with transfer hooks/fees/non-transferable etc. are refused for sending) |
| Ethereum: ETH and ERC-20 balances/transfers, EIP-1559 fees, nonce floor, replacement/drop detection, chain-id verification | Implemented |
| Litecoin: gap-limit discovery, confirmed-only UTXO selection, change, dust guard, BIP-143 signing, confirmations | Implemented (BIP-84 only for spending) |
| RPC manager: primary/fallback/custom endpoints, identity checks, backoff, rate-limit cooldown, health, single-shot broadcast | Implemented |
| Transaction lifecycle with persisted signed bytes, reconciliation after restart, "unknown" outcome handling | Implemented |
| Portfolio with PLN default, cached/offline display, stale labels, unpriced assets never shown as zero | Implemented |
| Token discovery: Solana via RPC; Ethereum via Blockscout indexer; manual add with on-chain validation; spam heuristics, hiding | Implemented |
| History: Solana via RPC, Ethereum via Blockscout, Litecoin via Esplora, plus locally initiated records | Implemented |
| Swaps: Jupiter (Solana), LI.FI (Ethereum same-chain and ETH↔SOL), quotes, min received, fees, expiry, exact-amount approvals, persisted monitoring | Implemented; **execution requires mainnet signing**, so quotes are informational in default builds |
| Native SOL staking: validators, delegate (seeded stake account, user keeps both authorities), deactivate, withdraw, epoch-based state, recent rewards | Implemented |
| Settings: accounts, removal (typed confirmation + auth), currency, theme, balances, auto-lock, PIN change, biometrics, phrase reveal, RPC, testnets, privacy, about | Implemented |
| Dark theme, reduced-motion support, TalkBack labels, font scaling | Implemented (not yet audited with real users) |

### Unsupported / limitations (by design or pending provider verification)

- **Mainnet signing** disabled by default until independent review and testnet integration testing are done.
- **Litecoin swaps**: LI.FI does not list Litecoin and no other provider has been verified → unavailable.
- **Swaps on testnets**: Jupiter and LI.FI are mainnet-only.
- **Buy (on-ramp)**: not integrated (would require a KYC provider).
- **QR scanning**: not included (would require the camera permission); paste is supported.
- **ETH staking / LTC staking**: out of scope for 0.1.
- **Spending from Litecoin BIP-44/BIP-49 accounts, MWEB**: not supported.
- **Validator names** and **token logos** are not fetched (privacy; untrusted content).
- Portfolio chart on Home is not implemented; per-asset price charts are.
- Stake activation states are derived from delegation epochs; warm-up/cool-down rate limits may stretch them.
- Ethereum history and ERC-20 discovery depend on the Blockscout indexer; a custom node RPC does not change that.
- Network providers (RPC, Blockscout, litecoinspace.org, CoinGecko, Jupiter, LI.FI) see your IP address and the
  public addresses queried. Spectra does not provide anonymity.

## Tests

`./gradlew :wallet-core:test` (48 tests) and `./gradlew :app:testDebugUnitTest` (security, formatting, screenshots).

- BIP-39: all Trezor English vectors (entropy → words → seed → xprv), wordlist SHA-256, checksum/length errors.
- BIP-32 vector 1, SLIP-10 Ed25519 vector 1, public vs private derivation equivalence.
- 58 addresses across every scheme compared with **bip_utils** (independent implementation), mainnet and testnet.
- EIP-1559 ETH and ERC-20 transactions byte-identical to **eth-account**; Solana transfer, ATA and seeded stake
  addresses identical to **solders**; official BIP-143 P2WPKH sighash and signature vector; RFC 6979 vector.
- Bech32/bech32m (BIP-173/350), Base58Check, RLP, EIP-55, network-mismatch detection.
- RPC failover, custom-endpoint priority, no broadcast retry, wrong-chain exclusion, rate-limit cooldown.
- Adapter fixtures (MockWebServer): Solana blockhash expiry, Ethereum replacement/confirmed/finalized/reverted,
  broadcast timeout = unknown (not failure), nonce conflicts, Litecoin UTXO conflicts and confirmations.
- Vault crypto (fake Keystore backend): round trip, wrong PIN, hardware-pepper dependency, AAD binding, tampering,
  PIN change; escalating lockout persistence.

Not yet covered: instrumented tests on devices (Keystore/StrongBox, BiometricPrompt, process death, rotation),
Room migrations (schema v1 is exported to `app/schemas`; there are no migrations yet), live testnet integration runs.
No real-value transactions are executed by tests.

## Before any production claim

Dependency review, threat modelling, key-handling audit, authorisation-bypass testing, backup/recovery testing,
end-to-end testnet signing tests, review of provider trust boundaries, contract-approval flows and cross-chain
failure handling, and an **independent security review**. None of these has been completed.

## Licences

Instrument Sans (SIL OFL 1.1, see `app/FONT_LICENSE_OFL.txt`), BouncyCastle (MIT), OkHttp/ZXing/AndroidX/Kotlin
(Apache 2.0), BIP-39 wordlist (bitcoin/bips).
