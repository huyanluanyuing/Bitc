import java.net.InetAddress;

import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Logger;
import org.apache.log4j.Level;

import org.apache.thrift.TProcessorFactory;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.server.THsHaServer;
import org.apache.thrift.server.TSimpleServer;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.transport.TNonblockingServerSocket;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.layered.TFramedTransport;

public class BEServer {
	static Logger log;

	public static void main(String[] args) throws Exception {

		if (args.length != 3) {
			System.err.println("Usage: java BEServer FE_host FE_port BE_port numCores");
			System.err.println("Example: java BEServer localhost 9090 9091 1");
			System.exit(-1);
		}

		// initialize log4j
		BasicConfigurator.configure();
		log = Logger.getLogger(BEServer.class.getName());

		Logger.getLogger("org.apache.thrift").setLevel(Level.INFO);

		String hostFE = args[0];
		int portFE = Integer.parseInt(args[1]);
		int portBE = Integer.parseInt(args[2]);

		Runtime runtime = Runtime.getRuntime();
		int numCores = runtime.availableProcessors();

		String hostBE = getHostName();

		// log.info("========================================");
		// log.info("Starting BE node");
		// log.info(" Port: " + portBE);
		// log.info(" Host: " + hostBE);
		// log.info(" CPU Cores: " + numCores);
		// log.info(" FE: " + hostFE + ":" + portFE);
		// log.info("========================================");

		MiningPoolService.Processor processor = new MiningPoolService.Processor<MiningPoolService.Iface>(
				new MiningPoolServiceHandler());
		TServerSocket serverTransport = new TServerSocket(portBE);

		TThreadPoolServer.Args targs = new TThreadPoolServer.Args(serverTransport);
		targs.processor(processor);
		targs.protocolFactory(new TBinaryProtocol.Factory());
		targs.transportFactory(new TFramedTransport.Factory());

		int minThreads = numCores;
		int maxThreads = 2 * numCores;
		targs.minWorkerThreads(minThreads);
		targs.maxWorkerThreads(maxThreads);

		targs.stopTimeoutVal(60); // 60

		final TThreadPoolServer server = new TThreadPoolServer(targs);

		try {
			serverTransport.getServerSocket().setReuseAddress(true);
		} catch (java.net.SocketException e) {

		}

		Thread serverThread = new Thread(new Runnable() {
			public void run() {
				// log.info("BE Server is now listening for mining requests...");
				server.serve();
			}
		});
		serverThread.setDaemon(false);
		serverThread.start();

		Thread.sleep(1000);

		boolean registered = false;
		int maxRetries = 50;
		int retryCount = 0;

		while (!registered && retryCount < maxRetries) {
			try {
				// log.info("Attempting to register with FE (attempt " + (retryCount + 1) + "/"
				// + maxRetries + ")");
				registerWithFE(hostFE, portFE, hostBE, portBE, numCores);
				registered = true;
				// log.info("========================================");
				// log.info("✓ Successfully registered with FE!");
				// log.info(" BE info sent: " + hostBE + ":" + portBE + " (" + numCores + "
				// cores)");
				// log.info("========================================");
				// log.info("BE Server is ready to receive mining tasks");
				// log.info("========================================");
			} catch (Exception e) {
				retryCount++;
				// log.error("Failed to register: " + e.getMessage());
				if (retryCount < maxRetries) {
					// log.info("Retrying in 2 seconds...");
					Thread.sleep(2000);
				} else {
					// log.error("========================================");
					// log.error("FATAL: Could not register with FE after " + maxRetries + "
					// attempts");
					// log.error("Please ensure FE server is running at " + hostFE + ":" + portFE);
					// log.error("BE Server will now exit");
					// log.error("========================================");
					System.exit(1);
				}
			}
		}

		serverThread.join();
	}

	private static void registerWithFE(String hostFE, int portFE, String hostBE, int portBE, int numCores)
			throws Exception {
		TSocket sock = new TSocket(hostFE, portFE);
		TTransport transport = new TFramedTransport(sock);
		TProtocol protocol = new TBinaryProtocol(transport);

		MiningPoolService.Client client = new MiningPoolService.Client(protocol);

		transport.open();

		try {

			client.registerBE(hostBE, portBE, numCores);
			// log.info("Registration request sent successfully");
		} finally {
			// transport.close();
		}
	}

	static String getHostName() {
		try {
			return InetAddress.getLocalHost().getHostName();
		} catch (Exception e) {
			return "localhost";
		}
	}
}