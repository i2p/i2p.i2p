package net.i2p.router;

import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.TimeZone;

import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.util.Log;

/**
 * Simply act as a pen register of messages sent in and out of the router.
 * This will be pulled out later on, but is useful now for debugging.
 * (with clock synchronization, this will generate a log that can be used to
 * analyze the entire network, if everyone provides their logs honestly)
 *
 */
public class MessageHistory {
    private Log _log;
    private RouterContext _context;
    private List _unwrittenEntries; // list of raw entries (strings) yet to be written
    private String _historyFile; // where to write 
    private String _localIdent; // placed in each entry to uniquely identify the local router
    private boolean _doLog; // true == we want to log
    private boolean _doPause; // true == briefly stop writing data to the log (used while submitting it)
    private ReinitializeJob _reinitializeJob;
    private WriteJob _writeJob;
    private SubmitMessageHistoryJob _submitMessageHistoryJob;
    
    private final static byte[] NL = System.getProperty("line.separator").getBytes();
    private final static int FLUSH_SIZE = 1000; // write out at least once every 1000 entries
        
    /** config property determining whether we want to debug with the message history */
    public final static String PROP_KEEP_MESSAGE_HISTORY = "router.keepHistory";
    public final static boolean DEFAULT_KEEP_MESSAGE_HISTORY = false;
    /** config property determining where we want to log the message history, if we're keeping one */
    public final static String PROP_MESSAGE_HISTORY_FILENAME = "router.historyFilename";
    public final static String DEFAULT_MESSAGE_HISTORY_FILENAME = "messageHistory.txt";

    private final SimpleDateFormat _fmt;

    public MessageHistory(RouterContext context) {
        _context = context;
         _fmt = new SimpleDateFormat("yy/MM/dd.HH:mm:ss.SSS");
        _fmt.setTimeZone(TimeZone.getTimeZone("GMT"));
        _reinitializeJob = new ReinitializeJob();
        _writeJob = new WriteJob();
        _submitMessageHistoryJob = new SubmitMessageHistoryJob(_context);
        initialize(true);
    }
    
    void setDoLog(boolean log) { _doLog = log; }
    boolean getDoLog() { return _doLog; }
    
    void setPauseFlushes(boolean doPause) { _doPause = doPause; }
    String getFilename() { return _historyFile; }
    
    private void updateSettings() {
        String keepHistory = _context.router().getConfigSetting(PROP_KEEP_MESSAGE_HISTORY);
        if (keepHistory != null) {
            _doLog = Boolean.TRUE.toString().equalsIgnoreCase(keepHistory);
        } else {
            _doLog = DEFAULT_KEEP_MESSAGE_HISTORY;
        }

        String filename = null;
        if (_doLog) {
            filename = _context.router().getConfigSetting(PROP_MESSAGE_HISTORY_FILENAME);
            if ( (filename == null) || (filename.trim().length() <= 0) )
                filename = DEFAULT_MESSAGE_HISTORY_FILENAME;
        }
    }
    
    /**
     * Initialize the message history according to the router's configuration.
     * Call this whenever the router identity changes.
     *
     */
    public void initialize(boolean forceReinitialize) {
        if (!forceReinitialize) return;

        if (_context.router() == null) return;
        
        if (_context.router().getRouterInfo() == null) {
            _reinitializeJob.getTiming().setStartAfter(_context.clock().now()+5000);
            _context.jobQueue().addJob(_reinitializeJob);
        } else {
            String filename = null;
            filename = _context.router().getConfigSetting(PROP_MESSAGE_HISTORY_FILENAME);
            if ( (filename == null) || (filename.trim().length() <= 0) )
                filename = DEFAULT_MESSAGE_HISTORY_FILENAME;

            _doLog = DEFAULT_KEEP_MESSAGE_HISTORY;
            _historyFile = filename;
            _localIdent = getName(_context.routerHash());
            _unwrittenEntries = new ArrayList(64);
            updateSettings();
            addEntry(getPrefix() + "** Router initialized (started up or changed identities)");
            _context.jobQueue().addJob(_writeJob);
            _submitMessageHistoryJob.getTiming().setStartAfter(_context.clock().now() + 2*60*1000);
            _context.jobQueue().addJob(_submitMessageHistoryJob);
        }
    }

    private final class ReinitializeJob extends JobImpl {
        private ReinitializeJob() {
            super(MessageHistory.this._context);
        }
        public void runJob() {
            initialize(true);
        }
        public String getName() { return "Reinitialize message history"; }
    }
    
    /**
     * We are requesting that the peerRequested create the tunnel specified with the 
     * given nextPeer, and we are sending that request to them through outTunnel with
     * a request that the reply is sent back to us through replyTunnel on the given
     * replyThrough router.
     *
     * @param createTunnel tunnel being created
     * @param outTunnel tunnel we are sending this request out
     * @param peerRequested peer asked to participate in the tunnel
     * @param nextPeer who peerRequested should forward messages to (or null if it is the endpoint)
     * @param sourceRoutePeer to whom peerRequested should forward its TunnelCreateStatusMessage through
     * @param replyTunnel the tunnel sourceRoutePeer should forward the source routed message to
     * @param replyThrough the gateway of the tunnel that the sourceRoutePeer will be sending to
     */
    public void requestTunnelCreate(TunnelId createTunnel, TunnelId outTunnel, Hash peerRequested, Hash nextPeer, Hash sourceRoutePeer, TunnelId replyTunnel, Hash replyThrough) {
        if (!_doLog) return;
        StringBuffer buf = new StringBuffer(128);
        buf.append(getPrefix());
        buf.append("request [").append(getName(peerRequested)).append("] to create tunnel [");
        buf.append(createTunnel.getTunnelId()).append("] ");
        if (nextPeer != null)
            buf.append("(next [").append(getName(nextPeer)).append("]) ");
        if (outTunnel != null)
            buf.append("via [").append(outTunnel.getTunnelId()).append("] ");
        if (sourceRoutePeer != null)
            buf.append("with replies routed through [").append(getName(sourceRoutePeer)).append("] ");
        if ( (replyTunnel != null) && (replyThrough != null) ) 
            buf.append("who forwards it through [").append(replyTunnel.getTunnelId()).append("] on [").append(getName(replyThrough)).append("]");
        addEntry(buf.toString());
    }
    
    /**
     * The local router has received a request to join the createTunnel with the next hop being nextPeer,
     * and we should send our decision to join it through sourceRoutePeer
     *
     * @param createTunnel tunnel being joined
     * @param nextPeer next hop in the tunnel (or null if this is the endpoint)
     * @param expire when this tunnel expires
     * @param ok whether we will join the tunnel
     * @param sourceRoutePeer peer through whom we should send our garlic routed ok through
     */
    public void receiveTunnelCreate(TunnelId createTunnel, Hash nextPeer, Date expire, boolean ok, Hash sourceRoutePeer) {
        if (!_doLog) return;
        StringBuffer buf = new StringBuffer(128);
        buf.append(getPrefix());
        buf.append("receive tunnel create [").append(createTunnel.getTunnelId()).append("] ");
        if (nextPeer != null)
            buf.append("(next [").append(getName(nextPeer)).append("]) ");
        buf.append("ok? ").append(ok).append(" expiring on [").append(getTime(expire)).append("]");
        addEntry(buf.toString());
    }
    
    /**
     * The local router has joined the given tunnel operating in the given state.
     *
     * @param state {"free inbound", "allocated inbound", "inactive inbound", "outbound", "participant", "pending"}
     * @param tunnel tunnel joined
     */
    public void tunnelJoined(String state, TunnelInfo tunnel) {
        if (!_doLog) return;
        if (tunnel == null) return;
        StringBuffer buf = new StringBuffer(128);
        buf.append(getPrefix());
        buf.append("joining tunnel [").append(tunnel.getTunnelId().getTunnelId()).append("] as [").append(state).append("] ");
        buf.append(" (next: ");
        TunnelInfo cur = tunnel;
        while (cur.getNextHopInfo() != null) {
            buf.append('[').append(getName(cur.getNextHopInfo().getThisHop()));
            buf.append("], ");
            cur = cur.getNextHopInfo();
        }
        if (cur.getNextHop() != null)
            buf.append('[').append(getName(cur.getNextHop())).append(']');
        buf.append(") expiring on [").append(getTime(new Date(tunnel.getSettings().getExpiration()))).append("]");
        addEntry(buf.toString());
    }
    
    /**
     * The local router has detected a failure in the given tunnel
     *
     * @param tunnel tunnel failed
     */
    public void tunnelFailed(TunnelId tunnel) {
        if (!_doLog) return;
        if (tunnel == null) return;
        StringBuffer buf = new StringBuffer(128);
        buf.append(getPrefix());
        buf.append("failing tunnel [").append(tunnel.getTunnelId()).append("]");
        addEntry(buf.toString());
    }
    
    /**
     * Note that we have reason to believe that the given tunnel is valid, since we could do something 
     * through it in the given amount of time
     *
     * @param tunnel tunnel in question
     * @param timeToTest milliseconds to verify the tunnel
     */
    public void tunnelValid(TunnelInfo tunnel, long timeToTest) {
        if (!_doLog) return;
        if (tunnel == null) return;
        StringBuffer buf = new StringBuffer(128);
        buf.append(getPrefix());
        buf.append("tunnel ").append(tunnel.getTunnelId().getTunnelId()).append(" tested ok after ").append(timeToTest).append("ms (containing ");
        TunnelInfo cur = tunnel;
        while (cur != null) {
            buf.append('[').append(getName(cur.getThisHop())).append("], ");
            if (cur.getNextHopInfo() != null) {
                cur = cur.getNextHopInfo();
            } else {
                if (cur.getNextHop() != null)
                    buf.append('[').append(getName(cur.getNextHop())).append(']');
                cur = null;		
            }
        }
        buf.append(')');
        addEntry(buf.toString());
    }
    
    /**
     * The peer did not accept the tunnel join for the given reason 
     *
     */
    public void tunnelRejected(Hash peer, TunnelId tunnel, Hash replyThrough, String reason) {
        if (!_doLog) return;
        if ( (tunnel == null) || (peer == null) ) return;
        StringBuffer buf = new StringBuffer(128);
        buf.append(getPrefix());
        buf.append("tunnel [").append(tunnel.getTunnelId()).append("] was rejected by [");
        buf.append(getName(peer)).append("] for [").append(reason).append("]");
        if (replyThrough != null)
            buf.append(" with their reply intended to come through [").append(getName(replyThrough)).append("]");
        addEntry(buf.toString());
    }
    
    /**
     * The peer did not accept the tunnel join for the given reason (this may be because
     * of a timeout or an explicit refusal).
     *
     */
    public void tunnelRequestTimedOut(Hash peer, TunnelId tunnel, Hash replyThrough) {
        if (!_doLog) return;
        if ( (tunnel == null) || (peer == null) ) return;
        StringBuffer buf = new StringBuffer(128);
        buf.append(getPrefix());
        buf.append("tunnel [").append(tunnel.getTunnelId()).append("] timed out on [");
        buf.append(getName(peer)).append("]");
        if (replyThrough != null)
            buf.append(" with their reply intended to come through [").append(getName(replyThrough)).append("]");
        addEntry(buf.toString());
    }
    
    /**
     * We don't know about the given tunnel, so we are dropping a message sent to us by the
     * given router
     *
     * @param id tunnel ID we received a message for
     * @param from peer that sent us this message (if known)
     */
    public void droppedTunnelMessage(TunnelId id, Hash from) {
        if (!_doLog) return;
        StringBuffer buf = new StringBuffer(128);
        buf.append(getPrefix());
        buf.append("dropped message for unknown tunnel [").append(id.getTunnelId()).append("] from [").append(getName(from)).append("]");
        addEntry(buf.toString());
    }
    
    /**
     * We received another message we weren't waiting for and don't know how to handle
     */
    public void droppedOtherMessage(I2NPMessage message) {
        if (!_doLog) return;
        if (message == null) return;
        StringBuffer buf = new StringBuffer(512);
        buf.append(getPrefix());
        buf.append("dropped [").append(message.getClass().getName()).append("] ").append(message.getUniqueId());
        buf.append(" [").append(message.toString()).append("]");
        addEntry(buf.toString());
    }
    
    /**
     * The message wanted a reply but no reply came in the time expected
     *
     * @param sentMessage message sent that didn't receive a reply
     */
    public void replyTimedOut(OutNetMessage sentMessage) {
        if (!_doLog) return;
        if (sentMessage == null) return;
        StringBuffer buf = new StringBuffer(512);
        buf.append(getPrefix());
        buf.append("timed out waiting for a reply to [").append(sentMessage.getMessageType());
        buf.append("] [").append(sentMessage.getMessageId()).append("] expiring on [");
        if (sentMessage != null)
            buf.append(getTime(new Date(sentMessage.getReplySelector().getExpiration())));
        buf.append("] ").append(sentMessage.getReplySelector().toString());
        addEntry(buf.toString());
    }
    
    /**
     * There was an error processing the given message that was received
     *
     * @param messageId message received
     * @param messageType type of message received
     * @param error error message related to the processing of the message
     */
    public void messageProcessingError(long messageId, String messageType, String error) {
        if (!_doLog) return;
        StringBuffer buf = new StringBuffer(128);
        buf.append(getPrefix());
        buf.append("Error processing [").append(messageType).append("] [").append(messageId).append("] failed with [").append(error).append("]");
        addEntry(buf.toString());
    }
    
    /**
     * We just sent a message to the peer
     *
     * @param messageType class name for the message object (e.g. DatabaseFindNearestMessage, TunnelMessage, etc)
     * @param messageId the unique message id of the message being sent (not including any tunnel or garlic wrapped
     *                  message ids)
     * @param expiration the expiration for the message sent
     * @param peer router that the message was sent to
     * @param sentOk whether the message was sent successfully
     */
    public void sendMessage(String messageType, long messageId, Date expiration, Hash peer, boolean sentOk) {
        if (!_doLog) return;
        StringBuffer buf = new StringBuffer(256);
        buf.append(getPrefix());
        buf.append("send [").append(messageType).append("] message [").append(messageId).append("] ");
        buf.append("to [").append(getName(peer)).append("] ");
        buf.append("expiring on [").append(getTime(expiration)).append("] ");
        if (sentOk)
            buf.append("successfully");
        else
            buf.append("failed");
        addEntry(buf.toString());
    }

    /**
     * We just received a message from the peer
     *
     * @param messageType class name for the message object (e.g. DatabaseFindNearestMessage, TunnelMessage, etc)
     * @param messageId the unique message id of the message received (not including any tunnel or garlic wrapped
     *                  message ids)
     * @param expiration the expiration for the message received
     * @param from router that the message was sent from (or null if we don't know)
     * @param isValid whether the message is valid (non duplicates, etc)
     *
     */
    public void receiveMessage(String messageType, long messageId, Date expiration, Hash from, boolean isValid) {
        if (!_doLog) return;
        StringBuffer buf = new StringBuffer(256);
        buf.append(getPrefix());
        buf.append("receive [").append(messageType).append("] with id [").append(messageId).append("] ");
        if (from != null)
            buf.append("from [").append(getName(from)).append("] ");
        buf.append("expiring on [").append(getTime(expiration)).append("] valid? ").append(isValid);
        addEntry(buf.toString());
        if (messageType.equals("net.i2p.data.i2np.TunnelMessage")) {
        //_log.warn("ReceiveMessage tunnel message ["+messageId+"]", new Exception("Receive tunnel"));
	}
    }
    public void receiveMessage(String messageType, long messageId, Date expiration, boolean isValid) {
        receiveMessage(messageType, messageId, expiration, null, isValid);
    }
    
    /**
     * Note that we're wrapping the given message within another message (via tunnel/garlic)
     *
     * @param bodyMessageType class name for the message contained (e.g. DatabaseFindNearestMessage, DataMessage, etc)
     * @param bodyMessageId the unique message id of the message 
     * @param containerMessageType class name for the message containing the body message (e.g. TunnelMessage, GarlicMessage, etc)
     * @param containerMessageId the unique message id of the message 
     */
    public void wrap(String bodyMessageType, long bodyMessageId, String containerMessageType, long containerMessageId) {
        if (!_doLog) return;
        StringBuffer buf = new StringBuffer(128);
        buf.append(getPrefix());
        buf.append("Wrap message [").append(bodyMessageType).append("] id [").append(bodyMessageId).append("] ");
        buf.append("in [").append(containerMessageType).append("] id [").append(containerMessageId).append("]");
        addEntry(buf.toString());
    }
    
    /**
     * Receive a payload message to distribute to a client 
     *
     */
    public void receivePayloadMessage(long messageId) {
        if (!_doLog) return;
        StringBuffer buf = new StringBuffer(64);
        buf.append(getPrefix());
        buf.append("Receive payload message [").append(messageId).append("]");
        addEntry(buf.toString());
    }
    
    /**
     * Note that the sending of a payload message completed (successfully or as a failure)
     *
     * @param messageId message that the payload message was sent in
     * @param successfullySent whether the message was delivered to the peer successfully
     * @param timeToSend how long it took to send the message
     */
    public void sendPayloadMessage(long messageId, boolean successfullySent, long timeToSend) {
        if (!_doLog) return;
        StringBuffer buf = new StringBuffer(128);
        buf.append(getPrefix());
        buf.append("Send payload message in [").append(messageId).append("] in [").append(timeToSend).append("] successfully? ").append(successfullySent);
        addEntry(buf.toString());
    }
    
    /**
     * Prettify the hash by doing a base64 and returning the first 6 characters
     *
     */
    private final static String getName(Hash router) {
        if (router == null) return "unknown";
        String str = router.toBase64();
        if ( (str == null) || (str.length() < 6) ) return "invalid";
        return str.substring(0, 6);
    }
    
    private final String getPrefix() {
        StringBuffer buf = new StringBuffer(48);
        buf.append(getTime(new Date(_context.clock().now())));
        buf.append(' ').append(_localIdent).append(": ");
        return buf.toString();
    }
    
    private final String getTime(Date when) {
        synchronized (_fmt) {
            return _fmt.format(when);
        }
    }
    
    /**
     * Responsible for adding the entry, flushing if necessary.  
     * This is the only thing that adds to _unwrittenEntries.
     *
     */
    private void addEntry(String entry) {
        if (entry == null) return;
        int sz = 0;
        synchronized (_unwrittenEntries) {
            _unwrittenEntries.add(entry);
            sz = _unwrittenEntries.size();
        }
        if (sz > FLUSH_SIZE)
            flushEntries();
    }
    
    /**
     * Write out any unwritten entries, and clear the pending list
     */
    private void flushEntries() {
        if (_doPause) return;
        List entries = null;
        synchronized (_unwrittenEntries) {
            entries = new ArrayList(_unwrittenEntries);
            _unwrittenEntries.clear();
        }
        writeEntries(entries);
    }
    
    /**
     * Actually write the specified entries
     *
     */
    private void writeEntries(List entries) {
        if (!_doLog) return;
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(_historyFile, true);
            for (Iterator iter = entries.iterator(); iter.hasNext(); ) {
                String entry = (String)iter.next();
                fos.write(entry.getBytes());
                fos.write(NL);
            }
        } catch (IOException ioe) {
            _log.error("Error writing trace entries", ioe);
        } finally {
            if (fos != null) try { fos.close(); } catch (IOException ioe) {}
        }
    }
    
    /** write out the message history once per minute, if not sooner */
    private final static long WRITE_DELAY = 60*1000;
    private class WriteJob extends JobImpl {
        public WriteJob() {
            super(MessageHistory.this._context);
        }
        public String getName() { return "Write History Entries"; }
        public void runJob() {
            flushEntries();
            updateSettings();
            requeue(WRITE_DELAY);
        }
    }
    
    public static void main(String args[]) {
        RouterContext ctx = new RouterContext(null);
        MessageHistory hist = new MessageHistory(ctx);
        //, new Hash(new byte[32]), "messageHistory.txt");
        hist.setDoLog(false);
        hist.addEntry("you smell before");
        hist.setDoLog(true);
        hist.addEntry("you smell after");
        hist.setDoLog(false);
        hist.addEntry("you smell finished");
        hist.flushEntries();
    }
}
