package net.i2p.client.impl;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PSessionException;
import net.i2p.client.I2PSessionListener;
import net.i2p.client.I2PSessionMuxedListener;
import net.i2p.client.SendMessageOptions;
import net.i2p.client.SendMessageStatusListener;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.MessageStatusMessage;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 * Thread safe implementation of an I2P session running over TCP.  
 *
 * Unused directly, see I2PSessionMuxedImpl extension.
 *
 * @author jrandom
 */
class I2PSessionImpl2 extends I2PSessionImpl {

    /** set of MessageState objects, representing all of the messages in the process of being sent */
    protected final Map<Long, MessageState> _sendingStates;
    protected final AtomicLong _sendMessageNonce;
    /** max # seconds to wait for confirmation of the message send */
    private final static long SEND_TIMEOUT = 60 * 1000; // 60 seconds to send 
    /** should we gzip each payload prior to sending it? */
    private final static boolean SHOULD_COMPRESS = true;
    private final static boolean SHOULD_DECOMPRESS = true;
    /** Don't expect any MSMs from the router for outbound traffic @since 0.8.1 */
    protected boolean _noEffort;

    private static final long REMOVE_EXPIRED_TIME = 63*1000;

    /**
     * for extension by SimpleSession (no dest)
     */
    protected I2PSessionImpl2(I2PAppContext context, Properties options,
                              I2PClientMessageHandlerMap handlerMap) {
        super(context, options, handlerMap);
        _sendingStates = null;
        _sendMessageNonce = null;
    }

    /**
     * for extension by I2PSessionMuxedImpl
     *
     * Create a new session, reading the Destination, PrivateKey, and SigningPrivateKey
     * from the destKeyStream, and using the specified options to connect to the router
     *
     * @param destKeyStream stream containing the private key data,
     *                             format is specified in {@link net.i2p.data.PrivateKeyFile PrivateKeyFile}
     * @param options set of options to configure the router with, if null will use System properties
     * @throws I2PSessionException if there is a problem loading the private keys
     */
    protected I2PSessionImpl2(I2PAppContext ctx, InputStream destKeyStream, Properties options) throws I2PSessionException {
        super(ctx, destKeyStream, options);
        _sendingStates = new ConcurrentHashMap<Long, MessageState>(32);
        _sendMessageNonce = new AtomicLong();
        // default is BestEffort
        _noEffort = "none".equals(getOptions().getProperty(I2PClient.PROP_RELIABILITY, "").toLowerCase(Locale.US));

        //ctx.statManager().createRateStat("i2cp.sendBestEffortTotalTime", "how long to do the full sendBestEffort call?", "i2cp", new long[] { 10*60*1000 } );
        //ctx.statManager().createRateStat("i2cp.sendBestEffortStage0", "first part of sendBestEffort?", "i2cp", new long[] { 10*60*1000 } );
        //ctx.statManager().createRateStat("i2cp.sendBestEffortStage1", "second part of sendBestEffort?", "i2cp", new long[] { 10*60*1000 } );
        //ctx.statManager().createRateStat("i2cp.sendBestEffortStage2", "third part of sendBestEffort?", "i2cp", new long[] { 10*60*1000 } );
        //ctx.statManager().createRateStat("i2cp.sendBestEffortStage3", "fourth part of sendBestEffort?", "i2cp", new long[] { 10*60*1000 } );
        //ctx.statManager().createRateStat("i2cp.sendBestEffortStage4", "fifth part of sendBestEffort?", "i2cp", new long[] { 10*60*1000 } );
        //_context.statManager().createRateStat("i2cp.receiveStatusTime.0", "How long it took to get status=0 back", "i2cp", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("i2cp.receiveStatusTime.1", "How long it took to get status=1 back", "i2cp", new long[] { 10*60*1000 });
        // best effort codes unused
        //_context.statManager().createRateStat("i2cp.receiveStatusTime.2", "How long it took to get status=2 back", "i2cp", new long[] { 60*1000, 10*60*1000 });
        //_context.statManager().createRateStat("i2cp.receiveStatusTime.3", "How long it took to get status=3 back", "i2cp", new long[] { 60*1000, 10*60*1000 });
        _context.statManager().createRateStat("i2cp.receiveStatusTime.4", "How long it took to get status=4 back", "i2cp", new long[] { 10*60*1000 });
        _context.statManager().createRateStat("i2cp.receiveStatusTime.5", "How long it took to get status=5 back", "i2cp", new long[] { 10*60*1000 });
        //_context.statManager().createRateStat("i2cp.receiveStatusTime", "How long it took to get any status", "i2cp", new long[] { 10*60*1000 });
        _context.statManager().createRateStat("i2cp.tx.msgCompressed", "compressed size transferred", "i2cp", new long[] { 30*60*1000 });
        _context.statManager().createRateStat("i2cp.tx.msgExpanded", "size before compression", "i2cp", new long[] { 30*60*1000 });
    }

    /*
     * For extension by SubSession via I2PSessionMuxedImpl
     *
     * @param destKeyStream stream containing the private key data,
     *                             format is specified in {@link net.i2p.data.PrivateKeyFile PrivateKeyFile}
     * @param options set of options to configure the router with, if null will use System properties
     * @since 0.9.21
     */
    protected I2PSessionImpl2(I2PSessionImpl primary, InputStream destKeyStream, Properties options) throws I2PSessionException {
        super(primary, destKeyStream, options);
        _sendingStates = new ConcurrentHashMap<Long, MessageState>(32);
        _sendMessageNonce = new AtomicLong();
        _noEffort = "none".equals(getOptions().getProperty(I2PClient.PROP_RELIABILITY, "").toLowerCase(Locale.US));
        _context.statManager().createRateStat("i2cp.receiveStatusTime.1", "How long it took to get status=1 back", "i2cp", new long[] { 10*60*1000 });
        _context.statManager().createRateStat("i2cp.receiveStatusTime.4", "How long it took to get status=4 back", "i2cp", new long[] { 10*60*1000 });
        _context.statManager().createRateStat("i2cp.receiveStatusTime.5", "How long it took to get status=5 back", "i2cp", new long[] { 10*60*1000 });
        _context.statManager().createRateStat("i2cp.tx.msgCompressed", "compressed size transferred", "i2cp", new long[] { 30*60*1000 });
        _context.statManager().createRateStat("i2cp.tx.msgExpanded", "size before compression", "i2cp", new long[] { 30*60*1000 });
    }

    /**
     *  Fire up a periodic task to check for unclaimed messages
     *  @since 0.9.14
     */
    @Override
    protected void startVerifyUsage() {
        super.startVerifyUsage();
        new RemoveExpired();
    }

    /**
     *  Check for expired message states, without wastefully setting a timer for each
     *  message.
     *  @since 0.9.14
     */
    private class RemoveExpired extends SimpleTimer2.TimedEvent {
        
        public RemoveExpired() {
             super(_context.simpleTimer2(), REMOVE_EXPIRED_TIME);
        }

        public void timeReached() {
            if (isClosed())
                return;
            if (!_sendingStates.isEmpty()) {
                long now = _context.clock().now();
                for (Iterator<MessageState> iter = _sendingStates.values().iterator(); iter.hasNext(); ) {
                    MessageState state = iter.next();
                    if (state.getExpires() < now)
                        iter.remove();
                }
            }
            schedule(REMOVE_EXPIRED_TIME);
        }
    }


    protected long getTimeout() {
        return SEND_TIMEOUT;
    }
    
    @Override
    public void destroySession(boolean sendDisconnect) {
        clearStates();
        super.destroySession(sendDisconnect);
    }

    /** Don't bother if really small.
     *  Three 66-byte messages will fit in one tunnel message.
     *  Four messages don't fit no matter how small. So below 66 it isn't worth it.
     *  See ConnectionOptions.java in the streaming lib for similar calculations.
     *  Since we still have to pass it through gzip -0 the CPU savings
     *  is trivial but it's the best we can do for now. See below.
     *  i2cp.gzip defaults to SHOULD_COMPRESS = true.
     *  Perhaps the http server (which does its own compression)
     *  and P2P apps (with generally uncompressible data) should
     *  set to false.
     *
     *  Todo: don't compress if destination is local?
     */
    private static final int DONT_COMPRESS_SIZE = 66;

    protected boolean shouldCompress(int size) {
         if (size <= DONT_COMPRESS_SIZE)
             return false;
         String p = getOptions().getProperty("i2cp.gzip");
         if (p != null)
             return Boolean.parseBoolean(p);
         return SHOULD_COMPRESS;
    }
    
    /** @throws UnsupportedOperationException always, use MuxedImpl */
    public void addSessionListener(I2PSessionListener lsnr, int proto, int port) {
        throw new UnsupportedOperationException("Use MuxedImpl");
    }
    /** @throws UnsupportedOperationException always, use MuxedImpl */
    public void addMuxedSessionListener(I2PSessionMuxedListener l, int proto, int port) {
        throw new UnsupportedOperationException("Use MuxedImpl");
    }
    /** @throws UnsupportedOperationException always, use MuxedImpl */
    public void removeListener(int proto, int port) {
        throw new UnsupportedOperationException("Use MuxedImpl");
    }
    /** @throws UnsupportedOperationException always, use MuxedImpl */
    public boolean sendMessage(Destination dest, byte[] payload, int proto, int fromport, int toport) throws I2PSessionException {
        throw new UnsupportedOperationException("Use MuxedImpl");
    }
    /** @throws UnsupportedOperationException always, use MuxedImpl */
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size, SessionKey keyUsed, Set<SessionTag> tagsSent,
                               int proto, int fromport, int toport) throws I2PSessionException {
        throw new UnsupportedOperationException("Use MuxedImpl");
    }
    /** @throws UnsupportedOperationException always, use MuxedImpl */
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size, SessionKey keyUsed, Set<SessionTag> tagsSent, long expire,
                               int proto, int fromport, int toport) throws I2PSessionException {
        throw new UnsupportedOperationException("Use MuxedImpl");
    }
    /** @throws UnsupportedOperationException always, use MuxedImpl */
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size, SessionKey keyUsed, Set<SessionTag> tagsSent, long expire,
                               int proto, int fromport, int toport, int flags) throws I2PSessionException {
        throw new UnsupportedOperationException("Use MuxedImpl");
    }
    /** @throws UnsupportedOperationException always, use MuxedImpl */
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size,
                               int proto, int fromport, int toport, SendMessageOptions options) throws I2PSessionException {
        throw new UnsupportedOperationException("Use MuxedImpl");
    }
    /** @throws UnsupportedOperationException always, use MuxedImpl */
    public long sendMessage(Destination dest, byte[] payload, int offset, int size,
                               int proto, int fromport, int toport,
                               SendMessageOptions options, SendMessageStatusListener listener) throws I2PSessionException {
        throw new UnsupportedOperationException("Use MuxedImpl");
    }

    /** unused, see MuxedImpl override */
    @Override
    public boolean sendMessage(Destination dest, byte[] payload) throws I2PSessionException {
        return sendMessage(dest, payload, 0, payload.length);
    }

    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size) throws I2PSessionException {
        // we don't do end-to-end crypto any more
        //return sendMessage(dest, payload, offset, size, new SessionKey(), new HashSet(64), 0);
        return sendMessage(dest, payload, offset, size, null, null, 0);
    }
    
    /**
     * @param keyUsed unused - no end-to-end crypto
     * @param tagsSent unused - no end-to-end crypto
     */
    @Override
    public boolean sendMessage(Destination dest, byte[] payload, SessionKey keyUsed, Set<SessionTag> tagsSent) throws I2PSessionException {
        return sendMessage(dest, payload, 0, payload.length, keyUsed, tagsSent, 0);
    }

    /**
     * @param keyUsed unused - no end-to-end crypto
     * @param tagsSent unused - no end-to-end crypto
     */
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size, SessionKey keyUsed, Set<SessionTag> tagsSent)
                   throws I2PSessionException {
        return sendMessage(dest, payload, offset, size, keyUsed, tagsSent, 0);
    }

    /**
     * Unused? see MuxedImpl override
     *
     * @param keyUsed unused - no end-to-end crypto
     * @param tagsSent unused - no end-to-end crypto
     */
    public boolean sendMessage(Destination dest, byte[] payload, int offset, int size, SessionKey keyUsed, Set<SessionTag> tagsSent, long expires)
                   throws I2PSessionException {
        if (_log.shouldLog(Log.DEBUG)) _log.debug("sending message");
        verifyOpen();
        updateActivity();

        // Sadly there is no way to send something completely uncompressed in a backward-compatible way,
        // so we have to still send it in a gzip format, which adds 23 bytes (2.4% for a 960-byte msg)
        // (10 byte header + 5 byte block header + 8 byte trailer)
        // In the future we can add a one-byte magic number != 0x1F to signal an uncompressed msg
        // (Gzip streams start with 0x1F 0x8B 0x08)
        // assuming we don't need the CRC-32 that comes with gzip (do we?)
        // Maybe implement this soon in receiveMessage() below so we are ready
        // in case we ever make an incompatible network change.
        // This would save 22 of the 23 bytes and a little CPU.
        boolean sc = shouldCompress(size);
        if (sc)
            payload = DataHelper.compress(payload, offset, size);
        else
            payload = DataHelper.compress(payload, offset, size, DataHelper.NO_COMPRESSION);
        //else throw new IllegalStateException("we need to update sendGuaranteed to support partial send");

        int compressed = payload.length;
        if (_log.shouldLog(Log.INFO)) {
            String d = dest.calculateHash().toBase64().substring(0,4);
            _log.info("sending message to: " + d + " compress? " + sc + " sizeIn=" + size + " sizeOut=" + compressed);
        }
        _context.statManager().addRateData("i2cp.tx.msgCompressed", compressed);
        _context.statManager().addRateData("i2cp.tx.msgExpanded", size);
        if (_noEffort)
            return sendNoEffort(dest, payload, expires, 0);
        else
            return sendBestEffort(dest, payload, keyUsed, tagsSent, expires);
    }

    /**
     * pull the unencrypted AND DECOMPRESSED data 
     */
    @Override
    public byte[] receiveMessage(int msgId) throws I2PSessionException {
        byte compressed[] = super.receiveMessage(msgId);
        if (compressed == null) {
            _log.error("Error: message " + msgId + " already received!");
            return null;
        }
        // future - check magic number to see whether to decompress
        if (SHOULD_DECOMPRESS) {
            try {
                return DataHelper.decompress(compressed);
            } catch (IOException ioe) {
                //throw new I2PSessionException("Error decompressing message", ioe);
                if (_log.shouldWarn())
                    _log.warn("Error decompressing message", ioe);
                return null;
            }
        }
        return compressed;
    }
    
    /**
     * @param keyUsed unused - no end-to-end crypto
     * @param tagsSent unused - no end-to-end crypto
     */
    protected boolean sendBestEffort(Destination dest, byte payload[], SessionKey keyUsed, Set<SessionTag> tagsSent, long expires)
                    throws I2PSessionException {
        return sendBestEffort(dest, payload, expires, 0);
    }

    /**
     * TODO - Don't need to save MessageState since actuallyWait is false...
     * But for now just use sendNoEffort() instead.
     *
     * @param flags to be passed to the router
     * @since 0.8.4
     */
    protected boolean sendBestEffort(Destination dest, byte payload[], long expires, int flags)
                    throws I2PSessionException {
        
        long nonce = _sendMessageNonce.incrementAndGet();
        MessageState state = new MessageState(_context, nonce, getPrefix());

        // since this is 'best effort', all we're waiting for is a status update 
        // saying that the router received it - in theory, that should come back
        // immediately, but in practice can take up to a second (though usually
        // much quicker).  setting this to false will short-circuit that delay
        boolean actuallyWait = false; // true;
        if (actuallyWait)
            _sendingStates.put(Long.valueOf(nonce), state);
        _producer.sendMessage(this, dest, nonce, payload, expires, flags);
        
        if (actuallyWait) {
            try {
                state.waitForAccept(_context.clock().now() + getTimeout());
            } catch (InterruptedException ie) {
                throw new I2PSessionException("interrupted");
            } finally {
                _sendingStates.remove(Long.valueOf(nonce));
            }
        }
        boolean found = !actuallyWait || state.wasAccepted();
        
        if (found) {
            if (_log.shouldLog(Log.INFO))
                _log.info(getPrefix() + "Message sent after " + state.getElapsed() + "ms with "
                          + payload.length + " bytes");
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info(getPrefix() + "Message send failed after " + state.getElapsed() + "ms with "
                          + payload.length + " bytes");
            //if (_log.shouldLog(Log.ERROR))
            //    _log.error(getPrefix() + "Never received *accepted* from the router!  dropping and reconnecting");
            //disconnect();
            return false;
        }
        return found;
    }
    
    /**
     * Same as sendBestEffort(), except we do not expect any MessageStatusMessage responses -
     * not for accepted, or success, or failure.
     * So we don't create a MessageState and save it on the _sendingStates HashSet
     *
     * @return true always
     * @since 0.8.1
     */
    protected boolean sendNoEffort(Destination dest, byte payload[], long expires, int flags)
                    throws I2PSessionException {
        // nonce always 0
        _producer.sendMessage(this, dest, 0, payload, expires, flags);
        return true;
    }
    
    /**
     *  Only call this with nonzero status, i.e. for outbound messages
     *  whose MessageState may be queued on _sendingStates.
     *
     *  Even when using sendBestEffort(), this is a waste, because the
     *  MessageState is removed from _sendingStates immediately and
     *  so the lookup here fails.
     *
     *  This is now pretty much avoided since streaming now sets
     *  i2cp.messageReliability = none, which forces sendNoEffort() instead of sendBestEffort(),
     *  so the router won't send us any MSM's for outbound traffic.
     *
     *  @param status != 0
     */
    @Override
    public void receiveStatus(int msgId, long nonce, int status) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug(getPrefix() + "Received status " + status + " for msgId " + msgId + " / " + nonce);

        MessageState state = null;
        if ((state = _sendingStates.get(Long.valueOf(nonce))) != null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(getPrefix() + "Found a matching state");
        } else if (!_sendingStates.isEmpty()) {
            // O(n**2)
            // shouldn't happen, router sends good nonce for all statuses as of 0.9.14
            for (MessageState s : _sendingStates.values()) {
                if (s.getMessageId() != null && s.getMessageId().getMessageId() == msgId) {
                    if (_log.shouldLog(Log.DEBUG)) _log.debug(getPrefix() + "Found a matching state by msgId");
                    state = s;
                    break;
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
            if (state.wasSuccessful())
                _sendingStates.remove(Long.valueOf(nonce));
            
            long lifetime = state.getElapsed();
            switch (status) {
                case 1:
                    _context.statManager().addRateData("i2cp.receiveStatusTime.1", lifetime);
                    break;
                // best effort codes unused
                //case 2:
                //    _context.statManager().addRateData("i2cp.receiveStatusTime.2", lifetime, 0);
                //    break;
                //case 3:
                //    _context.statManager().addRateData("i2cp.receiveStatusTime.3", lifetime, 0);
                //    break;
                case 4:
                    _context.statManager().addRateData("i2cp.receiveStatusTime.4", lifetime);
                    break;
                case 5:
                    _context.statManager().addRateData("i2cp.receiveStatusTime.5", lifetime);
                    break;
            }
            
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
    @Override
    protected boolean reconnect() {
        // even if we succeed in reconnecting, we want to clear the old states, 
        // since this will be a new sessionId
        clearStates();
        return super.reconnect();
    }

    private void clearStates() {
        if (_sendingStates == null)    // only null if overridden by I2PSimpleSession
            return;
        for (MessageState state : _sendingStates.values()) {
            state.cancel();
        }
        if (_log.shouldLog(Log.INFO))
            _log.info(getPrefix() + "Disconnecting " + _sendingStates.size() + " states");
        _sendingStates.clear();
    }
}
