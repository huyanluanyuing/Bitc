import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.nio.ByteBuffer;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.layered.TFramedTransport;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

// 假设这些是项目依赖，用于生成和验证比特币区块头
import org.bitcoinj.base.Difficulty;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.Transaction;


public class Client {

	private static final int TOTAL_RUNS = 500;

	private static final String DEFAULT_HEX_TARGET = "1e290000";

	public static void main(String[] args) {
		if (args.length < 2 || args.length > 3) {
			System.err.println("Usage: java Client <host> <port> [hex_target]");
			System.err.println("Example: java Client 127.0.0.1 9090 " + DEFAULT_HEX_TARGET);
			System.exit(-1);
		}

		String host = args[0];
		int port = Integer.parseInt(args[1]);
		String hexTarget = (args.length == 3) ? args[2] : DEFAULT_HEX_TARGET;
		long compactTarget = Long.parseLong(hexTarget, 16);


		long totalTimeNanos = 0;
		int successfulRuns = 0;
		Random random = new Random();

		TTransport transport = null;

		try {

			TSocket sock = new TSocket(host, port);

			transport = new TFramedTransport(sock);
			TProtocol protocol = new TBinaryProtocol(transport);
			MiningPoolService.Client client = new MiningPoolService.Client(protocol);
			transport.open();

			System.out.println("Connected to FE server at " + host + ":" + port);
			System.out.println("Starting " + TOTAL_RUNS + " mining tasks with Target: " + hexTarget);


			for (int i = 1; i <= TOTAL_RUNS; i++) {
				//System.out.println("[Main] Starting mineBlock #" + i);


				int version = 1;
				byte[] bytes1 = new byte[32];
				random.nextBytes(bytes1);
				Sha256Hash prevBlockHash = Sha256Hash.wrap(bytes1);

				byte[] bytes2 = new byte[32];
				random.nextBytes(bytes2);
				Sha256Hash merkleRootHash = Sha256Hash.wrap(bytes2);

				Instant time = Instant.now().truncatedTo(ChronoUnit.SECONDS);
				Difficulty difficulty = Difficulty.ofCompact(compactTarget);


				ByteBuffer buf1 = ByteBuffer.wrap(bytes1);
				ByteBuffer buf2 = ByteBuffer.wrap(bytes2);


				List<Transaction> txns = new ArrayList<>();
				Block b = new Block(version, prevBlockHash, merkleRootHash, time, difficulty, 0, txns);
				// -----------------------------

				long nonce = -1;
				long startTime = System.nanoTime();

				try {

					nonce = client.mineBlock(version, buf1, buf2, time.getEpochSecond(), difficulty.compact());
					long endTime = System.nanoTime();


					if (nonce != -1) {
						totalTimeNanos += (endTime - startTime);
						successfulRuns++;


						b.setNonce(nonce);
						if (difficulty.isMetByWork(b.getHash())) {
							// System.out.println("[Main] ✅ Nonce " + nonce + " found and verified.");
						} else {
							System.out.println("[Main] ❌ Nonce " + nonce + " found but FAILED verification: hash > target.");
						}
					} else {
						// System.out.println("[Main] mineBlock #" + i + " returned nonce = -1 (not found/cancelled).");
					}

				} catch (TException e) {
					System.err.println("[Main] RPC call #" + i + " failed (TException): " + e.getMessage());
				} catch (Exception e) {
					System.err.println("[Main] RPC call #" + i + " failed (Exception): " + e.getMessage());
				}

				if (i % 10 == 0) {
					System.out.println("" + (i * 100 / TOTAL_RUNS) + "% complete (" + successfulRuns + " successful)");
				}
			}

		} catch (TException x) {
			System.err.println("Fatal TException: Could not connect or transport error.");
			x.printStackTrace();
		} finally {

			if (transport != null) {
				transport.close();
			}
		}


		System.out.println("\n=================================================");
		System.out.println("(Target: " + hexTarget + "):");
		System.out.println("" + TOTAL_RUNS);
		System.out.println("" + successfulRuns);

		if (successfulRuns > 0) {
			long totalTimeMs = totalTimeNanos / 1_000_000;


			float throughputBlocksPerSecond = (float) successfulRuns * 1000f / totalTimeMs;


			float averageLatencyMs = (float) totalTimeMs / successfulRuns;

			System.out.printf("Throughput (blocks/s) for %d successful runs: %.3f\n", successfulRuns, throughputBlocksPerSecond);
			System.out.printf("Latency (ms/block)      for %d successful runs: %.3f\n", successfulRuns, averageLatencyMs);
		} else {
			System.out.println("");
		}
		System.out.println("=================================================");
	}
}