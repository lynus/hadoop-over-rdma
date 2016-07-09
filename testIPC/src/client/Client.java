package client;

import java.io.IOException;
import java.net.InetSocketAddress;

import javax.net.SocketFactory;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeys;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.ipc.RPC;

import protocol.DemoProtocol;
import server.TestConf;
public class Client {
    public static void main(String[] args) {
	Configuration conf = new Configuration(false);
	ClassLoader cl = TestConf.class.getClassLoader();
	String p = cl.getResource("server/TestConf.class").getPath();
	
	Path path = new Path(new Path(p).getParent().getParent().getParent()
		, "my.xml");
	System.out.println(path);
	conf.addResource(path);
	DemoProtocol protocol = null;
	String addr = conf.get(CommonConfigurationKeys.IPC_RDMA_BIND_ADDRESS,
		"172.18.0.13");
	int port = conf.getInt(CommonConfigurationKeys.IPC_RDMA_BIND_PORT,
		CommonConfigurationKeys.IPC_RDMA_BIND_PORT_DEFAULT);
	InetSocketAddress sockaddr = new InetSocketAddress(addr, port);
	try {
	    protocol = (DemoProtocol)RPC.getProxy(
	    	DemoProtocol.class, 1L,sockaddr, conf,SocketFactory.getDefault());
	} catch (IOException e) {
	    // TODO Auto-generated catch block
	    e.printStackTrace();
	}
	if (protocol != null) {
	    int i = protocol.mycall("haha");
	    System.out.println("get message: " + i);
	    RPC.stopProxy(protocol);
	}
    }

}
