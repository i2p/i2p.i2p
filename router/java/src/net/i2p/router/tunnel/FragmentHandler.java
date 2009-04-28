package net.i2p.router.tunnel;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageException;
import net.i2p.data.i2np.I2NPMessageHandler;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer;

/**
 * Handle fragments at the endpoint of a tunnel, peeling off fully completed 
 * I2NPMessages when they arrive, and dropping fragments if they take too long
 * to arrive.
 *
 */
public class FragmentHandler {
    private I2PAppContext _context;
    private Log _log;
    private final Map _fragmentedMessages;
    private DefragmentedReceiver _receiver;
    private int _completed;
    private int _failed;
    
    /** don't wait more than 60s to defragment the partial message */
    static long MAX_DEFRAGMENT_TIME = 60*1000;
    private static final ByteCache _cache = ByteCache.getInstance(512, TrivialPreprocessor.PREPROCESSED_SIZE);

    public FragmentHandler(I2PAppContext context, DefragmentedReceiver receiver) {
        _context = context;
        _log = context.logManager().getLog(FragmentHandler.class);
        _fragmentedMessages = new HashMap(4);
        _receiver = receiver;
        _context.statManager().createRateStat("tunnel.smallFragments", "How many pad bytes are in small fragments?", 
                                              "Tunnels", new long[] { 10*60*1000l, 60*60*1000l, 3*60*60*1000l, 24*60*60*1000 });
        _context.statManager().createRateStat("tunnel.fullFragments", "How many tunnel messages use the full data area?", 
                                              "Tunnels", new long[] { 10*60*1000l, 60*60*1000l, 3*60*60*1000l, 24*60*60*1000 });
        _context.statManager().createRateStat("tunnel.fragmentedComplete", "How many fragments were in a completely received message?", 
                                              "Tunnels", new long[] { 10*60*1000l, 60*60*1000l, 3*60*60*1000l, 24*60*60*1000 });
        _context.statManager().createRateStat("tunnel.fragmentedDropped", "How many fragments were in a partially received yet failed message?", 
                                              "Tunnels", new long[] { 10*60*1000l, 60*60*1000l, 3*60*60*1000l, 24*60*60*1000 });
        _context.statManager().createRateStat("tunnel.corruptMessage", "How many corrupted messages arrived?", 
                                              "Tunnels", new long[] { 10*60*1000l, 60*60*1000l, 3*60*60*1000l, 24*60*60*1000 });
    }
    
    /**
     * Receive the raw preprocessed message at the endpoint, parsing out each
     * of the fragments, using those to fill various FragmentedMessages, and 
     * sending the resulting I2NPMessages where necessary.  The received 
     * fragments are all verified.
     *
     */
    public void receiveTunnelMessage(byte preprocessed[], int offset, int length) {
        boolean ok = verifyPreprocessed(preprocessed, offset, length);
        if (!ok) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to verify preprocessed data (pre.length=" 
                          + preprocessed.length + " off=" +offset + " len=" + length);
            _cache.release(new ByteArray(preprocessed));
            _context.statManager().addRateData("tunnel.corruptMessage", 1, 1);
            return;
        }
        offset += HopProcessor.IV_LENGTH; // skip the IV
        offset += 4; // skip the hash segment
        int padding = 0;
        while (preprocessed[offset] != (byte)0x00) {
            offset++; // skip the padding
            // AIOOBE http://forum.i2p/viewtopic.php?t=3187
            if (offset >= TrivialPreprocessor.PREPROCESSED_SIZE) {
                _cache.release(new ByteArray(preprocessed));
                _context.statManager().addRateData("tunnel.corruptMessage", 1, 1);
                return;
            }
            padding++;
        }
        offset++; // skip the final 0x00, terminating the padding
        if (_log.shouldLog(Log.DEBUG)) {
            _log.debug("Fragments begin at offset=" + offset + " padding=" + padding);
            //_log.debug("fragments: " + Base64.encode(preprocessed, offset, preprocessed.length-offset));
        }
        try {
            while (offset < length) {
                int off = receiveFragment(preprocessed, offset, length);
                if (off < 0) {
                    _context.statManager().addRateData("tunnel.corruptMessage", 1, 1);
                    return;
                }
                offset = off;
            }
        } catch (ArrayIndexOutOfBoundsException aioobe) {
            _context.statManager().addRateData("tunnel.corruptMessage", 1, 1);
        } catch (NullPointerException npe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Corrupt fragment received: offset = " + offset, npe);
            _context.statManager().addRateData("tunnel.corruptMessage", 1, 1);
        } catch (RuntimeException e) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Corrupt fragment received: offset = " + offset, e);
            _context.statManager().addRateData("tunnel.corruptMessage", 1, 1);
            // java.lang.IllegalStateException: wtf, don't get the completed size when we're not complete - null fragment i=0 of 1
            // at net.i2p.router.tunnel.FragmentedMessage.getCompleteSize(FragmentedMessage.java:194)
            // at net.i2p.router.tunnel.FragmentedMessage.toByteArray(FragmentedMessage.java:223)
            // at net.i2p.router.tunnel.FragmentHandler.receiveComplete(FragmentHandler.java:380)
            // at net.i2p.router.tunnel.FragmentHandler.receiveSubsequentFragment(FragmentHandler.java:353)
            // at net.i2p.router.tunnel.FragmentHandler.receiveFragment(FragmentHandler.java:208)
            // at net.i2p.router.tunnel.FragmentHandler.receiveTunnelMessage(FragmentHandler.java:92)
            // ...
            // still trying to find root cause
            // let's limit the damage here and skip the:
            // .transport.udp.MessageReceiver: b0rked receiving a message.. wazza huzza hmm?
            //throw e;
        } finally {
            // each of the FragmentedMessages populated make a copy out of the
            // payload, which they release separately, so we can release 
            // immediately
            _cache.release(new ByteArray(preprocessed));
        }
    }
    
    public int getCompleteCount() { return _completed; }
    public int getFailedCount() { return _failed; }
    
    private static final ByteCache _validateCache = ByteCache.getInstance(512, TrivialPreprocessor.PREPROCESSED_SIZE);
    
    /**
     * Verify that the preprocessed data hasn't been modified by checking the 
     * H(payload+IV)[0:3] vs preprocessed[16:19], where payload is the data 
     * after the padding.  Remember, the preprocessed data is formatted as
     * { IV + H[0:3] + padding + {instructions, fragment}* }.  This function is
     * very wasteful of memory usage as it doesn't operate inline (since IV and
     * payload are mixed up).  Later it may be worthwhile to explore optimizing
     * this.
     */
    private boolean verifyPreprocessed(byte preprocessed[], int offset, int length) {
        // now we need to verify that the message was received correctly
        int paddingEnd = HopProcessor.IV_LENGTH + 4;
        while (preprocessed[offset+paddingEnd] != (byte)0x00) {
            paddingEnd++;
            if (offset+paddingEnd >= length) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("cannot verify, going past the end [off=" 
                              + offset + " len=" + length + " paddingEnd=" 
                              + paddingEnd + " data:\n"
                              + Base64.encode(preprocessed, offset, length));
                return false;
            }
        }
        paddingEnd++; // skip the last
        
        ByteArray ba = _validateCache.acquire(); // larger than necessary, but always sufficient
        byte preV[] = ba.getData();
        int validLength = length - offset - paddingEnd + HopProcessor.IV_LENGTH;
        System.arraycopy(preprocessed, offset + paddingEnd, preV, 0, validLength - HopProcessor.IV_LENGTH);
        System.arraycopy(preprocessed, 0, preV, validLength - HopProcessor.IV_LENGTH, HopProcessor.IV_LENGTH);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("endpoint IV: " + Base64.encode(preV, validLength - HopProcessor.IV_LENGTH, HopProcessor.IV_LENGTH));
        
        Hash v = _context.sha().calculateHash(preV, 0, validLength);
        
        //Hash v = _context.sha().calculateHash(preV, 0, validLength);
        boolean eq = DataHelper.eq(v.getData(), 0, preprocessed, offset + HopProcessor.IV_LENGTH, 4);
        if (!eq) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Corrupt tunnel message - verification fails: \n" + Base64.encode(preprocessed, offset+HopProcessor.IV_LENGTH, 4)
                           + "\n" + Base64.encode(v.getData(), 0, 4));
            if (_log.shouldLog(Log.WARN))
                _log.warn("nomatching endpoint: # pad bytes: " + (paddingEnd-(HopProcessor.IV_LENGTH+4)-1) + "\n" 
                           + " offset=" + offset + " length=" + length + " paddingEnd=" + paddingEnd
                           + Base64.encode(preprocessed, offset, length));
        }
        
        _validateCache.release(ba);
        
        if (eq) {
            int excessPadding = paddingEnd - (HopProcessor.IV_LENGTH + 4 + 1);
            if (excessPadding > 0) // suboptimal fragmentation
                _context.statManager().addRateData("tunnel.smallFragments", excessPadding, 0);
            else
                _context.statManager().addRateData("tunnel.fullFragments", 1, 0);
        }
        
        return eq;
    }
    
    /** is this a follw up byte? */
    static final byte MASK_IS_SUBSEQUENT = (byte)(1 << 7);
    /** how should this be delivered.  shift this 5 the right and get TYPE_* */
    static final byte MASK_TYPE = (byte)(3 << 5);
    /** is this the first of a fragmented message? */
    static final byte MASK_FRAGMENTED = (byte)(1 << 3);
    /** are there follow up headers? */
    static final byte MASK_EXTENDED = (byte)(1 << 2);
    /** for subsequent fragments, which bits contain the fragment #? */
    private static final int MASK_FRAGMENT_NUM = (byte)((1 << 7) - 2); // 0x7E;
    
    static final short TYPE_LOCAL = 0;
    static final short TYPE_TUNNEL = 1;
    static final short TYPE_ROUTER = 2;
    
    /** 
     * @return the offset for the next byte after the received fragment 
     */
    private int receiveFragment(byte preprocessed[], int offset, int length) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("CONTROL: " + Integer.toHexString(preprocessed[offset]) + " / " 
                       + "/" + Base64.encode(preprocessed, offset, 1) + " at offset " + offset);
        if (0 == (preprocessed[offset] & MASK_IS_SUBSEQUENT))
            return receiveInitialFragment(preprocessed, offset, length);
        else
            return receiveSubsequentFragment(preprocessed, offset, length);
    }
    
    /**
     * Handle the initial fragment in a message (or a full message, if it fits)
     *
     * @return offset after reading the full fragment
     */
    private int receiveInitialFragment(byte preprocessed[], int offset, int length) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("initial begins at " + offset + " for " + length);
        int type = (preprocessed[offset] & MASK_TYPE) >>> 5;
        boolean fragmented = (0 != (preprocessed[offset] & MASK_FRAGMENTED));
        boolean extended = (0 != (preprocessed[offset] & MASK_EXTENDED));
        offset++;
        
        TunnelId tunnelId = null;
        Hash router = null;
        long messageId = -1;
        
        if (type == TYPE_TUNNEL) {
            if (offset + 4 >= preprocessed.length)
                return -1;
            long id = DataHelper.fromLong(preprocessed, offset, 4);
            tunnelId = new TunnelId(id);
            offset += 4;
        }
        if ( (type == TYPE_ROUTER) || (type == TYPE_TUNNEL) ) {
            byte h[] = new byte[Hash.HASH_LENGTH];
            if (offset + Hash.HASH_LENGTH >= preprocessed.length)
                return -1;
            System.arraycopy(preprocessed, offset, h, 0, Hash.HASH_LENGTH);
            router = new Hash(h);
            offset += Hash.HASH_LENGTH;
        }
        if (fragmented) {
            if (offset + 4 >= preprocessed.length)
                return -1;
            messageId = DataHelper.fromLong(preprocessed, offset, 4);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("reading messageId " + messageId + " at offset "+ offset 
                           + " type = " + type + " router = " 
                           + (router != null ? router.toBase64().substring(0,4) : "n/a") 
                           + " tunnelId = " + tunnelId);
            offset += 4;
        }
        if (extended) {
            int extendedSize = (int)DataHelper.fromLong(preprocessed, offset, 1);
            offset++;
            offset += extendedSize; // we don't interpret these yet, but skip them for now
        }
        
        if (offset + 2 >= preprocessed.length)
            return -1;
        int size = (int)DataHelper.fromLong(preprocessed, offset, 2);
        offset += 2;
        
        boolean isNew = false;
        FragmentedMessage msg = null;
        if (fragmented) {
            synchronized (_fragmentedMessages) {
                msg = (FragmentedMessage)_fragmentedMessages.get(new Long(messageId));
                if (msg == null) {
                    msg = new FragmentedMessage(_context);
                    _fragmentedMessages.put(new Long(messageId), msg);
                    isNew = true;
                }
            }
        } else {
            msg = new FragmentedMessage(_context);
        }
        
        boolean ok = msg.receive(messageId, preprocessed, offset, size, !fragmented, router, tunnelId);
        if (!ok) return -1;
        if (msg.isComplete()) {
            if (fragmented) {
                synchronized (_fragmentedMessages) {
                    _fragmentedMessages.remove(new Long(messageId));
                }
            }
            if (msg.getExpireEvent() != null)
                SimpleTimer.getInstance().removeEvent(msg.getExpireEvent());
            receiveComplete(msg);
        } else {
            noteReception(msg.getMessageId(), 0, msg);
        }
        
        if (isNew && fragmented && !msg.isComplete()) {
            RemoveFailed evt = new RemoveFailed(msg);
            msg.setExpireEvent(evt);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("In " + MAX_DEFRAGMENT_TIME + " dropping " + messageId);
            SimpleTimer.getInstance().addEvent(evt, MAX_DEFRAGMENT_TIME);
        }
        
        offset += size;
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Handling finished message " + msg.getMessageId() + " at offset " + offset);
        return offset;
    }
    
    /**
     * Handle a fragment beyond the initial fragment in a message
     *
     * @return offset after reading the full fragment
     */
    private int receiveSubsequentFragment(byte preprocessed[], int offset, int length) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("subsequent begins at " + offset + " for " + length);
        int fragmentNum = ((preprocessed[offset] & MASK_FRAGMENT_NUM) >>> 1);
        boolean isLast = (0 != (preprocessed[offset] & 1));
        offset++;
        
        long messageId = DataHelper.fromLong(preprocessed, offset, 4);
        offset += 4;
        
        int size = (int)DataHelper.fromLong(preprocessed, offset, 2);
        offset += 2;
        
        if (messageId < 0)
            throw new RuntimeException("Preprocessed message was invalid [messageId =" + messageId + " size=" 
                                       + size + " offset=" + offset + " fragment=" + fragmentNum);
        
        boolean isNew = false;
        FragmentedMessage msg = null;
        synchronized (_fragmentedMessages) {
            msg = (FragmentedMessage)_fragmentedMessages.get(new Long(messageId));
            if (msg == null) {
                msg = new FragmentedMessage(_context);
                _fragmentedMessages.put(new Long(messageId), msg);
                isNew = true;
            }
        }
        
        boolean ok = msg.receive(messageId, fragmentNum, preprocessed, offset, size, isLast);
        if (!ok) return -1;
        
        if (msg.isComplete()) {
            synchronized (_fragmentedMessages) {
                _fragmentedMessages.remove(new Long(messageId));
            }
            if (msg.getExpireEvent() != null)
                SimpleTimer.getInstance().removeEvent(msg.getExpireEvent());
            _context.statManager().addRateData("tunnel.fragmentedComplete", msg.getFragmentCount(), msg.getLifetime());
            receiveComplete(msg);
        } else {
            noteReception(msg.getMessageId(), fragmentNum, msg);
        }
        
        if (isNew && !msg.isComplete()) {
            RemoveFailed evt = new RemoveFailed(msg);
            msg.setExpireEvent(evt);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("In " + MAX_DEFRAGMENT_TIME + " dropping " + msg.getMessageId() + "/" + fragmentNum);
            SimpleTimer.getInstance().addEvent(evt, MAX_DEFRAGMENT_TIME);
        }
        
        offset += size;
        return offset;
    }
    
    private void receiveComplete(FragmentedMessage msg) {
        if (msg == null)
            return;
        _completed++;
        String stringified = null;
        if (_log.shouldLog(Log.DEBUG))
            stringified = msg.toString();
        try {
            int fragmentCount = msg.getFragmentCount();
            // toByteArray destroys the contents of the message completely
            byte data[] = msg.toByteArray();
            if (data == null)
                throw new I2NPMessageException("null data");   // fragments already released???
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("RECV(" + data.length + "): " + Base64.encode(data)  
                           + " " + _context.sha().calculateHash(data).toBase64());
            I2NPMessage m = new I2NPMessageHandler(_context).readMessage(data);
            noteReception(m.getUniqueId(), fragmentCount-1, "complete: ");// + msg.toString());
            noteCompletion(m.getUniqueId());
            _receiver.receiveComplete(m, msg.getTargetRouter(), msg.getTargetTunnel());
        } catch (IOException ioe) {
            if (stringified == null) stringified = msg.toString();
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error receiving fragmented message (corrupt?): " + stringified, ioe);
        } catch (I2NPMessageException ime) {
            if (stringified == null) stringified = msg.toString();
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error receiving fragmented message (corrupt?): " + stringified, ime);
        }
    }

    protected void noteReception(long messageId, int fragmentId, Object status) {}
    protected void noteCompletion(long messageId) {}
    protected void noteFailure(long messageId, String status) {}
    
    /**
     * Receive messages out of the tunnel endpoint.  There should be a single 
     * instance of this object per tunnel so that it can tell what tunnel various
     * messages come in on (e.g. to prevent DataMessages arriving from anywhere 
     * other than the client's inbound tunnels)
     * 
     */
    public interface DefragmentedReceiver {
        /**
         * Receive a fully formed I2NPMessage out of the tunnel
         *
         * @param msg message received 
         * @param toRouter where we are told to send the message (null means locally)
         * @param toTunnel where we are told to send the message (null means locally or to the specified router)
         */
        public void receiveComplete(I2NPMessage msg, Hash toRouter, TunnelId toTunnel);
    }
    
    private class RemoveFailed implements SimpleTimer.TimedEvent {
        private FragmentedMessage _msg;
        public RemoveFailed(FragmentedMessage msg) {
            _msg = msg;
        }
        public void timeReached() {
            boolean removed = false;
            synchronized (_fragmentedMessages) {
                removed = (null != _fragmentedMessages.remove(new Long(_msg.getMessageId())));
            }
            if (removed && !_msg.getReleased()) {
                _failed++;
                noteFailure(_msg.getMessageId(), _msg.toString());
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Dropped failed fragmented message: " + _msg);
                _context.statManager().addRateData("tunnel.fragmentedDropped", _msg.getFragmentCount(), _msg.getLifetime());
                _msg.failed();
            } else {
                // succeeded before timeout
            }
        }
        
    }
}
