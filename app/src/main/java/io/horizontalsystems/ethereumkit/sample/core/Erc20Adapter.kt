package io.horizontalsystems.ethereumkit.sample.core

import android.content.Context
import io.horizontalsystems.erc20kit.core.Erc20Kit
import io.horizontalsystems.erc20kit.core.TransactionKey
import io.horizontalsystems.erc20kit.models.TransactionInfo
import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.core.hexStringToByteArray
import io.reactivex.Flowable
import io.reactivex.Single
import java.math.BigDecimal

class Erc20Adapter(
        context: Context,
        private val ethereumKit: EthereumKit,
        override val name: String,
        override val coin: String,
        private val contractAddress: String,
        private val decimal: Int)
    : IAdapter {

    private val erc20Kit = Erc20Kit.getInstance(context, ethereumKit, contractAddress)

    override val lastBlockHeight: Long?
        get() = ethereumKit.lastBlockHeight

    override val syncState: EthereumKit.SyncState
        get() = convertToEthereumKitSyncState(erc20Kit.syncState)

    override val transactionsSyncState: EthereumKit.SyncState
        get() = convertToEthereumKitSyncState(erc20Kit.transactionsSyncState)

    override val balance: BigDecimal
        get() = erc20Kit.balance?.toBigDecimal()?.movePointLeft(decimal) ?: BigDecimal.ZERO

    override val receiveAddress: String
        get() = ethereumKit.receiveAddress

    override val lastBlockHeightFlowable: Flowable<Unit>
        get() = ethereumKit.lastBlockHeightFlowable.map { Unit }

    override val syncStateFlowable: Flowable<Unit>
        get() = erc20Kit.syncStateFlowable.map { Unit }

    override val transactionsSyncStateFlowable: Flowable<Unit>
        get() = erc20Kit.transactionsSyncStateFlowable.map { Unit }

    override val balanceFlowable: Flowable<Unit>
        get() = erc20Kit.balanceFlowable.map { Unit }

    override val transactionsFlowable: Flowable<Unit>
        get() = erc20Kit.transactionsFlowable.map { Unit }

    override fun refresh() {
        erc20Kit.refresh()
    }

    override fun validateAddress(address: String) {
        EthereumKit.validateAddress(address)
    }

    override fun estimatedGasLimit(toAddress: String?, value: BigDecimal): Single<Long> {
        val poweredDecimal = value.scaleByPowerOfTen(decimal)
        val noScaleDecimal = poweredDecimal.setScale(0)

        return erc20Kit.estimateGas(toAddress = toAddress, contractAddress = contractAddress, value = noScaleDecimal.toBigInteger(), gasPrice = 5_000_000_000)
    }

    override fun send(address: String, amount: BigDecimal, gasLimit: Long): Single<Unit> {
        val poweredDecimal = amount.scaleByPowerOfTen(decimal)
        val noScaleDecimal = poweredDecimal.setScale(0)

        return erc20Kit.send(address, noScaleDecimal.toPlainString(), 5_000_000_000, gasLimit).map { Unit }
    }

    override fun transactions(from: Pair<String, Int>?, limit: Int?): Single<List<TransactionRecord>> {
        return erc20Kit.transactions(from?.let { TransactionKey(from.first.hexStringToByteArray(), from.second) }, limit)
                .map { transactions ->
                    transactions.map { transactionRecord(it) }
                }
    }

    private fun transactionRecord(transaction: TransactionInfo): TransactionRecord {
        val mineAddress = ethereumKit.receiveAddress

        val from = TransactionAddress(transaction.from, transaction.from == mineAddress)
        val to = TransactionAddress(transaction.to, transaction.to == mineAddress)

        var amount: BigDecimal

        transaction.value.toBigDecimal().let {
            amount = it.movePointLeft(decimal)
            if (from.mine) {
                amount = -amount
            }
        }

        return TransactionRecord(
                transactionHash = transaction.transactionHash,
                transactionIndex = transaction.transactionIndex ?: 0,
                interTransactionIndex = transaction.interTransactionIndex,
                amount = amount,
                timestamp = transaction.timestamp,
                from = from,
                to = to,
                blockHeight = transaction.blockNumber,
                isError = transaction.isError

        )
    }

    private fun convertToEthereumKitSyncState(syncState: Erc20Kit.SyncState): EthereumKit.SyncState {
        return when (syncState) {
            Erc20Kit.SyncState.Synced -> EthereumKit.SyncState.Synced()
            Erc20Kit.SyncState.Syncing -> EthereumKit.SyncState.Syncing()
            is Erc20Kit.SyncState.NotSynced -> EthereumKit.SyncState.NotSynced(syncState.error)
        }
    }

}
