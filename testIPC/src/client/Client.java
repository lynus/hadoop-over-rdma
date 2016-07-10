package client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.SocketFactory;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeys;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.ipc.RPC;

import protocol.DemoProtocol;
import server.TestConf;
public class Client {
    public static void main(String[] args) {
	final String TASK_THREAD_NUM_KEY = "client.task.size";
	final int TASK_THREAD_NUM_DEFAULT = 1;
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
	    return;
	}
	
	int nThreads = conf.getInt(TASK_THREAD_NUM_KEY, TASK_THREAD_NUM_DEFAULT);
	ExecutorService executorService = Executors.newFixedThreadPool(nThreads);
	for (int i = 0; i < nThreads; i++) {
	    executorService.submit(new Task(i, protocol));
	}
	executorService.shutdown();
	try {
	    executorService.awaitTermination(3, TimeUnit.SECONDS);
	} catch (InterruptedException e) {}
	RPC.stopProxy(protocol);
    }
    
    static class Task implements Runnable {
	private int id;
	private DemoProtocol proto = null;
	public Task(int id, DemoProtocol proto) {
	    this.id = id;
	    this.proto = proto;
	}
	@Override
	public void run() {
	   int i = proto.mycall("task #" + id);
	   System.out.println("Task #" + id + " get reply: " + i);
	}
	
    }

}
