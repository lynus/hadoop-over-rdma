package org.apache.hadoop.ipc;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.security.PrivilegedExceptionAction;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Hashtable;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.accelio.jxio.EventName;
import org.accelio.jxio.EventQueueHandler;
import org.accelio.jxio.EventReason;
import org.accelio.jxio.Msg;
import org.accelio.jxio.MsgPool;
import org.accelio.jxio.ServerPortal;
import org.accelio.jxio.ServerSession;
import org.accelio.jxio.ServerSession.SessionKey;
import org.accelio.jxio.WorkerCache.Worker;
import org.accelio.jxio.exceptions.JxioSessionClosedException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.ByteBufferDataOutputStream;
import org.apache.hadoop.io.DataInputBuffer;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableUtils;
import org.apache.hadoop.ipc.Server.ExceptionsHandler;
import org.apache.hadoop.ipc.metrics.RpcInstrumentation;
import org.apache.hadoop.security.SaslRpcServer.AuthMethod;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.StringUtils;

public abstract class RDMAServer {
	public static final byte[] HEADER = "hrpc".getBytes();
	public static final byte CURRENT_VERSION = 4;
	private static final int IPC_RDMA_SERVER_HANDLER_QUEUE_SIZE_DEFAULT = 100;
	private static final String  IPC_RDMA_SERVER_HANDLER_QUEUE_SIZE_KEY = 
			"ipc.rdmaserver.handler.queue.size";
	private static final int IPC_RDMA_SERVER_WORKER_THREADS_DEFAULT =  1;
	private static final String IPC_RDMA_SERVER_WORKER_THREADS_KEY = 
			"ipc.rdmaserver.worker.size";
	private static final String IPC_JXIO_TRANSMISSION_KEY = 
			"ipc.jxio.transmission";
	private static final String IPC_JXIO_TRANSMISSION_DEFAULT = "rdma";
	public static final Log LOG = LogFactory.getLog(RDMAServer.class);
	//private static final ThreadLocal<RDMAServer> SERVER = new ThreadLocal<RDMAServer>();
	private static final Map<String, Class<?> > PROTOCOL_CACHE = 
			new ConcurrentHashMap<String, Class<?>>();
	private Class<? extends Writable> paramClass;
	static Class<?> getProtocolClass(String protocolName, Configuration conf) 
			throws ClassNotFoundException {
		Class<?> protocol = PROTOCOL_CACHE.get(protocolName);
		if (protocol == null) {
			protocol = conf.getClassByName(protocolName);
			PROTOCOL_CACHE.put(protocolName, protocol);
		}
		return protocol;
	}

	private static final ThreadLocal<Call> CurCall = new ThreadLocal<Call>();
	public static InetAddress getRemoteIp() {
		Call  call = CurCall.get();
		if (call != null) {
			return call.connection.getInetAddress();
		}
		return null;
	}
	public static String getRemoteAddress() {
		InetAddress addr = getRemoteIp();
		return (addr == null) ? null : addr.getHostAddress();
	}
	private String bindAddress;
	private int port;
	private int handlerCount;
	private int readerCount;
	protected RpcInstrumentation rpcMetrics;

	private Configuration conf;
	private volatile boolean running = true;
	private BlockingQueue<Call> callQueue;
	private List<Connection> connectionList = 
			Collections.synchronizedList(new LinkedList<Connection>());
	private Listener listener = null;
	private int numConnections = 0;
	private Handler [] handlers = null;
	public RpcInstrumentation getRpcMetrics() {
		return rpcMetrics;
	}

	private class Call {
		private int id;                               // the client's call id
		private Writable param;                       // the parameter passed
		private Connection connection;                // connection to client
		private long timestamp;     // the time received when response is null
		// the time served when response is not null
		private Msg msg;

		public Call(int id, Writable param, Connection connection, Msg msg) { 
			this.id = id;
			this.param = param;
			this.connection = connection;
			this.timestamp = System.currentTimeMillis();
			this.msg = msg;
		}

		@Override
		public String toString() {
			return "call # " + this.id + param.toString() + " from " + connection.toString();
		}
	}	
	private class Listener extends Thread {
		private ServerPortal acceptPortal = null;
		private EventQueueHandler eqh = null;
		private Reader[] readers = null;
		private int nextReader = 0;
		private InetSocketAddress address;

		private class ListenerCallbacks implements ServerPortal.Callbacks {
			@Override
			public void onSessionEvent(EventName arg0, EventReason arg1) {
				//only receive PORTAL_CLOSE event
				LOG.info("acceptPortal closing, reason: " + arg1.toString());
				eqh.close();
			}
			@Override
			public void onSessionNew(SessionKey sesKey, String srcIP, Worker hint) {
				LOG.info("new session comming from "+ srcIP);
				Reader reader = getReader();
				try {
					acceptPortal.forward(reader.getPortal(), 
							new Connection(sesKey, reader, srcIP).getSession());
				} catch (UnknownHostException e) {
					LOG.error(e);
				}  		
			}
		}	
		public Listener() throws IOException {
			address  = new InetSocketAddress(bindAddress, port);
			eqh = new EventQueueHandler(null);
			URI uri;
			String trans = conf.get(IPC_JXIO_TRANSMISSION_KEY,
					IPC_JXIO_TRANSMISSION_DEFAULT);
			String uri_str = trans + "://" + address.getHostName() + ":" + address.getPort();
			try {
				uri = new URI(uri_str);
			}catch (URISyntaxException e) {
				LOG.error("Listener: bad listen uri:"+ uri_str);
				throw new IOException("bad listener uri");
			}
			this.acceptPortal = new ServerPortal(eqh, uri,  new ListenerCallbacks());
			readers = new Reader[readerCount];
			for (int i = 0; i < readerCount; i++) {
				Reader reader = new Reader(i, acceptPortal.getUriForServer());
				readers[i] = reader;
				readers[i].start();
			}
			this.setName("RDMA RPC listener on "+ uri_str);
			this.setDaemon(true);
		}
		public void run() {
			LOG.info(getName() + ": starting");
			eqh.run();
			Exception e = eqh.getCaughtException();
			LOG.info(getName() + ": stoping");
			if (e != null)
				LOG.error("listener event queue caught: ", e);
			//TODO cleanup remaining connections
		}

		public InetSocketAddress getAddress() {
			return address;
		}
		synchronized void doStop() {
			acceptPortal.close();
			for (int i = 0; i < readerCount; i++ )
				readers[i].doStop();
		}
		Reader getReader() {
			Reader  r = readers[nextReader];
			nextReader = (nextReader + 1) % readers.length;
			return r;
		}
		private class Reader extends Thread {
			public final int id;
			private final ServerPortal portal;
			private final EventQueueHandler eqh;
			private final MsgPool msgPool;
			//private AtomicInteger sessionCounter; 
			private Hashtable<Integer, Connection> connectionTable = 
					new Hashtable<Integer, Connection>();
			private class EqhCallback implements EventQueueHandler.Callbacks {
				@Override
				public MsgPool getAdditionalMsgPool(int inSize, int outSize) {
					LOG.warn("reader " + id + " need additinal msg pool"); 
					return new MsgPool(128 , inSize, outSize);
				}
			} //EqhCallback
			private class ReaderPortalCallbacks extends ListenerCallbacks {
				@Override
				public void onSessionEvent(EventName arg0, EventReason arg1) {
					//only receive PORTAL_CLOSE event
					LOG.info(" reader #" + id + " closing, reason: "+arg1.toString());
				}	      
			}
			public Reader(int id, URI uri) {
				this.id = id;
				this.msgPool = new MsgPool(128 + 64, 256, 256 );
				this.eqh = new EventQueueHandler(new EqhCallback());
				eqh.bindMsgPool(msgPool);
				portal = new ServerPortal(eqh, uri, new ReaderPortalCallbacks());
				//sessionCounter = new AtomicInteger(0);		
			}
			public ServerPortal getPortal() {
				return portal;
			}

			public void addConnection(Connection conn) {
				synchronized (connectionTable) {
					connectionTable.put(conn.id, conn);
				}
			}
			public void removeConnection(Connection conn) {
				synchronized (connectionTable) {
					connectionTable.remove(conn.id);  
				}
			}

			public void run() {
				LOG.info("reader #" + id + " starting.");
				while (running)
					eqh.run();

				Exception e = eqh.getCaughtException();
				LOG.info("reader #" + id + " stoping");
				if (e != null)
					LOG.error("reader #" + id +" event queue caught: ", e);
			}
			public void doStop() {
				eqh.close();
				portal.close();
				eqh.releaseMsgPool(msgPool);
			}
		} // Reader

	}
	private static int connIdCounter  = 0;
	public class Connection {
		private ServerSession session;
		private boolean headerProcessed = false;
		private Listener.Reader reader;
		private ServerPortal portal;
		private InetAddress srcIP;
		private volatile boolean self_closing = false;
		private int id;
		private Msg discardingMsg = null;

		private ConnectionHeader header;
		private UserGroupInformation user;
		Class<?> protocol;

		public Connection(SessionKey sesKey, Listener.Reader reader, String ip) throws UnknownHostException{
			LOG.info("Connection from " + sesKey.getUri() + " establishing");
			this.session = new ServerSession(sesKey, new SessionCallbacks());
			this.headerProcessed = false;
			this.reader = reader;
			this.header = new ConnectionHeader();
			this.srcIP = InetAddress.getByName(ip);
			this.id = connIdCounter++;
			this.portal = reader.getPortal();
			this.reader.addConnection(this);
		}
		public String toString() {
			return "connection #" + id + ", (" + srcIP.getHostAddress() + ")"; 
		}

		public ServerSession getSession() {
			return session;
		}
		public InetAddress  getInetAddress() {
			return srcIP;
		}

		public  void closeUp(boolean isLocalInitiated) {
			reader.removeConnection(this);
			if (isLocalInitiated) {		 
				session.close();		     
				self_closing = true;
			}
		}
		private class SessionCallbacks implements ServerSession.Callbacks {
			@Override
			public boolean onMsgError(Msg arg0, EventReason arg1) {
				//TODO  on receiving error, close connection; on sending error,  notify 
				LOG.error("connection #" + id + " on reader #" + reader.id + 
						"get onMsgError() EventReason: " + arg1.toString());
				return true;  //auto release the msg after this call
			}

			@Override
			public void onRequest(Msg msg) {
				try{
					LOG.info("new msg comming");
					readAndProcess(msg);
				}catch (IOException e) {
					LOG.error("connection #" + id + "on reader #" + reader.id +
							"readAndProcess get exception: " + e.getMessage() + " close session");
					discardingMsg = msg;
					closeUp(true);
				} catch (InterruptedException e) {
				}		     
			}

			@Override
			public void onSessionEvent(EventName event, EventReason reason) {
				//SESSION_CLOSED   OR  SESSION_ERROR	
				LOG.info("connection #" + id +" on reader #" + reader.id +
						" get session event: "+ event.toString() + ",  reason: " + reason.toString());
				if (event == EventName.SESSION_CLOSED){
					if (!self_closing) {
						// remote end close this session
						LOG.info("client close this connect");
						closeUp(false);
					} else
						LOG.info("server close this connect");
					if (discardingMsg != null) {
						session.discardRequest(discardingMsg);
						discardingMsg = null;
					}	   		
				} else {
					//SESSION_ERROR
					closeUp(true);
				}
			}		
		} //SessionCallbacks
		/**
		 * @param msg
		 * @throws IOException
		 * @throws InterruptedException
		 */
		@SuppressWarnings("resource")
		private void readAndProcess(Msg msg) throws IOException, InterruptedException{
			/* 	deal with header if the connection is newly established
			then read the msg into a call object 
			put call into global BlockingQueue
			 */
			ByteBuffer msgBuf = msg.getIn();
			DataInputBuffer in = new DataInputBuffer();
			byte array[] = null;
			int connHeaderLength;
			if (!headerProcessed) {
				byte [] rpcHeader = new byte[4];
				msgBuf.get(rpcHeader, 0, 4);
				byte version = msgBuf.get(4);
				byte authMethodCode = msgBuf.get(5);
				if (!Arrays.equals(HEADER, rpcHeader)) 
					throw new IOException("RPC header initial string not 'hrpc'");
				if (version != CURRENT_VERSION)
					throw new IOException("RPC version not matched");
				if (authMethodCode != AuthMethod.SIMPLE.code)
					throw new IOException("AuthMethod not supported");

				connHeaderLength = msgBuf.getInt(6);
				msgBuf.position(10);
				array = new byte[connHeaderLength];
				msgBuf.get(array, 0, connHeaderLength);
				in.reset(array, connHeaderLength);
				header.readFields(in);


				try {
					String protocolClassName = header.getProtocol();
					if (protocolClassName != null) 
						protocol = getProtocolClass(header.getProtocol(), conf);
				} catch(ClassNotFoundException e) {
					throw new IOException("Unknown protocol: " + header.getProtocol());
				}
				user = header.getUgi();
				if (user != null)
					user.setAuthenticationMethod(AuthMethod.SIMPLE.authenticationMethod);

				array = new byte[msgBuf.limit() - 10 - connHeaderLength];
				//msgBuf.position(10 + connHeaderLength);
				msgBuf.get(array);
				in.reset(array, 0, array.length);
				headerProcessed = true;
			} else {
				array = new byte[msgBuf.limit()];
				msgBuf.get(array);
				in.reset(array, 0, array.length);
			}
			int dataLength = in.readInt();
			int callId = in.readInt();
			LOG.info("get call #" + id);
			Writable  param = ReflectionUtils.newInstance(paramClass, conf);
			param.readFields(in);

			Call call  =  new Call(callId, param, this, msg);
			callQueue.put(call);
		}
	} //Connection
	private class Handler extends Thread {
		public Handler(int instanceNumber) {
			this.setDaemon(true);
			this.setName("IPC Server handler # " + instanceNumber + " on " + port);
		}
		public void run() {
			LOG.info(getName() + ": starting");
			while (running) {
				try{
					final Call call = callQueue.take();
					LOG.info(getName() + "take call #" + call.id + " from "
							+ call.connection);
					if (call.connection.getSession().getIsClosing()) {
						LOG.info("call #" + call.id + " 's connection is closing discard call");
						continue;
					}
					String errorClass = null;
					String error = null;
					CurCall.set(call);
					Writable value = null;
					try {
						if (call.connection.user == null) {
							value = call(call.connection.protocol, call.param, call.timestamp);
						}else {
							value = call.connection.user.doAs(
									new PrivilegedExceptionAction<Writable>() {
										@Override
										public Writable run() throws Exception {
											return call(call.connection.protocol, call.param, call.timestamp);
										}
									});
						}	
					} catch (Throwable e) {
						String logMsg = getName() + ", call " + call + ": error: " +e;
						if (e instanceof RuntimeException || e instanceof Error){
							LOG.warn(logMsg, e);
						} else {
							LOG.info(logMsg, e);
						}		    
						errorClass = e.getClass().getName();
						error = org.apache.hadoop.util.StringUtils.stringifyException(e);
					}
					CurCall.set(null);
					setupResponse(call.msg.getOut(), call, (error == null)? Status.SUCCESS: Status.ERROR,
							value, errorClass, error);
					if (call.connection.getSession().getIsClosing()) {
						LOG.info("call #" + call.id + " 's connection is closing discard call");
						continue;
					}        		    
					try{
						call.connection.session.sendResponse(call.msg);
					} catch (JxioSessionClosedException e) {
						LOG.info("sendResponse() caught JxioSessionClosedException");
						if (!call.connection.getSession().discardRequest(call.msg))
							LOG.error("discard call #" + call.id + " failed!!!!");
					}
				} catch (InterruptedException e) {
					if (running) 
						LOG.info(getName() + " caught: " + StringUtils.stringifyException(e));	
				} catch (Exception e) {
					LOG.info(getName() + " caught: " + StringUtils.stringifyException(e));
				}
			} //while
			LOG.info(getName() + ": exiting");
		}
	}

	public void setupResponse(ByteBuffer buf, Call call, Status status,
			Writable value, String errorClass, String error) throws IOException{
		buf.clear();
		ByteBufferDataOutputStream out = new ByteBufferDataOutputStream(buf);
		out.writeInt(call.id);
		out.writeInt(status.state);

		if (status == Status.SUCCESS)
			value.write(out);
		else {
			WritableUtils.writeString(out, errorClass);
			WritableUtils.writeString(out, error);
		}
	}
	protected RDMAServer (String bindAddress, int port, 
			Class<? extends Writable> paramClass, int handlerCount, Configuration conf,
			String serverName) throws IOException {
		this.bindAddress = bindAddress;
		this.conf = conf;
		this.port = port;
		this.paramClass = paramClass;
		this.handlerCount = handlerCount;
		int queueSize = handlerCount * conf.getInt(IPC_RDMA_SERVER_HANDLER_QUEUE_SIZE_KEY,
				IPC_RDMA_SERVER_HANDLER_QUEUE_SIZE_DEFAULT);
		this.callQueue = new LinkedBlockingQueue<Call>(queueSize);
		this.readerCount = conf.getInt(IPC_RDMA_SERVER_WORKER_THREADS_KEY,
				IPC_RDMA_SERVER_WORKER_THREADS_DEFAULT);
		this.listener = new Listener();
		this.rpcMetrics = RpcInstrumentation.create(serverName, port);	
	}
	public synchronized void start() {
		listener.start();
		handlers = new Handler[handlerCount];
		for (int i = 0; i < handlerCount; i++) {
			handlers[i] = new Handler(i);
			handlers[i].start();
		}
	}

	public synchronized void stop() {
		LOG.info("stoping rpc server on " + port);
		running = false;
		if (handlers != null) {
			for (int i = 0; i < handlerCount; i++) {
				handlers[i].interrupt();
			}
		}
		notifyAll();
		listener.doStop();
		rpcMetrics.shutdown();
	}
	public synchronized void join()  throws InterruptedException {
		while(running) 
			wait();
	}
	public synchronized InetSocketAddress getListenerAddress() {
		return listener.getAddress();
	}
	public abstract Writable call (Class<?> protocol, Writable param, long receiveTime)
			throws IOException;
}

