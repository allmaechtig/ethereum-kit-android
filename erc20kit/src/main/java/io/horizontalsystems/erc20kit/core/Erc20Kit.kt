package io.horizontalsystems.erc20kit.core

import android.content.Context
import io.horizontalsystems.erc20kit.core.Erc20Kit.SyncState.*
import io.horizontalsystems.erc20kit.models.TokenError
import io.horizontalsystems.erc20kit.models.Transaction
import io.horizontalsystems.erc20kit.models.TransactionInfo
import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.core.hexStringToByteArray
import io.horizontalsystems.ethereumkit.models.ValidationError
import io.horizontalsystems.ethereumkit.network.EtherscanService
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import java.math.BigInteger

class Erc20Kit(
        private val ethereumKit: EthereumKit,
        private val transactionManager: ITransactionManager,
        private val balanceManager: IBalanceManager,
        private val state: KitState = KitState()
) : ITransactionManagerListener, IBalanceManagerListener {

    private val disposables = CompositeDisposable()

    sealed class SyncState {
        object Synced : SyncState()
        class NotSynced(val error: Throwable) : SyncState()
        object Syncing : SyncState()
    }

    init {
        onSyncStateUpdate(ethereumKit.syncState)
        state.balance = balanceManager.balance

        ethereumKit.syncStateFlowable
                .subscribe { syncState ->
                    onSyncStateUpdate(syncState)
                }.let {
                    disposables.add(it)
                }
    }

    private fun onSyncStateUpdate(syncState: EthereumKit.SyncState) {
        when (syncState) {
            is EthereumKit.SyncState.NotSynced -> state.syncState = NotSynced(syncState.error)
            is EthereumKit.SyncState.Syncing -> state.syncState = Syncing
            is EthereumKit.SyncState.Synced -> {
                state.syncState = Syncing
                balanceManager.sync()
            }
        }
    }

    val syncState: SyncState
        get() = state.syncState

    val transactionsSyncState: SyncState
        get() = state.transactionsSyncState

    val balance: BigInteger?
        get() = state.balance

    @Throws(TokenError::class)
    private fun convert(address: String): ByteArray {
        try {
            return address.hexStringToByteArray()
        } catch (e: Exception) {
            throw TokenError.InvalidAddress()
        }
    }

    @Throws(ValidationError::class)
    private fun convertValue(value: String): BigInteger {
        try {
            return value.toBigInteger()
        } catch (e: Exception) {
            throw ValidationError.InvalidValue
        }
    }

    fun refresh() {
        state.transactionsSyncState = Syncing
        transactionManager.sync()
    }

    fun estimateGas(toAddress: String?, contractAddress: String, value: BigInteger, gasPrice: Long?): Single<Long> {
        // without address - provide default gas limit
        if (toAddress == null) {
            return Single.just(ethereumKit.defaultGasLimit)
        }

        val transactionInput = transactionManager.getTransactionInput(convert(toAddress), value)
        return ethereumKit.estimateGas(contractAddress, null, gasPrice, transactionInput)
    }

    fun send(to: String, value: String, gasPrice: Long, gasLimit: Long): Single<TransactionInfo> {
        return transactionManager.send(convert(to), convertValue(value), gasPrice, gasLimit)
                .map { TransactionInfo(it) }
                .doOnSuccess { txInfo ->
                    state.transactionsSubject.onNext(listOf(txInfo))
                }
    }

    fun transactions(fromTransaction: TransactionKey?, limit: Int?): Single<List<TransactionInfo>> {
        return transactionManager.getTransactions(fromTransaction, limit)
                .map { transactions ->
                    transactions.map {
                        TransactionInfo(it)
                    }
                }
    }

    val syncStateFlowable: Flowable<SyncState>
        get() = state.syncStateSubject.toFlowable(BackpressureStrategy.LATEST)

    val transactionsSyncStateFlowable: Flowable<SyncState>
        get() = state.transactionsSyncStateSubject.toFlowable(BackpressureStrategy.LATEST)

    val balanceFlowable: Flowable<BigInteger>
        get() = state.balanceSubject.toFlowable(BackpressureStrategy.LATEST)

    val transactionsFlowable: Flowable<List<TransactionInfo>>
        get() = state.transactionsSubject.toFlowable(BackpressureStrategy.BUFFER)

    fun stop() {
        disposables.clear()
    }

    // ITransactionManagerListener

    override fun onSyncSuccess(transactions: List<Transaction>) {
        state.transactionsSyncState = Synced

        if (transactions.isNotEmpty())
            state.transactionsSubject.onNext(transactions.map { TransactionInfo(it) })
    }

    override fun onSyncTransactionsError(error: Throwable) {
        state.transactionsSyncState = NotSynced(error)
    }

    // IBalanceManagerListener

    override fun onSyncBalanceSuccess(balance: BigInteger) {
        state.balance = balance
        state.syncState = Synced
    }

    override fun onSyncBalanceError(error: Throwable) {
        state.syncState = NotSynced(error)
    }

    companion object {

        fun getInstance(context: Context,
                        ethereumKit: EthereumKit,
                        contractAddress: String): Erc20Kit {

            val contractAddressRaw = contractAddress.hexStringToByteArray()
            val address = ethereumKit.receiveAddressRaw

            val erc20KitDatabase = Erc20DatabaseManager.getErc20Database(context, ethereumKit.networkType, ethereumKit.walletId, contractAddress)
            val roomStorage = Erc20Storage(erc20KitDatabase)
            val transactionStorage: ITransactionStorage = roomStorage
            val balanceStorage: ITokenBalanceStorage = roomStorage

            val dataProvider: IDataProvider = DataProvider(ethereumKit)
            val transactionsProvider = EtherscanTransactionsProvider(EtherscanService(ethereumKit.networkType, ethereumKit.etherscanKey)) // TransactionsProvider(dataProvider)
            val transactionBuilder: ITransactionBuilder = TransactionBuilder()
            val transactionManager: ITransactionManager = TransactionManager(contractAddressRaw, address, transactionStorage, transactionsProvider, dataProvider, transactionBuilder)
            val balanceManager: IBalanceManager = BalanceManager(contractAddressRaw, address, balanceStorage, dataProvider)

            val erc20Kit = Erc20Kit(ethereumKit, transactionManager, balanceManager)

            transactionManager.listener = erc20Kit
            balanceManager.listener = erc20Kit

            return erc20Kit
        }

        fun clear(context: Context, networkType: EthereumKit.NetworkType, walletId: String) {
            Erc20DatabaseManager.clear(context, networkType, walletId)
        }
    }

}
