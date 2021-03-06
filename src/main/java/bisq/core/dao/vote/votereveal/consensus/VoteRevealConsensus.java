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

package bisq.core.dao.vote.votereveal.consensus;

import bisq.core.dao.consensus.OpReturnType;
import bisq.core.dao.vote.blindvote.BlindVoteList;

import bisq.common.app.Version;
import bisq.common.crypto.Hash;

import javax.crypto.SecretKey;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import lombok.extern.slf4j.Slf4j;

/**
 * All consensus critical aspects are handled here.
 */
@Slf4j
public class VoteRevealConsensus {
    public static byte[] getHashOfBlindVoteList(BlindVoteList blindVoteList) {
        final byte[] bytes = blindVoteList.toProtoMessage().toByteArray();
        return Hash.getSha256Ripemd160hash(bytes);
    }

    public static byte[] getOpReturnData(byte[] hashOfBlindVoteList, SecretKey secretKey) throws IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            outputStream.write(OpReturnType.VOTE_REVEAL.getType());
            outputStream.write(Version.VOTE_REVEAL_VERSION);
            outputStream.write(hashOfBlindVoteList);     // hash is 20 bytes
            outputStream.write(secretKey.getEncoded()); // encoded secretKey has 32 bytes
            return outputStream.toByteArray();
        } catch (IOException e) {
            // Not expected to happen ever
            e.printStackTrace();
            log.error(e.toString());
            throw e;
        }
    }
}
