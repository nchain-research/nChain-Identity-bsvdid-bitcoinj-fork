package org.bitcoinj.msg.bitcoin.api.extended;

import org.bitcoinj.msg.bitcoin.api.base.AbstractBlock;
import org.bitcoinj.msg.bitcoin.api.base.Header;

import java.math.BigInteger;

public interface LiteBlock<C extends AbstractBlock> extends AbstractBlock<LiteBlock>, ChainInfoReadOnly {

    public static final int FIXED_MESSAGE_SIZE =
                    Header.FIXED_MESSAGE_SIZE +
                    BlockMeta.FIXED_MESSAGE_SIZE +
                    ChainInfo.FIXED_MESSAGE_SIZE;

    void setHeader(Header header);

    BlockMeta getBlockMeta();

    void setBlockMeta(BlockMeta blockMeta);

    ChainInfo getChainInfo();

    void setChainInfo(ChainInfo chainInfo);

    @Override
    default BigInteger getChainWork() {
        return getChainInfo().getChainWork();
    }

    @Override
    default int getHeight() {
        return getChainInfo().getHeight();
    }

    @Override
    default int fixedSize() {
        return FIXED_MESSAGE_SIZE;
    }
}
