/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.core.btc.wallet;

import bisq.core.app.BisqEnvironment;
import bisq.core.btc.Restrictions;
import bisq.core.btc.exceptions.TransactionVerificationException;
import bisq.core.btc.exceptions.WalletException;
import bisq.core.dao.blockchain.BsqBlockChain;
import bisq.core.dao.blockchain.ReadableBsqBlockChain;
import bisq.core.dao.blockchain.vo.BsqBlock;
import bisq.core.dao.blockchain.vo.Tx;
import bisq.core.dao.blockchain.vo.TxOutput;
import bisq.core.provider.fee.FeeService;
import bisq.core.user.Preferences;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.ScriptException;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.script.Script;
import org.bitcoinj.wallet.CoinSelection;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.listeners.AbstractWalletEventListener;

import javax.inject.Inject;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.bitcoinj.core.TransactionConfidence.ConfidenceType.BUILDING;
import static org.bitcoinj.core.TransactionConfidence.ConfidenceType.PENDING;

@Slf4j
public class BsqWalletService extends WalletService implements BsqBlockChain.Listener {
    private final BsqCoinSelector bsqCoinSelector;
    private final ReadableBsqBlockChain readableBsqBlockChain;
    private final ObservableList<Transaction> walletTransactions = FXCollections.observableArrayList();
    private final CopyOnWriteArraySet<BsqBalanceListener> bsqBalanceListeners = new CopyOnWriteArraySet<>();

    private Coin availableBalance = Coin.ZERO;
    @Getter
    private Coin pendingBalance = Coin.ZERO;
    @Getter
    private Coin lockedInBondsBalance = Coin.ZERO;
    @Getter
    private Coin lockedForVotingBalance = Coin.ZERO;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public BsqWalletService(WalletsSetup walletsSetup,
                            BsqCoinSelector bsqCoinSelector,
                            ReadableBsqBlockChain readableBsqBlockChain,
                            Preferences preferences,
                            FeeService feeService) {
        super(walletsSetup,
                preferences,
                feeService);

        this.bsqCoinSelector = bsqCoinSelector;
        this.readableBsqBlockChain = readableBsqBlockChain;

        if (BisqEnvironment.isBaseCurrencySupportingBsq()) {
            walletsSetup.addSetupCompletedHandler(() -> {
                wallet = walletsSetup.getBsqWallet();
                if (wallet != null) {
                    wallet.setCoinSelector(bsqCoinSelector);
                    wallet.addEventListener(walletEventListener);

                    //noinspection deprecation
                    wallet.addEventListener(new AbstractWalletEventListener() {
                        @Override
                        public void onCoinsReceived(Wallet wallet, Transaction tx, Coin prevBalance, Coin newBalance) {
                            updateBsqWalletTransactions();
                        }

                        @Override
                        public void onCoinsSent(Wallet wallet, Transaction tx, Coin prevBalance, Coin newBalance) {
                            updateBsqWalletTransactions();
                        }

                        @Override
                        public void onReorganize(Wallet wallet) {
                            log.warn("onReorganize ");
                            updateBsqWalletTransactions();
                        }

                        @Override
                        public void onTransactionConfidenceChanged(Wallet wallet, Transaction tx) {
                            updateBsqWalletTransactions();
                        }

                        @Override
                        public void onKeysAdded(List<ECKey> keys) {
                            updateBsqWalletTransactions();
                        }

                        @Override
                        public void onScriptsChanged(Wallet wallet, List<Script> scripts, boolean isAddingScripts) {
                            updateBsqWalletTransactions();
                        }

                        @Override
                        public void onWalletChanged(Wallet wallet) {
                            updateBsqWalletTransactions();
                        }

                    });
                }

                final BlockChain chain = walletsSetup.getChain();
                if (chain != null) {
                    chain.addNewBestBlockListener(block -> chainHeightProperty.set(block.getHeight()));
                    chainHeightProperty.set(chain.getBestChainHeight());
                    updateBsqWalletTransactions();
                }
            });
        }

        readableBsqBlockChain.addListener(this);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // BsqBlockChain.Listener
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onBlockAdded(BsqBlock bsqBlock) {
        if (isWalletReady())
            updateBsqWalletTransactions();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Overridden Methods
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    String getWalletAsString(boolean includePrivKeys) {
        return wallet.toString(includePrivKeys, true, true, walletsSetup.getChain()) + "\n\n" +
                "All pubKeys as hex:\n" +
                wallet.printAllPubKeysAsHex();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Balance
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void updateBsqBalance() {
        pendingBalance = Coin.valueOf(getTransactions(false).stream()
                .flatMap(tx -> tx.getOutputs().stream())
                .filter(out -> {
                    final Transaction parentTx = out.getParentTransaction();
                    return parentTx != null &&
                            out.isMine(wallet) &&
                            parentTx.getConfidence().getConfidenceType() == PENDING;
                })
                .mapToLong(out -> out.getValue().value)
                .sum());

        Set<String> confirmedTxIdSet = getTransactions(false).stream()
                .filter(tx -> tx.getConfidence().getConfidenceType() == BUILDING)
                .map(Transaction::getHashAsString)
                .collect(Collectors.toSet());

        lockedForVotingBalance = Coin.valueOf(readableBsqBlockChain.getBlindVoteStakeTxOutputs().stream()
                .filter(txOutput -> confirmedTxIdSet.contains(txOutput.getTxId()))
                .mapToLong(TxOutput::getValue)
                .sum());

        lockedInBondsBalance = Coin.valueOf(readableBsqBlockChain.getLockedInBondsOutputs().stream()
                .filter(txOutput -> confirmedTxIdSet.contains(txOutput.getTxId()))
                .mapToLong(TxOutput::getValue)
                .sum());

        availableBalance = bsqCoinSelector.select(NetworkParameters.MAX_MONEY, wallet.calculateAllSpendCandidates())
                .valueGathered
                .subtract(lockedForVotingBalance)
                .subtract(lockedInBondsBalance);
        if (availableBalance.isNegative())
            availableBalance = Coin.ZERO;

        bsqBalanceListeners.forEach(e -> e.onUpdateBalances(availableBalance, pendingBalance,
                lockedForVotingBalance, lockedInBondsBalance));
    }

    public void addBsqBalanceListener(BsqBalanceListener listener) {
        bsqBalanceListeners.add(listener);
    }

    public void removeBsqBalanceListener(BsqBalanceListener listener) {
        bsqBalanceListeners.remove(listener);
    }

    @Override
    public Coin getAvailableBalance() {
        return availableBalance;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // BSQ TransactionOutputs and Transactions
    ///////////////////////////////////////////////////////////////////////////////////////////

    public ObservableList<Transaction> getWalletTransactions() {
        return walletTransactions;
    }

    private void updateBsqWalletTransactions() {
        walletTransactions.setAll(getTransactions(false));
        // walletTransactions.setAll(getBsqWalletTransactions());
        updateBsqBalance();
    }

    private Set<Transaction> getBsqWalletTransactions() {
        return getTransactions(false).stream()
                .filter(transaction -> transaction.getConfidence().getConfidenceType() == PENDING ||
                        readableBsqBlockChain.containsTx(transaction.getHashAsString()))
                .collect(Collectors.toSet());
    }

    public Set<Transaction> getUnverifiedBsqTransactions() {
        Set<Transaction> bsqWalletTransactions = getBsqWalletTransactions();
        Set<Transaction> walletTxs = new HashSet<>(getTransactions(false));
        checkArgument(walletTxs.size() >= bsqWalletTransactions.size(),
                "We cannot have more txsWithOutputsFoundInBsqTxo than walletTxs");
        if (walletTxs.size() == bsqWalletTransactions.size()) {
            // As expected
            return new HashSet<>();
        } else {
            Map<String, Transaction> map = walletTxs.stream()
                    .collect(Collectors.toMap(Transaction::getHashAsString, Function.identity()));

            Set<String> walletTxIds = walletTxs.stream()
                    .map(Transaction::getHashAsString).collect(Collectors.toSet());
            Set<String> bsqTxIds = bsqWalletTransactions.stream()
                    .map(Transaction::getHashAsString).collect(Collectors.toSet());

            walletTxIds.stream()
                    .filter(bsqTxIds::contains)
                    .forEach(map::remove);
            return new HashSet<>(map.values());
        }
    }

    @Override
    public Coin getValueSentFromMeForTransaction(Transaction transaction) throws ScriptException {
        Coin result = Coin.ZERO;
        // We check all our inputs and get the connected outputs.
        for (int i = 0; i < transaction.getInputs().size(); i++) {
            TransactionInput input = transaction.getInputs().get(i);
            // We grab the connected output for that input
            TransactionOutput connectedOutput = input.getConnectedOutput();
            if (connectedOutput != null) {
                // We grab the parent tx of the connected output
                final Transaction parentTransaction = connectedOutput.getParentTransaction();
                final boolean isConfirmed = parentTransaction != null &&
                        parentTransaction.getConfidence().getConfidenceType() == TransactionConfidence.ConfidenceType.BUILDING;
                if (connectedOutput.isMineOrWatched(wallet)) {
                    if (isConfirmed) {
                        // We lookup if we have a BSQ tx matching the parent tx
                        // We cannot make that findTx call outside of the loop as the parent tx can change at each iteration
                        Optional<Tx> txOptional = readableBsqBlockChain.getTx(parentTransaction.getHash().toString());
                        if (txOptional.isPresent()) {
                            // BSQ tx and BitcoinJ tx have same outputs (mirrored data structure)
                            TxOutput txOutput = txOptional.get().getOutputs().get(connectedOutput.getIndex());
                            if (txOutput.isVerified()) {
                                //TODO check why values are not the same
                                if (txOutput.getValue() != connectedOutput.getValue().value)
                                    log.warn("getValueSentToMeForTransaction: Value of BSQ output do not match BitcoinJ tx output. " +
                                                    "txOutput.getValue()={}, output.getValue().value={}, txId={}",
                                            txOutput.getValue(), connectedOutput.getValue().value, txOptional.get().getId());

                                // If it is a valid BSQ output we add it
                                result = result.add(Coin.valueOf(txOutput.getValue()));
                            }
                        }
                    } /*else {
                        // TODO atm we don't display amounts of unconfirmed txs but that might change so we leave that code
                        // if it will be required
                        // If the tx is not confirmed yet we add the value and assume it is a valid BSQ output.
                        result = result.add(connectedOutput.getValue());
                    }*/
                }
            }
        }
        return result;
    }

    @Override
    public Coin getValueSentToMeForTransaction(Transaction transaction) throws ScriptException {
        Coin result = Coin.ZERO;
        final String txId = transaction.getHashAsString();
        // We check if we have a matching BSQ tx. We do that call here to avoid repeated calls in the loop.
        Optional<Tx> txOptional = readableBsqBlockChain.getTx(txId);
        // We check all the outputs of our tx
        for (int i = 0; i < transaction.getOutputs().size(); i++) {
            TransactionOutput output = transaction.getOutputs().get(i);
            final boolean isConfirmed = output.getParentTransaction() != null &&
                    output.getParentTransaction().getConfidence().getConfidenceType() == TransactionConfidence.ConfidenceType.BUILDING;
            if (output.isMineOrWatched(wallet)) {
                if (isConfirmed) {
                    if (txOptional.isPresent()) {
                        // The index of the BSQ tx outputs are the same like the bitcoinj tx outputs
                        TxOutput txOutput = txOptional.get().getOutputs().get(i);
                        if (txOutput.isVerified()) {
                            //TODO check why values are not the same
                            if (txOutput.getValue() != output.getValue().value)
                                log.warn("getValueSentToMeForTransaction: Value of BSQ output do not match BitcoinJ tx output. " +
                                                "txOutput.getValue()={}, output.getValue().value={}, txId={}",
                                        txOutput.getValue(), output.getValue().value, txId);

                            // If it is a valid BSQ output we add it
                            result = result.add(Coin.valueOf(txOutput.getValue()));
                        }
                    }
                } /*else {
                    // TODO atm we don't display amounts of unconfirmed txs but that might change so we leave that code
                    // if it will be required
                    // If the tx is not confirmed yet we add the value and assume it is a valid BSQ output.
                    result = result.add(output.getValue());
                }*/
            }
        }
        return result;
    }

    public Optional<Transaction> isWalletTransaction(String txId) {
        return getWalletTransactions().stream().filter(e -> e.getHashAsString().equals(txId)).findAny();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Sign tx
    ///////////////////////////////////////////////////////////////////////////////////////////

    public Transaction signTx(Transaction tx) throws WalletException, TransactionVerificationException {
        for (int i = 0; i < tx.getInputs().size(); i++) {
            TransactionInput txIn = tx.getInputs().get(i);
            TransactionOutput connectedOutput = txIn.getConnectedOutput();
            if (connectedOutput != null && connectedOutput.isMine(wallet)) {
                signTransactionInput(wallet, aesKey, tx, txIn, i);
                checkScriptSig(tx, txIn, i);
            }
        }

        checkWalletConsistency(wallet);
        verifyTransaction(tx);
        printTx("BSQ wallet: Signed Tx", tx);
        return tx;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Commit tx
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void commitTx(Transaction tx) {
        wallet.commitTx(tx);
        //printTx("BSQ commit Tx", tx);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Send BSQ with BTC fee
    ///////////////////////////////////////////////////////////////////////////////////////////

    public Transaction getPreparedSendTx(String receiverAddress, Coin receiverAmount)
            throws AddressFormatException, InsufficientBsqException, WalletException, TransactionVerificationException {
        Transaction tx = new Transaction(params);
        checkArgument(Restrictions.isAboveDust(receiverAmount),
                "The amount is too low (dust limit).");
        tx.addOutput(receiverAmount, Address.fromBase58(params, receiverAddress));

        SendRequest sendRequest = SendRequest.forTx(tx);
        sendRequest.fee = Coin.ZERO;
        sendRequest.feePerKb = Coin.ZERO;
        sendRequest.ensureMinRequiredFee = false;
        sendRequest.aesKey = aesKey;
        sendRequest.shuffleOutputs = false;
        sendRequest.signInputs = false;
        sendRequest.ensureMinRequiredFee = false;
        sendRequest.changeAddress = getUnusedAddress();
        try {
            wallet.completeTx(sendRequest);
        } catch (InsufficientMoneyException e) {
            throw new InsufficientBsqException(e.missing);
        }
        checkWalletConsistency(wallet);
        verifyTransaction(tx);
        // printTx("prepareSendTx", tx);
        return tx;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Burn fee tx
    ///////////////////////////////////////////////////////////////////////////////////////////

    // We create a tx with Bsq inputs for the fee and optional BSQ change output.
    // As the fee amount will be missing in the output those BSQ fees are burned.
    public Transaction getPreparedBurnFeeTx(Coin fee) throws InsufficientBsqException {
        final Transaction tx = new Transaction(params);
        addInputsForTx(tx, fee, bsqCoinSelector);
        printTx("getPreparedFeeTx", tx);
        return tx;
    }

    // TODO add tests
    private void addInputsForTx(Transaction tx, Coin target, BsqCoinSelector bsqCoinSelector)
            throws InsufficientBsqException {
        Coin requiredInput;
        // If our target is less then dust limit we increase it so we are sure to not get any dust output.
        if (Restrictions.isDust(target))
            requiredInput = Restrictions.getMinNonDustOutput().add(target);
        else
            requiredInput = target;

        CoinSelection coinSelection = bsqCoinSelector.select(requiredInput, wallet.calculateAllSpendCandidates());
        coinSelection.gathered.forEach(tx::addInput);
        try {
            Coin change = this.bsqCoinSelector.getChange(target, coinSelection);
            if (change.isPositive()) {
                checkArgument(Restrictions.isAboveDust(change), "We must not get dust output here.");
                tx.addOutput(change, getUnusedAddress());
            }
        } catch (InsufficientMoneyException e) {
            throw new InsufficientBsqException(e.missing);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Blind vote tx
    ///////////////////////////////////////////////////////////////////////////////////////////

    // We create a tx with Bsq inputs for the fee, one output for the stake and optional one BSQ change output.
    // As the fee amount will be missing in the output those BSQ fees are burned.
    public Transaction getPreparedBlindVoteTx(Coin fee, Coin stake) throws InsufficientBsqException {
        Transaction tx = new Transaction(params);
        tx.addOutput(new TransactionOutput(params, tx, stake, getUnusedAddress()));
        addInputsForTx(tx, fee.add(stake), bsqCoinSelector);
        printTx("getPreparedBlindVoteTx", tx);
        return tx;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // MyVote reveal tx
    ///////////////////////////////////////////////////////////////////////////////////////////

    public Transaction getPreparedVoteRevealTx(TxOutput stakeTxOutput) {
        Transaction tx = new Transaction(params);
        final Coin stake = Coin.valueOf(stakeTxOutput.getValue());
        Transaction blindVoteTx = getTransaction(stakeTxOutput.getTxId());
        checkNotNull(blindVoteTx, "blindVoteTx must not be null");
        TransactionOutPoint outPoint = new TransactionOutPoint(params, stakeTxOutput.getIndex(), blindVoteTx);
        // Input is not signed yet so we use new byte[]{}
        tx.addInput(new TransactionInput(params, tx, new byte[]{}, outPoint, stake));
        tx.addOutput(new TransactionOutput(params, tx, stake, getUnusedAddress()));
        printTx("getPreparedVoteRevealTx", tx);
        return tx;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Addresses
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected Set<Address> getAllAddressesFromActiveKeys() {
        return wallet.getActiveKeychain().getLeafKeys().stream().
                map(key -> Address.fromP2SHHash(params, key.getPubKeyHash())).
                collect(Collectors.toSet());
    }

    public Address getUnusedAddress() {
        return wallet.currentReceiveAddress();
    }
}
