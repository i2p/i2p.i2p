package net.i2p.client.streaming;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;

import net.i2p.I2PException;
import net.i2p.client.I2PSessionException;
import net.i2p.data.Destination;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Initial stub implementation for the socket
 *
 */
class I2PSocketImpl implements I2PSocket {
    private final static Log _log = new Log(I2PSocketImpl.class);

    public static final int MAX_PACKET_SIZE = 1024*32;
    public static final int PACKET_DELAY=100;
    
    private I2PSocketManager manager;
    private Destination local;
    private Destination remote;
    private String localID;
    private String remoteID;
    private Object remoteIDWaiter = new Object();
    private I2PInputStream in;
    private I2POutputStream out;
    private boolean outgoing;
    private Object flagLock = new Object();
    private boolean closed = false, sendClose=true, closed2=false;
    
    public I2PSocketImpl(Destination peer, I2PSocketManager mgr,
			 boolean outgoing, String localID) {
	this.outgoing=outgoing;
	manager = mgr;
	remote = peer;
	local = mgr.getSession().getMyDestination();
	in = new I2PInputStream();
	I2PInputStream pin = new I2PInputStream();
	out = new I2POutputStream(pin);
	new I2PSocketRunner(pin);
	this.localID = localID;
    }
    
    public String getLocalID() {
	return localID;
    }

    public void setRemoteID(String id) {
	synchronized(remoteIDWaiter) {
	    remoteID=id;
	    remoteIDWaiter.notifyAll();
	}
    }

    public String getRemoteID(boolean wait) throws InterruptedIOException {
	return getRemoteID(wait, -1);
    }
    public String getRemoteID(boolean wait, long maxWait) throws InterruptedIOException {
	long dieAfter = System.currentTimeMillis() + maxWait;
	synchronized(remoteIDWaiter) {
	    while (wait && remoteID==null) {
		try {
		    if (maxWait > 0)
			remoteIDWaiter.wait(maxWait);
		    else
			remoteIDWaiter.wait();
		} catch (InterruptedException ex) {}
		    
		if ( (maxWait > 0) && (System.currentTimeMillis() > dieAfter) )
		    throw new InterruptedIOException("Timed out waiting for remote ID");
	    }
	    if (wait) {
		_log.debug("TIMING: RemoteID set to " + I2PSocketManager.getReadableForm(remoteID) +" for "+this.hashCode());
	    }
	    return remoteID;
	}
    }

    public String getRemoteID() throws InterruptedIOException {
	return getRemoteID(false);
    }

    public void queueData(byte[] data) {
	in.queueData(data);
    }

    /**
     * Return the Destination of this side of the socket.
     */
    public Destination getThisDestination() { return local; }

    /**
     * Return the destination of the peer.
     */
    public Destination getPeerDestination() { return remote; }

    /**
     * Return an InputStream to read from the socket.
     */
    public InputStream getInputStream() throws IOException { 
	if ( (in == null) )
	    throw new IOException("Not connected");
	return in;
    }

    /**
     * Return an OutputStream to write into the socket.
     */
    public OutputStream getOutputStream() throws IOException {
	if ( (out == null) ) 
	    throw new IOException("Not connected");
	return out;
    }

    /**
     * Closes the socket if not closed yet
     */
    public void close() throws IOException {
	synchronized(flagLock) {
	    _log.debug("Closing connection");
	    closed=true;
	}
	out.close();
	in.notifyClosed();
    }

    public void internalClose() {
	synchronized(flagLock) {
	    closed=true;
	    closed2=true;
	    sendClose=false;
	}
	out.close();
	in.notifyClosed();
    }
	

    private byte getMask(int add) {
	return (byte)((outgoing?(byte)0xA0:(byte)0x50)+(byte)add);
    }
    
    //--------------------------------------------------
    public class I2PInputStream extends InputStream {

	private ByteCollector bc = new ByteCollector();

	public int read() throws IOException {
	    byte[] b = new byte[1];
	    int res = read(b);
	    if (res == 1) return b[0] & 0xff;
	    if (res == -1) return -1;
	    throw new RuntimeException("Incorrect read() result");
	}

	public synchronized int read(byte[] b, int off, int len) throws IOException {
	    _log.debug("Read called: "+this.hashCode());
	    if (len==0) return 0;
	    byte[] read = bc.startToByteArray(len);
	    while (read.length==0) {
		synchronized(flagLock) {
		    if (closed){
			_log.debug("Closed is set, so closing stream: "+this.hashCode());
			return -1;
		    }
		}
		try {
		    wait();
		} catch (InterruptedException ex) {}
		read = bc.startToByteArray(len);
	    }
	    if (read.length>len) throw new RuntimeException("BUG");
	    System.arraycopy(read,0,b,off,read.length);

	    if (_log.shouldLog(Log.DEBUG)) {
		_log.debug("Read from I2PInputStream " + this.hashCode()
			   + " returned "+read.length+" bytes");
	    }
	    //if (_log.shouldLog(Log.DEBUG)) {
	    //  _log.debug("Read from I2PInputStream " + this.hashCode()
	    //	   + " returned "+read.length+" bytes:\n"
	    //	   + HexDump.dump(read));
	    //}
	    return read.length;
	}

	public int available() {
	    return bc.getCurrentSize();
	}

	public void queueData(byte[] data) {
	    queueData(data,0,data.length);
	}

	public synchronized void queueData(byte[] data, int off, int len) {
	    _log.debug("Insert "+len+" bytes into queue: "+this.hashCode());
	    bc.append(data, off, len);
	    notifyAll();
	}

	public synchronized void notifyClosed() {
	    notifyAll();
	}
	
    }

    public class I2POutputStream extends OutputStream {

	public I2PInputStream sendTo;
	
	public I2POutputStream(I2PInputStream sendTo) {
	    this.sendTo=sendTo;
	}
	public void write(int b) throws IOException {
	    write(new byte[] {(byte)b});
	}

	public void write (byte[] b, int off, int len)  throws IOException {
	    sendTo.queueData(b,off,len);
	}

	public void close() {
	    sendTo.notifyClosed();
	}
    }

    public class I2PSocketRunner extends I2PThread {

	public InputStream in;

	public I2PSocketRunner(InputStream in) {
	    _log.debug("Runner's input stream is: "+in.hashCode());
	    this.in=in;
	    setName("SocketRunner from " + I2PSocketImpl.this.remote.calculateHash().toBase64().substring(0, 4));
	    start();
	}

	public void run() {
	    byte[] buffer = new byte[MAX_PACKET_SIZE];
	    ByteCollector bc = new ByteCollector();
	    boolean sent = true;
	    try {
		int len, bcsize;
//		try {
		while (true) {
		    len = in.read(buffer);
		    bcsize = bc.getCurrentSize();
		    if (len != -1) {
			bc.append(buffer,len);
		    } else if (bcsize == 0) {
			break;
		    }
		    if ((bcsize < MAX_PACKET_SIZE)
			&& (in.available()==0)) {
			_log.debug("Runner Point d: "+this.hashCode());
						
			try {
			    Thread.sleep(PACKET_DELAY);
			} catch (InterruptedException e) {
			    e.printStackTrace();
			}
		    } 
		    if ((bcsize >= MAX_PACKET_SIZE)
			|| (in.available()==0) ) {
			byte[] data = bc.startToByteArray(MAX_PACKET_SIZE);
			if (data.length > 0) {
			    _log.debug("Message size is: "+data.length);
			    sent = sendBlock(data);
			    if (!sent) {
				_log.error("Error sending message to peer.  Killing socket runner");
				break;
			    }
			}
		    }
		}
		if ((bc.getCurrentSize() > 0) && sent) {
		    _log.error("A SCARY MONSTER HAS EATEN SOME DATA! "
			       + "(input stream: " + in.hashCode() + "; "
			       + "queue size: " + bc.getCurrentSize() + ")");
		}
		synchronized(flagLock) {
		    closed2=true;
		}
// 		} catch (IOException ex) {
// 		    if (_log.shouldLog(Log.INFO))
// 			_log.info("Error reading and writing", ex);
// 		}
		boolean sc;
		synchronized(flagLock) {
		    sc=sendClose;
		} // FIXME: Race here?
		if (sc) {
		    _log.info("Sending close packet: "+outgoing);
		    byte[] packet = I2PSocketManager.makePacket
			((byte)(getMask(0x02)),remoteID, new byte[0]);
		    synchronized(manager.getSession()) {
			sent = manager.getSession().sendMessage(remote, packet);
		    }
		    if (!sent) {
			_log.error("Error sending close packet to peer");
		    }
		}
		manager.removeSocket(I2PSocketImpl.this);
	    } catch (IOException ex) {
		// WHOEVER removes this event on inconsistent
		// state before fixing the inconsistent state (a
		// reference on the socket in the socket manager
		// etc.) will get hanged by me personally -- mihi
		_log.error("Error running - **INCONSISTENT STATE!!!**", ex);
	    } catch (I2PException ex) {
		_log.error("Error running - **INCONSISTENT STATE!!!**" , ex);
	    }
	}
	
	private boolean sendBlock(byte data[]) throws I2PSessionException {
	    _log.debug("TIMING: Block to send for "+I2PSocketImpl.this.hashCode());
	    if (remoteID==null) {
		_log.error("NULL REMOTEID");
		return false;
	    }
	    byte[] packet = I2PSocketManager.makePacket(getMask(0x00), remoteID,
						 data);
	    boolean sent;
	    synchronized(flagLock) {
		if (closed2) return false;
	    }
	    synchronized(manager.getSession()) {
		sent = manager.getSession().sendMessage(remote, packet);
	    }
	    return sent;
	}
    }
}
