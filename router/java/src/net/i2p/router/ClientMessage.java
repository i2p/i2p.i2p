package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.Payload;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.SessionConfig;

/**
 * Wrap a message either destined for a local client or received from one.
 *
 * @author jrandom
 */
public class ClientMessage {
    private Payload _payload;
    private Destination _destination;
    private Destination _fromDestination;
    //private MessageReceptionInfo _receptionInfo;
    private SessionConfig _senderConfig;
    private Hash _destinationHash;
    private MessageId _messageId;
    private long _expiration;
    /** only for outbound messages */
    private int _flags;
    
    public ClientMessage() {
    }
    
    /**
     * Retrieve the payload of the message.  All ClientMessage objects should have
     * a payload
     *
     */
    public Payload getPayload() { return _payload; }
    public void setPayload(Payload payload) { _payload = payload; }
    
    /**
     * Retrieve the destination to which this message is directed.  All ClientMessage
     * objects should have a destination.
     *
     */
    public Destination getDestination() { return _destination; }
    public void setDestination(Destination dest) { _destination = dest; }
    
    /**
     * 
     *
     */
    public Destination getFromDestination() { return _fromDestination; }
    public void setFromDestination(Destination dest) { _fromDestination = dest; }
    
    /**
     * Retrieve the destination to which this message is directed.  All ClientMessage
     * objects should have a destination.
     *
     */
    public Hash getDestinationHash() { return _destinationHash; }
    public void setDestinationHash(Hash dest) { _destinationHash = dest; }
    
    /**
     * 
     */
    public MessageId getMessageId() { return _messageId; }
    public void setMessageId(MessageId id) { _messageId = id; }
    
    /**
     * Retrieve the information regarding how the router received this message.  Only
     * messages received from the network will have this information, not locally 
     * originated ones.
     *
     */
    //public MessageReceptionInfo getReceptionInfo() { return _receptionInfo; }
    //public void setReceptionInfo(MessageReceptionInfo info) { _receptionInfo = info; }

    /**
     * Retrieve the session config of the client that sent the message.  This will only be available
     * if the client was local
     *
     */
    public SessionConfig getSenderConfig() { return _senderConfig; }
    public void setSenderConfig(SessionConfig config) { _senderConfig = config; }

    /**
     * Expiration requested by the client that sent the message.  This will only be available
     * for locally originated messages.
     *
     */
    public long getExpiration() { return _expiration; }
    public void setExpiration(long e) { _expiration = e; }

    /**
     * Flags requested by the client that sent the message.  This will only be available
     * for locally originated messages.
     *
     * @since 0.8.4
     */
    public int getFlags() { return _flags; }

    /**
     * @since 0.8.4
     */
    public void setFlags(int f) { _flags = f; }
}
