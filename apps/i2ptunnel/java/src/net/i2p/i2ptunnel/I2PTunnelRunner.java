/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.HashMap;

import net.i2p.client.I2PSession;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.util.Log;
import net.i2p.util.Clock;
import net.i2p.util.I2PThread;

public class I2PTunnelRunner extends I2PThread {
    private final static Log _log = new Log(I2PTunnelRunner.class);
    
    /** 
     * max bytes streamed in a packet - smaller ones might be filled
     * up to this size. Larger ones are not split (at least not on
     * Sun's impl of BufferedOutputStream), but that is the streaming
     * api's job...
     */
    static int MAX_PACKET_SIZE = 1024*32;

    static final int NETWORK_BUFFER_SIZE = MAX_PACKET_SIZE;

    private Socket s;
    private I2PSocket i2ps;
    Object slock, finishLock = new Object();
    boolean finished=false;
    HashMap ostreams, sockets;
    I2PSession session;
    byte[] initialData;
    /** when the last data was sent/received (or -1 if never) */
    private long lastActivityOn;
    /** when the runner started up */
    private long startedOn;

    public I2PTunnelRunner(Socket s, I2PSocket i2ps, Object slock,
			   byte[] initialData) {
	this.s=s;
	this.i2ps=i2ps;
	this.slock=slock;
	this.initialData = initialData;
	lastActivityOn = -1;
	startedOn = -1;
	_log.info("I2PTunnelRunner started");
	setName("I2PTunnelRunner");
	start();
    }

    /** 
     * have we closed at least one (if not both) of the streams 
     * [aka we're done running the streams]? 
     *
     */
    public boolean isFinished() { return finished; }
    
    /** 
     * When was the last data for this runner sent or received?  
     *
     * @return date (ms since the epoch), or -1 if no data has been transferred yet
     *
     */
    public long getLastActivityOn() { return lastActivityOn; }
    private void updateActivity() { lastActivityOn = Clock.getInstance().now(); }
    
    /**
     * When this runner started up transferring data
     *
     */
    public long getStartedOn() { return startedOn; }
    
    public void run() {
	startedOn = Clock.getInstance().now();
	try {
	    InputStream in = s.getInputStream();
	    OutputStream out = new BufferedOutputStream(s.getOutputStream(),
							NETWORK_BUFFER_SIZE);
	    InputStream i2pin = i2ps.getInputStream();
	    OutputStream i2pout = new BufferedOutputStream
		(i2ps.getOutputStream(), MAX_PACKET_SIZE);
	    if (initialData != null) {
		synchronized(slock) {
		    i2pout.write(initialData);
		    i2pout.flush();
		}
	    }
	    Thread t1 = new StreamForwarder(in, i2pout);
	    Thread t2 = new StreamForwarder(i2pin, out);
	    synchronized(finishLock) {
		while (!finished) {
		    finishLock.wait();
		}
	    }
	    // now one connection is dead - kill the other as well.
	    s.close();
	    s = null;
	    i2ps.close();
	    i2ps = null;
	    t1.join();
	    t2.join();
	} catch (InterruptedException ex) {
	    _log.error("Interrupted", ex);
	} catch (IOException ex) {
	    ex.printStackTrace();
	    _log.error("Error forwarding", ex);
	} finally {
	    try {
		if (s != null) s.close();
		if (i2ps != null) i2ps.close();
	    } catch (IOException ex) {
		ex.printStackTrace();
		_log.error("Could not close socket", ex);
	    }
	}
    }

    private class StreamForwarder extends I2PThread {

	InputStream in;
	OutputStream out;
	
	private StreamForwarder(InputStream in, OutputStream out) {
	    this.in=in;
	    this.out=out;
	    setName("StreamForwarder");
	    start();
	}

	public void run() {
	    byte[] buffer = new byte[NETWORK_BUFFER_SIZE];
	    try {
		int len;
		while ((len=in.read(buffer)) != -1) {
		    out.write(buffer, 0, len);
		    
		    if (len > 0)
			updateActivity();
		    
		    if (in.available()==0) {
			try {
			    Thread.sleep(I2PTunnel.PACKET_DELAY);
			} catch (InterruptedException e) {
			    e.printStackTrace();
			}
		    }
		    if (in.available()==0) {
			out.flush(); // make sure the data get though
		    }
		}
	    } catch (SocketException ex) {
		// this *will* occur when the other threads closes the socket
		synchronized(finishLock) {
		    if (!finished) 
			_log.error("Error reading and writing", ex);
		    else
			_log.warn("You may ignore this", ex);
		}
	    } catch (IOException ex) {
		if (!finished)
		    _log.error("Error forwarding", ex);
		else
		    _log.warn("You may ignore this", ex);
	    } finally {
		try {
		    out.close();
		    in.close();
		} catch (IOException ex) {
		    _log.error("Error closing streams", ex);
		}
		synchronized(finishLock) {
		    finished=true;
		    finishLock.notifyAll();
		    // the main thread will close sockets etc. now
		}
	    }
	}
    }    
}
