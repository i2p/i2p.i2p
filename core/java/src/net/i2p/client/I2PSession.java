package net.i2p.client;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Set;

import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SigningPrivateKey;

/**
 * <p>Define the standard means of sending and receiving messages on the 
 * I2P network by using the I2CP (the client protocol).  This is done over a 
 * bidirectional TCP socket and never sends any private keys - all end to end 
 * encryption is done transparently within the client's I2PSession
 * itself.  Periodically the router will ask the client to authorize a new set of
 * tunnels to be allocated to the client, which the client can accept by sending a
 * {@link net.i2p.data.LeaseSet} signed by the {@link net.i2p.data.Destination}.  
 * In addition, the router may on occation provide the client with an updated 
 * clock offset so that the client can stay in sync with the network (even if 
 * the host computer's clock is off).</p>
 *
 */
public interface I2PSession {
    /** Send a new message to the given destination, containing the specified
     * payload, returning true if the router feels confident that the message
     * was delivered.
     * @param dest location to send the message
     * @param payload body of the message to be sent (unencrypted)
     * @return whether it was accepted by the router for delivery or not
     */
    public boolean sendMessage(Destination dest, byte[] payload) throws I2PSessionException;
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size) throws I2PSessionException;
    /** See I2PSessionMuxedImpl for details */
    public boolean sendMessage(Destination dest, byte[] payload, int proto, int fromport, int toport) throws I2PSessionException;

    /**
     * Like sendMessage above, except the key used and the tags sent are exposed to the 
     * application.  <p /> 
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
     * @param keyUsed session key delivered to the destination for association with the tags sent.  This is essentially
     *                an output parameter - keyUsed.getData() is ignored during this call, but after the call completes,
     *                it will be filled with the bytes of the session key delivered.  Typically the key delivered is the
     *                same one as the key encrypted with, but not always.  If this is null then the key data will not be
     *                exposed.
     * @param tagsSent set of tags delivered to the peer and associated with the keyUsed.  This is also an output parameter -
     *                 the contents of the set is ignored during the call, but afterwards it contains a set of SessionTag 
     *                 objects that were sent along side the given keyUsed.
     */
    public boolean sendMessage(Destination dest, byte[] payload, SessionKey keyUsed, Set tagsSent) throws I2PSessionException;
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size, SessionKey keyUsed, Set tagsSent) throws I2PSessionException;
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size, SessionKey keyUsed, Set tagsSent, long expire) throws I2PSessionException;
    /** See I2PSessionMuxedImpl for details */
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size, SessionKey keyUsed, Set tagsSent,
                               int proto, int fromport, int toport) throws I2PSessionException;
    /** See I2PSessionMuxedImpl for details */
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size, SessionKey keyUsed, Set tagsSent, long expire,
                               int proto, int fromport, int toport) throws I2PSessionException;

    /** Receive a message that the router has notified the client about, returning
     * the payload.
     * @param msgId message to fetch
     * @return unencrypted body of the message
     */
    public byte[] receiveMessage(int msgId) throws I2PSessionException;

    /** Instruct the router that the message received was abusive (including how
     * abusive on a 1-100 scale) in the hopes the router can do something to
     * minimize receiving abusive messages like that in the future.
     * @param msgId message that was abusive (or -1 for not message related)
     * @param severity how abusive
     */
    public void reportAbuse(int msgId, int severity) throws I2PSessionException;

    /** Instruct the I2PSession where it should send event notifications
     * @param lsnr listener to retrieve events
     */
    public void setSessionListener(I2PSessionListener lsnr);

    /**
     * Tear down the session and release any resources.
     *
     */
    public void destroySession() throws I2PSessionException;

    /**
     * Actually connect the session and start receiving/sending messages
     *
     */
    public void connect() throws I2PSessionException;

    /** 
     * Have we closed the session? 
     *
     * @return true if the session is closed
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
     * Lookup up a Hash
     *
     */
    public Destination lookupDest(Hash h) throws I2PSessionException;

    /**
     * Get the current bandwidth limits
     */
    public int[] bandwidthLimits() throws I2PSessionException;

    /** See I2PSessionMuxedImpl for details */
    public void addSessionListener(I2PSessionListener lsnr, int proto, int port);
    /** See I2PSessionMuxedImpl for details */
    public void addMuxedSessionListener(I2PSessionMuxedListener l, int proto, int port);
    /** See I2PSessionMuxedImpl for details */
    public void removeListener(int proto, int port);

    public static final int PORT_ANY = 0;
    public static final int PORT_UNSPECIFIED = 0;
    public static final int PROTO_ANY = 0;
    public static final int PROTO_UNSPECIFIED = 0;
    public static final int PROTO_STREAMING = 6;
    public static final int PROTO_DATAGRAM = 17;
}
