import java.net.InetAddress;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Logger;
import org.apache.log4j.Level;

import org.apache.thrift.TProcessorFactory;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.layered.TFramedTransport;

public class FEServer {
    static Logger log;

    public static void main(String [] args) throws Exception {

        if (args.length != 1) {
            System.err.println("Usage: java FEServer port");
            System.err.println("Example: java FEServer 9090");
            System.exit(-1);
        }

        // initialize log4j
        BasicConfigurator.configure();
        log = Logger.getLogger(FEServer.class.getName());


        Logger.getLogger("org.apache.thrift").setLevel(Level.INFO);

        int port = Integer.parseInt(args[0]);
        Runtime runtime = Runtime.getRuntime();
        int numCores = runtime.availableProcessors();
        FEServiceHandler handler = new FEServiceHandler(numCores);
        MiningPoolService.Processor processor = new MiningPoolService.Processor<MiningPoolService.Iface>(handler);
        TServerSocket serverTransport = new TServerSocket(port);
        TThreadPoolServer.Args targs = new TThreadPoolServer.Args(serverTransport);
        targs.processor(processor);
        targs.protocolFactory(new TBinaryProtocol.Factory());
        targs.transportFactory(new TFramedTransport.Factory());
        TThreadPoolServer server = new TThreadPoolServer(targs);
        try {
            serverTransport.getServerSocket().setReuseAddress(true);
        } catch (java.net.SocketException e) {

        }
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