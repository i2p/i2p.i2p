/*
 * licensed under BSD license...
 * (if you know the proper clause for that, add it ...)
 */
package net.i2p.client.streaming;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Properties;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.I2PSessionListener;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.util.Log;


/**
 * Centralize the coordination and multiplexing of the local client's streaming.
 * There should be one I2PSocketManager for each I2PSession, and if an application
 * is sending and receiving data through the streaming library using an
 * I2PSocketManager, it should not attempt to call I2PSession's setSessionListener
 * or receive any messages with its .receiveMessage
 *
 */
public interface I2PSocketManager {
    public I2PSession getSession();
    
    /**
     * How long should we wait for the client to .accept() a socket before
     * sending back a NACK/Close?  
     *
     * @param ms milliseconds to wait, maximum
     */
    public void setAcceptTimeout(long ms);
    public long getAcceptTimeout();
    public void setDefaultOptions(I2PSocketOptions options);
    public I2PSocketOptions getDefaultOptions();
    public I2PServerSocket getServerSocket();
    
    public I2PSocketOptions buildOptions();
    public I2PSocketOptions buildOptions(Properties opts);

    /**
     * Create a new connected socket (block until the socket is created)
     *
     * @param peer Destination to connect to
     * @param options I2P socket options to be used for connecting
     *
     * @throws ConnectException if the peer refuses the connection
     * @throws NoRouteToHostException if the peer is not found or not reachable
     * @throws InterruptedIOException if the connection timeouts
     * @throws I2PException if there is some other I2P-related problem
     */
    public I2PSocket connect(Destination peer, I2PSocketOptions options) 
                             throws I2PException, ConnectException, 
                             NoRouteToHostException, InterruptedIOException;

    /**
     * Create a new connected socket (block until the socket is created)
     *
     * @param peer Destination to connect to
     *
     * @throws ConnectException if the peer refuses the connection
     * @throws NoRouteToHostException if the peer is not found or not reachable
     * @throws InterruptedIOException if the connection timeouts
     * @throws I2PException if there is some other I2P-related problem
     */
    public I2PSocket connect(Destination peer) throws I2PException, ConnectException, 
                                               NoRouteToHostException, InterruptedIOException;
    
    /**
     * Destroy the socket manager, freeing all the associated resources.  This
     * method will block untill all the managed sockets are closed.
     *
     */
    public void destroySocketManager();

    /**
     * Retrieve a set of currently connected I2PSockets, either initiated locally or remotely.
     *
     */
    public Set listSockets();

    /**
     * Ping the specified peer, returning true if they replied to the ping within 
     * the timeout specified, false otherwise.  This call blocks.
     *
     */
    public boolean ping(Destination peer, long timeoutMs);

    public String getName();
    public void setName(String name);

    public void init(I2PAppContext context, I2PSession session, Properties opts, String name);
    
    public void addDisconnectListener(DisconnectListener lsnr);
    public void removeDisconnectListener(DisconnectListener lsnr);
    
    public static interface DisconnectListener {
        public void sessionDisconnected();
    }
}
