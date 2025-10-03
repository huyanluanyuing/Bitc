import java.util.List;
import java.util.Random;
import java.nio.ByteBuffer;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.layered.TFramedTransport;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.bitcoinj.base.Difficulty;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.Transaction;

public class Client {
	public static void main(String[] args) {
		if (args.length != 2) {
			System.err.println("Usage: java Client host port");
			System.exit(-1);
		}

		try {
			// 建立连接
			TSocket sock = new TSocket(args[0], Integer.parseInt(args[1]));
			TTransport transport = new TFramedTransport(sock);
			TProtocol protocol = new TBinaryProtocol(transport);
			MiningPoolService.Client client = new MiningPoolService.Client(protocol);
			transport.open();


			// 启动 cancel 线程：15s 后调用一次 cancel
			Thread cancelThread = new Thread(() -> {
				try {
					while(true){
						Thread.sleep(15000);
						try (TSocket cancelSock = new TSocket(args[0], Integer.parseInt(args[1]))) {
							TTransport cancelTransport = new TFramedTransport(cancelSock);
							TProtocol cancelProtocol = new TBinaryProtocol(cancelTransport);
							MiningPoolService.Client cancelClient = new MiningPoolService.Client(cancelProtocol);
							cancelTransport.open();
							cancelClient.cancel();
							System.out.println("[CancelThread] Sending cancel()1111111111111111111111111");
							cancelTransport.close();
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			});
			cancelThread.start(); // 启动线程

			// 运行 10 次挖矿任务
			for (int i = 1; i <= 10; i++) {
				System.out.println("[Main] Starting mineBlock #" + i);

				int version = 1;
				Random random = new Random();
				byte[] bytes1 = new byte[32];
				random.nextBytes(bytes1);
				Sha256Hash prevBlockHash = Sha256Hash.wrap(bytes1);

				byte[] bytes2 = new byte[32];
				random.nextBytes(bytes2);
				Sha256Hash merkleRootHash = Sha256Hash.wrap(bytes2);

				Instant time = Instant.now().truncatedTo(ChronoUnit.SECONDS);

				// 难度
				String hexTarget = "1d7fffff";
				long compactTarget = Long.parseLong(hexTarget, 16);
				Difficulty difficulty = Difficulty.ofCompact(compactTarget);

				List<Transaction> txns = null;
				Block b = new Block(version, prevBlockHash, merkleRootHash, time, difficulty, 0, txns);
				System.out.println("[Main] Block pre-check:\n" + b);

				ByteBuffer buf1 = ByteBuffer.wrap(bytes1);
				ByteBuffer buf2 = ByteBuffer.wrap(bytes2);

				// 挖矿请求
				long nonce = -1;
				try {
					nonce = client.mineBlock(version, buf1, buf2, time.getEpochSecond(), difficulty.compact());
					System.out.println("[Main] mineBlock #" + i + " returned nonce = " + nonce);
				} catch (Exception e) {
					System.out.println("[Main] mineBlock #" + i + " failed: " + e.getMessage());
					continue;
				}

				b.setNonce(nonce);
				System.out.println("[Main] hash = " + b.getHash());
				System.out.println("[Main] target = " + difficulty.toIntegerString());

				if (difficulty.isMetByWork(b.getHash())) {
					System.out.println("[Main] ✅ hash <= target (valid)");
				} else {
					System.out.println("[Main] ❌ hash > target (invalid)");
				}

				System.out.println();
			}

			transport.close();
		} catch (TException x) {
			x.printStackTrace();
		}
	}
}
