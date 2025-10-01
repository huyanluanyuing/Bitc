import java.util.ArrayList;
import java.util.List;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.log4j.Logger;

import org.apache.thrift.TException;
import org.bitcoinj.base.Difficulty;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.Transaction;

public class MiningPoolServiceHandler implements MiningPoolService.Iface {

    private static Logger log = Logger.getLogger(MiningPoolServiceHandler.class.getName());

    // 每个 Handler 实例的取消标志
    private volatile AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile AtomicLong foundNonce = new AtomicLong(-1);

    @Override
    public long mineBlock(int version, java.nio.ByteBuffer prevBlockHash, java.nio.ByteBuffer merkleRootHash, long time, long target) throws IllegalArgument, org.apache.thrift.TException {
        return 43;
    }

    @Override
    public long mineBlockInRange(int version, ByteBuffer prevBlockHash, ByteBuffer merkleRootHash,
                                 long time, long target, long startNonce, long endNonce, int numThreads)
            throws IllegalArgument, org.apache.thrift.TException {

        // 重置状态
        cancelled.set(false);
        foundNonce.set(-1);

        log.info("Starting mining: range [" + startNonce + ", " + endNonce + ") with " + numThreads + " threads");

        // 参数验证
        if (prevBlockHash == null || prevBlockHash.remaining() != 32) {
            throw new IllegalArgument("prevBlockHash must be 32 bytes");
        }
        if (merkleRootHash == null || merkleRootHash.remaining() != 32) {
            throw new IllegalArgument("merkleRootHash must be 32 bytes");
        }
        if (startNonce >= endNonce) {
            throw new IllegalArgument("Invalid range: startNonce >= endNonce");
        }

        // 将 ByteBuffer 转换为字节数组
        byte[] prevHashBytes = new byte[32];
        prevBlockHash.get(prevHashBytes);
        Sha256Hash prevHash = Sha256Hash.wrap(prevHashBytes);

        byte[] merkleBytes = new byte[32];
        merkleRootHash.get(merkleBytes);
        Sha256Hash merkleHash = Sha256Hash.wrap(merkleBytes);

        // 创建时间戳和难度
        Instant timestamp = Instant.ofEpochSecond(time);
        Difficulty difficulty = Difficulty.ofCompact(target);

        // 计算每个线程的搜索范围
        long rangeSize = endNonce - startNonce;
        long rangePerThread = rangeSize / numThreads;

        // 创建线程池
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        List<Future<Long>> futures = new ArrayList<>();

        try {
            // 为每个线程分配搜索范围
            for (int i = 0; i < numThreads; i++) {
                final long threadStart = startNonce + i * rangePerThread;
                final long threadEnd = (i == numThreads - 1) ? endNonce : (threadStart + rangePerThread);
                final int threadId = i;

                Future<Long> future = executor.submit(new Callable<Long>() {
                    @Override
                    public Long call() throws Exception {
                        return mineInRange(version, prevHash, merkleHash, timestamp, difficulty,
                                threadStart, threadEnd, threadId);
                    }
                });

                futures.add(future);
            }

            while (foundNonce.get() == -1 && !cancelled.get()) { // foundNonce=-1 没找到cancelled
                int doneSize = 0;
                for (Future<Long> future : futures) {
                    if (future.isDone()) {
                        doneSize++;
                        try {
                            // 返回运行结果
                            long nonce = future.get(10, TimeUnit.MILLISECONDS);
                            // 找到
                            if (nonce != -1) {
                                foundNonce.set(nonce);
                                log.info("Found nonce: " + nonce);
                                break;
                            }
                        } catch (ExecutionException e) {
                            log.warn("Thread failed: " + e.getMessage());
                        } catch (TimeoutException e) {
                            // 继续等待
                        }
                    }
                }
                // 全部线程结束了
                if (doneSize == futures.size()) {
                    break;
                }
                if (foundNonce.get() == -1) {
                    Thread.sleep(10);
                }
            }
            // 取消所有线程
            cancelled.set(true);
            for (Future<Long> future : futures) {
                future.cancel(true);
            }

            if (foundNonce.get() == -1) {
                throw new IllegalArgument("Mining cancelled");
            }

            return foundNonce.get();

        } catch (IllegalArgument e) {
            throw e;
        } catch (Exception e) {
            log.error("Mining failed: " + e.getMessage(), e);
            throw new IllegalArgument("Mining failed: " + e.getMessage());
        } finally {
            executor.shutdownNow();
        }
    }
    // 单个线程在范围内挖矿
    private long mineInRange(int version, Sha256Hash prevHash, Sha256Hash merkleHash,
                             Instant timestamp, Difficulty difficulty,
                             long startNonce, long endNonce, int threadId) {

        log.info("Thread[" + threadId + "] searching range [" + startNonce + ", " + endNonce + ")");

        List<Transaction> txns = null;
        Block block = new Block(version, prevHash, merkleHash, timestamp, difficulty, startNonce, txns);

        long nonce = startNonce;
        long lastLog = System.currentTimeMillis();

        while (nonce < endNonce  && foundNonce.get() == -1 && !cancelled.get()) {
            block.setNonce(nonce);

            if (difficulty.isMetByWork(block.getHash())) {
                log.info("Thread[" + threadId + "] ★★★ FOUND nonce: " + nonce + " ★★★");
                foundNonce.compareAndSet(-1, nonce);
                return nonce;
            }

            nonce++;

            // 定期日志（每5秒）
            long now = System.currentTimeMillis();
            if (now - lastLog > 5000) {
                long progress = nonce - startNonce;
                long total = endNonce - startNonce;
                log.info("Thread[" + threadId + "] progress: " + progress + "/" + total +
                        " (" + (100 * progress / total) + "%)");
                lastLog = now;
            }
        }

        if (cancelled.get()) {
            log.info("Thread[" + threadId + "] stopped by global signal");
        } else if (foundNonce.get() != -1) {
            log.info("Thread[" + threadId + "] stopped (another thread found nonce)");
        } else {
            log.info("Thread[" + threadId + "] exhausted range without finding nonce");
        }

        return -1;
    }

    @Override
    public void cancel() {
        log.info("Cancel being called from FEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEEE");
        cancelled.set(true);
    }
    @Override
    public  void registerBE(String host, int port, int numCores) throws IllegalArgument, TException {

    }
}