package app.spectra.wallet.domain

import app.spectra.core.chain.AccountView
import app.spectra.core.model.AssetId
import app.spectra.core.model.AssetKind
import app.spectra.core.model.DerivationScheme
import app.spectra.core.model.Network
import app.spectra.core.model.TokenInfo
import app.spectra.core.model.TokenSupport
import app.spectra.core.rpc.EndpointKind
import app.spectra.core.rpc.EndpointRole
import app.spectra.core.rpc.RpcEndpoint
import app.spectra.wallet.data.ChainAddressEntity
import app.spectra.wallet.data.RpcEndpointEntity
import app.spectra.wallet.data.TokenEntity

fun ChainAddressEntity.toView(index: Int) = AccountView(Network.fromId(networkId), DerivationScheme.valueOf(scheme), index, address, accountXpub)

fun TokenEntity.toInfo() = TokenInfo(
    AssetId(Network.fromId(networkId), AssetKind.valueOf(kind), address), symbol, name, decimals, null, coingeckoId,
    verified, possibleSpam, runCatching { TokenSupport.valueOf(support) }.getOrDefault(TokenSupport.SUPPORTED), supportNote,
)

fun TokenInfo.toEntity(hidden: Boolean, userAdded: Boolean) = TokenEntity(
    id.key, id.network.id, id.kind.name, id.address, symbol, name, decimals, coingeckoId, verified, possibleSpam, hidden, userAdded, support.name, supportNote,
)

fun RpcEndpointEntity.toEndpoint() = RpcEndpoint(id, Network.fromId(networkId), url, EndpointRole.valueOf(role), EndpointKind.valueOf(kind), enabled)

object DefaultEndpoints {
    private fun e(id: String, net: String, url: String, role: EndpointRole, kind: EndpointKind = EndpointKind.NODE) =
        RpcEndpointEntity(id, net, url, role.name, kind.name, true, builtIn = true)

    val all = listOf(
        e("sol-main-1", "solana:mainnet", "https://api.mainnet-beta.solana.com", EndpointRole.PRIMARY),
        e("sol-main-2", "solana:mainnet", "https://solana-rpc.publicnode.com", EndpointRole.FALLBACK),
        e("sol-dev-1", "solana:testnet", "https://api.devnet.solana.com", EndpointRole.PRIMARY),
        e("eth-main-1", "ethereum:mainnet", "https://ethereum-rpc.publicnode.com", EndpointRole.PRIMARY),
        e("eth-main-2", "ethereum:mainnet", "https://eth.drpc.org", EndpointRole.FALLBACK),
        e("eth-sep-1", "ethereum:testnet", "https://ethereum-sepolia-rpc.publicnode.com", EndpointRole.PRIMARY),
        e("eth-sep-2", "ethereum:testnet", "https://sepolia.gateway.tenderly.co", EndpointRole.FALLBACK),
        e("eth-main-idx", "ethereum:mainnet", "https://eth.blockscout.com/api/v2", EndpointRole.PRIMARY, EndpointKind.INDEXER),
        e("eth-sep-idx", "ethereum:testnet", "https://eth-sepolia.blockscout.com/api/v2", EndpointRole.PRIMARY, EndpointKind.INDEXER),
        e("ltc-main-1", "litecoin:mainnet", "https://litecoinspace.org/api", EndpointRole.PRIMARY),
        e("ltc-test-1", "litecoin:testnet", "https://litecoinspace.org/testnet/api", EndpointRole.PRIMARY),
    )
}
