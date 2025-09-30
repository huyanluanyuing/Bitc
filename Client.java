import java.util.List;
import java.util.Random;
import java.nio.ByteBuffer;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.layered.TFramedTransport;
import org.apache.thrift.transport.TTransportFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.bitcoinj.base.Difficulty;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.Block;
import org.bitcoinj.core.Transaction;

public class Client {
    public static void main(String [] args) {
	if (args.length != 2) {
	    System.err.println("Usage: java Client host port");
	    System.exit(-1);
	}

	try {
	    TSocket sock = new TSocket(args[0], Integer.parseInt(args[1]));
	    TTransport transport = new TFramedTransport(sock);
	    TProtocol protocol = new TBinaryProtocol(transport);
	    MiningPoolService.Client client = new MiningPoolService.Client(protocol);
	    transport.open();

	    int version = 1;
	    Random random = new Random();
	    byte[] bytes1 = new byte[32];
	    random.nextBytes(bytes1);
	    Sha256Hash prevBlockHash = Sha256Hash.wrap(bytes1);
	    byte[] bytes2 = new byte[32];
	    random.nextBytes(bytes2);
	    Sha256Hash merkleRootHash = Sha256Hash.wrap(bytes2);
	    Instant time = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        // 1. 定义你的高难度紧凑目标值 (1d7fffff)
        String hexTarget = "1d7fffff";
        // 2. 将十六进制字符串解析成 long
        // 注意：必须使用 Long.parseLong(string, 16)
        long compactTarget = Long.parseLong(hexTarget, 16);
        // 3. 使用解析后的 long 值创建 Difficulty 对象
        Difficulty difficulty = Difficulty.ofCompact(compactTarget); 
	    long nonce = 0;
	    List<Transaction> txns = null;
	    Block b = new Block(version, prevBlockHash, merkleRootHash, time, difficulty, nonce, txns);
	    System.out.println(b);

	    try {
		ByteBuffer buf1 = ByteBuffer.wrap(bytes1);
		ByteBuffer buf2 = ByteBuffer.wrap(bytes2);
		nonce = client.mineBlock(version, buf1, buf2, time.getEpochSecond(), difficulty.compact());
	    } catch (Exception e) {
		System.out.println("Exception check: exception thrown");
	    }

	    b.setNonce(nonce);
	    
	    System.out.println("nonce = " + nonce);
	    System.out.println("hash =   " + b.getHash());
	    System.out.println("target = " + difficulty.toIntegerString());
	    System.out.println();
	    if (difficulty.isMetByWork(b.getHash())) {
		System.out.println("hash <= target, response correct :)");
	    } else {
		System.out.println("hash > target, response INCORRECT :(");
	    }
	    System.out.println();

	    transport.close();
	} catch (TException x) {
	    x.printStackTrace();
	} 
    }
}
