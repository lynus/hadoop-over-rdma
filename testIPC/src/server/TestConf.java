package server;

import org.apache.hadoop.conf.*;
import org.apache.hadoop.fs.CommonConfigurationKeys;
import org.apache.hadoop.fs.Path;
public class TestConf {

    public static void main(String[] args) {
	Configuration conf = new Configuration(false);
	ClassLoader cl = TestConf.class.getClassLoader();
	String p = cl.getResource("server/TestConf.class").getPath();
	
	Path path = new Path(new Path(p).getParent().getParent().getParent()
		, "my.xml");
	System.out.println(path);
	conf.addResource(path);
	String address = conf.get("ipc.rdma.bind.address");
	if (address != null)
	    System.out.println(address);
	else 
	    System.out.println("nothing found");
    }

}
