package net.i2p.sam;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.nio.ByteBuffer;
import java.util.Properties;

import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 * Base class for SAM protocol handlers.  It implements common
 * methods, but is not able to actually parse the protocol itself:
 * this task is delegated to subclasses.
 *
 * @author human
 */
public abstract class SAMHandler implements Runnable {

    private final static Log _log = new Log(SAMHandler.class);

    protected I2PAppThread thread = null;
    protected SAMBridge bridge = null;

    private final Object socketWLock = new Object(); // Guards writings on socket
    protected final SocketChannel socket;

    protected final int verMajor;
    protected final int verMinor;
    
    /** I2CP options configuring the I2CP connection (port, host, numHops, etc) */
    protected final Properties i2cpProps;

    private final Object stopLock = new Object();
    private volatile boolean stopHandler;

    /**
     * SAMHandler constructor (to be called by subclasses)
     *
     * @param s Socket attached to a SAM client
     * @param verMajor SAM major version to manage
     * @param verMinor SAM minor version to manage
     * @param i2cpProps properties to configure the I2CP connection (host, port, etc)
     * @throws IOException 
     */
    protected SAMHandler(SocketChannel s,
                         int verMajor, int verMinor, Properties i2cpProps) throws IOException {
        socket = s;

        this.verMajor = verMajor;
        this.verMinor = verMinor;
        this.i2cpProps = i2cpProps;
    }

    /**
     * Start handling the SAM connection, detaching an handling thread.
     *
     */
    public final void startHandling() {
        thread = new I2PAppThread(this, "SAMHandler");
        thread.start();
    }
    
    public void setBridge(SAMBridge bridge) { this.bridge = bridge; }
    
    /**
     * Actually handle the SAM protocol.
     *
     */
    protected abstract void handle();

    /**
     * Get the input stream of the socket connected to the SAM client
     *
     * @return input stream
     * @throws IOException 
     */
    protected final SocketChannel getClientSocket() {
        return socket ;
    }

    /**
     * Write a byte array on the handler's socket.  This method must
     * always be used when writing data, unless you really know what
     * you're doing.
     *
     * @param data A byte array to be written
     * @throws IOException 
     */
    protected final void writeBytes(ByteBuffer data) throws IOException {
        synchronized (socketWLock) {
            writeBytes(data, socket);
        }
    }
    
    static public void writeBytes(ByteBuffer data, SocketChannel out) throws IOException {
        while (data.hasRemaining()) out.write(data);           
        out.socket().getOutputStream().flush();
    }
    
    /** 
     * If you're crazy enough to write to the raw socket, grab the write lock
     * with getWriteLock(), synchronize against it, and write to the getOut()
     *
     * @return socket Write lock object
     */
    protected Object getWriteLock() { return socketWLock; }

    /**
     * Write a string to the handler's socket.  This method must
     * always be used when writing strings, unless you really know what
     * you're doing.
     *
     * @param str A byte array to be written
     *
     * @return True if the string was successfully written, false otherwise
     */
    protected final boolean writeString(String str) {
        synchronized (socketWLock) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Sending the client: [" + str + "]");
            return writeString(str, socket);
        }
    }

    public static boolean writeString(String str, SocketChannel out)
    {
    	try {
            writeBytes(ByteBuffer.wrap(str.getBytes("ISO-8859-1")), out);
        } catch (IOException e) {
            _log.debug("Caught IOException", e);
            return false;
        }
        return true ;
    }
    
    /**
     * Close the socket connected to the SAM client.
     *
     * @throws IOException 
     */
    protected final void closeClientSocket() throws IOException {
            socket.close();
    }

    /**
     * Stop the SAM handler
     *
     */
    public final void stopHandling() {
        synchronized (stopLock) {
            stopHandler = true;
        }
    }

    /**
     * Should the handler be stopped?
     *
     * @return True if the handler should be stopped, false otherwise
     */
    protected final boolean shouldStop() {
        synchronized (stopLock) {
            return stopHandler;
        }
    }

    /**
     * Get a string describing the handler.
     *
     * @return A String describing the handler;
     */
    @Override
    public final String toString() {
        return ("SAM handler (class: " + this.getClass().getName()
                + "; SAM version: " + verMajor + "." + verMinor
                + "; client: "
                + this.socket.socket().getInetAddress().toString() + ":"
                + this.socket.socket().getPort() + ")");
    }

    public final void run() {
        handle();
    }
}
