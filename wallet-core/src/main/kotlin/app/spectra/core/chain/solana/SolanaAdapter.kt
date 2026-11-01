package app.spectra.core.chain.solana

import app.spectra.core.chain.AccountView
import app.spectra.core.chain.AddressCheck
import app.spectra.core.chain.AddressValidator
import app.spectra.core.chain.AddressWarning
import app.spectra.core.chain.BroadcastOutcome
import app.spectra.core.chain.ChainAdapter
import app.spectra.core.chain.ChainPayload
import app.spectra.core.chain.FeeLevel
import app.spectra.core.chain.PreparedTransaction
import app.spectra.core.chain.RawEncoding
import app.spectra.core.chain.SignedTransaction
import app.spectra.core.chain.TrackedTransaction
import app.spectra.core.chain.TransferRequest
import app.spectra.core.chain.TxWarning
import app.spectra.core.crypto.SecureMemory
import app.spectra.core.model.Amount
import app.spectra.core.model.AssetBalance
import app.spectra.core.model.AssetId
import app.spectra.core.model.AssetKind
import app.spectra.core.model.HistoryItem
import app.spectra.core.model.Network
import app.spectra.core.model.StatusObservation
import app.spectra.core.model.TokenInfo
import app.spectra.core.model.TokenSupport
import app.spectra.core.model.TxDirection
import app.spectra.core.model.TxState
import app.spectra.core.model.TxType
import app.spectra.core.model.WalletError
import app.spectra.core.rpc.JsonRpcException
import app.spectra.core.rpc.arr
import app.spectra.core.rpc.bool
import app.spectra.core.rpc.get
import app.spectra.core.rpc.long
import app.spectra.core.rpc.str
import app.spectra.core.wallet.Keyring
import kotlinx.serialization.json.JsonElement
import java.math.BigInteger
import java.util.Base64

data class PreparedSolanaMessage(val messageBytes: ByteArray, val fee: Amount, val lastValidBlockHeight: Long, val expiresAtMillis: Long, val feeDetail: String)

/** Optional off-chain token metadata source (e.g. Jupiter token API). Returned data is untrusted. */
fun interface SolanaTokenDirectory {
    suspend fun lookup(network: Network, mints: List<String>): Map<String, TokenInfo>
}

class SolanaAdapter(
    private val rpc: SolanaRpc,
    private val directory: SolanaTokenDirectory?,
    private val clock: () -> Long = System::currentTimeMillis,
) : ChainAdapter {

    companion object {
        const val BASE_FEE_LAMPORTS_PER_SIGNATURE = 5_000L
        const val TOKEN_ACCOUNT_SIZE = 165L
        /** ATA size for Token-2022 mints whose extensions Spectra supports (base + account type + ImmutableOwner). */
        const val TOKEN_2022_ACCOUNT_SIZE = 170L
        const val BLOCKHASH_VALIDITY_MS = 60_000L
        const val MAX_PRIORITY_MICROLAMPORTS = 2_000_000L

        /** Token-2022 mint extensions that do not change transfer semantics for a plain TransferChecked. */
        val SAFE_EXTENSIONS = setOf("metadataPointer", "tokenMetadata", "groupPointer", "groupMemberPointer", "tokenGroup",
            "tokenGroupMember", "mintCloseAuthority", "interestBearingConfig", "confidentialTransferMint")
        val WARN_EXTENSIONS = setOf("permanentDelegate", "pausableConfig", "defaultAccountState")
    }

    // ---------- Balances & metadata ----------

    override suspend fun balances(account: AccountView, knownTokens: List<TokenInfo>): List<AssetBalance> {
        val n = account.network
        val owner = account.primaryAddress
        val lamports = rpc.getBalance(n, owner)
        val out = mutableListOf(AssetBalance(TokenInfo.native(n), Amount(BigInteger.valueOf(lamports), 9), owner))
        val tokenAccounts = rpc.tokenAccounts(n, owner, SolanaPrograms.TOKEN) + rpc.tokenAccounts(n, owner, SolanaPrograms.TOKEN_2022)
        val byMint = tokenAccounts.groupBy { it.mint }
        val known = knownTokens.associateBy { it.id.address }
        val unknownMints = byMint.keys.filter { it !in known }
        val looked = if (unknownMints.isEmpty()) emptyMap() else runCatching { directory?.lookup(n, unknownMints) ?: emptyMap() }.getOrDefault(emptyMap())
        for ((mint, accounts) in byMint) {
            val total = accounts.sumOf { BigInteger.valueOf(it.amount) }
            val largest = accounts.maxBy { it.amount }
            val kind = if (largest.program == SolanaPrograms.TOKEN_2022) AssetKind.SPL_TOKEN_2022 else AssetKind.SPL_TOKEN
            val info = known[mint] ?: looked[mint]?.copy(id = AssetId(n, kind, mint), decimals = largest.decimals)
                ?: TokenInfo(AssetId(n, kind, mint), mint.take(4) + "…", "Unknown token ${mint.take(4)}…${mint.takeLast(4)}", largest.decimals, possibleSpam = false)
            out.add(AssetBalance(info, Amount(total, largest.decimals), largest.address))
        }
        return out
    }

    override suspend fun tokenInfo(network: Network, address: String): TokenInfo {
        val check = AddressValidator.validate(network, address)
        if (check !is AddressCheck.Valid) throw WalletError.InvalidAddress("mint")
        val acc = rpc.accountInfo(network, address) ?: throw WalletError.UnsupportedToken("Mint account not found on ${network.displayName}")
        val program = acc["owner"].str
        val parsed = acc["data"]["parsed"]
        if (parsed["type"].str != "mint") throw WalletError.UnsupportedToken("Address is not a token mint")
        val kind = when (program) {
            SolanaPrograms.TOKEN.base58 -> AssetKind.SPL_TOKEN
            SolanaPrograms.TOKEN_2022.base58 -> AssetKind.SPL_TOKEN_2022
            else -> throw WalletError.UnsupportedToken("Unknown token program")
        }
        val info = parsed["info"]
        val decimals = info["decimals"].long?.toInt() ?: throw WalletError.UnknownDecimals(address)
        val extensions = info["extensions"].arr?.mapNotNull { it["extension"].str } ?: emptyList()
        val metaExt = info["extensions"].arr?.firstOrNull { it["extension"].str == "tokenMetadata" }?.get("state")
        val (support, note) = evaluateExtensions(extensions)
        val dir = runCatching { directory?.lookup(network, listOf(address))?.get(address) }.getOrNull()
        val symbol = TokenInfo.sanitize(dir?.symbol ?: metaExt["symbol"].str ?: address.take(4), 12)
        val name = TokenInfo.sanitize(dir?.name ?: metaExt["name"].str ?: "Token ${address.take(4)}…", 40)
        return TokenInfo(AssetId(network, kind, address), symbol, name, decimals, dir?.logoUrl, dir?.coingeckoId,
            verified = dir?.verified == true, possibleSpam = TokenInfo.looksLikeSpam(symbol, name), support = support, supportNote = note)
    }

    fun evaluateExtensions(extensions: List<String>): Pair<TokenSupport, String?> {
        val unsupported = extensions.filter { it !in SAFE_EXTENSIONS && it !in WARN_EXTENSIONS }
        val warn = extensions.filter { it in WARN_EXTENSIONS }
        return when {
            unsupported.isNotEmpty() -> TokenSupport.UNSUPPORTED to "Token-2022 extensions not supported for sending: ${unsupported.joinToString()}"
            warn.isNotEmpty() -> TokenSupport.WARN to "Token-2022 extensions: ${warn.joinToString()}"
            else -> TokenSupport.SUPPORTED to null
        }
    }

    // ---------- History ----------

    override suspend fun history(account: AccountView, limit: Int): List<HistoryItem> {
        val n = account.network
        val me = account.primaryAddress
        val sigs = rpc.signatures(n, me, limit)
        return sigs.mapIndexedNotNull { i, s ->
            val sig = s["signature"].str ?: return@mapIndexedNotNull null
            val failed = s["err"] != null && s["err"].toString() != "null"
            val state = when {
                failed -> TxState.FAILED
                s["confirmationStatus"].str == "finalized" -> TxState.FINALIZED
                s["confirmationStatus"].str == "confirmed" -> TxState.CONFIRMED
                else -> TxState.PENDING
            }
            val detail = if (i < 15) runCatching { rpc.transaction(n, sig) }.getOrNull() else null
            val parsed = detail?.let { parseHistory(n, me, it) }
            HistoryItem(n, sig, s["blockTime"].long, parsed?.type ?: TxType.UNKNOWN, parsed?.direction ?: TxDirection.SELF,
                parsed?.asset, parsed?.symbol, parsed?.amount, parsed?.counterparty, state, parsed?.fee)
        }
    }

    private data class Parsed(val type: TxType, val direction: TxDirection, val asset: AssetId?, val symbol: String?, val amount: Amount?, val counterparty: String?, val fee: Amount?)

    private fun parseHistory(n: Network, me: String, tx: JsonElement): Parsed? {
        val meta = tx["meta"] ?: return null
        val keys = tx["transaction"]["message"]["accountKeys"].arr?.map { it["pubkey"].str ?: it.str } ?: return null
        val idx = keys.indexOf(me)
        val fee = meta["fee"].long?.let { Amount.of(it, 9) }
        val feePayer = keys.firstOrNull() == me
        val programs = tx["transaction"]["message"]["instructions"].arr?.mapNotNull { it["programId"].str } ?: emptyList()
        if (programs.contains(SolanaPrograms.STAKE.base58)) {
            return Parsed(TxType.STAKE_DELEGATE, TxDirection.OUT, AssetId.native(n), "SOL", null, null, fee)
        }
        // Token deltas owned by me.
        val pre = meta["preTokenBalances"].arr.orEmpty().filter { it["owner"].str == me }
        val post = meta["postTokenBalances"].arr.orEmpty().filter { it["owner"].str == me }
        val mints = (pre + post).mapNotNull { it["mint"].str }.distinct()
        if (mints.size == 1) {
            val mint = mints.first()
            fun amt(list: List<JsonElement>) = list.filter { it["mint"].str == mint }.sumOf { BigInteger(it["uiTokenAmount"]["amount"].str ?: "0") }
            val dec = (pre + post).first { it["mint"].str == mint }["uiTokenAmount"]["decimals"].long?.toInt() ?: 0
            val delta = amt(post) - amt(pre)
            if (delta.signum() != 0) {
                return Parsed(TxType.TOKEN_TRANSFER, if (delta.signum() > 0) TxDirection.IN else TxDirection.OUT,
                    AssetId(n, AssetKind.SPL_TOKEN, mint), null, Amount(delta.abs(), dec), null, if (feePayer) fee else null)
            }
        }
        if (mints.size > 1) return Parsed(TxType.SWAP, TxDirection.SELF, null, null, null, null, if (feePayer) fee else null)
        if (idx < 0) return null
        val preB = meta["preBalances"].arr?.getOrNull(idx).long ?: return null
        val postB = meta["postBalances"].arr?.getOrNull(idx).long ?: return null
        var delta = postB - preB
        if (feePayer) delta += meta["fee"].long ?: 0
        if (delta == 0L) return Parsed(TxType.CONTRACT_CALL, TxDirection.SELF, null, null, null, null, if (feePayer) fee else null)
        val counterparty = keys.withIndex().firstOrNull { (i, k) -> i != idx && k != null && (meta["postBalances"].arr?.getOrNull(i).long ?: 0) - (meta["preBalances"].arr?.getOrNull(i).long ?: 0) == -delta }?.value
        return Parsed(if (delta > 0) TxType.RECEIVE else TxType.TRANSFER, if (delta > 0) TxDirection.IN else TxDirection.OUT,
            AssetId.native(n), "SOL", Amount.of(kotlin.math.abs(delta), 9), counterparty, if (feePayer) fee else null)
    }

    // ---------- Transfer preparation ----------

    private suspend fun priorityFee(n: Network, accounts: List<String>, level: FeeLevel): Long {
        val fees = runCatching { rpc.recentPriorityFees(n, accounts) }.getOrDefault(emptyList()).filter { it > 0 }.sorted()
        if (fees.isEmpty()) return 0
        val q = when (level) { FeeLevel.SLOW -> 0.25; FeeLevel.NORMAL -> 0.5; FeeLevel.FAST -> 0.75 }
        return fees[((fees.size - 1) * q).toInt()].coerceAtMost(MAX_PRIORITY_MICROLAMPORTS)
    }

    private fun unsignedWire(message: LegacyMessage): String {
        val msg = message.serialize()
        return Base64.getEncoder().encodeToString(SolWriter().shortVec(message.numRequiredSignatures).bytes(ByteArray(64 * message.numRequiredSignatures)).bytes(msg).toByteArray())
    }

    /**
     * Builds the final instruction list: compute budget (limit from simulation) + price + [core].
     * Returns the message, CU limit and price.
     */
    private data class Budget(val probe: LegacyMessage, val limit: Int, val price: Long, val lastValidBlockHeight: Long)

    private suspend fun buildWithBudget(n: Network, payer: PublicKey, core: (Long) -> List<Instruction>, probeAmount: Long, level: FeeLevel, writable: List<String>): Budget {
        val price = priorityFee(n, writable, level)
        val (blockhash, lvbh) = rpc.latestBlockhash(n)
        val probe = LegacyMessage.compile(payer, listOf(ComputeBudgetInstructions.setComputeUnitLimit(200_000), ComputeBudgetInstructions.setComputeUnitPrice(price)) + core(probeAmount), blockhash)
        val sim = rpc.simulate(n, unsignedWire(probe))
        if (sim.error != null) throw WalletError.SimulationFailed(describeSimulationError(sim.error, sim.logs))
        val units = sim.unitsConsumed ?: 200_000
        val limit = ((units * 1.2).toLong() + 300).coerceIn(1_000, 1_400_000).toInt()
        return Budget(probe, limit, price, lvbh)
    }

    private fun describeSimulationError(err: String, logs: List<String>): String {
        val tail = logs.lastOrNull { it.contains("Error", ignoreCase = true) || it.contains("failed") }
        return when {
            err.contains("InsufficientFundsForRent") -> "Account would fall below the rent-exempt minimum"
            err.contains("InsufficientFunds") || logs.any { it.contains("insufficient", true) } -> "Insufficient funds"
            err.contains("AccountNotFound") -> "Sender account does not exist on this network (no SOL for fees)"
            err.contains("BlockhashNotFound") -> "Blockhash expired"
            logs.any { it.contains("frozen", true) } -> "Token account is frozen"
            else -> (tail ?: err).take(200)
        }
    }

    override suspend fun prepareTransfer(request: TransferRequest): PreparedTransaction {
        val n = request.account.network
        val check = AddressValidator.validate(n, request.recipient)
        if (check !is AddressCheck.Valid) throw WalletError.InvalidAddress(check.toString())
        val from = PublicKey.of(request.account.primaryAddress)
        val to = PublicKey.of(check.normalized)
        val warnings = mutableListOf<TxWarning>()
        if (check.warning == AddressWarning.OFF_CURVE_PROGRAM_ADDRESS) warnings += TxWarning.OFF_CURVE_RECIPIENT
        if (to == from) warnings += TxWarning.RECIPIENT_IS_SELF
        val balance = rpc.getBalance(n, from.base58)
        return if (request.token.id.isNative) prepareSol(request, from, to, balance, warnings) else prepareToken(request, from, to, balance, warnings)
    }

    private suspend fun prepareSol(r: TransferRequest, from: PublicKey, to: PublicKey, balance: Long, warnings: MutableList<TxWarning>): PreparedTransaction {
        val n = r.account.network
        val rentMin0 = rpc.rentExemptMinimum(n, 0)
        val toBalance = if (to == from) balance else rpc.getBalance(n, to.base58)
        if (toBalance == 0L) warnings += TxWarning.RECIPIENT_NEW_ACCOUNT
        val (probe, limit, price, lvbh) = buildWithBudget(n, from, { amt -> listOf(SystemInstructions.transfer(from, to, amt)) },
            if (r.sendMax) maxOf(rentMin0, 1) else r.amount.units.toLong(), r.feeLevel, listOf(from.base58, to.base58))
        val priority = (BigInteger.valueOf(price) * BigInteger.valueOf(limit.toLong()) + BigInteger.valueOf(999_999)) / BigInteger.valueOf(1_000_000)
        val fee = BASE_FEE_LAMPORTS_PER_SIGNATURE + priority.toLong()
        val amount = if (r.sendMax) balance - fee else r.amount.units.toLong()
        if (amount <= 0 || amount + fee > balance) throw WalletError.InsufficientBalance(Amount.of(maxOf(amount, 0) + fee, 9), Amount.of(balance, 9))
        val remaining = balance - amount - fee
        if (remaining in 1 until rentMin0) throw WalletError.BelowRentExemption(Amount.of(rentMin0, 9))
        if (toBalance == 0L && to != from && amount < rentMin0) throw WalletError.BelowRentExemption(Amount.of(rentMin0, 9))
        val msg = LegacyMessage.compile(from, listOf(ComputeBudgetInstructions.setComputeUnitLimit(limit), ComputeBudgetInstructions.setComputeUnitPrice(price),
            SystemInstructions.transfer(from, to, amount)), probe.recentBlockhash)
        return PreparedTransaction(r, TxType.TRANSFER, Amount.of(amount, 9), Amount.of(fee, 9), Amount.zero(9), warnings,
            clock() + BLOCKHASH_VALIDITY_MS, "Base ${BASE_FEE_LAMPORTS_PER_SIGNATURE} + priority $priority lamports ($price µL × $limit CU)",
            ChainPayload.Solana(msg.serialize(), lvbh, probe.recentBlockhash))
    }

    private suspend fun prepareToken(r: TransferRequest, from: PublicKey, to: PublicKey, balance: Long, warnings: MutableList<TxWarning>): PreparedTransaction {
        val n = r.account.network
        val token = r.token
        val mint = PublicKey.of(token.id.address ?: throw WalletError.UnsupportedToken("missing mint"))
        val decimals = token.decimals ?: throw WalletError.UnknownDecimals(mint.base58)
        val live = tokenInfo(n, mint.base58)
        if (live.decimals != decimals) throw WalletError.UnknownDecimals(mint.base58)
        if (live.support == TokenSupport.UNSUPPORTED) throw WalletError.UnsupportedToken(live.supportNote ?: "unsupported")
        if (live.support == TokenSupport.WARN) warnings += TxWarning.TOKEN_EXTENSION_WARNING
        val program = if (live.id.kind == AssetKind.SPL_TOKEN_2022) SolanaPrograms.TOKEN_2022 else SolanaPrograms.TOKEN
        val sources = rpc.tokenAccounts(n, from.base58, program, mint.base58).filter { !it.frozen }
        val source = sources.maxByOrNull { it.amount } ?: throw WalletError.InsufficientBalance(null, Amount.zero(decimals))
        val amount = if (r.sendMax) source.amount else r.amount.units.toLong()
        if (amount <= 0 || amount > source.amount) {
            val total = sources.sumOf { it.amount }
            throw if (amount <= total && sources.size > 1) WalletError.UnsupportedToken("Funds are split across several token accounts; v0.1 sends from one account at a time")
            else WalletError.InsufficientBalance(Amount.of(amount, decimals), Amount.of(source.amount, decimals))
        }
        // Destination: a token account for this mint pasted directly, or the owner's associated token account.
        val recipientInfo = rpc.accountInfo(n, to.base58)
        val recipientIsTokenAccount = recipientInfo?.get("data")?.get("parsed")?.get("type")?.str == "account" &&
            recipientInfo["data"]["parsed"]["info"]["mint"].str == mint.base58
        val destination: PublicKey
        val ixPrefix = mutableListOf<Instruction>()
        var rent = 0L
        if (recipientIsTokenAccount) {
            destination = to
        } else {
            if (recipientInfo != null && SolanaPrograms.isTokenProgram(PublicKey.of(recipientInfo["owner"].str ?: SolanaPrograms.SYSTEM.base58)))
                throw WalletError.InvalidAddress("Recipient is a token account for a different mint")
            destination = SolanaPrograms.associatedTokenAddress(to, mint, program)
            if (rpc.accountInfo(n, destination.base58) == null) {
                rent = rpc.rentExemptMinimum(n, if (program == SolanaPrograms.TOKEN_2022) TOKEN_2022_ACCOUNT_SIZE else TOKEN_ACCOUNT_SIZE)
                ixPrefix += TokenInstructions.createAssociatedTokenAccountIdempotent(from, destination, to, mint, program)
                warnings += TxWarning.CREATES_TOKEN_ACCOUNT
            }
        }
        val core = { amt: Long -> ixPrefix + TokenInstructions.transferChecked(program, PublicKey.of(source.address), mint, destination, from, amt, decimals) }
        val (probe, limit, price, lvbh) = buildWithBudget(n, from, core, amount, r.feeLevel, listOf(from.base58, source.address, destination.base58))
        val priority = (BigInteger.valueOf(price) * BigInteger.valueOf(limit.toLong()) + BigInteger.valueOf(999_999)) / BigInteger.valueOf(1_000_000)
        val fee = BASE_FEE_LAMPORTS_PER_SIGNATURE + priority.toLong()
        if (fee + rent > balance) throw WalletError.InsufficientFeeBalance(Amount.of(fee + rent, 9), Amount.of(balance, 9))
        val rentMin0 = rpc.rentExemptMinimum(n, 0)
        if (balance - fee - rent in 1 until rentMin0) throw WalletError.BelowRentExemption(Amount.of(rentMin0, 9))
        val msg = LegacyMessage.compile(from, listOf(ComputeBudgetInstructions.setComputeUnitLimit(limit), ComputeBudgetInstructions.setComputeUnitPrice(price)) + core(amount), probe.recentBlockhash)
        return PreparedTransaction(r, TxType.TOKEN_TRANSFER, Amount.of(amount, decimals), Amount.of(fee, 9), Amount.of(rent, 9), warnings,
            clock() + BLOCKHASH_VALIDITY_MS, "Base ${BASE_FEE_LAMPORTS_PER_SIGNATURE} + priority $priority lamports" + if (rent > 0) "; token account rent $rent lamports" else "",
            ChainPayload.Solana(msg.serialize(), lvbh, probe.recentBlockhash))
    }

    /** Prepares an arbitrary instruction list (e.g. staking) with simulated compute budget and priority fee. */
    suspend fun prepareCustom(n: Network, payer: PublicKey, instructions: List<Instruction>, level: FeeLevel, writable: List<String>): PreparedSolanaMessage {
        val (probe, limit, price, lvbh) = buildWithBudget(n, payer, { instructions }, 0, level, writable)
        val priority = (BigInteger.valueOf(price) * BigInteger.valueOf(limit.toLong()) + BigInteger.valueOf(999_999)) / BigInteger.valueOf(1_000_000)
        val fee = BASE_FEE_LAMPORTS_PER_SIGNATURE + priority.toLong()
        val msg = LegacyMessage.compile(payer, listOf(ComputeBudgetInstructions.setComputeUnitLimit(limit), ComputeBudgetInstructions.setComputeUnitPrice(price)) + instructions, probe.recentBlockhash)
        return PreparedSolanaMessage(msg.serialize(), Amount.of(fee, 9), lvbh, clock() + BLOCKHASH_VALIDITY_MS, "Base $BASE_FEE_LAMPORTS_PER_SIGNATURE + priority $priority lamports")
    }

    // ---------- Signing, broadcast, status ----------

    override fun sign(prepared: PreparedTransaction, keyring: Keyring): SignedTransaction {
        val p = prepared.payload as ChainPayload.Solana
        if (prepared.expiresAtMillis != null && clock() > prepared.expiresAtMillis) throw WalletError.BlockhashExpired()
        return signMessage(prepared.request.account, p.messageBytes, p.lastValidBlockHeight, keyring)
    }

    fun signMessage(account: AccountView, messageBytes: ByteArray, lastValidBlockHeight: Long?, keyring: Keyring): SignedTransaction {
        val seed = keyring.solanaPrivateKey(account.scheme, account.accountIndex)
        try {
            val unsigned = SolWriter().shortVec(1).bytes(ByteArray(64)).bytes(messageBytes).toByteArray()
            val (_, signed) = SolanaTransactions.signExternal(unsigned, seed)
            return SignedTransaction(account.network, signed.signature, Base64.getEncoder().encodeToString(signed.wire), RawEncoding.BASE64, lastValidBlockHeight)
        } finally { SecureMemory.wipe(seed) }
    }

    /** Signs a provider-built transaction (swap). The wallet must be the fee payer; see [SolanaTransactions.signExternal]. */
    fun signExternal(account: AccountView, wireBase64: String, lastValidBlockHeight: Long?, keyring: Keyring): Pair<ParsedSolanaTransaction, SignedTransaction> {
        val seed = keyring.solanaPrivateKey(account.scheme, account.accountIndex)
        try {
            val (parsed, signed) = SolanaTransactions.signExternal(Base64.getDecoder().decode(wireBase64), seed)
            return parsed to SignedTransaction(account.network, signed.signature, Base64.getEncoder().encodeToString(signed.wire), RawEncoding.BASE64, lastValidBlockHeight)
        } finally { SecureMemory.wipe(seed) }
    }

    override suspend fun broadcast(signed: SignedTransaction): BroadcastOutcome = try {
        broadcastInner(signed)
    } catch (e: JsonRpcException) {
        val msg = e.message + (e.data ?: "")
        when {
            msg.contains("already been processed", true) -> BroadcastOutcome.Accepted(signed.txId)
            msg.contains("Blockhash not found", true) -> BroadcastOutcome.Rejected(WalletError.BlockhashExpired())
            msg.contains("insufficient", true) -> BroadcastOutcome.Rejected(WalletError.InsufficientBalance(null, null))
            else -> BroadcastOutcome.Rejected(WalletError.Rejected(e.message.take(200)))
        }
    } catch (e: WalletError.RateLimited) {
        BroadcastOutcome.Rejected(e)
    } catch (e: WalletError.NoInternet) {
        BroadcastOutcome.Rejected(e)
    } catch (e: Exception) {
        BroadcastOutcome.Unknown(signed.txId, e.message ?: e.javaClass.simpleName)
    }

    private suspend fun broadcastInner(signed: SignedTransaction): BroadcastOutcome {
        val sig = rpc.sendTransaction(signed.network, signed.rawHex)
        return if (sig != signed.txId) BroadcastOutcome.Unknown(signed.txId, "RPC returned a different signature") else BroadcastOutcome.Accepted(sig)
    }

    override suspend fun status(tracked: TrackedTransaction): StatusObservation {
        val n = tracked.network
        val st = rpc.signatureStatus(n, tracked.txId)
        if (st == null) {
            val lvbh = tracked.lastValidBlockHeight
            if (lvbh != null && rpc.blockHeight(n) > lvbh) {
                // Expired: re-check once more to avoid a race with late inclusion.
                return if (rpc.signatureStatus(n, tracked.txId) == null) StatusObservation(TxState.EXPIRED, error = "Blockhash expired; the transaction was not included")
                else status(tracked)
            }
            return StatusObservation(TxState.PENDING)
        }
        if (st.err != null) return StatusObservation(TxState.FAILED, blockRef = st.slot?.toString(), error = st.err.take(200))
        return when (st.confirmationStatus) {
            "finalized" -> StatusObservation(TxState.FINALIZED, blockRef = st.slot?.toString())
            "confirmed" -> StatusObservation(TxState.CONFIRMED, blockRef = st.slot?.toString())
            else -> StatusObservation(TxState.PENDING, blockRef = st.slot?.toString())
        }
    }
}

private fun <T> List<T>?.orEmpty(): List<T> = this ?: emptyList()
