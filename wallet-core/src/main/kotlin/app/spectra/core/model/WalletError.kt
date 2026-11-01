package app.spectra.core.model

/** Normalised, user-presentable error categories. Messages never include secrets. */
sealed class WalletError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NoInternet(cause: Throwable? = null) : WalletError("No internet connection", cause)
    class RpcUnavailable(val endpoint: String, detail: String, cause: Throwable? = null) : WalletError("RPC unavailable: $detail", cause)
    class RateLimited(val retryAfterSeconds: Long?) : WalletError("Rate limited by provider")
    class Timeout(val operation: String) : WalletError("Timed out: $operation")
    class WrongNetwork(val expected: String, val actual: String) : WalletError("Endpoint serves $actual, expected $expected")
    class InvalidAddress(val reason: String) : WalletError("Invalid address: $reason")
    class InsufficientBalance(val required: Amount?, val available: Amount?) : WalletError("Insufficient balance")
    class InsufficientFeeBalance(val requiredFee: Amount, val available: Amount) : WalletError("Insufficient native balance for network fee")
    class BelowRentExemption(val minimum: Amount) : WalletError("Amount would leave an account below the rent-exempt minimum")
    class BlockhashExpired : WalletError("Solana blockhash expired before confirmation")
    class NonceConflict(val nonce: Long) : WalletError("Ethereum nonce $nonce already used")
    class UtxoConflict : WalletError("Inputs already spent or missing")
    class DustAmount(val minimum: Amount) : WalletError("Amount below dust threshold")
    class UnknownDecimals(val asset: String) : WalletError("Token decimals unknown")
    class UnsupportedToken(val reason: String) : WalletError("Unsupported token behaviour: $reason")
    class SimulationFailed(val detail: String) : WalletError("Transaction simulation failed: $detail")
    class Rejected(val detail: String) : WalletError("Rejected by network: $detail")
    class QuoteExpired : WalletError("Quote expired")
    class RouteUnavailable(val reason: String) : WalletError("Route unavailable: $reason")
    class ProviderError(val provider: String, detail: String) : WalletError("$provider: $detail")
    class FeatureUnavailable(val reason: String) : WalletError(reason)
    class Unexpected(detail: String, cause: Throwable? = null) : WalletError(detail, cause)
}
