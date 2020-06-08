package org.bitcoinj.msg.bitcoin;

import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Utils;
import org.bitcoinj.core.VarInt;
import org.bitcoinj.utils.Threading;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

import static org.bitcoinj.core.Sha256Hash.hashTwice;

public class FullBlockBean extends BitcoinObjectImpl<FullBlock> implements FullBlock, MerkleRootProvider {

    private HeaderBean header;

    private List<Tx> transactions;

    public FullBlockBean(byte[] payload, int offset) {
        super(null, payload, offset);
    }

    public FullBlockBean(byte[] payload) {
        super(null, payload, 0);
    }

    @Override
    public HeaderBean getHeader() {
        return header;
    }

    @Override
    public void setHeader(HeaderBean header) {
        checkMutable();
        this.header = header;
    }

    @Override
    public Sha256Hash getHash() {
        return header.getHash();
    }

    @Override
    public List<Tx> getTransactions() {
        return isMutable() ? transactions : Collections.unmodifiableList(transactions);
    }

    @Override
    public void setTransactions(List<Tx> transactions) {
        checkMutable();
        this.transactions = transactions;
    }

    @Override
    protected void parse() {

        header = new HeaderBean(this, payload, offset);
        cursor += header.getMessageSize();

        int numTransactions = (int) readVarInt();
        transactions = new ArrayList<>(numTransactions);
        CountDownLatch hashLatch = new CountDownLatch(numTransactions);

        for (int i = 0; i < numTransactions; i++) {
            TxBean tx = new TxBean(this, payload, cursor);

            transactions.add(tx);
            cursor += tx.getMessageSize();

            //we are doing batches of hashes so we can take advantage of multi threading
            Threading.THREAD_POOL.execute(new Runnable() {
                @Override
                public void run() {
                    //calculates hash and caches it, this is 99% of the runtime

                    //tx.getHash();


                    // Label the transaction as coming from the P2P network, so code that cares where we first saw it knows
                    // This holds references to txHash which may not be cleared automagically.
                    //tx.getConfidence().setSource(TransactionConfidence.Source.NETWORK);
                    hashLatch.countDown();
                }
            });
        }
        try {
            ExecutorService exec = Threading.THREAD_POOL;
            long awaiting = hashLatch.getCount();
            hashLatch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void serializeTo(OutputStream stream) throws IOException {
        header.serializeTo(stream);
        stream.write(new VarInt(transactions.size()).encode());
        for (Tx tx : transactions) {
            tx.serializeTo(stream);
        }
    }

    @Override
    public FullBlock makeNew(byte[] serialized) {
        return new FullBlockBean(serialized);
    }

    @Override
    public void makeSelfMutable() {
        super.makeSelfMutable();
        header.makeSelfMutable(); //also nulls block hash
        header.setMerkleRoot(null); //needs to be nulled in case txs change.
        for (Tx tx: getTransactions())
            tx.makeSelfMutable();
    }

    /**
     * This is here for early testing, we'll replace it with something more efficient.
     * @return
     */

    @Override
    public Sha256Hash calculateMerkleRoot() {
        List<byte[]> tree = buildMerkleTree();
        return Sha256Hash.wrap(tree.get(tree.size() - 1));
    }

    private List<byte[]> buildMerkleTree() {
        // The Merkle root is based on a tree of hashes calculated from the transactions:
        //
        //     root
        //      / \
        //   A      B
        //  / \    / \
        // t1 t2 t3 t4
        //
        // The tree is represented as a list: t1,t2,t3,t4,A,B,root where each
        // entry is a hash.
        //
        // The hashing algorithm is double SHA-256. The leaves are a hash of the serialized contents of the transaction.
        // The interior nodes are hashes of the concenation of the two child hashes.
        //
        // This structure allows the creation of proof that a transaction was included into a block without having to
        // provide the full block contents. Instead, you can provide only a Merkle branch. For example to prove tx2 was
        // in a block you can just provide tx2, the hash(tx1) and B. Now the other party has everything they need to
        // derive the root, which can be checked against the block header. These proofs aren't used right now but
        // will be helpful later when we want to download partial block contents.
        //
        // Note that if the number of transactions is not even the last tx is repeated to make it so (see
        // tx3 above). A tree with 5 transactions would look like this:
        //
        //         root
        //        /     \
        //       1        5
        //     /   \     / \
        //    2     3    4  4
        //  / \   / \   / \
        // t1 t2 t3 t4 t5 t5
        ArrayList<byte[]> tree = new ArrayList<byte[]>();
        // Start by adding all the hashes of the transactions as leaves of the tree.
        for (Tx t : transactions) {
            tree.add(t.calculateHash().getBytes());
        }
        int levelOffset = 0; // Offset in the list where the currently processed level starts.
        // Step through each level, stopping when we reach the root (levelSize == 1).
        for (int levelSize = transactions.size(); levelSize > 1; levelSize = (levelSize + 1) / 2) {
            // For each pair of nodes on that level:
            for (int left = 0; left < levelSize; left += 2) {
                // The right hand node can be the same as the left hand, in the case where we don't have enough
                // transactions.
                int right = Math.min(left + 1, levelSize - 1);
                byte[] leftBytes = Utils.reverseBytes(tree.get(levelOffset + left));
                byte[] rightBytes = Utils.reverseBytes(tree.get(levelOffset + right));
                tree.add(Utils.reverseBytes(hashTwice(leftBytes, 0, 32, rightBytes, 0, 32)));
            }
            // Move to the next level.
            levelOffset += levelSize;
        }
        return tree;
    }
}
