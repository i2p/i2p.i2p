/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.HashMap;
import java.util.List;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.data.ByteArray;
import net.i2p.util.ByteCache;
import net.i2p.util.Clock;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

public class I2PTunnelRunner extends I2PThread implements I2PSocket.SocketErrorListener {
    private final static Log _log = new Log(I2PTunnelRunner.class);

    private static volatile long __runnerId;
    private long _runnerId;
    /** 
     * max bytes streamed in a packet - smaller ones might be filled
     * up to this size. Larger ones are not split (at least not on
     * Sun's impl of BufferedOutputStream), but that is the streaming
     * api's job...
     */
    static int MAX_PACKET_SIZE = 1024 * 32;

    static final int NETWORK_BUFFER_SIZE = MAX_PACKET_SIZE;

    private Socket s;
    private I2PSocket i2ps;
    Object slock, finishLock = new Object();
    boolean finished = false;
    HashMap ostreams, sockets;
    byte[] initialData;
    /** when the last data was sent/received (or -1 if never) */
    private long lastActivityOn;
    /** when the runner started up */
    private long startedOn;
    private List sockList;

    private volatile long __forwarderId;
    
    public I2PTunnelRunner(Socket s, I2PSocket i2ps, Object slock, byte[] initialData, List sockList) {
        this.sockList = sockList;
        this.s = s;
        this.i2ps = i2ps;
        this.slock = slock;
        this.initialData = initialData;
        lastActivityOn = -1;
        startedOn = Clock.getInstance().now();
        if (_log.shouldLog(Log.INFO))
            _log.info("I2PTunnelRunner started");
        _runnerId = ++__runnerId;
        __forwarderId = i2ps.hashCode();
        setName("I2PTunnelRunner " + _runnerId);
        start();
    }

    /** 
     * have we closed at least one (if not both) of the streams 
     * [aka we're done running the streams]? 
     *
     */
    public boolean isFinished() {
        return finished;
    }

    /** 
     * When was the last data for this runner sent or received?  
     *
     * @return date (ms since the epoch), or -1 if no data has been transferred yet
     *
     */
    public long getLastActivityOn() {
        return lastActivityOn;
    }

    private void updateActivity() {
        lastActivityOn = Clock.getInstance().now();
    }

    /**
     * When this runner started up transferring data
     *
     */
    public long getStartedOn() {
        return startedOn;
    }

    public void run() {
        boolean closedCleanly = false;
        try {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream(); // = new BufferedOutputStream(s.getOutputStream(), NETWORK_BUFFER_SIZE);
            i2ps.setSocketErrorListener(this);
            InputStream i2pin = i2ps.getInputStream();
            OutputStream i2pout = i2ps.getOutputStream(); //new BufferedOutputStream(i2ps.getOutputStream(), MAX_PACKET_SIZE);
            if (initialData != null) {
                synchronized (slock) {
                    i2pout.write(initialData);
                    //i2pout.flush();
                }
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Initial data " + (initialData != null ? initialData.length : 0) 
                           + " written, starting forwarders");
            Thread t1 = new StreamForwarder(in, i2pout, "toI2P");
            Thread t2 = new StreamForwarder(i2pin, out, "fromI2P");
            synchronized (finishLock) {
                while (!finished) {
                    finishLock.wait();
                }
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("At least one forwarder completed, closing and joining");
            // now one connection is dead - kill the other as well.
            s.close();
            i2ps.close();
            t1.join(30*1000);
            t2.join(30*1000);
            closedCleanly = true;
        } catch (InterruptedException ex) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Interrupted", ex);
        } catch (IOException ex) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Error forwarding", ex);
        } catch (Exception e) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Internal error", e);
        } finally {
            removeRef();
            try {
                if ( (s != null) && (!closedCleanly) )
                    s.close();
            } catch (IOException ex) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Could not close java socket", ex);
            }
            try {
                if (i2ps != null) {
                    if (!closedCleanly)
                        i2ps.close();
                    i2ps.setSocketErrorListener(null);
                }
            } catch (IOException ex) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Could not close I2PSocket", ex);
            }
        }
    }
    
    public void errorOccurred() {
        synchronized (finishLock) {
            finished = true;
            finishLock.notifyAll();
        }
    }
    
    private void removeRef() {
        if (sockList != null) {
            synchronized (slock) {
                boolean removed = sockList.remove(i2ps);
                //System.out.println("Removal of i2psocket " + i2ps + " successful? " 
                //                   + removed + " remaining: " + sockList.size());
            }
        }
    }
    
    private class StreamForwarder extends I2PThread {

        InputStream in;
        OutputStream out;
        String direction;
        private ByteCache _cache;

        private StreamForwarder(InputStream in, OutputStream out, String dir) {
            this.in = in;
            this.out = out;
            direction = dir;
            _cache = ByteCache.getInstance(256, NETWORK_BUFFER_SIZE);
            setName("StreamForwarder " + _runnerId + "." + (++__forwarderId));
            start();
        }

        public void run() {
            String from = i2ps.getThisDestination().calculateHash().toBase64().substring(0,6);
            String to = i2ps.getPeerDestination().calculateHash().toBase64().substring(0,6);

            if (_log.shouldLog(Log.DEBUG)) {
                _log.debug(direction + ": Forwarding between " 
                           + from + " and " + to);
            }
            
            ByteArray ba = _cache.acquire();
            byte[] buffer = ba.getData(); // new byte[NETWORK_BUFFER_SIZE];
            try {
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);

                    if (len > 0) updateActivity();

                    if (in.available() == 0) {
                        //if (_log.shouldLog(Log.DEBUG))
                        //    _log.debug("Flushing after sending " + len + " bytes through");
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug(direction + ": " + len + " bytes flushed through to " 
                                       + i2ps.getPeerDestination().calculateHash().toBase64().substring(0,6));
                        try {
                            Thread.sleep(I2PTunnel.PACKET_DELAY);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        
                        if (in.available() <= 0)
                            out.flush(); // make sure the data get though
                    }
                }
                //out.flush(); // close() flushes
            } catch (SocketException ex) {
                // this *will* occur when the other threads closes the socket
                synchronized (finishLock) {
                    if (!finished) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug(direction + ": Socket closed - error reading and writing",
                                       ex);
                    }
                }
            } catch (InterruptedIOException ex) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn(direction + ": Closing connection due to timeout (error: \""
                              + ex.getMessage() + "\")");
            } catch (IOException ex) {
                if (!finished) {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error(direction + ": Error forwarding", ex);
                }
                //else
                //    _log.warn("You may ignore this", ex);
            } finally {
                if (_log.shouldLog(Log.INFO)) {
                    _log.info(direction + ": done forwarding between " 
                              + from + " and " + to);
                }
                try {
                    in.close();
                } catch (IOException ex) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn(direction + ": Error closing input stream", ex);
                }
                try {
                    out.close();
                } catch (IOException ex) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn(direction + ": Error closing output stream", ex);
                }
                synchronized (finishLock) {
                    finished = true;
                    finishLock.notifyAll();
                    // the main thread will close sockets etc. now
                }
                _cache.release(ba);
            }
        }
    }
}
