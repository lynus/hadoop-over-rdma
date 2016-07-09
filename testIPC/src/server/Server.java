package server;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.fs.CommonConfigurationKeys;
import org.apache.hadoop.fs.Path;

import protocol.DemoProtocol;
public class Server implements DemoProtocol {
    AtomicInteger count;
    Configuration conf;
    RPC.RServer rserver;
    public Server() {
	this.conf = new Configuration(false);
	ClassLoader cl = TestConf.class.getClassLoader();
	String p = cl.getResource("server/TestConf.class").getPath();
	
	Path path = new Path(new Path(p).getParent().getParent().getParent()
		, "my.xml");
	System.out.println(path);
	conf.addResource(path);
	this.count = new AtomicInteger();
	String bindAddress = 
		conf.get(CommonConfigurationKeys.IPC_RDMA_BIND_ADDRESS,
		"172.18.0.13");
	int port = 
		conf.getInt(CommonConfigurationKeys.IPC_RDMA_BIND_PORT,
			CommonConfigurationKeys.IPC_RDMA_BIND_PORT_DEFAULT);
	try {
	    this.rserver = new RPC.RServer(this, conf,bindAddress, port,1,true,null);
	} catch (IOException e) {
	    // TODO Auto-generated catch block
	    e.printStackTrace();
	}
    }
    public void start() {
	rserver.start();
    }
    public void join() {
	try {
	    rserver.join();
	} catch (InterruptedException e) {
	    // TODO Auto-generated catch block
	    e.printStackTrace();
	}
    }
    public static void main(String[] args) {
	Server  server = new Server();
	server.start();
	server.join();
    }

    @Override
    public long getProtocolVersion(String arg0, long arg1) throws IOException {
	return DemoProtocol.versionID;
    }

    @Override
    public int mycall(String msg) {
	System.out.println(msg);
	return count.incrementAndGet();
    }

}
