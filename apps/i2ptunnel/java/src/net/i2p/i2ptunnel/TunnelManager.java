/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.util.Clock;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Quick and dirty socket listener to control an I2PTunnel.  
 * Basically run this class as TunnelManager [listenHost] [listenPort] and 
 * then send it commands on that port.  Commands are one shot deals -
 * Send a command + newline, get a response plus newline, then get disconnected.
 * <p />
 * <b>Implemented commands:</b>
 * <pre>
 * -------------------------------------------------
 * lookup &lt;name&gt;\n
 * --
 * &lt;base64 of the destination&gt;\n
 *  or
 * &lt;error message, usually 'Unknown host'&gt;\n
 * 
 *  Lookup the public key of a named destination (i.e. listed in hosts.txt)
 * -------------------------------------------------
 * genkey\n
 * --
 * &lt;base64 of the destination&gt;\t&lt;base64 of private data&gt;\n
 * 
 *  Generates a new public and private key pair
 * -------------------------------------------------
 * convertprivate &lt;base64 of privkey&gt;
 * --
 * &lt;base64 of destination&gt;\n
 *  or
 * &lt;error message&gt;\n
 *
 *  Returns the destination (pubkey) of a given private key. 
 * -------------------------------------------------
 * listen_on &lt;ip&gt;\n
 * --
 * ok\n
 *  or
 * error\n
 * 
 *  Sets the ip address clients will listen on. By default this is the
 *  localhost (127.0.0.1)
 * -------------------------------------------------
 * openclient &lt;listenPort&gt; &lt;peer&gt;\n
 * --
 * ok [&lt;jobId&gt;]\n
 *  or
 * ok &lt;listenPort&gt; [&lt;jobId&gt;]\n
 *  or
 * error\n
 * 
 *  Open a tunnel on the given &lt;listenport&gt; to the destination specified
 *  by &lt;peer&gt;. If &lt;listenPort&gt; is 0 a free port is picked and returned in
 *  the reply message. Otherwise the short reply message is used.
 *  Peer can be the base64 of the destination, a file with the public key
 *  specified as 'file:&lt;filename&gt;' or the name of a destination listed in
 *  hosts.txt. The &lt;jobId&gt; returned together with "ok" and &lt;listenport&gt; can
 *  later be used as argument for the "close" command.
 * -------------------------------------------------
 * openhttpclient &lt;listenPort&gt; [&lt;proxy&gt;]\n
 * --
 * ok [&lt;jobId&gt;]\n
 *  or
 * ok &lt;listenPort&gt; [&lt;jobId&gt;]\n
 *  or
 * error\n
 * 
 *  Open an HTTP proxy through the I2P on the given
 *  &lt;listenport&gt;. &lt;proxy&gt; (optional) specifies a
 *  destination to be used as an outbound proxy, to access normal WWW
 *  sites out of the .i2p domain. If &lt;listenPort&gt; is 0 a free
 *  port is picked and returned in the reply message. Otherwise the
 *  short reply message is used.  &lt;proxy&gt; can be the base64 of the
 *  destination, a file with the public key specified as
 *  'file:&lt;filename&gt;' or the name of a destination listed in
 *  hosts.txt. The &lt;jobId&gt; returned together with "ok" and
 *  &lt;listenport&gt; can later be used as argument for the "close"
 *  command.
 * -------------------------------------------------
 * opensockstunnel &lt;listenPort&gt;\n
 * --
 * ok [&lt;jobId&gt;]\n
 *  or
 * ok &lt;listenPort&gt; [&lt;jobId&gt;]\n
 *  or
 * error\n
 * 
 *  Open an SOCKS tunnel through the I2P on the given
 *  &lt;listenport&gt;. If &lt;listenPort&gt; is 0 a free port is
 *  picked and returned in the reply message. Otherwise the short
 *  reply message is used.  The &lt;jobId&gt; returned together with
 *  "ok" and &lt;listenport&gt; can later be used as argument for the
 *  "close" command.
 * -------------------------------------------------
 * openserver &lt;serverHost&gt; &lt;serverPort&gt; &lt;serverKeys&gt;\n
 * --
 * ok [&lt;jobId&gt;]\n
 *  or
 * error\n
 * 
 *  Starts receiving traffic for the destination specified by &lt;serverKeys&gt;
 *  and forwards it to the &lt;serverPort&gt; of &lt;serverHost&gt;.
 *  &lt;serverKeys&gt; is the base 64 encoded private key set of the local
 *  destination. The &lt;joId&gt; returned together with "ok" can later be used
 *  as argument for the "close" command.
 * -------------------------------------------------
 * close [forced] &lt;jobId&gt;\n
 *  or
 * close [forced] all\n
 * --
 * ok\n
 *  or
 * error\n
 * 
 *  Closes the job specified by &lt;jobId&gt; or all jobs. Use the list command
 *  for a list of running jobs.
 *  Normally a connection job is not closed when it still has an active
 *  connection. Use the optional 'forced' keyword to close connections
 *  regardless of their use.
 * -------------------------------------------------
 * list\n
 * --
 *  Example output:
 * 
 * [0] i2p.dnsalias.net/69.55.226.145:5555 &lt;- C:\i2pKeys\squidPriv
 * [1] 8767 -&gt; HTTPClient
 * [2] 7575 -&gt; file:C:\i2pKeys\squidPub
 * [3] 5252 -&gt; sCcSANIO~f4AQtCNI1BvDp3ZBS~9Ag5O0k0Msm7XBWWz5eOnZWL3MQ-2rxlesucb9XnpASGhWzyYNBpWAfaIB3pux1J1xujQLOwscMIhm7T8BP76Ly5jx6BLZCYrrPj0BI0uV90XJyT~4UyQgUlC1jzFQdZ9HDgBPJDf1UI4-YjIwEHuJgdZynYlQ1oUFhgno~HhcDByXO~PDaO~1JDMDbBEfIh~v6MgmHp-Xchod1OfKFrxFrzHgcJbn7E8edTFjZA6JCi~DtFxFelQz1lSBd-QB1qJnA0g-pVL5qngNUojXJCXs4qWcQ7ICLpvIc-Fpfj-0F1gkVlGDSGkb1yLH3~8p4czYgR3W5D7OpwXzezz6clpV8kmbd~x2SotdWsXBPRhqpewO38coU4dJG3OEUbuYmdN~nJMfWbmlcM1lXzz2vBsys4sZzW6dV3hZnbvbfxNTqbdqOh-KXi1iAzXv7CVTun0ubw~CfeGpcAqutC5loRUq7Mq62ngOukyv8Z9AAAA
 *
 *  Lists descriptions of all running jobs. The exact format of the
 *  description depends on the type of job.
 * -------------------------------------------------
 * </pre>
 */
public class TunnelManager implements Runnable {
    private final static Log _log = new Log(TunnelManager.class);
    private I2PTunnel _tunnel;
    private ServerSocket _socket;
    private boolean _keepAccepting;
    
    public TunnelManager(int listenPort) {
	this(null, listenPort);
    }
    public TunnelManager(String listenHost, int listenPort) {
	_tunnel = new I2PTunnel();
	_keepAccepting = true;
	try {
	    if (listenHost != null) {
		_socket = new ServerSocket(listenPort, 0, InetAddress.getByName(listenHost));
		_log.info("Listening for tunnel management clients on " + listenHost + ":" + listenPort);
	    } else {
		_socket = new ServerSocket(listenPort);
		_log.info("Listening for tunnel management clients on localhost:" + listenPort);
	    }
	} catch (Exception e) {
	    _log.error("Error starting up tunnel management listener on " + listenPort, e);
	}
    }

    public static void main(String args[]) {
	int port = 7676;
	String host = null;
	if (args.length == 1) {
	    try {
		port = Integer.parseInt(args[0]);
	    } catch (NumberFormatException nfe) {
		_log.error("Usage: TunnelManager [host] [port]");
		return;
	    }
	} else if (args.length == 2) {
	    host = args[0];
	    try {
		port = Integer.parseInt(args[1]);
	    } catch (NumberFormatException nfe) {
		_log.error("Usage: TunnelManager [host] [port]");
		return;
	    }
	}

	TunnelManager mgr = new TunnelManager(host, port);
	Thread t = new Thread(mgr, "Listener");
	t.start();
    }
    
    public void run() {
	if (_socket == null) {
	    _log.error("Unable to start listening, since the socket was not bound.  Already running?");
	    return;
	}
	_log.debug("Running");
	try {
	    while (_keepAccepting) {
		Socket socket = _socket.accept();
		_log.debug("Client accepted");
		if (socket != null) {
		    Thread t = new I2PThread(new TunnelManagerClientRunner(this, socket));
		    t.setName("TunnelManager Client");
		    t.setPriority(I2PThread.MIN_PRIORITY);
		    t.start();
		}
	    }
	} catch (IOException ioe) {
	    _log.error("Error accepting connections", ioe);
	} catch (Exception e) {
	    _log.error("Other error?!", e);
	} finally {
	    if (_socket != null) try { _socket.close(); } catch (IOException ioe) {}
	} 
	try { Thread.sleep(5000); } catch (InterruptedException ie) {}
    }

    public void error(String msg, OutputStream out) throws IOException {
	out.write(msg.getBytes());
	out.write('\n');
    }
	
    public void processQuit(OutputStream out) throws IOException {
	out.write("Nice try".getBytes());
	out.write('\n');
    }
    
    public void processList(OutputStream out) throws IOException {
	BufferLogger buf = new BufferLogger();
	long startCommand = Clock.getInstance().now();
	_tunnel.runCommand("list", buf);
	Object obj = _tunnel.waitEventValue("listDone");
	long endCommand = Clock.getInstance().now();
	String str = buf.getBuffer();
	_log.debug("ListDone complete after " + (endCommand-startCommand) + "ms: [" + str + "]");
	out.write(str.getBytes());
	out.write('\n');
	buf.ignoreFurtherActions();
    }

    public void processListenOn(String ip, OutputStream out) throws IOException {
	BufferLogger buf = new BufferLogger();
	_tunnel.runCommand("listen_on " + ip, buf);
	String status = (String)_tunnel.waitEventValue("listen_onResult");
	out.write((status + "\n").getBytes());
	buf.ignoreFurtherActions();
    }
    
    /**
     * "lookup <name>" returns with the result in base64, else "Unknown host" [or something like that],
     * then a newline.
     *
     */
    public void processLookup(String name, OutputStream out) throws IOException {
	BufferLogger buf = new BufferLogger();
	_tunnel.runCommand("lookup " + name, buf);
	String rv = (String)_tunnel.waitEventValue("lookupResult");
	out.write(rv.getBytes());
	out.write('\n');
	buf.ignoreFurtherActions();
    }

    public void processTestDestination(String destKey, OutputStream out) throws IOException {
	try { 
	    Destination d = new Destination(); 
	    d.fromBase64(destKey); 
	    out.write("valid\n".getBytes());
	} catch (DataFormatException dfe) { 
	    out.write("invalid\n".getBytes());
	}
	out.flush();
    }
    
    public void processConvertPrivate(String priv, OutputStream out) throws IOException {
	try {
	    Destination dest = new Destination();
	    dest.fromBase64(priv);
	    String str = dest.toBase64();
	    out.write(str.getBytes());
	    out.write('\n');
	} catch (DataFormatException dfe) {
	    _log.error("Error converting private data", dfe);
	    out.write("Error converting private key\n".getBytes());
	}
    }
    
    public void processClose(String which, boolean forced, OutputStream out) throws IOException {
	BufferLogger buf = new BufferLogger();
	_tunnel.runCommand((forced?"close forced ":"close ") + which, buf);
	String str = (String)_tunnel.waitEventValue("closeResult");
	out.write((str + "\n").getBytes());
	buf.ignoreFurtherActions();
    }
    
    /**
     * "genkey" returns with the base64 of the destination, followed by a tab, then the base64 of that
     * destination's private keys, then a newline.
     *
     */
    public void processGenKey(OutputStream out) throws IOException {
	BufferLogger buf = new BufferLogger();
	_tunnel.runCommand("gentextkeys", buf);
	String priv = (String)_tunnel.waitEventValue("privateKey");
	String pub = (String)_tunnel.waitEventValue("publicDestination");
	out.write((pub + "\t" + priv).getBytes());
	out.write('\n');
	buf.ignoreFurtherActions();
    }
    
    public void processOpenClient(int listenPort, String peer, OutputStream out) throws IOException {
	BufferLogger buf = new BufferLogger();
	_tunnel.runCommand("client " + listenPort + " " + peer, buf);
	Integer taskId = (Integer)_tunnel.waitEventValue("clientTaskId");
	if (taskId.intValue() < 0) {
	    out.write("error\n".getBytes());
	    buf.ignoreFurtherActions();
	    return;
	}
	String rv = (String)_tunnel.waitEventValue("openClientResult");
	if (rv.equals("error")) {
	    out.write((rv + "\n").getBytes());
	    buf.ignoreFurtherActions();
	    return;
	}

	if (listenPort != 0) {
	    out.write((rv + " [" + taskId.intValue() + "]\n").getBytes());
	    buf.ignoreFurtherActions();
	    return;
	}
	Integer port = (Integer)_tunnel.waitEventValue("clientLocalPort");
	out.write((rv + " " + port.intValue() + " [" + taskId.intValue()
		   + "]\n").getBytes());
	buf.ignoreFurtherActions();
    }

    public void processOpenHTTPClient(int listenPort,
				      String proxy,
				      OutputStream out) throws IOException {
	BufferLogger buf = new BufferLogger();
	_tunnel.runCommand("httpclient " + listenPort + " " + proxy, buf);
	Integer taskId = (Integer)_tunnel.waitEventValue("httpclientTaskId");
	if (taskId.intValue() < 0) {
	    out.write("error\n".getBytes());
	    buf.ignoreFurtherActions();
	    return;
	}
	String rv = (String)_tunnel.waitEventValue("openHTTPClientResult");
	if (rv.equals("error")) {
	    out.write((rv + "\n").getBytes());
	    buf.ignoreFurtherActions();
	    return;
	}

	if (listenPort != 0) {
	    out.write((rv + " [" + taskId.intValue() + "]\n").getBytes());
	    buf.ignoreFurtherActions();
	    return;
	}
	Integer port = (Integer)_tunnel.waitEventValue("clientLocalPort");
	out.write((rv + " " + port.intValue() + " [" + taskId.intValue()
		   + "]\n").getBytes());
	buf.ignoreFurtherActions();
    }
    
    public void processOpenSOCKSTunnel(int listenPort,
				       OutputStream out) throws IOException {
	BufferLogger buf = new BufferLogger();
	_tunnel.runCommand("sockstunnel " + listenPort, buf);
	Integer taskId = (Integer)_tunnel.waitEventValue("sockstunnelTaskId");
	if (taskId.intValue() < 0) {
	    out.write("error\n".getBytes());
	    buf.ignoreFurtherActions();
	    return;
	}
	String rv = (String)_tunnel.waitEventValue("openSOCKSTunnelResult");
	if (rv.equals("error")) {
	    out.write((rv + "\n").getBytes());
	    buf.ignoreFurtherActions();
	    return;
	}

	if (listenPort != 0) {
	    out.write((rv + " [" + taskId.intValue() + "]\n").getBytes());
	    buf.ignoreFurtherActions();
	    return;
	}
	Integer port = (Integer)_tunnel.waitEventValue("clientLocalPort");
	out.write((rv + " " + port.intValue() + " [" + taskId.intValue()
		   + "]\n").getBytes());
	buf.ignoreFurtherActions();
    }

    public void processOpenServer(String serverHost, int serverPort, String privateKeys, OutputStream out) throws IOException {
	BufferLogger buf = new BufferLogger();
	_tunnel.runCommand("textserver " + serverHost + " " + serverPort + " " + privateKeys, buf);
	Integer taskId = (Integer)_tunnel.waitEventValue("serverTaskId");
	if (taskId.intValue() < 0) {
	    out.write("error\n".getBytes());
	    buf.ignoreFurtherActions();
	    return;
	}
	
	String rv = (String)_tunnel.waitEventValue("openServerResult");

	if (rv.equals("error")) {
	    out.write((rv + "\n").getBytes());
	    buf.ignoreFurtherActions();
	    return;
	}

	out.write((rv + " [" + taskId.intValue() + "]\n").getBytes());
	buf.ignoreFurtherActions();
    }
    
    /**
     * Frisbee.
     *
     */
    public void unknownCommand(String command, OutputStream out) throws IOException {
	out.write("Unknown command: ".getBytes());
	out.write(command.getBytes());
	out.write("\n".getBytes());
    }
}
