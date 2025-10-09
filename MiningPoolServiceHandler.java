import java.util.ArrayList;
import java.util.List;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.thrift.TException;
import org.bitcoinj.base.Difficulty;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.Transaction;

public class MiningPoolServiceHandler implements MiningPoolService.Iface {


    private volatile AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile AtomicLong foundNonce = new AtomicLong(-1);


    private volatile CountDownLatch completionLatch = null;

    @Override
    public long mineBlock(int version, java.nio.ByteBuffer prevBlockHash, java.nio.ByteBuffer merkleRootHash, long time, long target) throws IllegalArgument, org.apache.thrift.TException {
        return 43;
    }

    @Override
    public long mineBlockInRange(int version, ByteBuffer prevBlockHash, ByteBuffer merkleRootHash,
                                 long time, long target, long startNonce, long endNonce, int numThreads)
            throws IllegalArgument, org.apache.thrift.TException {


        cancelled.set(false);
        foundNonce.set(-1);
        completionLatch = new CountDownLatch(1);


        byte[] prevHashBytes = new byte[32];
        prevBlockHash.get(prevHashBytes);
        Sha256Hash prevHash = Sha256Hash.wrap(prevHashBytes);

        byte[] merkleBytes = new byte[32];
        merkleRootHash.get(merkleBytes);
        Sha256Hash merkleHash = Sha256Hash.wrap(merkleBytes);


        Instant timestamp = Instant.ofEpochSecond(time);
        Difficulty difficulty = Difficulty.ofCompact(target);


        long rangeSize = endNonce - startNonce;
        long rangePerThread = rangeSize / numThreads;


        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        List<Future<Long>> futures = new ArrayList<>();

        try {

            for (int i = 0; i < numThreads; i++) {
                final long threadStart = startNonce + i * (endNonce - startNonce) / numThreads;
                final long threadEnd = (i == numThreads - 1) ? endNonce : (threadStart + (endNonce - startNonce) / numThreads);
                final int threadId = i;

                futures.add(executor.submit(() -> mineInRange(version, prevHash, merkleHash, timestamp, difficulty,
                        threadStart, threadEnd, threadId)));
            }


            try {
                completionLatch.await(120, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
            }


            for (Future<Long> f : futures) {
                f.cancel(true);
            }
            return foundNonce.get();

        } finally {
            executor.shutdownNow();
        }
    }


    private long mineInRange(int version, Sha256Hash prevHash, Sha256Hash merkleHash,
                             Instant timestamp, Difficulty difficulty,
                             long startNonce, long endNonce, int threadId) {

        List<Transaction> txns = null;
        Block block = new Block(version, prevHash, merkleHash, timestamp, difficulty, startNonce, txns);

        long nonce = startNonce;
        long lastLog = System.currentTimeMillis();

        while (nonce < endNonce  && foundNonce.get() == -1 && !cancelled.get()) {
            block.setNonce(nonce);

            if (difficulty.isMetByWork(block.getHash())) {
                if (foundNonce.compareAndSet(-1, nonce)) {
                    completionLatch.countDown();
                }
                return nonce;
            }

            nonce++;
        }
        return -1;
    }

    @Override
    public void cancel() {
        cancelled.set(true);
        if (completionLatch != null) {
            completionLatch.countDown();
        }
    }
    @Override
    public  void registerBE(String host, int port, int numCores) throws IllegalArgument, TException {

    }
}