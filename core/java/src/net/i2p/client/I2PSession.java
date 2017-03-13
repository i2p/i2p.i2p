package net.i2p.client;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.data.SigningPrivateKey;

/**
 * <p>Define the standard means of sending and receiving messages on the 
 * I2P network by using the I2CP (the client protocol).  This is done over a 
 * bidirectional TCP socket.
 *
 * End to end encryption in I2PSession was disabled in release 0.6.
 *
 * Periodically the router will ask the client to authorize a new set of
 * tunnels to be allocated to the client, which the client can accept by sending a
 * {@link net.i2p.data.LeaseSet} signed by the {@link net.i2p.data.Destination}.  
 * In addition, the router may on occasion provide the client with an updated 
 * clock offset so that the client can stay in sync with the network (even if 
 * the host computer's clock is off).</p>
 *
 */
public interface I2PSession {

    /** Send a new message to the given destination, containing the specified
     * payload, returning true if the router feels confident that the message
     * was delivered.
     *
     * WARNING: It is recommended that you use a method that specifies the protocol and ports.
     *
     * @param dest location to send the message
     * @param payload body of the message to be sent (unencrypted)
     * @return whether it was accepted by the router for delivery or not
     */
    public boolean sendMessage(Destination dest, byte[] payload) throws I2PSessionException;

    /** Send a new message to the given destination, containing the specified
     * payload, returning true if the router feels confident that the message
     * was delivered.
     *
     * WARNING: It is recommended that you use a method that specifies the protocol and ports.
     *
     * @param dest location to send the message
     * @param payload body of the message to be sent (unencrypted)
     * @return success
     */
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size) throws I2PSessionException;

    /**
     * See I2PSessionMuxedImpl for proto/port details.
     * @return success
     * @since 0.7.1
     */
    public boolean sendMessage(Destination dest, byte[] payload, int proto, int fromport, int toport) throws I2PSessionException;

    /**
     * End-to-End Crypto is disabled, tags and keys are ignored!
     * 
     * Like sendMessage above, except the key used and the tags sent are exposed to the 
     * application.  <p> 
     * 
     * If some application layer message delivery confirmation is used,
     * rather than i2p's (slow) built in confirmation via guaranteed delivery mode, the 
     * application can update the SessionKeyManager, ala:
     * <pre>
     *   SessionKeyManager.getInstance().tagsDelivered(dest.getPublicKey(), keyUsed, tagsSent);
     * </pre>
     * If an application is using guaranteed delivery mode, this is not useful, but for 
     * applications using best effort delivery mode, if they can know with certainty that a message
     * was delivered and can update the SessionKeyManager appropriately, a significant performance
     * boost will occur (subsequent message encryption and decryption will be done via AES and a SessionTag,
     * rather than ElGamal+AES, which is 1000x slower).
     *
     * @param dest location to send the message
     * @param payload body of the message to be sent (unencrypted)
     * @param keyUsed UNUSED, IGNORED. Session key delivered to the destination for association with the tags sent.  This is essentially
     *                an output parameter - keyUsed.getData() is ignored during this call, but after the call completes,
     *                it will be filled with the bytes of the session key delivered.  Typically the key delivered is the
     *                same one as the key encrypted with, but not always.  If this is null then the key data will not be
     *                exposed.
     * @param tagsSent UNUSED, IGNORED. Set of tags delivered to the peer and associated with the keyUsed.  This is also an output parameter -
     *                 the contents of the set is ignored during the call, but afterwards it contains a set of SessionTag 
     *                 objects that were sent along side the given keyUsed.
     * @return success
     */
    public boolean sendMessage(Destination dest, byte[] payload, SessionKey keyUsed, Set<SessionTag> tagsSent) throws I2PSessionException;

    /**
     * End-to-End Crypto is disabled, tags and keys are ignored.
     * @param keyUsed UNUSED, IGNORED.
     * @param tagsSent UNUSED, IGNORED.
     * @return success
     */
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size, SessionKey keyUsed, Set<SessionTag> tagsSent) throws I2PSessionException;

    /**
     * End-to-End Crypto is disabled, tags and keys are ignored.
     * @param keyUsed UNUSED, IGNORED.
     * @param tagsSent UNUSED, IGNORED.
     * @param expire absolute expiration timestamp, NOT interval from now
     * @return success
     * @since 0.7.1
     */
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size, SessionKey keyUsed, Set<SessionTag> tagsSent, long expire) throws I2PSessionException;

    /**
     * See I2PSessionMuxedImpl for proto/port details.
     * End-to-End Crypto is disabled, tags and keys are ignored.
     * @param keyUsed UNUSED, IGNORED.
     * @param tagsSent UNUSED, IGNORED.
     * @param proto 1-254 or 0 for unset; recommended:
     *         I2PSession.PROTO_UNSPECIFIED
     *         I2PSession.PROTO_STREAMING
     *         I2PSession.PROTO_DATAGRAM
     *         255 disallowed
     * @param fromPort 1-65535 or 0 for unset
     * @param toPort 1-65535 or 0 for unset
     * @return success
     * @since 0.7.1
     */
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size, SessionKey keyUsed, Set<SessionTag> tagsSent,
                               int proto, int fromPort, int toPort) throws I2PSessionException;

    /**
     * See I2PSessionMuxedImpl for proto/port details.
     * End-to-End Crypto is disabled, tags and keys are ignored.
     * @param keyUsed UNUSED, IGNORED.
     * @param tagsSent UNUSED, IGNORED.
     * @param expire absolute expiration timestamp, NOT interval from now
     * @param proto 1-254 or 0 for unset; recommended:
     *         I2PSession.PROTO_UNSPECIFIED
     *         I2PSession.PROTO_STREAMING
     *         I2PSession.PROTO_DATAGRAM
     *         255 disallowed
     * @param fromPort 1-65535 or 0 for unset
     * @param toPort 1-65535 or 0 for unset
     * @return success
     * @since 0.7.1
     */
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size, SessionKey keyUsed, Set<SessionTag> tagsSent, long expire,
                               int proto, int fromPort, int toPort) throws I2PSessionException;

    /**
     * See I2PSessionMuxedImpl for proto/port details.
     * End-to-End Crypto is disabled, tags and keys are ignored.
     * @param keyUsed UNUSED, IGNORED.
     * @param tagsSent UNUSED, IGNORED.
     * @param expire absolute expiration timestamp, NOT interval from now
     * @param proto 1-254 or 0 for unset; recommended:
     *         I2PSession.PROTO_UNSPECIFIED
     *         I2PSession.PROTO_STREAMING
     *         I2PSession.PROTO_DATAGRAM
     *         255 disallowed
     * @param fromPort 1-65535 or 0 for unset
     * @param toPort 1-65535 or 0 for unset
     * @return success
     * @since 0.8.4
     */
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size, SessionKey keyUsed, Set<SessionTag> tagsSent, long expire,
                               int proto, int fromPort, int toPort, int flags) throws I2PSessionException;

    /**
     * See I2PSessionMuxedImpl for proto/port details.
     * See SendMessageOptions for option details.
     *
     * @param proto 1-254 or 0 for unset; recommended:
     *         I2PSession.PROTO_UNSPECIFIED
     *         I2PSession.PROTO_STREAMING
     *         I2PSession.PROTO_DATAGRAM
     *         255 disallowed
     * @param fromPort 1-65535 or 0 for unset
     * @param toPort 1-65535 or 0 for unset
     * @param options to be passed to the router
     * @return success
     * @since 0.9.2
     */
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size,
                               int proto, int fromPort, int toPort, SendMessageOptions options) throws I2PSessionException;

    /**
     * Send a message and request an asynchronous notification of delivery status.
     * Notifications will be delivered at least up to the expiration specified in the options,
     * or 60 seconds if not specified.
     *
     * See I2PSessionMuxedImpl for proto/port details.
     * See SendMessageOptions for option details.
     *
     * @param proto 1-254 or 0 for unset; recommended:
     *         I2PSession.PROTO_UNSPECIFIED
     *         I2PSession.PROTO_STREAMING
     *         I2PSession.PROTO_DATAGRAM
     *         255 disallowed
     * @param fromPort 1-65535 or 0 for unset
     * @param toPort 1-65535 or 0 for unset
     * @param options to be passed to the router
     * @return the message ID to be used for later notification to the listener
     * @throws I2PSessionException on all errors
     * @since 0.9.14
     */
    public long sendMessage(Destination dest, byte[] payload, int offset, int size,
                               int proto, int fromPort, int toPort,
                               SendMessageOptions options, SendMessageStatusListener listener) throws I2PSessionException;

    /** Receive a message that the router has notified the client about, returning
     * the payload.
     * This may only be called once for a given msgId (until the counter wraps)
     *
     * @param msgId message to fetch
     * @return unencrypted body of the message, or null if not found
     */
    public byte[] receiveMessage(int msgId) throws I2PSessionException;

    /** Instruct the router that the message received was abusive (including how
     * abusive on a 1-100 scale) in the hopes the router can do something to
     * minimize receiving abusive messages like that in the future.
     *
     * Unused. Not fully implemented.
     *
     * @param msgId message that was abusive (or -1 for not message related)
     * @param severity how abusive
     */
    public void reportAbuse(int msgId, int severity) throws I2PSessionException;

    /** Instruct the I2PSession where it should send event notifications
     *
     *  WARNING: It is recommended that you use a method that specifies the protocol and ports.
     *
     * @param lsnr listener to retrieve events
     */
    public void setSessionListener(I2PSessionListener lsnr);

    /**
     * Tear down the session and release any resources.
     *
     */
    public void destroySession() throws I2PSessionException;
    
    /**
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
     * Actually connect the session and start receiving/sending messages.
     * Connecting a primary session will not automatically connect subsessions.
     * Connecting a subsession will automatically connect the primary session
     * if not previously connected.
     */
    public void connect() throws I2PSessionException;

    /** 
     * Have we closed the session? 
     *
     * @return true if the session is closed, OR connect() has not been called yet
     */
    public boolean isClosed();
    
    /**
     * Retrieve the Destination this session serves as the endpoint for.
     * Returns null if no destination is available.
     *
     */
    public Destination getMyDestination();

    /**
     * Retrieve the decryption PrivateKey associated with the Destination
     *
     */
    public PrivateKey getDecryptionKey();

    /**
     * Retrieve the signing SigningPrivateKey associated with the Destination
     */
    public SigningPrivateKey getPrivateKey();

    /**
     * Lookup a Destination by Hash.
     * Blocking. Waits a max of 10 seconds by default.
     */
    public Destination lookupDest(Hash h) throws I2PSessionException;

    /**
     *  Lookup a Destination by Hash.
     *  Blocking.
     *  @param maxWait ms
     *  @since 0.8.3
     *  @return null on failure
     */
    public Destination lookupDest(Hash h, long maxWait) throws I2PSessionException;

    /**
     *  Ask the router to lookup a Destination by host name.
     *  Blocking. Waits a max of 10 seconds by default.
     *
     *  This only makes sense for a b32 hostname, OR outside router context.
     *  Inside router context, just query the naming service.
     *  Outside router context, this does NOT query the context naming service.
     *  Do that first if you expect a local addressbook.
     *
     *  This will log a warning for non-b32 in router context.
     *
     *  Suggested implementation:
     *
     *<pre>
     *  if (name.length() == 60 &amp;&amp; name.toLowerCase(Locale.US).endsWith(".b32.i2p")) {
     *      if (session != null)
     *          return session.lookup(Hash.create(Base32.decode(name.toLowerCase(Locale.US).substring(0, 52))));
     *      else
     *          return ctx.namingService().lookup(name); // simple session for xxx.b32.i2p handled by naming service (optional if you need lookup w/o an existing session)
     *  } else if (ctx.isRouterContext()) {
     *      return ctx.namingService().lookup(name); // hostname from router's naming service
     *  } else {
     *      Destination d = ctx.namingService().lookup(name); // local naming svc, optional
     *      if (d != null)
     *          return d;
     *      if (session != null)
     *          return session.lookup(name);
     *      // simple session (optional if you need lookup w/o an existing session)
     *      Destination rv = null;
     *      I2PClient client = new I2PSimpleClient();
     *      Properties opts = new Properties();
     *      opts.put(I2PClient.PROP_TCP_HOST, host);
     *      opts.put(I2PClient.PROP_TCP_PORT, port);
     *      I2PSession session = null;
     *      try {
     *          session = client.createSession(null, opts);
     *          session.connect();
     *          rv = session.lookupDest(name);
     *      } finally {
     *          if (session != null)
     *              session.destroySession();
     *      }
     *      return rv;
     *  }
     *</pre>
     *
     *  Requires router side to be 0.9.11 or higher. If the router is older,
     *  this will return null immediately.
     *
     *  @since 0.9.11
     */
    public Destination lookupDest(String name) throws I2PSessionException;

    /**
     *  Ask the router to lookup a Destination by host name.
     *  Blocking. See above for details.
     *  @param maxWait ms
     *  @since 0.9.11
     *  @return null on failure
     */
    public Destination lookupDest(String name, long maxWait) throws I2PSessionException;

    /**
     *  Pass updated options to the router.
     *  Does not remove properties previously present but missing from this options parameter.
     *  Fails silently if session is not connected.
     *
     *  @param options non-null
     *  @since 0.8.4
     */
    public void updateOptions(Properties options);

    /**
     * Get the current bandwidth limits. Blocking.
     * @since 0.8.3
     */
    public int[] bandwidthLimits() throws I2PSessionException;

    /**
     *  Listen on specified protocol and port.
     *
     *  An existing listener with the same proto and port is replaced.
     *  Only the listener with the best match is called back for each message.
     *
     *  @param proto 1-254 or PROTO_ANY (0) for all; recommended:
     *         I2PSession.PROTO_STREAMING
     *         I2PSession.PROTO_DATAGRAM
     *         255 disallowed
     *  @param port 1-65535 or PORT_ANY (0) for all
     *  @since 0.7.1
     */
    public void addSessionListener(I2PSessionListener lsnr, int proto, int port);

    /**
     *  Listen on specified protocol and port, and receive notification
     *  of proto, fromPort, and toPort for every message.
     *  @param proto 1-254 or PROTO_ANY (0) for all; 255 disallowed
     *  @param port 1-65535 or PORT_ANY (0) for all
     *  @since 0.7.1
     */
    public void addMuxedSessionListener(I2PSessionMuxedListener l, int proto, int port);

    /**
     *  removes the specified listener (only)
     *  @since 0.7.1
     */
    public void removeListener(int proto, int port);

    public static final int PORT_ANY = 0;
    public static final int PORT_UNSPECIFIED = 0;
    public static final int PROTO_ANY = 0;
    public static final int PROTO_UNSPECIFIED = 0;
    public static final int PROTO_STREAMING = 6;

    /**
     *  Generally a signed datagram, but could
     *  also be a raw datagram, depending on the application
     */
    public static final int PROTO_DATAGRAM = 17;

    /**
     *  A raw (unsigned) datagram
     *  @since 0.9.2
     */
    public static final int PROTO_DATAGRAM_RAW = 18;
}
