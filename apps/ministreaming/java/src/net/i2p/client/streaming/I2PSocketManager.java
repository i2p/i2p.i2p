/*
 * licensed under BSD license...
 * (if you know the proper clause for that, add it ...)
 */
package net.i2p.client.streaming;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
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

    /**
     *  @return the session, non-null
     */
    public I2PSession getSession();
    
    /**
     *  For a server, you must call connect() on the returned object.
     *  Connecting the primary session does NOT connect any subsessions.
     *  If the primary session is not connected, connecting a subsession will connect the primary session first.
     *
     *  @return a new subsession, non-null
     *  @param privateKeyStream null for transient, if non-null must have same encryption keys as primary session
     *                          and different signing keys
     *  @param opts subsession options if any, may be null
     *  @since 0.9.21
     */
    public I2PSession addSubsession(InputStream privateKeyStream, Properties opts) throws I2PSessionException;
    
    /**
     *  @since 0.9.21
     */
    public void removeSubsession(I2PSession session);
    
    /**
     *  @return a list of subsessions, non-null, does not include the primary session
     *  @since 0.9.21
     */
    public List<I2PSession> getSubsessions();

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

    /**
     *  Returns non-null socket.
     *  This method does not throw exceptions, but methods on the returned socket
     *  may throw exceptions if the socket or socket manager is closed.
     *
     *  @return non-null
     */
    public I2PServerSocket getServerSocket();
    
    /**
     *  Create a copy of the current options, to be used in a setDefaultOptions() call.
     */
    public I2PSocketOptions buildOptions();

    /**
     *  Create a modified copy of the current options, to be used in a setDefaultOptions() call.
     *
     *  As of 0.9.19, defaults in opts are honored.
     *
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
     * The socket manager CANNOT be reused after this.
     */
    public void destroySocketManager();
    
    /**
     * Has the socket manager been destroyed?
     *
     * @since 0.9.9
     */
    public boolean isDestroyed();

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
     * Uses the ports from the default options.
     *
     * @param peer Destination to ping
     * @param timeoutMs timeout in ms, greater than zero
     * @throws IllegalArgumentException
     * @return success or failure
     */
    public boolean ping(Destination peer, long timeoutMs);

    /**
     * Ping the specified peer, returning true if they replied to the ping within 
     * the timeout specified, false otherwise.  This call blocks.
     *
     * Uses the ports specified.
     *
     * @param peer Destination to ping
     * @param localPort 0 - 65535
     * @param remotePort 0 - 65535
     * @param timeoutMs timeout in ms, greater than zero
     * @return success or failure
     * @throws IllegalArgumentException
     * @since 0.9.12
     */
    public boolean ping(Destination peer, int localPort, int remotePort, long timeoutMs);

    /**
     * Ping the specified peer, returning true if they replied to the ping within 
     * the timeout specified, false otherwise.  This call blocks.
     *
     * Uses the ports specified.
     *
     * @param peer Destination to ping
     * @param localPort 0 - 65535
     * @param remotePort 0 - 65535
     * @param timeoutMs timeout in ms, greater than zero
     * @param payload to include in the ping
     * @return the payload received in the pong, zero-length if none, null on failure or timeout
     * @throws IllegalArgumentException
     * @since 0.9.18
     */
    public byte[] ping(Destination peer, int localPort, int remotePort, long timeoutMs, byte[] payload);

    /**
     *  For logging / diagnostics only
     */
    public String getName();

    /**
     *  For logging / diagnostics only
     */
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
     *  @param timeout ms if &gt; 0, forces blocking (disables connectDelay)
     *  @since 0.8.4
     */
    public Socket connectToSocket(Destination peer, int timeout) throws IOException;
}
