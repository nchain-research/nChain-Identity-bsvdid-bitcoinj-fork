package org.bitcoinj.msg.bitcoin;

import org.bitcoinj.core.Sha256Hash;

public interface Header extends BitcoinObject {
    Sha256Hash getHash();

    void setHash(Sha256Hash hash);

    long getVersion();

    void setVersion(long version);

    Sha256Hash getPrevBlockHash();

    void setPrevBlockHash(Sha256Hash prevBlockHash);

    Sha256Hash getMerkleRoot();

    void setMerkleRoot(Sha256Hash merkleRoot);

    long getTime();

    void setTime(long time);

    long getDifficultyTarget();

    void setDifficultyTarget(long difficultyTarget);

    long getNonce();

    void setNonce(long nonce);

    long getTxCount();

    void setTxCount(long txCount);

    Tx getCoinbase();

    void setCoinbase(Tx coinbase);

    long getSerializedLength();

    void setSerializedLength(long serializedLength);
}
