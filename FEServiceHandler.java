import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.nio.ByteBuffer;
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


    MiningPoolServiceHandler localHandler=null;


    private final List<BEServerInfo> beServers;

    List<MiningPoolService.Client> clients = new ArrayList<>();

    List<MiningPoolService.Client> mining = new ArrayList<>();

    private final AtomicBoolean globalStop = new AtomicBoolean(false);

    private final AtomicLong globalFoundNonce = new AtomicLong(-1);

    private volatile CountDownLatch resultLatch = null;

    private final int feCores;


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


    public FEServiceHandler(int feCores) {
        this.beServers = new ArrayList<>();
        this.feCores = feCores;
    }

    @Override
    public synchronized void registerBE(String host, int port, int numCores) throws IllegalArgument, TException {
        BEServerInfo newBE = new BEServerInfo(host, port, numCores);
        MiningPoolService.Client client = connectToBE(host, port);
        MiningPoolService.Client client1 = connectToBE(host, port);
        beServers.add(newBE);
        clients.add(client);
        mining.add(client1);
//        try {
//
//
//
//        } catch (Exception e) {
//            throw new IllegalArgument("Cannot connect to BE server at " + host + ":" + port);
//        }
    }

    @Override
    public long mineBlock(int version, ByteBuffer prevBlockHash, ByteBuffer merkleRootHash, long time, long target)
            throws IllegalArgument, TException {

        final long MAX_TARGET = 0x207fffffL;
        final long MIN_TARGET = 0x1d00ffffL;

        if (target > MAX_TARGET) {
            String msg = String.format(
                    "Target 0x%X is too high. Max allowed target is 0x%X.", target, MAX_TARGET
            );
            throw new IllegalArgument(msg);
        }

        if (target < MIN_TARGET) {
            String msg = String.format(
                    "Target 0x%X is too low. Min allowed target is 0x%X.", target, MIN_TARGET
            );
            throw new IllegalArgument(msg);
        }

        // Reset coordinator
        globalStop.set(false);
        globalFoundNonce.set(-1);
        resultLatch = new CountDownLatch(1);

        // Compute total cores and select up to 2 BEs
        int totalCores = feCores;
        int numBEs = Math.min(2, beServers.size());
        for (int i = 0; i < numBEs; i++) {
            totalCores += beServers.get(i).numCores;
        }

        // Define search range
        long totalRange = 1200000000L;
        long rangePerCore = Math.max(1L, totalRange / Math.max(1, totalCores));

        // partition ranges
        long currentStart = 0;
        long feStart = currentStart;
        long feEnd = currentStart + rangePerCore * feCores;
        currentStart = feEnd;

        // executor and bookkeeping
        ExecutorService executor = Executors.newFixedThreadPool(1 + Math.max(0, beServers.size()));
        List<Future<Long>> futures = new ArrayList<>();


        try {
            // fe local mining task (wrap buffers with independent copies)
            final long feFinalStart = feStart;
            final long feFinalEnd = feEnd;
            final ByteBuffer fePrevCopy = cloneBufferSafe(prevBlockHash);
            final ByteBuffer feMerkleCopy = cloneBufferSafe(merkleRootHash);


            Future<Long> feFuture = executor.submit(() -> {
                try {
                    long nonce = mineLocally(version, fePrevCopy, feMerkleCopy, time, target, feFinalStart, feFinalEnd,
                            feCores);

                    if (nonce != -1 && globalFoundNonce.compareAndSet(-1, nonce)) {
                        resultLatch.countDown();
                        globalStop.set(true);
                    }
                    return nonce;
                } catch (Exception e) {
                    return -1L;
                }
            });
            futures.add(feFuture);
            int i=0;
            for (MiningPoolService.Client beClient : mining) {
                final BEServerInfo beInfo = beServers.get(i);
                final long beStart = currentStart;
                final long beEnd = currentStart + rangePerCore * beInfo.numCores;
                currentStart = beEnd;
                // Create independent ByteBuffer copies for RPC
                final ByteBuffer prevHashCopy = cloneBufferSafe(prevBlockHash);
                final ByteBuffer merkleCopy = cloneBufferSafe(merkleRootHash);
                Future<Long> beFuture = executor.submit(() -> {
                    try {
                        //synchronized (beClient){
                        if(!beClient.getInputProtocol().getTransport().isOpen()){
                            //System.out.println("reopen");
                            beClient.getInputProtocol().getTransport().open();
                        }
                        //beClient = this.clients.get(beIndex);
                        //beClient = connectToBE(beInfo.host, beInfo.port);
                        // RPC call to BE
                        long nonce = beClient.mineBlockInRange(version, prevHashCopy, merkleCopy, time, target, beStart,
                                beEnd, beInfo.numCores);

                        if (nonce != -1 && globalFoundNonce.compareAndSet(-1, nonce)) {
                            resultLatch.countDown();
                            globalStop.set(true);
                            return nonce;
                        }
                        return nonce;
                        // }
                    } catch (Exception e) {
                        return -1L;
                    }
                });
                i++;
                futures.add(beFuture);
            }

            // Prepare BE tasks
//            for (int i = 0; i < beServers.size(); i++) {
//                final BEServerInfo beInfo = beServers.get(i);
//                final long beStart = currentStart;
//                final long beEnd = currentStart + rangePerCore * beInfo.numCores;
//                currentStart = beEnd;
//                final int beIndex = i;
//                // Create independent ByteBuffer copies for RPC
//                final ByteBuffer prevHashCopy = cloneBufferSafe(prevBlockHash);
//                final ByteBuffer merkleCopy = cloneBufferSafe(merkleRootHash);
//                Future<Long> beFuture = executor.submit(() -> {
//                    MiningPoolService.Client beClient = null;
//                    try {
//                        beClient = this.mining.get(beIndex);
//                        //synchronized (beClient){
//                        if(!beClient.getInputProtocol().getTransport().isOpen()){
//                            System.out.println("reopen");
//                            beClient.getInputProtocol().getTransport().open();
//                        }
//                        //beClient = this.clients.get(beIndex);
//                        //beClient = connectToBE(beInfo.host, beInfo.port);
//                        // RPC call to BE
//                        long nonce = beClient.mineBlockInRange(version, prevHashCopy, merkleCopy, time, target, beStart,
//                                beEnd, beInfo.numCores);
//
//                        if (nonce != -1 && globalFoundNonce.compareAndSet(-1, nonce)) {
//                            resultLatch.countDown();
//                            globalStop.set(true);
//                            return nonce;
//                        }
//                        return nonce;
//                        // }
//                    } catch (Exception e) {
//                        return -1L;
//                    }
//                });
//                futures.add(beFuture);
//            }

            // Monitor tasks, stop when someone finds nonce or coordinator indicates stop
            long result = -1;
            try {
                resultLatch.await(120, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cancel();
            globalStop.set(true);
            result = globalFoundNonce.get();
            // Cancel remaining futures
            for (Future<Long> future : futures) {
                if (future != null) {
                    future.cancel(true);
                }
            }
            return result;

        } catch (Exception e) {

        } finally {
            executor.shutdownNow();
        }
        return -1;
    }


    private long mineLocally(int version, ByteBuffer prevBlockHash, ByteBuffer merkleRootHash, long time, long target,
                             long startNonce, long endNonce, int numThreads) throws IllegalArgument, TException {

        localHandler = new MiningPoolServiceHandler();
        return localHandler.mineBlockInRange(version, prevBlockHash, merkleRootHash, time, target, startNonce, endNonce,
                numThreads);
    }
    private MiningPoolService.Client connectToBE(String host, int port) throws TException {
        TSocket sock = new TSocket(host, port);
        // try {
        //     sock.getSocket().setKeepAlive(true);
        // } catch (SocketException e) {
        //     throw new RuntimeException(e);
        // }
        // sock.setTimeout(30000000);
        TTransport transport = new TFramedTransport(sock);
        TProtocol protocol = new TBinaryProtocol(transport);
        MiningPoolService.Client client = new MiningPoolService.Client(protocol);
        transport.open();
        return client;
    }
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
    @Override
    public long mineBlockInRange(int version, ByteBuffer prevBlockHash, ByteBuffer merkleRootHash,
                                 long time, long target, long startNonce, long endNonce, int numThreads)
            throws IllegalArgument, org.apache.thrift.TException {
        return -1;
    }
    @Override
    public void cancel() throws TException {
        globalStop.set(true);
        if (resultLatch != null) {
            resultLatch.countDown();
        }
        if(localHandler!=null){
            localHandler.cancel();
            localHandler=null;
        }
        for (MiningPoolService.Client client : clients) {
            client.cancel();
        }
//        for (BEServerInfo be:beServers){
//            TSocket sock = new TSocket(be.host, be.port);
//
//            TTransport transport = new TFramedTransport(sock);
//            TProtocol protocol = new TBinaryProtocol(transport);
//            MiningPoolService.Client client = new MiningPoolService.Client(protocol);
//            transport.open();
//            client.cancel();
//            transport.close();
//        }
    }
}