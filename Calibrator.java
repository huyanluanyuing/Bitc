import java.util.List;
import java.util.ArrayList;
import java.util.Random;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.bitcoinj.base.Difficulty;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.Transaction;

public class Calibrator {
	public static void main(String [] args) throws Exception {
		if (args.length != 1) {
			System.err.println("Usage: java Calibrator hex_target");
			System.exit(-1);
		} else {
			System.out.println("Patience please, this may take a while");
		}
		long target = Long.parseLong(args[0], 16);


		Random random = new Random();
		int n = 100;
		long startTime = System.currentTimeMillis();
		for (int r = 0; r < n; r++) {
			int version = 1;
			byte[] bytes1 = new byte[32];
			random.nextBytes(bytes1);
			Sha256Hash prevBlockHash = Sha256Hash.wrap(bytes1);
			byte[] bytes2 = new byte[32];
			random.nextBytes(bytes2);
			Sha256Hash merkleRootHash = Sha256Hash.wrap(bytes2);
			Instant time = Instant.now().truncatedTo(ChronoUnit.SECONDS);
			Difficulty difficulty = Difficulty.ofCompact(target);
			long nonce = 0;
			List<Transaction> txns = null;
			Block b = new Block(version, prevBlockHash, merkleRootHash, time, difficulty, nonce, txns);

			while (!difficulty.isMetByWork(b.getHash())) {
				b.setNonce(++nonce);
			}

			System.out.println("" + (r+1) + "% complete");
		}
		long endTime = System.currentTimeMillis();

		System.out.println("Throughput (blocks/s) for target " + args[0] + ": " + n*1000f/(endTime-startTime));
		System.out.println("Latency (ms/block)    for target " + args[0] + ": " + 1f*(endTime-startTime)/n);
	}
}
