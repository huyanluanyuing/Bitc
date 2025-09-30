import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.nio.ByteBuffer;

import org.apache.log4j.Logger;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.layered.TFramedTransport;

/**
 * FEServiceHandler — front-end handler that dispatches mining tasks to FE local
 * threads and to up to two BE servers. It uses a GlobalMiningCoordinator to
 * broadcast/observe stop condition.
 */
public class FEServiceHandler implements MiningPoolService.Iface {

    private static Logger log = Logger.getLogger(FEServiceHandler.class.getName());

    // BE 服务器信息列表
    private final List<BEServerInfo> beServers;

    // FE 自己的核心数
    private final int feCores;

    // 全局停止协调器
    private final GlobalMiningCoordinator coordinator;

    // 内部类：BE 服务器信息
    private static class BEServerInfo {
        String host;
        int port;
        int numCores;

        BEServerInfo(String host, int port, int numCores) {
            this.host = host;
            this.port = port;
            this.numCores = numCores;
        }

        @Override
        public String toString() {
            return host + ":" + port + "(" + numCores + " cores)";
        }
    }

    // 全局挖矿协调器
    private class GlobalMiningCoordinator implements MiningPoolServiceHandler.GlobalStopChecker {
        private final AtomicBoolean globalStop = new AtomicBoolean(false);
        private final AtomicLong globalFoundNonce = new AtomicLong(-1);

        public void reset() {
            globalStop.set(false);
            globalFoundNonce.set(-1);
        }

        public void setFound(long nonce) {
            globalFoundNonce.compareAndSet(-1, nonce);
            globalStop.set(true);
        }

        @Override
        public boolean shouldStop() {
            return globalStop.get();
        }

        @Override
        public long getFoundNonce() {
            return globalFoundNonce.get();
        }
    }

    public FEServiceHandler(int feCores) {
        this.beServers = new ArrayList<>();
        this.feCores = feCores;
        this.coordinator = new GlobalMiningCoordinator();
        log.info("FE initialized with " + feCores + " cores");
    }

    @Override
    public synchronized void registerBE(String host, int port, int numCores) throws IllegalArgument, TException {
        BEServerInfo newBE = new BEServerInfo(host, port, numCores);

        try {
            log.info("Verifying BE server: " + newBE);
            MiningPoolService.Client client = connectToBE(host, port);
            if (client.getInputProtocol().getTransport().isOpen()) {
                client.getInputProtocol().getTransport().close();
            }

            beServers.add(newBE);
            log.info("✓ Successfully registered BE server: " + newBE + " (total: " + beServers.size() + ")");

        } catch (Exception e) {
            log.error("✗ Failed to verify BE server " + newBE + ": " + e.getMessage());
            throw new IllegalArgument("Cannot connect to BE server at " + host + ":" + port);
        }
    }

    @Override
    public long mineBlock(int version, ByteBuffer prevBlockHash, ByteBuffer merkleRootHash, long time, long target)
            throws IllegalArgument, TException {

        log.info("========================================");
        log.info("Received mining request from client");

        // Reset coordinator
        coordinator.reset();

        // Compute total cores and select up to 2 BEs
        int totalCores = feCores;
        List<BEServerInfo> availableBEs = new ArrayList<>();

        synchronized (this) {
            int numBEs = Math.min(2, beServers.size());
            for (int i = 0; i < numBEs; i++) {
                availableBEs.add(beServers.get(i));
                totalCores += beServers.get(i).numCores;
            }
        }

        log.info("Total cores: " + totalCores + " (FE: " + feCores + ", BEs: " + (totalCores - feCores) + ")");

        // Define search range (note: for local testing you may want to reduce this)
        long totalRange = 100000000L; // <= Integer.MAX_VALUE to avoid int overflow issues in testing
        long rangePerCore = Math.max(1L, totalRange / Math.max(1, totalCores));

        // Partition ranges
        long currentStart = 0;
        long feStart = currentStart;
        long feEnd = currentStart + rangePerCore * feCores;
        currentStart = feEnd;

        log.info("Range allocation:");
        log.info("  FE: [" + feStart + ", " + feEnd + ") - " + feCores + " cores");

        // Executor and bookkeeping
        ExecutorService executor = Executors.newFixedThreadPool(1 + Math.max(0, availableBEs.size()));
        List<Future<Long>> futures = new ArrayList<>();
        List<MiningPoolService.Client> clients = new CopyOnWriteArrayList<>();

        try {
            // FE local mining task (wrap buffers with independent copies)
            final long feFinalStart = feStart;
            final long feFinalEnd = feEnd;
            final ByteBuffer fePrevCopy = cloneBufferSafe(prevBlockHash);
            final ByteBuffer feMerkleCopy = cloneBufferSafe(merkleRootHash);

            Future<Long> feFuture = executor.submit(() -> {
                try {
                    log.info("FE local mining started: [" + feFinalStart + ", " + feFinalEnd + ")");
                    long nonce = mineLocally(version, fePrevCopy, feMerkleCopy, time, target, feFinalStart, feFinalEnd,
                            feCores);

                    if (nonce != -1) {
                        coordinator.setFound(nonce);
                        log.info("★★★ FE found nonce: " + nonce + " ★★★");
                    }
                    return nonce;
                } catch (Exception e) {
                    log.error("FE mining error: " + e.getMessage());
                    return -1L;
                }
            });
            futures.add(feFuture);

            // Prepare BE tasks
            for (int i = 0; i < availableBEs.size(); i++) {
                final BEServerInfo beInfo = availableBEs.get(i);
                final long beStart = currentStart;
                final long beEnd = currentStart + rangePerCore * beInfo.numCores;
                currentStart = beEnd;
                final int beIndex = i;

                log.info("  BE[" + beIndex + "] " + beInfo + ": [" + beStart + ", " + beEnd + ")");

                // Create independent ByteBuffer copies for RPC
                final ByteBuffer prevHashCopy = cloneBufferSafe(prevBlockHash);
                final ByteBuffer merkleCopy = cloneBufferSafe(merkleRootHash);

                log.info("  BE[" + beIndex + "] buffer check: prevHash.remaining=" + prevHashCopy.remaining()
                        + ", merkle.remaining=" + merkleCopy.remaining());

                Future<Long> beFuture = executor.submit(() -> {
                    MiningPoolService.Client beClient = null;
                    try {
                        beClient = connectToBE(beInfo.host, beInfo.port);
                        clients.add(beClient);

                        log.info("BE[" + beIndex + "] " + beInfo + " started mining");
                        long startTime = System.currentTimeMillis();

                        // RPC call to BE
                        long nonce = beClient.mineBlockInRange(version, prevHashCopy, merkleCopy, time, target, beStart,
                                beEnd, beInfo.numCores);

                        if (nonce != -1) {
                            coordinator.setFound(nonce);
                            long elapsed = System.currentTimeMillis() - startTime;
                            log.info("★★★ BE[" + beIndex + "] found nonce: " + nonce + " in " + elapsed + "ms ★★★");
                        }

                        return nonce;

                    } catch (Exception e) {
                        if (!coordinator.shouldStop()) {
                            log.error("BE[" + beIndex + "] failed: " + e.getMessage());
                        }
                        return -1L;
                    } finally {
                        if (beClient != null) {
                            try {
                                beClient.getInputProtocol().getTransport().close();
                            } catch (Exception e) {
                                // ignore
                            }
                        }
                    }
                });

                futures.add(beFuture);
            }

            log.info("All mining tasks submitted, waiting for first result...");

            // Monitor tasks, stop when someone finds nonce or coordinator indicates stop
            long result = -1;
            long startTime = System.currentTimeMillis();

            while (!coordinator.shouldStop() && result == -1) {
                for (int i = 0; i < futures.size(); i++) {
                    Future<Long> future = futures.get(i);
                    if (future != null && future.isDone()) {
                        try {
                            long nonce = future.get(10, TimeUnit.MILLISECONDS);
                            if (nonce != -1) {
                                result = nonce;
                                coordinator.setFound(nonce);
                                break;
                            }
                        } catch (ExecutionException e) {
                            // task failed — ignore here
                        } catch (TimeoutException e) {
                            // nothing
                        }
                    }
                }
                if (result == -1) {
                    Thread.sleep(50);
                }
            }

            // If coordinator already set found, use it
            if (result == -1) {
                result = coordinator.getFoundNonce();
            }

            long elapsed = System.currentTimeMillis() - startTime;

            log.info("========================================");
            log.info("✓ Mining completed in " + elapsed + "ms");
            log.info("  Found nonce: " + result);
            log.info("========================================");

            // Notify all BE to stop
            log.info("Sending cancel to all BE servers...");
            for (MiningPoolService.Client client : clients) {
                try {
                    if (client.getInputProtocol().getTransport().isOpen()) {
                        client.cancel();
                    }
                } catch (Exception e) {
                    // ignore errors while notifying
                }
            }

            // Cancel remaining futures
            for (Future<Long> future : futures) {
                if (future != null && !future.isDone()) {
                    future.cancel(true);
                }
            }

            if (result == -1) {
                throw new IllegalArgument("Mining failed: no nonce found");
            }

            return result;

        } catch (IllegalArgument e) {
            throw e;
        } catch (Exception e) {
            log.error("Error during distributed mining", e);
            throw new IllegalArgument("Mining failed: " + e.getMessage());
        } finally {
            executor.shutdownNow();

            for (MiningPoolService.Client client : clients) {
                try {
                    if (client.getInputProtocol().getTransport().isOpen()) {
                        client.getInputProtocol().getTransport().close();
                    }
                } catch (Exception e) {
                    // ignore
                }
            }
        }
    }

    @Override
    public void cancel() {
        log.info("Cancel request received at FE");
        // mark coordinator as stopped; using setFound(-1) would set stop but also overwrite found nonce — keep stop only:
        coordinator.reset();
        // set a stop flag to interrupt running tasks (use setFound with -2 to indicate client-cancel if you want)
        // simpler: call coordinator.setFound(-1) to signal stop (it sets globalStop true)
        coordinator.setFound(-1);
    }

    // FE 本地挖矿
    private long mineLocally(int version, ByteBuffer prevBlockHash, ByteBuffer merkleRootHash, long time, long target,
            long startNonce, long endNonce, int numThreads) throws IllegalArgument, TException {
        log.info("FE local mining with " + numThreads + " threads in range [" + startNonce + ", " + endNonce + ")");

        // 为本地挖矿创建独立副本
        ByteBuffer prevHashCopy = cloneBufferSafe(prevBlockHash);
        ByteBuffer merkleCopy = cloneBufferSafe(merkleRootHash);

        // 创建本地 Handler 并设置全局协调器
        MiningPoolServiceHandler localHandler = new MiningPoolServiceHandler();
        localHandler.setGlobalStopChecker(coordinator);

        return localHandler.mineBlockInRange(version, prevHashCopy, merkleCopy, time, target, startNonce, endNonce,
                numThreads);
    }

    // 连接到 BE
    private MiningPoolService.Client connectToBE(String host, int port) throws TException {
        TSocket sock = new TSocket(host, port);
        sock.setTimeout(300000);
        TTransport transport = new TFramedTransport(sock);
        TProtocol protocol = new TBinaryProtocol(transport);
        MiningPoolService.Client client = new MiningPoolService.Client(protocol);
        transport.open();
        return client;
    }

    // 克隆 ByteBuffer（线程安全，完全独立的副本，假设 input is exactly 32 bytes long）
    private ByteBuffer cloneBufferSafe(ByteBuffer original) {
        byte[] data;
        synchronized (original) {
            int pos = original.position();
            int rem = original.remaining();
            data = new byte[rem];
            original.get(data);
            original.position(pos);
        }
        return ByteBuffer.wrap(data);
    }

    // 克隆 ByteBuffer（线程安全）
    @SuppressWarnings("unused")
    private synchronized ByteBuffer cloneBuffer(ByteBuffer original) {
        int originalPosition = original.position();
        original.rewind();
        ByteBuffer clone = ByteBuffer.allocate(original.remaining());
        clone.put(original);
        clone.flip();
        original.position(originalPosition);
        return clone;
    }
    @Override
    public long mineBlockInRange(int version, ByteBuffer prevBlockHash, ByteBuffer merkleRootHash,
                                 long time, long target, long startNonce, long endNonce, int numThreads)
            throws IllegalArgument, org.apache.thrift.TException {
                return -1;
            }

}
