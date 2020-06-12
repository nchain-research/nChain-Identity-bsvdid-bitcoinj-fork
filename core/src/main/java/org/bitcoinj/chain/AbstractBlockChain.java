/*
 * Copyright 2012 Google Inc.
 * Copyright 2014 Andreas Schildbach
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bitcoinj.chain;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import org.bitcoinj.blockchain.ChainUtils;
import org.bitcoinj.blockchain.pow.RulesPoolChecker;
import org.bitcoinj.blockstore.SPVBlockStore;
import org.bitcoinj.blockchain.pow.AbstractRuleCheckerFactory;
import org.bitcoinj.blockchain.pow.factory.RuleCheckerFactory;
import org.bitcoinj.chain_legacy.*;
import org.bitcoinj.core.*;
import org.bitcoinj.core.listeners.NewBestBlockListener;
import org.bitcoinj.core.listeners.ReorganizeListener;
import org.bitcoinj.exception.BlockStoreException;
import org.bitcoinj.exception.PrunedException;
import org.bitcoinj.exception.VerificationException;
import org.bitcoinj.msg.bitcoin.api.base.FullBlock;
import org.bitcoinj.msg.bitcoin.api.extended.ChainInfoReadOnly;
import org.bitcoinj.msg.bitcoin.api.extended.LiteBlock;
import org.bitcoinj.msg.protocol.Block;
import org.bitcoinj.params.NetworkParameters;
import org.bitcoinj.blockstore.BlockStore;
import org.bitcoinj.utils.ListenerRegistration;
import org.bitcoinj.utils.Threading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.base.Preconditions.*;

/**
 * <p>An AbstractBlockChain holds a series of {@link LiteBlock} objects, links them together, and knows how to verify that
 * the chain follows the rules of the {@link NetworkParameters} for this chain.</p>
 *
 *
 * <p>An AbstractBlockChain implementation must be connected to a {@link BlockStore} implementation. The chain object
 * by itself doesn't store any data, that's delegated to the store. Which store you use is a decision best made by
 * reading the getting started guide, but briefly, fully validating block chains need fully validating stores. In
 * the lightweight SPV mode, a {@link SPVBlockStore} is the right choice.</p>
 *
 * <p>This class implements an abstract class which makes it simple to create a BlockChain that does/doesn't do full
 * verification.  It verifies headers and is implements most of what is required to implement SPV mode, but
 * also provides callback hooks which can be used to do full verification.</p>
 *
 * <p>There are two subclasses of AbstractBlockChain that are useful: {@link SPVBlockChain}, which is the simplest
 * class and implements <i>simplified payment verification</i>. This is a lightweight and efficient mode that does
 * not verify the contents of blocks, just their headers. A { FullPrunedBlockChain} paired with a
 * { H2FullPrunedBlockStore} implements full verification, which is equivalent to
 * Bitcoin Core. To learn more about the alternative security models, please consult the articles on the
 * website.</p>
 *
 * <b>Theory</b>
 *
 * <p>The 'chain' is actually a tree although in normal operation it operates mostly as a list of {@link FullBlock}s.
 * When multiple new head blocks are found simultaneously, there are multiple stories of the economy competing to become
 * the one true consensus. This can happen naturally when two miners solve a block within a few seconds of each other,
 * or it can happen when the chain is under attack.</p>
 *
 * <p>A reference to the head block of the best known chain is stored. If you can reach the genesis block by repeatedly
 * walking through the prevBlock pointers, then we say this is a full chain. If you cannot reach the genesis block
 * we say it is an orphan chain. Orphan chains can occur when blocks are solved and received during the initial block
 * chain download, or if we connect to a peer that doesn't send us blocks in order.</p>
 *
 * <p>A reorganize occurs when the blocks that make up the best known chain changes. Note that simply adding a
 * new block to the top of the best chain isn't as reorganize, but that a reorganize is always triggered by adding
 * a new block that connects to some other (non best head) block. By "best" we mean the chain representing the largest
 * amount of work done.</p>
 *
 * <p>Every so often the block chain passes a difficulty transition point. At that time, all the blocks in the last
 * 2016 blocks are examined and a new difficulty target is calculated from them.</p>
 */
public abstract class AbstractBlockChain {
    private static final Logger log = LoggerFactory.getLogger(AbstractBlockChain.class);
    protected final ReentrantLock lock = Threading.lock("blockchain");

    /** Keeps a map of block hashes to StoredBlocks. */
    private final BlockStore blockStore;

    /**
     * Tracks the top of the best known chain.<p>
     *
     * Following this one down to the genesis block produces the story of the economy from the creation of Bitcoin
     * until the present day. The chain head can change if a new set of blocks is received that results in a chain of
     * greater work than the one obtained by following this one down. In that case a reorganize is triggered,
     * potentially invalidating transactions in our wallet.
     */
    protected LiteBlock chainHead;

    // TODO: Scrap this and use a proper read/write for all of the block chain objects.
    // The chainHead field is read/written synchronized with this object rather than BlockChain. However writing is
    // also guaranteed to happen whilst BlockChain is synchronized (see setChainHead). The goal of this is to let
    // clients quickly access the chain head even whilst the block chain is downloading and thus the BlockChain is
    // locked most of the time.
    private final Object chainHeadLock = new Object();

    protected final NetworkParameters params;
    protected final AbstractRuleCheckerFactory ruleCheckerFactory;
    private final CopyOnWriteArrayList<ListenerRegistration<NewBestBlockListener>> newBestBlockListeners;
    private final CopyOnWriteArrayList<ListenerRegistration<ReorganizeListener>> reorganizeListeners;

    // Holds a block header and, optionally, a list of tx hashes or block's transactions
    class OrphanBlock {
        final LiteBlock block;
        OrphanBlock(LiteBlock block) {
            this.block = block;
        }
    }
    // Holds blocks that we have received but can't plug into the chain yet, eg because they were created whilst we
    // were downloading the block chain.
    private final LinkedHashMap<Sha256Hash, OrphanBlock> orphanBlocks = new LinkedHashMap<Sha256Hash, OrphanBlock>();

    /** False positive estimation uses a double exponential moving average. */
    public static final double FP_ESTIMATOR_ALPHA = 0.0001;
    /** False positive estimation uses a double exponential moving average. */
    public static final double FP_ESTIMATOR_BETA = 0.01;

    private double falsePositiveRate;
    private double falsePositiveTrend;
    private double previousFalsePositiveRate;

    private final VersionTally versionTally;

    /**
     * Constructs a BlockChain connected to the given list of listeners (eg, wallets) and a store.
     */
    public AbstractBlockChain(NetworkParameters params, List<? extends ChainEventListener_legacy> wallets,
                              BlockStore blockStore) throws BlockStoreException {
        this.blockStore = blockStore;
        chainHead = blockStore.getChainHead();
        log.info("chain head is at height {}:\n{}", chainHead.getChainInfo().getHeight(), chainHead.getHeader());
        this.params = params;
        this.ruleCheckerFactory = RuleCheckerFactory.create(this.params);

        this.newBestBlockListeners = new CopyOnWriteArrayList<ListenerRegistration<NewBestBlockListener>>();
        this.reorganizeListeners = new CopyOnWriteArrayList<ListenerRegistration<ReorganizeListener>>();
        for (ChainEventListener_legacy l : wallets) {
            addChainEventListener(l);
        }

        this.versionTally = new VersionTally(params);
        this.versionTally.initialize(blockStore, chainHead);
    }

    /**
     * Add a wallet to the BlockChain. Note that the wallet will be unaffected by any blocks received while it
     * was not part of this BlockChain. This method is useful if the wallet has just been created, and its keys
     * have never been in use, or if the wallet has been loaded along with the BlockChain. Note that adding multiple
     * wallets is not well tested!
     */
    public final void addChainEventListener(ChainEventListener_legacy chainEventListener) {
        addNewBestBlockListener(Threading.SAME_THREAD, chainEventListener);
        addReorganizeListener(Threading.SAME_THREAD, chainEventListener);
    }

    /** Removes a wallet from the chain. */
    public void removeChainEventListener(ChainEventListener_legacy chainEventListener) {
        removeNewBestBlockListener(chainEventListener);
        removeReorganizeListener(chainEventListener);
    }

    /**
     * Adds a {@link NewBestBlockListener} listener to the chain.
     */
    public void addNewBestBlockListener(NewBestBlockListener listener) {
        addNewBestBlockListener(Threading.USER_THREAD, listener);
    }

    /**
     * Adds a {@link NewBestBlockListener} listener to the chain.
     */
    public final void addNewBestBlockListener(Executor executor, NewBestBlockListener listener) {
        newBestBlockListeners.add(new ListenerRegistration<NewBestBlockListener>(listener, executor));
    }

    /**
     * Adds a generic {@link ReorganizeListener} listener to the chain.
     */
    public void addReorganizeListener(ReorganizeListener listener) {
        addReorganizeListener(Threading.USER_THREAD, listener);
    }

    /**
     * Adds a generic {@link ReorganizeListener} listener to the chain.
     */
    public final void addReorganizeListener(Executor executor, ReorganizeListener listener) {
        reorganizeListeners.add(new ListenerRegistration<ReorganizeListener>(listener, executor));
    }

    /**
     * Removes the given {@link NewBestBlockListener} from the chain.
     */
    public void removeNewBestBlockListener(NewBestBlockListener listener) {
        ListenerRegistration.removeFromList(listener, newBestBlockListeners);
    }

    /**
     * Removes the given {@link ReorganizeListener} from the chain.
     */
    public void removeReorganizeListener(ReorganizeListener listener) {
        ListenerRegistration.removeFromList(listener, reorganizeListeners);
    }

    /**
     * Returns the {@link BlockStore} the chain was constructed with. You can use this to iterate over the chain.
     */
    public BlockStore getBlockStore() {
        return blockStore;
    }
    
    /**
     * Adds/updates the given {@link Block} with the block store.
     * This version is used when the transactions have not been verified.
     * @param storedPrev The {@link LiteBlock} which immediately precedes block.
     * @param block The {@link Block} to add/update.
     * @return the newly created {@link LiteBlock}
     */
    protected abstract LiteBlock addToBlockStore(LiteBlock storedPrev, LiteBlock block)
            throws BlockStoreException, VerificationException;

    /**
     * Rollback the block store to a given height. This is currently only supported by {@link SPVBlockChain_legacy} instances.
     * 
     * @throws BlockStoreException
     *             if the operation fails or is unsupported.
     */
    protected abstract void rollbackBlockStore(int height) throws BlockStoreException;

    /**
     * Called before setting chain head in memory.
     * Should write the new head to block store and then commit any database transactions
     * that were started by disconnectTransactions/connectTransactions.
     */
    protected abstract void doSetChainHead(LiteBlock chainHead) throws BlockStoreException;
    
    /**
     * Called if we (possibly) previously called disconnectTransaction/connectTransactions,
     * but will not be calling preSetChainHead as a block failed verification.
     * Can be used to abort database transactions that were started by
     * disconnectTransactions/connectTransactions.
     */
    protected abstract void notSettingChainHead() throws BlockStoreException;
    
    /**
     * For a standard BlockChain, this should return blockStore.get(hash),
     * for a FullPrunedBlockChain blockStore.getOnceUndoableStoredBlock(hash)
     */
    protected abstract LiteBlock getStoredBlockInCurrentScope(Sha256Hash hash) throws BlockStoreException;

    /**
     * Processes a received block and tries to add it to the chain. If there's something wrong with the block an
     * exception is thrown. If the block is OK but cannot be connected to the chain at this time, returns false.
     * If the block can be connected to the chain, returns true.
     * Accessing block's transactions in another thread while this method runs may result in undefined behavior.
     */
    public boolean add(LiteBlock block) throws VerificationException, PrunedException {
        try {
            return add(block, true);
        } catch (BlockStoreException e) {
            // TODO: Figure out a better way to propagate this exception to the user.
            throw new RuntimeException(e);
        } catch (VerificationException e) {
            try {
                notSettingChainHead();
            } catch (BlockStoreException e1) {
                throw new RuntimeException(e1);
            }
            throw new VerificationException("Could not verify block:\n" +
                    block.toString(), e);
        }
    }

    private boolean add(LiteBlock block, boolean tryConnecting)
            throws BlockStoreException, VerificationException, PrunedException {
        // TODO: Use read/write locks to ensure that during chain download properties are still low latency.
        lock.lock();
        try {
            // Quick check for duplicates to avoid an expensive check further down (in findSplit). This can happen a lot
            // when connecting orphan transactions due to the dumb brute force algorithm we use.
            if (block.equals(getChainHead().getHeader())) {
                return true;
            }
            if (tryConnecting && orphanBlocks.containsKey(block.getHash())) {
                return false;
            }

            final LiteBlock storedPrev;
            final int height;
            final EnumSet<BitcoinJ.BlockVerifyFlag> flags;

            // Prove the block is internally valid: hash is lower than target, etc. This only checks the block contents
            // if there is a tx sending or receiving coins using an address in one of our wallets. And those transactions
            // are only lightly verified: presence in a valid connecting block is taken as proof of validity. See the
            // article here for more details: https://bitcoinj.github.io/security-model
            try {
                block.verifyHeader();
                storedPrev = getStoredBlockInCurrentScope(block.getPrevBlockHash());
                if (storedPrev != null) {
                    height = storedPrev.getChainInfo().getHeight() + 1;
                } else {
                    height = BitcoinJ.BLOCK_HEIGHT_UNKNOWN;
                }

            } catch (VerificationException e) {
                log.error("Failed to verify block: ", e);
                log.error(block.getHashAsString());
                throw e;
            }

            // Try linking it to a place in the currently known blocks.

            if (storedPrev == null) {
                // We can't find the previous block. Probably we are still in the process of downloading the chain and a
                // block was solved whilst we were doing it. We put it to one side and try to connect it later when we
                // have more blocks.
                checkState(tryConnecting, "bug in tryConnectingOrphans");
                log.warn("Block does not connect: {} prev {}", block.getHashAsString(), block.getPrevBlockHash());
                orphanBlocks.put(block.getHash(), new OrphanBlock(block));
                return false;
            } else {
                checkState(lock.isHeldByCurrentThread());
                // It connects to somewhere on the chain. Not necessarily the top of the best known chain.
                RulesPoolChecker rulesChecker = ruleCheckerFactory.getRuleChecker(storedPrev, block);
                rulesChecker.checkRules(storedPrev, block, blockStore);
                connectBlock(block, storedPrev, true);
            }

            if (tryConnecting)
                tryConnectingOrphans();

            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the hashes of the currently stored orphan blocks and then deletes them from this objects storage.
     * Used by Peer when a filter exhaustion event has occurred and thus any orphan blocks that have been downloaded
     * might be inaccurate/incomplete.
     */
    public Set<Sha256Hash> drainOrphanBlocks() {
        lock.lock();
        try {
            Set<Sha256Hash> hashes = new HashSet<Sha256Hash>(orphanBlocks.keySet());
            orphanBlocks.clear();
            return hashes;
        } finally {
            lock.unlock();
        }
    }

    // expensiveChecks enables checks that require looking at blocks further back in the chain
    // than the previous one when connecting (eg median timestamp check)
    // It could be exposed, but for now we just set it to shouldVerifyTransactions()
    private void connectBlock(final LiteBlock block, LiteBlock storedPrev, boolean expensiveChecks
                              ) throws BlockStoreException, VerificationException, PrunedException {
        checkState(lock.isHeldByCurrentThread());

        // Check that we aren't connecting a block that fails a checkpoint check
        if (!params.passesCheckpoint(storedPrev.getChainInfo().getHeight() + 1, block.getHash()))
            throw new VerificationException("Block failed checkpoint lockin at " + (storedPrev.getChainInfo().getHeight() + 1));

        LiteBlock head = getChainHead();
        if (storedPrev.equals(head)) {

            if (expensiveChecks && block.getHeader().getTime() <= ChainUtils.getMedianTimestampOfRecentBlocks(head, blockStore))
                throw new VerificationException("Block's timestamp is too early");

            // BIP 66 & 65: Enforce block version 3/4 once they are a supermajority of blocks
            // NOTE: This requires 1,000 blocks since the last checkpoint (on main
            // net, less on test) in order to be applied. It is also limited to
            // stopping addition of new v2/3 blocks to the tip of the chain.
            long blockVersion = block.getHeader().getVersion();
            if (blockVersion == BitcoinJ.BLOCK_VERSION_BIP34
                || blockVersion == BitcoinJ.BLOCK_VERSION_BIP66) {
                final Integer count = versionTally.getCountAtOrAbove(blockVersion + 1);
                if (count != null
                    && count >= params.getMajorityRejectBlockOutdated()) {
                    throw new VerificationException.BlockVersionOutOfDate(blockVersion);
                }
            }

            // This block connects to the best known block, it is a normal continuation of the system.
            LiteBlock newStoredBlock = addToBlockStore(storedPrev, block);
            versionTally.add(blockVersion);
            setChainHead(newStoredBlock);
            log.debug("Chain is now {} blocks high, running listeners", newStoredBlock.getChainInfo().getHeight());
            informListenersForNewBlock(NewBlockType.BEST_CHAIN, newStoredBlock);
        } else {
            // This block connects to somewhere other than the top of the best known chain. We treat these differently.
            //
            // Note that we send the transactions to the wallet FIRST, even if we're about to re-organize this block
            // to become the new best chain head. This simplifies handling of the re-org in the Wallet class.

            //LiteBlock newBlock = storedPrev.build(block);
            LiteBlock newBlock = ChainUtils.buildNextInChain(storedPrev, block);

            //boolean haveNewBestChain = newBlock.moreWorkThan(head);
            boolean haveNewBestChain = ChainUtils.isMoreWorkThan(newBlock, head);

            if (haveNewBestChain) {
                log.info("Block is causing a re-organize");
            } else {
                LiteBlock splitPoint = findSplit(newBlock, head, blockStore);
                if (splitPoint != null && splitPoint.equals(newBlock)) {
                    // newStoredBlock is a part of the same chain, there's no fork. This happens when we receive a block
                    // that we already saw and linked into the chain previously, which isn't the chain head.
                    // Re-processing it is confusing for the wallet so just skip.
                    log.warn("Saw duplicated block in main chain at height {}: {}",
                            newBlock.getChainInfo().getHeight(), newBlock.getHeader().getHash());
                    return;
                }
                if (splitPoint == null) {
                    // This should absolutely never happen
                    // (lets not write the full block to disk to keep any bugs which allow this to happen
                    //  from writing unreasonable amounts of data to disk)
                    throw new VerificationException("Block forks the chain but splitPoint is null");
                } else {
                    // We aren't actually spending any transactions (yet) because we are on a fork
                    addToBlockStore(storedPrev, block);
                    int splitPointHeight = splitPoint.getChainInfo().getHeight();
                    String splitPointHash = splitPoint.getHeader().getHashAsString();
                    log.info("Block forks the chain at height {}/block {}, but it did not cause a reorganize:\n{}",
                            splitPointHeight, splitPointHash, newBlock.getHeader().getHashAsString());
                }
            }

            if (haveNewBestChain)
                handleNewBestChain(storedPrev, newBlock);
        }
    }

    private void informListenersForNewBlock(final NewBlockType newBlockType,
                                            final LiteBlock newStoredBlock) throws VerificationException {
        // Notify the listeners of the new block, so the depth and workDone of stored transactions can be updated
        // (in the case of the listener being a wallet). Wallets need to know how deep each transaction is so
        // coinbases aren't used before maturity.
        boolean first = true;

        for (final ListenerRegistration<NewBestBlockListener> registration : newBestBlockListeners) {
            if (registration.executor == Threading.SAME_THREAD) {
                if (newBlockType == NewBlockType.BEST_CHAIN)
                    registration.listener.notifyNewBestBlock(newStoredBlock.getChainInfo());
            } else {
                // Listener wants to be run on some other thread, so marshal it across here.
                registration.executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (newBlockType == NewBlockType.BEST_CHAIN)
                                registration.listener.notifyNewBestBlock(newStoredBlock.getChainInfo());
                        } catch (VerificationException e) {
                            log.error("Block chain listener threw exception: ", e);
                            // Don't attempt to relay this back to the original peer thread if this was an async
                            // listener invocation.
                            // TODO: Make exception reporting a global feature and use it here.
                        }
                    }
                });
            }
            first = false;
        }

    }

    /**
     * Called as part of connecting a block when the new block results in a different chain having higher total work.
     * 
     * if (shouldVerifyTransactions)
     *     Either newChainHead needs to be in the block store as a FullStoredBlock, or (block != null && block.transactions != null)
     */
    private void handleNewBestChain(LiteBlock storedPrev, LiteBlock newChainHead)
            throws BlockStoreException, VerificationException {
        checkState(lock.isHeldByCurrentThread());
        // This chain has overtaken the one we currently believe is best. Reorganize is required.
        //
        // Firstly, calculate the block at which the chain diverged. We only need to examine the
        // chain from beyond this block to find differences.
        LiteBlock head = getChainHead();
        final LiteBlock splitPoint = findSplit(newChainHead, head, blockStore);
        log.info("Re-organize after split at height {}", splitPoint.getChainInfo().getHeight());
        log.info("Old chain head: {}", head.getHeader().getHashAsString());
        log.info("New chain head: {}", newChainHead.getHeader().getHashAsString());
        log.info("Split at block: {}", splitPoint.getHeader().getHashAsString());
        // Then build a list of all blocks in the old part of the chain and the new part.
        final LinkedList<LiteBlock> oldBlocks = getPartialChain(head, splitPoint, blockStore);
        final LinkedList<ChainInfoReadOnly> oldChainInfo = new LinkedList<>(oldBlocks);
        final LinkedList<LiteBlock> newBlocks = getPartialChain(newChainHead, splitPoint, blockStore);
        final LinkedList<ChainInfoReadOnly> newChainInfo = new LinkedList<>(newBlocks);// Disconnect each transaction in the previous main chain that is no longer in the new main chain
        LiteBlock storedNewHead = splitPoint;

        // (Finally) write block to block store
        storedNewHead = addToBlockStore(storedPrev, newChainHead);

        // Now inform the listeners. This is necessary so the set of currently active transactions (that we can spend)
        // can be updated to take into account the re-organize. We might also have received new coins we didn't have
        // before and our previous spends might have been undone.
        for (final ListenerRegistration<ReorganizeListener> registration : reorganizeListeners) {
            if (registration.executor == Threading.SAME_THREAD) {
                // Short circuit the executor so we can propagate any exceptions.
                // TODO: Do we really need to do this or should it be irrelevant?
                registration.listener.reorganize(splitPoint, oldChainInfo, newChainInfo);
            } else {
                registration.executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            registration.listener.reorganize(splitPoint, oldChainInfo, newChainInfo);
                        } catch (VerificationException e) {
                            log.error("Block chain listener threw exception during reorg", e);
                        }
                    }
                });
            }
        }
        // Update the pointer to the best known block.
        setChainHead(storedNewHead);
    }

    /**
     * Returns the set of contiguous blocks between 'higher' and 'lower'. Higher is included, lower is not.
     */
    private static LinkedList<LiteBlock> getPartialChain(LiteBlock higher, LiteBlock lower, BlockStore store) throws BlockStoreException {
        checkArgument(higher.getChainInfo().getHeight() > lower.getChainInfo().getHeight(), "higher and lower are reversed");
        LinkedList<LiteBlock> results = new LinkedList<>();
        LiteBlock cursor = higher;
        while (true) {
            results.add(cursor);
            cursor = checkNotNull(store.getPrev(cursor), "Ran off the end of the chain");
            if (cursor.equals(lower)) break;
        }
        return results;
    }


    /**
     * Locates the point in the chain at which newStoredBlock and chainHead diverge. Returns null if no split point was
     * found (ie they are not part of the same chain). Returns newChainHead or chainHead if they don't actually diverge
     * but are part of the same chain.
     */
    private static LiteBlock findSplit(LiteBlock newChainHead, LiteBlock oldChainHead,
                                         BlockStore store) throws BlockStoreException {
        LiteBlock currentChainCursor = oldChainHead;
        LiteBlock newChainCursor = newChainHead;
        // Loop until we find the block both chains have in common. Example:
        //
        //    A -> B -> C -> D
        //         \--> E -> F -> G
        //
        // findSplit will return block B. oldChainHead = D and newChainHead = G.
        while (!currentChainCursor.equals(newChainCursor)) {
            if (currentChainCursor.getChainInfo().getHeight() > newChainCursor.getChainInfo().getHeight()) {
                currentChainCursor = store.getPrev(currentChainCursor);
                checkNotNull(currentChainCursor, "Attempt to follow an orphan chain");
            } else {
                newChainCursor = store.getPrev(newChainCursor);
                checkNotNull(newChainCursor, "Attempt to follow an orphan chain");
            }
        }
        return currentChainCursor;
    }

    /**
     * @return the height of the best known chain, convenience for <tt>getChainHead().getHeight()</tt>.
     */
    public final int getBestChainHeight() {
        return getChainHead().getChainInfo().getHeight();
    }

    public enum NewBlockType {
        BEST_CHAIN,
        SIDE_CHAIN
    }

    protected void setChainHead(LiteBlock chainHead) throws BlockStoreException {
        doSetChainHead(chainHead);
        synchronized (chainHeadLock) {
            this.chainHead = chainHead;
        }
    }

    /**
     * For each block in orphanBlocks, see if we can now fit it on top of the chain and if so, do so.
     */
    private void tryConnectingOrphans() throws VerificationException, BlockStoreException, PrunedException {
        checkState(lock.isHeldByCurrentThread());
        // For each block in our orphan list, try and fit it onto the head of the chain. If we succeed remove it
        // from the list and keep going. If we changed the head of the list at the end of the round try again until
        // we can't fit anything else on the top.
        //
        // This algorithm is kind of crappy, we should do a topo-sort then just connect them in order, but for small
        // numbers of orphan blocks it does OK.
        int blocksConnectedThisRound;
        do {
            blocksConnectedThisRound = 0;
            Iterator<OrphanBlock> iter = orphanBlocks.values().iterator();
            while (iter.hasNext()) {
                OrphanBlock orphanBlock = iter.next();
                // Look up the blocks previous.
                LiteBlock prev = getStoredBlockInCurrentScope(orphanBlock.block.getPrevBlockHash());
                if (prev == null) {
                    // This is still an unconnected/orphan block.
                    log.debug("Orphan block {} is not connectable right now", orphanBlock.block.getHash());
                    continue;
                }
                // Otherwise we can connect it now.
                // False here ensures we don't recurse infinitely downwards when connecting huge chains.
                log.info("Connected orphan {}", orphanBlock.block.getHash());
                add(orphanBlock.block, false);
                iter.remove();
                blocksConnectedThisRound++;
            }
            if (blocksConnectedThisRound > 0) {
                log.info("Connected {} orphan blocks.", blocksConnectedThisRound);
            }
        } while (blocksConnectedThisRound > 0);
    }

    /**
     * Returns the block at the head of the current best chain. This is the block which represents the greatest
     * amount of cumulative work done.
     */
    public LiteBlock getChainHead() {
        synchronized (chainHeadLock) {
            return chainHead;
        }
    }

    /**
     * An orphan block is one that does not connect to the chain anywhere (ie we can't find its parent, therefore
     * it's an orphan). Typically this occurs when we are downloading the chain and didn't reach the head yet, and/or
     * if a block is solved whilst we are downloading. It's possible that we see a small amount of orphan blocks which
     * chain together, this method tries walking backwards through the known orphan blocks to find the bottom-most.
     *
     * @return from or one of froms parents, or null if "from" does not identify an orphan block
     */
    @Nullable
    public LiteBlock getOrphanRoot(Sha256Hash from) {
        lock.lock();
        try {
            OrphanBlock cursor = orphanBlocks.get(from);
            if (cursor == null)
                return null;
            OrphanBlock tmp;
            while ((tmp = orphanBlocks.get(cursor.block.getPrevBlockHash())) != null) {
                cursor = tmp;
            }
            return cursor.block;
        } finally {
            lock.unlock();
        }
    }

    /** Returns true if the given block is currently in the orphan blocks list. */
    public boolean isOrphan(Sha256Hash block) {
        lock.lock();
        try {
            return orphanBlocks.containsKey(block);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns an estimate of when the given block will be reached, assuming a perfect 10 minute average for each
     * block. This is useful for turning transaction lock times into human readable times. Note that a height in
     * the past will still be estimated, even though the time of solving is actually known (we won't scan backwards
     * through the chain to obtain the right answer).
     */
    public Date estimateBlockTime(int height) {
        synchronized (chainHeadLock) {
            long offset = height - chainHead.getChainInfo().getHeight();
            long headTime = chainHead.getHeader().getTime();
            long estimated = (headTime * 1000) + (1000L * 60L * 10L * offset);
            return new Date(estimated);
        }
    }

    /**
     * Returns a future that completes when the block chain has reached the given height. Yields the
     * {@link LiteBlock} of the block that reaches that height first. The future completes on a peer thread.
     */
    public ListenableFuture<LiteBlock> getHeightFuture(final int height) {
        final SettableFuture<LiteBlock> result = SettableFuture.create();
        addNewBestBlockListener(Threading.SAME_THREAD, new NewBestBlockListener() {
            @Override
            public void notifyNewBestBlock(ChainInfoReadOnly chainInfo) throws VerificationException {
                if (chainInfo.getHeight() >= height) {
                    removeNewBestBlockListener(this);
                    result.set((LiteBlock) chainInfo.getHeader());
                }
            }
        });
        return result;
    }

    protected VersionTally getVersionTally() {
        return versionTally;
    }
}
