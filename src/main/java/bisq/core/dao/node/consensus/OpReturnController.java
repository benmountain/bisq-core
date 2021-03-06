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

package bisq.core.dao.node.consensus;

import bisq.core.dao.blockchain.vo.Tx;
import bisq.core.dao.blockchain.vo.TxOutput;
import bisq.core.dao.blockchain.vo.TxOutputType;
import bisq.core.dao.consensus.OpReturnType;

import bisq.common.app.DevEnv;

import org.bitcoinj.core.Utils;

import javax.inject.Inject;

import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

/**
 * Verifies if a given transaction is a BSQ OP_RETURN transaction.
 */
@Slf4j
public class OpReturnController {
    private final OpReturnProposalController opReturnProposalController;
    private final OpReturnCompReqController opReturnCompReqController;
    private final OpReturnBlindVoteController opReturnBlindVoteController;
    private final OpReturnVoteRevealController opReturnVoteRevealController;

    @Inject
    public OpReturnController(OpReturnProposalController opReturnProposalController,
                              OpReturnCompReqController opReturnCompReqController,
                              OpReturnBlindVoteController opReturnBlindVoteController,
                              OpReturnVoteRevealController opReturnVoteRevealController) {
        this.opReturnProposalController = opReturnProposalController;
        this.opReturnCompReqController = opReturnCompReqController;
        this.opReturnBlindVoteController = opReturnBlindVoteController;
        this.opReturnVoteRevealController = opReturnVoteRevealController;
    }

    // We only check partially the rules here as we do not know the BSQ fee at that moment which is always used when
    // we have OP_RETURN data.
    public void processOpReturnCandidate(TxOutput txOutput, Model model) {
        // We do not check for pubKeyScript.scriptType.NULL_DATA because that is only set if dumpBlockchainData is true
        final byte[] opReturnData = txOutput.getOpReturnData();
        if (txOutput.getValue() == 0 && opReturnData != null && opReturnData.length >= 1) {
            OpReturnType.getOpReturnType(opReturnData[0]).ifPresent(model::setOpReturnTypeCandidate);
        }
    }

    public void processTxOutput(byte[] opReturnData, TxOutput txOutput, Tx tx, int index, long bsqFee,
                                int blockHeight, Model model) {
        getOptionalOpReturnType(opReturnData, txOutput, tx, index)
                .ifPresent(opReturnType -> {
                    switch (opReturnType) {
                        case COMPENSATION_REQUEST:
                            if (opReturnCompReqController.verify(opReturnData, bsqFee, blockHeight, model)) {
                                opReturnCompReqController.applyStateChange(txOutput, model);
                            } else {
                                txOutput.setTxOutputType(TxOutputType.INVALID_OUTPUT);
                                log.info("We expected a compensation request op_return data but it did not " +
                                        "match our rules. tx={}", tx);
                            }
                            break;
                        case PROPOSAL:
                            if (opReturnProposalController.verify(opReturnData, bsqFee, blockHeight)) {
                                opReturnProposalController.applyStateChange(txOutput, model);
                            } else {
                                txOutput.setTxOutputType(TxOutputType.INVALID_OUTPUT);
                                txOutput.setTxOutputType(TxOutputType.COMP_REQ_OP_RETURN_OUTPUT);
                                log.info("We expected a proposal op_return data but it did not " +
                                        "match our rules. tx={}", tx);
                            }
                            break;
                        case BLIND_VOTE:
                            if (opReturnBlindVoteController.verify(opReturnData, bsqFee, blockHeight, model)) {
                                opReturnBlindVoteController.applyStateChange(txOutput, model);
                            } else {
                                txOutput.setTxOutputType(TxOutputType.INVALID_OUTPUT);
                                log.info("We expected a blind vote op_return data but it did not " +
                                        "match our rules. tx={}", tx);
                            }
                            break;
                        case VOTE_REVEAL:
                            if (opReturnVoteRevealController.verify(opReturnData, blockHeight, model)) {
                                opReturnVoteRevealController.applyStateChange(txOutput, model);
                            } else {
                                txOutput.setTxOutputType(TxOutputType.INVALID_OUTPUT);
                                log.info("We expected a vote reveal op_return data but it did not " +
                                        "match our rules. tx={}", tx);
                            }
                            break;
                        case LOCK_UP:
                            // TODO
                            txOutput.setTxOutputType(TxOutputType.BOND_LOCK_OP_RETURN_OUTPUT);
                            break;
                        case UNLOCK:
                            // TODO
                            txOutput.setTxOutputType(TxOutputType.BOND_UNLOCK_OP_RETURN_OUTPUT);
                            break;
                        default:
                            // Should never happen as long we keep OpReturnType entries in sync with out switch case.
                            final String msg = "Unsupported OpReturnType. tx=" + tx +
                                    "; opReturnData=" + Utils.HEX.encode(opReturnData);
                            log.error(msg);
                            if (DevEnv.isDevMode())
                                throw new RuntimeException(msg);

                            break;
                    }
                });
    }

    private Optional<OpReturnType> getOptionalOpReturnType(byte[] opReturnData, TxOutput txOutput, Tx tx, int index) {
        if (txOutput.getValue() == 0 &&
                index == tx.getOutputs().size() - 1 &&
                opReturnData.length >= 1) {
            return OpReturnType.getOpReturnType(opReturnData[0]);
        } else {
            log.warn("OP_RETURN version of the BSQ tx ={} does not match expected version bytes. opReturnData={}",
                    tx, Utils.HEX.encode(opReturnData));
            return Optional.empty();
        }
    }
}
