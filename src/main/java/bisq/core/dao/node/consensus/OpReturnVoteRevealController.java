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

import bisq.core.dao.blockchain.ReadableBsqBlockChain;
import bisq.core.dao.blockchain.vo.TxOutput;
import bisq.core.dao.blockchain.vo.TxOutputType;
import bisq.core.dao.consensus.OpReturnType;

import bisq.common.app.Version;

import javax.inject.Inject;

import lombok.extern.slf4j.Slf4j;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * Verifies if OP_RETURN data matches rules for a vote reveal tx and applies state change.
 */
@Slf4j
public class OpReturnVoteRevealController {

    private final ReadableBsqBlockChain readableBsqBlockChain;

    @Inject
    public OpReturnVoteRevealController(ReadableBsqBlockChain readableBsqBlockChain) {
        this.readableBsqBlockChain = readableBsqBlockChain;
    }

    public boolean verify(byte[] opReturnData, int blockHeight, Model model) {
        return model.isVoteStakeSpentAtInputs() &&
                opReturnData.length == 54 &&
                Version.VOTE_REVEAL_VERSION == opReturnData[1] &&
                readableBsqBlockChain.isVoteRevealPeriodValid(blockHeight);
    }

    public void applyStateChange(TxOutput txOutput, Model model) {
        txOutput.setTxOutputType(TxOutputType.VOTE_REVEAL_OP_RETURN_OUTPUT);
        model.setVerifiedOpReturnType(OpReturnType.VOTE_REVEAL);
        checkArgument(model.getVoteRevealUnlockStakeOutput() != null,
                "model.getVoteRevealUnlockStakeOutput() must not be null");
        model.getVoteRevealUnlockStakeOutput().setTxOutputType(TxOutputType.VOTE_REVEAL_UNLOCK_STAKE_OUTPUT);
    }
}
