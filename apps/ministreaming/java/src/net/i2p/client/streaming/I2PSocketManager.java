/*
 * licensed under BSD license...
 * (if you know the proper clause for that, add it ...)
 */
package net.i2p.client.streaming;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Properties;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PSession;
import net.i2p.data.Destination;


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

    /**
     *  Update the options on a running socket manager.
     *  Parameters in the I2PSocketOptions interface may be changed directly
     *  with the setters; no need to use this method for those.
     *  This does NOT update the underlying I2CP or tunnel options; use getSession().updateOptions() for that.
     *  @param options as created from a call to buildOptions(properties), non-null
     */
    public void setDefaultOptions(I2PSocketOptions options);

    /**
     *  Current options, not a copy, setters may be used to make changes.
     */
    public I2PSocketOptions getDefaultOptions();

    public I2PServerSocket getServerSocket();
    
    /**
     *  Create a copy of the current options, to be used in a setDefaultOptions() call.
     */
    public I2PSocketOptions buildOptions();

    /**
     *  Create a modified copy of the current options, to be used in a setDefaultOptions() call.
     *  @param opts The new options, may be null
     */
    public I2PSocketOptions buildOptions(Properties opts);

    /**
     * Create a new connected socket (block until the socket is created)
     *
     * @param peer Destination to connect to
     * @param options I2P socket options to be used for connecting
     *
     * @return new connected socket
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
     * @return new connected socket
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
     * @return a set of currently connected I2PSockets
     */
    public Set<I2PSocket> listSockets();

    /**
     * Ping the specified peer, returning true if they replied to the ping within 
     * the timeout specified, false otherwise.  This call blocks.
     *
     * @param peer Destination to ping
     * @param timeoutMs timeout in ms
     * @return success or failure
     */
    public boolean ping(Destination peer, long timeoutMs);

    public String getName();
    public void setName(String name);

    /**
     * Deprecated - Factory will initialize.
     * @throws UnsupportedOperationException always
     */
    public void init(I2PAppContext context, I2PSession session, Properties opts, String name);
    
    public void addDisconnectListener(DisconnectListener lsnr);
    public void removeDisconnectListener(DisconnectListener lsnr);
    
    public static interface DisconnectListener {
        public void sessionDisconnected();
    }

    /**
     *  Like getServerSocket but returns a real ServerSocket for easier porting of apps.
     *  @since 0.8.4
     */
    public ServerSocket getStandardServerSocket() throws IOException;

    /**
     *  Like connect() but returns a real Socket, and throws only IOE,
     *  for easier porting of apps.
     *  @since 0.8.4
     */
    public Socket connectToSocket(Destination peer) throws IOException;

    /**
     *  Like connect() but returns a real Socket, and throws only IOE,
     *  for easier porting of apps.
     *  @param timeout ms if > 0, forces blocking (disables connectDelay)
     *  @since 0.8.4
     */
    public Socket connectToSocket(Destination peer, int timeout) throws IOException;
}
