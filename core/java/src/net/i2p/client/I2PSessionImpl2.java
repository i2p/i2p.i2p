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
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Properties;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.MessageStatusMessage;
import net.i2p.util.Log;

/**
 * Thread safe implementation of an I2P session running over TCP.  
 *
 * @author jrandom
 */
class I2PSessionImpl2 extends I2PSessionImpl {
    private Log _log;

    /** set of MessageState objects, representing all of the messages in the process of being sent */
    private Set _sendingStates;
    /** max # seconds to wait for confirmation of the message send */
    private final static long SEND_TIMEOUT = 60 * 1000; // 60 seconds to send 
    /** should we gzip each payload prior to sending it? */
    private final static boolean SHOULD_COMPRESS = true;

    /**
     * Create a new session, reading the Destination, PrivateKey, and SigningPrivateKey
     * from the destKeyStream, and using the specified options to connect to the router
     *
     * @throws I2PSessionException if there is a problem loading the private keys or 
     */
    public I2PSessionImpl2(I2PAppContext ctx, InputStream destKeyStream, Properties options) throws I2PSessionException {
        super(ctx, destKeyStream, options);
        _log = ctx.logManager().getLog(I2PSessionImpl2.class);
        _sendingStates = new HashSet(32);
    }

    protected long getTimeout() {
        return SEND_TIMEOUT;
    }

    public void destroySession(boolean sendDisconnect) {
        clearStates();
        super.destroySession(sendDisconnect);
    }

    public boolean sendMessage(Destination dest, byte[] payload) throws I2PSessionException {

        return sendMessage(dest, payload, new SessionKey(), new HashSet(64));
    }

    public boolean sendMessage(Destination dest, byte[] payload, SessionKey keyUsed, Set tagsSent)
                   throws I2PSessionException {
        if (isClosed()) throw new I2PSessionException("Already closed");
        if (SHOULD_COMPRESS) payload = DataHelper.compress(payload);
        // we always send as guaranteed (so we get the session keys/tags acked), 
        // but only block until the appropriate event has been reached (guaranteed
        // success or accepted).  we may want to break this out into a seperate
        // attribute, allowing both nonblocking sends and transparently managed keys,
        // as well as the nonblocking sends with application managed keys.  Later.
        if (isGuaranteed() || true) {
            return sendGuaranteed(dest, payload, keyUsed, tagsSent);
        } else {
            return sendBestEffort(dest, payload, keyUsed, tagsSent);
        }
    }

    /**
     * pull the unencrypted AND DECOMPRESSED data 
     */
    public byte[] receiveMessage(int msgId) throws I2PSessionException {
        byte compressed[] = super.receiveMessage(msgId);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("receiving message " + msgId + " with " + compressed.length + " compressed bytes");
        if (SHOULD_COMPRESS) {
            try {
                return DataHelper.decompress(compressed);
            } catch (IOException ioe) {
                throw new I2PSessionException("Error decompressing message", ioe);
            }
        } else {
            return compressed;
        }
    }

    private boolean sendBestEffort(Destination dest, byte payload[], SessionKey keyUsed, Set tagsSent)
                    throws I2PSessionException {
        SessionKey key = _context.sessionKeyManager().getCurrentKey(dest.getPublicKey());
        if (key == null) key = _context.sessionKeyManager().createSession(dest.getPublicKey());
        SessionTag tag = _context.sessionKeyManager().consumeNextAvailableTag(dest.getPublicKey(), key);
        Set sentTags = null;
        if (_context.sessionKeyManager().getAvailableTags(dest.getPublicKey(), key) < 10) {
            sentTags = createNewTags(50);
        } else if (_context.sessionKeyManager().getAvailableTimeLeft(dest.getPublicKey(), key) < 30 * 1000) {
            // if we have > 10 tags, but they expire in under 30 seconds, we want more
            sentTags = createNewTags(50);
            if (_log.shouldLog(Log.DEBUG)) _log.debug(getPrefix() + "Tags are almost expired, adding 50 new ones");
        }
        SessionKey newKey = null;
        if (false) // rekey
            newKey = _context.keyGenerator().generateSessionKey();

        long nonce = (long)_context.random().nextInt(Integer.MAX_VALUE);
        MessageState state = new MessageState(nonce, getPrefix());
        state.setKey(key);
        state.setTags(sentTags);
        state.setNewKey(newKey);
        state.setTo(dest);
        if (_log.shouldLog(Log.DEBUG)) _log.debug(getPrefix() + "Setting key = " + key);

        if (keyUsed != null) {
            if (newKey != null)
                keyUsed.setData(newKey.getData());
            else
                keyUsed.setData(key.getData());
        }
        if (tagsSent != null) {
            if (sentTags != null) {
                tagsSent.addAll(sentTags);
            }
        }

        synchronized (_sendingStates) {
            _sendingStates.add(state);
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getPrefix() + "Adding sending state " + state.getMessageId() + " / "
                       + state.getNonce());
        _producer.sendMessage(this, dest, nonce, payload, tag, key, sentTags, newKey);
        state.waitFor(MessageStatusMessage.STATUS_SEND_ACCEPTED, 
                      _context.clock().now() + getTimeout());
        synchronized (_sendingStates) {
            _sendingStates.remove(state);
        }
        boolean found = state.received(MessageStatusMessage.STATUS_SEND_ACCEPTED);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getPrefix() + "After waitFor sending state " + state.getMessageId().getMessageId()
                       + " / " + state.getNonce() + " found = " + found);
        if (found) {
            if (_log.shouldLog(Log.INFO))
                _log.info(getPrefix() + "Message sent after " + state.getElapsed() + "ms with "
                          + payload.length + " bytes");
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info(getPrefix() + "Message send failed after " + state.getElapsed() + "ms with "
                          + payload.length + " bytes");
            if (_log.shouldLog(Log.ERROR))
                _log.error(getPrefix() + "Never received *accepted* from the router!  dropping and reconnecting");
            disconnect();
            return false;
        }
        return found;
    }

    private boolean sendGuaranteed(Destination dest, byte payload[], SessionKey keyUsed, Set tagsSent)
                    throws I2PSessionException {
        SessionKey key = _context.sessionKeyManager().getCurrentKey(dest.getPublicKey());
        if (key == null) key = _context.sessionKeyManager().createSession(dest.getPublicKey());
        SessionTag tag = _context.sessionKeyManager().consumeNextAvailableTag(dest.getPublicKey(), key);
        Set sentTags = null;
        if (_context.sessionKeyManager().getAvailableTags(dest.getPublicKey(), key) < 10) {
            sentTags = createNewTags(50);
        } else if (_context.sessionKeyManager().getAvailableTimeLeft(dest.getPublicKey(), key) < 30 * 1000) {
            // if we have > 10 tags, but they expire in under 30 seconds, we want more
            sentTags = createNewTags(50);
            if (_log.shouldLog(Log.DEBUG)) _log.debug(getPrefix() + "Tags are almost expired, adding 50 new ones");
        }
        SessionKey newKey = null;
        if (false) // rekey
            newKey = _context.keyGenerator().generateSessionKey();

        long nonce = (long)_context.random().nextInt(Integer.MAX_VALUE);
        MessageState state = new MessageState(nonce, getPrefix());
        state.setKey(key);
        state.setTags(sentTags);
        state.setNewKey(newKey);
        state.setTo(dest);
        if (_log.shouldLog(Log.DEBUG)) _log.debug(getPrefix() + "Setting key = " + key);

        if (keyUsed != null) {
            if (newKey != null)
                keyUsed.setData(newKey.getData());
            else
                keyUsed.setData(key.getData());
        }
        if (tagsSent != null) {
            if (sentTags != null) {
                tagsSent.addAll(sentTags);
            }
        }

        synchronized (_sendingStates) {
            _sendingStates.add(state);
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getPrefix() + "Adding sending state " + state.getMessageId() + " / "
                       + state.getNonce());
        _producer.sendMessage(this, dest, nonce, payload, tag, key, sentTags, newKey);
        if (isGuaranteed())
            state.waitFor(MessageStatusMessage.STATUS_SEND_GUARANTEED_SUCCESS, 
                          _context.clock().now() + SEND_TIMEOUT);
        else
            state.waitFor(MessageStatusMessage.STATUS_SEND_ACCEPTED, 
                          _context.clock().now() + SEND_TIMEOUT);
        synchronized (_sendingStates) {
            _sendingStates.remove(state);
        }
        boolean found = state.received(MessageStatusMessage.STATUS_SEND_GUARANTEED_SUCCESS);
        boolean accepted = state.received(MessageStatusMessage.STATUS_SEND_ACCEPTED);

        if ((!accepted) || (state.getMessageId() == null)) {
            if (_log.shouldLog(Log.CRIT))
                _log.log(Log.CRIT, getPrefix() + "State with nonce " + state.getNonce()
                           + " was not accepted?  (no messageId!! found=" + found 
                           + " msgId=" + state.getMessageId() + ")");
            //if (true) 
            //    throw new OutOfMemoryError("not really an OOM, but more of jr fucking shit up");
            nackTags(state);
            return false;
        }

        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getPrefix() + "After waitFor sending state " + state.getMessageId().getMessageId()
                       + " / " + state.getNonce() + " found = " + found);
        
        // WARNING: this will always be false for mode=BestEffort, even though the message may go 
        // through, causing every datagram to be ElGamal encrypted!  
        // TODO: Fix this to include support for acks received after the sendMessage completes
        if (found) {
            if (_log.shouldLog(Log.INFO))
                _log.info(getPrefix() + "Message sent after " + state.getElapsed() + "ms with "
                          + payload.length + " bytes");
            ackTags(state);
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info(getPrefix() + "Message send failed after " + state.getElapsed() + "ms with "
                          + payload.length + " bytes");
            nackTags(state);
        }
        return found;
    }

    private void ackTags(MessageState state) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getPrefix() + "ack tags for msgId " + state.getMessageId() + " / "
                       + state.getNonce() + " key = " + state.getKey() + ", tags = "
                       + state.getTags());
        if ((state.getTags() != null) && (state.getTags().size() > 0)) {
            if (state.getNewKey() == null)
                _context.sessionKeyManager().tagsDelivered(state.getTo().getPublicKey(), state.getKey(), state.getTags());
            else
                _context.sessionKeyManager().tagsDelivered(state.getTo().getPublicKey(), state.getNewKey(), state.getTags());
        }
    }

    private void nackTags(MessageState state) {
        if (_log.shouldLog(Log.INFO))
            _log.info(getPrefix() + "nack tags for msgId " + state.getMessageId() + " / " + state.getNonce()
                      + " key = " + state.getKey());
        _context.sessionKeyManager().failTags(state.getTo().getPublicKey());
    }

    public void receiveStatus(int msgId, long nonce, int status) {
        if (_log.shouldLog(Log.DEBUG)) _log.debug(getPrefix() + "Received status " + status + " for msgId " + msgId + " / " + nonce);
        MessageState state = null;
        synchronized (_sendingStates) {
            for (Iterator iter = _sendingStates.iterator(); iter.hasNext();) {
                state = (MessageState) iter.next();
                if (_log.shouldLog(Log.DEBUG)) _log.debug(getPrefix() + "State " + state.getMessageId() + " / " + state.getNonce());
                if (state.getNonce() == nonce) {
                    if (_log.shouldLog(Log.DEBUG)) _log.debug(getPrefix() + "Found a matching state");
                    break;
                } else if ((state.getMessageId() != null) && (state.getMessageId().getMessageId() == msgId)) {
                    if (_log.shouldLog(Log.DEBUG)) _log.debug(getPrefix() + "Found a matching state by msgId");
                    break;
                } else {
                    if (_log.shouldLog(Log.DEBUG)) _log.debug(getPrefix() + "State does not match");
                    state = null;
                }
            }
        }

        if (state != null) {
            if (state.getMessageId() == null) {
                MessageId id = new MessageId();
                id.setMessageId(msgId);
                state.setMessageId(id);
            }
            state.receive(status);
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info(getPrefix() + "No matching state for messageId " + msgId + " / " + nonce
                          + " w/ status = " + status);
        }
    }

    /**
     * Called whenever we want to reconnect (used only in the superclass).  We need
     * to override this to clear out the message state
     *
     */
    protected boolean reconnect() {
        // even if we succeed in reconnecting, we want to clear the old states, 
        // since this will be a new sessionId
        clearStates();
        return super.reconnect();
    }

    private void clearStates() {
        synchronized (_sendingStates) {
            for (Iterator iter = _sendingStates.iterator(); iter.hasNext();) {
                MessageState state = (MessageState) iter.next();
                state.cancel();
            }
            if (_log.shouldLog(Log.INFO)) _log.info(getPrefix() + "Disconnecting " + _sendingStates.size() + " states");
            _sendingStates.clear();
        }
    }
}