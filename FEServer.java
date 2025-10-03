import java.net.InetAddress;

import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Logger;
import org.apache.log4j.Level;

import org.apache.thrift.TProcessorFactory;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.server.THsHaServer;
import org.apache.thrift.server.TSimpleServer;
import org.apache.thrift.transport.TNonblockingServerSocket;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.layered.TFramedTransport;


public class FEServer {
    static Logger log;

    public static void main(String [] args) throws Exception {
        // 用法：java FEServer port numCores
        if (args.length != 1) {
            System.err.println("Usage: java FEServer port numCores");
            System.err.println("Example: java FEServer 9090 2");
            System.exit(-1);
        }

        // initialize log4j
        BasicConfigurator.configure();
        log = Logger.getLogger(FEServer.class.getName());

        // 设置 Thrift 框架的日志级别为 INFO，隐藏 DEBUG 信息
        Logger.getLogger("org.apache.thrift").setLevel(Level.INFO);

        int port = Integer.parseInt(args[0]);
        Runtime runtime = Runtime.getRuntime();
        int numCores = runtime.availableProcessors();

        log.info("========================================");
        log.info("Launching FE server");
        log.info("  Port: " + port);
        log.info("  Host: " + getHostName());
        log.info("  CPU Cores: " + numCores);
        log.info("========================================");

        // 创建 FE 服务处理器（传入核心数）
        FEServiceHandler handler = new FEServiceHandler(numCores);

        // 使用 FEService.Processor
        MiningPoolService.Processor processor = new MiningPoolService.Processor<MiningPoolService.Iface>(handler);
        TNonblockingServerSocket socket = new TNonblockingServerSocket(port);
        THsHaServer.Args sargs = new THsHaServer.Args(socket);
        sargs.protocolFactory(new TBinaryProtocol.Factory());
        sargs.transportFactory(new TFramedTransport.Factory());
        sargs.processorFactory(new TProcessorFactory(processor));
        THsHaServer server = new THsHaServer(sargs);

        log.info("FE Server ready and waiting for:");
        log.info("  1. BE servers to register");
        log.info("  2. Client mining requests");
        log.info("========================================");
        log.info("To start BE servers:");
        log.info("  java BEServer <FE_host> <FE_port> <BE_port> <numCores>");
        log.info("  Example: java BEServer localhost " + port + " 9091 1");
        log.info("========================================");

        server.serve();
    }

    static String getHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "localhost";
        }
    }
}