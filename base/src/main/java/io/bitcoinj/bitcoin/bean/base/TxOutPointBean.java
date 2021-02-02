/**
 * Copyright (c) 2020 Steve Shadders.
 * All rights reserved.
 */
package io.bitcoinj.bitcoin.bean.base;

import io.bitcoinj.core.Sha256Hash;
import io.bitcoinj.core.Utils;
import io.bitcoinj.bitcoin.api.base.TxInput;
import io.bitcoinj.bitcoin.api.base.TxOutPoint;
import io.bitcoinj.bitcoin.bean.BitcoinObjectImpl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

public class TxOutPointBean extends BitcoinObjectImpl<TxOutPoint> implements TxOutPoint {

    /** Hash of the transaction to which we refer. */
    private Sha256Hash hash;
    /** Which output of that transaction we are talking about. */
    private long index;

    public TxOutPointBean(TxInput input, byte[] payload, int offset) {
        super(input, payload, offset);
    }

    public TxOutPointBean(byte[] payload) {
        super(null, payload, 0);
    }

    /**
     * Constructor for building manually
     * @param parent
     */
    public TxOutPointBean(TxInput parent) {
        super(parent);
    }

    public TxOutPointBean(TxInput inputBean, InputStream in) {
        super(inputBean, in);
    }


    @Override
    public Sha256Hash getHash() {
        return hash;
    }

    @Override
    public void setHash(Sha256Hash hash) {
        checkMutable();
        this.hash = hash;
    }

    @Override
    public long getIndex() {
        return index;
    }

    @Override
    public void setIndex(long index) {
        checkMutable();
        this.index = index;
    }

    @Override
    protected void parse() {
        hash = readHash();
        index = readUint32();
    }

    @Override
    public void serializeTo(OutputStream stream) throws IOException {
        stream.write(hash.getReversedBytes());
        Utils.uint32ToByteStreamLE(index, stream);
    }

    @Override
    public TxOutPoint makeNew(byte[] serialized) {
        return new TxOutPointBean(serialized);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TxOutPointBean that = (TxOutPointBean) o;
        return index == that.index &&
                Objects.equals(hash, that.hash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hash, index);
    }
}
