package net.i2p.router;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Queue;
import java.util.TimeZone;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.tunnel.HopConfig;
import net.i2p.util.Log;
import net.i2p.util.SecureFileOutputStream;

/**
 * Simply act as a pen register of messages sent in and out of the router.
 * This will be pulled out later on, but is useful now for debugging.
 * (with clock synchronization, this will generate a log that can be used to
 * analyze the entire network, if everyone provides their logs honestly)
 *
 * This is always instantiated in the context and the WriteJob runs every minute,
 * but unless router.keepHistory=true it does nothing.
 * It generates a LARGE log file.
 */
public class MessageHistory {
    private final Log _log;
    private final RouterContext _context;
    private final Queue<String> _unwrittenEntries; // list of raw entries (strings) yet to be written
    private String _historyFile; // where to write 
    private String _localIdent; // placed in each entry to uniquely identify the local router
    private boolean _doLog; // true == we want to log
    private boolean _doPause; // true == briefly stop writing data to the log (used while submitting it)
    private final ReinitializeJob _reinitializeJob;
    private final WriteJob _writeJob;
    //private SubmitMessageHistoryJob _submitMessageHistoryJob;
    private volatile boolean _firstPass;
    
    private final static byte[] NL = System.getProperty("line.separator").getBytes();
    private final static int FLUSH_SIZE = 1000; // write out at least once every 1000 entries
        
    /** config property determining whether we want to debug with the message history - default false */
    public final static String PROP_KEEP_MESSAGE_HISTORY = "router.keepHistory";
    /** config property determining where we want to log the message history, if we're keeping one */
    public final static String PROP_MESSAGE_HISTORY_FILENAME = "router.historyFilename";
    public final static String DEFAULT_MESSAGE_HISTORY_FILENAME = "messageHistory.txt";

    private final SimpleDateFormat _fmt;

    public MessageHistory(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(getClass());
         _fmt = new SimpleDateFormat("yy/MM/dd.HH:mm:ss.SSS");
        _fmt.setTimeZone(TimeZone.getTimeZone("GMT"));
        _unwrittenEntries = new LinkedBlockingQueue();
        _reinitializeJob = new ReinitializeJob();
        _writeJob = new WriteJob();
        _firstPass = true;
        //_submitMessageHistoryJob = new SubmitMessageHistoryJob(_context);
        initialize(true);
    }
    
    /** @since 0.8.12 */
    public void shutdown() {
        if (_doLog)
            addEntry(getPrefix() + "** Router shutdown");
        _doPause = false;
        flushEntries();
        _doLog = false;
    }

    public boolean getDoLog() { return _doLog; }
    
    /** @deprecated unused */
    void setPauseFlushes(boolean doPause) { _doPause = doPause; }
    String getFilename() { return _historyFile; }
    
    private void updateSettings() {
        _doLog = Boolean.valueOf(_context.getProperty(PROP_KEEP_MESSAGE_HISTORY)).booleanValue();
        _historyFile = _context.getProperty(PROP_MESSAGE_HISTORY_FILENAME, DEFAULT_MESSAGE_HISTORY_FILENAME);
    }
    
    /**
     * Initialize the message history according to the router's configuration.
     * Call this whenever the router identity changes.
     *
     */
    public void initialize(boolean forceReinitialize) {
        if (!forceReinitialize) return;

        if (_context.router().getRouterInfo() == null) {
            _reinitializeJob.getTiming().setStartAfter(_context.clock().now() + 15*1000);
            _context.jobQueue().addJob(_reinitializeJob);
        } else {
            _localIdent = getName(_context.routerHash());
            // _unwrittenEntries = new ArrayList(64);
            updateSettings();
            // clear the history file on startup
            if (_firstPass) {
                File f = new File(_historyFile);
                if (!f.isAbsolute())
                    f = new File(_context.getLogDir(), _historyFile);
                f.delete();
                _writeJob.getTiming().setStartAfter(_context.clock().now() + WRITE_DELAY);
                _context.jobQueue().addJob(_writeJob);
                _firstPass = false;
            }
            if (_doLog)
                addEntry(getPrefix() + "** Router initialized (started up or changed identities)");
            //_submitMessageHistoryJob.getTiming().setStartAfter(_context.clock().now() + 2*60*1000);
            //_context.jobQueue().addJob(_submitMessageHistoryJob);
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
     * @param replyTunnel the tunnel sourceRoutePeer should forward the source routed message to
     * @param replyThrough the gateway of the tunnel that the sourceRoutePeer will be sending to
     */
/********
    public void requestTunnelCreate(TunnelId createTunnel, TunnelId outTunnel, Hash peerRequested, Hash nextPeer, TunnelId replyTunnel, Hash replyThrough) {
        if (!_doLog) return;
        StringBuilder buf = new StringBuilder(128);
        buf.append(getPrefix());
        buf.append("request [").append(getName(peerRequested)).append("] to create tunnel [");
        buf.append(createTunnel.getTunnelId()).append("] ");
        if (nextPeer != null)
            buf.append("(next [").append(getName(nextPeer)).append("]) ");
        if (outTunnel != null)
            buf.append("via [").append(outTunnel.getTunnelId()).append("] ");
        if ( (replyTunnel != null) && (replyThrough != null) ) 
            buf.append("who forwards it through [").append(replyTunnel.getTunnelId()).append("] on [").append(getName(replyThrough)).append("]");
        addEntry(buf.toString());
    }
*********/
    
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
/*********
    public void receiveTunnelCreate(TunnelId createTunnel, Hash nextPeer, Date expire, boolean ok, Hash sourceRoutePeer) {
        if (!_doLog) return;
        StringBuilder buf = new StringBuilder(128);
        buf.append(getPrefix());
        buf.append("receive tunnel create [").append(createTunnel.getTunnelId()).append("] ");
        if (nextPeer != null)
            buf.append("(next [").append(getName(nextPeer)).append("]) ");
        buf.append("ok? ").append(ok).append(" expiring on [").append(getTime(expire.getTime())).append("]");
        addEntry(buf.toString());
    }
*********/
    
    /**
     * The local router has joined the given tunnel operating in the given state.
     *
     * @param state {"free inbound", "allocated inbound", "inactive inbound", "outbound", "participant", "pending"}
     * @param tunnel tunnel joined
     */
    public void tunnelJoined(String state, TunnelInfo tunnel) {
        if (!_doLog) return;
        if (tunnel == null) return;
        StringBuilder buf = new StringBuilder(128);
        buf.append(getPrefix());
        buf.append("joining as [").append(state);
        buf.append("] to tunnel: ").append(tunnel.toString());
        addEntry(buf.toString());
    }
    
    /**
     * The local router has joined the given tunnel operating in the given state.
     *
     * @param state {"free inbound", "allocated inbound", "inactive inbound", "outbound", "participant", "pending"}
     * @param tunnel tunnel joined
     */
    public void tunnelJoined(String state, HopConfig tunnel) {
        if (!_doLog) return;
        if (tunnel == null) return;
        StringBuilder buf = new StringBuilder(128);
        buf.append(getPrefix());
        buf.append("joining as [").append(state);
        buf.append("] to tunnel: ").append(tunnel.toString());
        addEntry(buf.toString());
    }
    
    public void tunnelDispatched(String info) {
        if (!_doLog) return;
        if (info == null) return;
        addEntry(getPrefix() + "tunnel dispatched: " + info);
    }
    
    public void tunnelDispatched(long messageId, long tunnelId, String type) {
        if (!_doLog) return;
        addEntry(getPrefix() + "message " + messageId + " on tunnel " + tunnelId + " as " + type);
    }
    
    public void tunnelDispatched(long messageId, long tunnelId, long toTunnel, Hash toPeer, String type) {
        if (!_doLog) return;
        if (toPeer != null)
            addEntry(getPrefix() + "message " + messageId + " on tunnel " + tunnelId + " / " + toTunnel + " to " + toPeer.toBase64() + " as " + type);
        else
            addEntry(getPrefix() + "message " + messageId + " on tunnel " + tunnelId + " / " + toTunnel + " as " + type);
    }
    
    public void tunnelDispatched(long messageId, long innerMessageId, long tunnelId, String type) {
        if (!_doLog) return;
        addEntry(getPrefix() + "message " + messageId + "/" + innerMessageId + " on " + tunnelId + " as " + type);
    }
    
    /**
     * The local router has detected a failure in the given tunnel
     *
     * @param tunnel tunnel failed
     */
    public void tunnelFailed(TunnelId tunnel) {
        if (!_doLog) return;
        if (tunnel == null) return;
        StringBuilder buf = new StringBuilder(128);
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
        StringBuilder buf = new StringBuilder(128);
        buf.append(getPrefix());
        buf.append("tunnel ").append(tunnel).append(" tested ok after ").append(timeToTest).append("ms");
        addEntry(buf.toString());
    }
    
    /**
     * The peer did not accept the tunnel join for the given reason 
     *
     */
    public void tunnelRejected(Hash peer, TunnelId tunnel, Hash replyThrough, String reason) {
        if (!_doLog) return;
        if ( (tunnel == null) || (peer == null) ) return;
        StringBuilder buf = new StringBuilder(128);
        buf.append(getPrefix());
        buf.append("tunnel [").append(tunnel.getTunnelId()).append("] was rejected by [");
        buf.append(getName(peer)).append("] for [").append(reason).append("]");
        if (replyThrough != null)
            buf.append(" with their reply intended to come through [").append(getName(replyThrough)).append("]");
        addEntry(buf.toString());
    }
    
    public void tunnelParticipantRejected(Hash peer, String msg) {
        if (!_doLog) return;
        if (peer == null) return;
        StringBuilder buf = new StringBuilder(128);
        buf.append(getPrefix());
        buf.append("tunnel participation rejected by [");
        buf.append(getName(peer)).append("]: ").append(msg);
        addEntry(buf.toString());
    }
    
    /**
     * The peer did not accept the tunnel join for the given reason (this may be because
     * of a timeout or an explicit refusal).
     *
     */
    public void tunnelRequestTimedOut(Hash peer, TunnelId tunnel) {
        if (!_doLog) return;
        if ( (tunnel == null) || (peer == null) ) return;
        StringBuilder buf = new StringBuilder(128);
        buf.append(getPrefix());
        buf.append("tunnel [").append(tunnel.getTunnelId()).append("] timed out on [");
        buf.append(getName(peer)).append("]");
        addEntry(buf.toString());
    }
    
    /**
     * We don't know about the given tunnel, so we are dropping a message sent to us by the
     * given router
     *
     * @param id tunnel ID we received a message for
     * @param from peer that sent us this message (if known)
     */
    public void droppedTunnelMessage(TunnelId id, long msgId, Date expiration, Hash from) {
        if (!_doLog) return;
        StringBuilder buf = new StringBuilder(128);
        buf.append(getPrefix());
        buf.append("dropped message ").append(msgId).append(" for unknown tunnel [").append(id.getTunnelId());
        buf.append("] from [").append(getName(from)).append("]").append(" expiring on ");
        buf.append(getTime(expiration.getTime()));
        addEntry(buf.toString());
    }
    
    /**
     * We received another message we weren't waiting for and don't know how to handle
     */
    public void droppedOtherMessage(I2NPMessage message, Hash from) {
        if (!_doLog) return;
        if (message == null) return;
        StringBuilder buf = new StringBuilder(512);
        buf.append(getPrefix());
        buf.append("dropped [").append(message.getClass().getName()).append("] ").append(message.getUniqueId());
        buf.append(" [").append(message.toString()).append("] from [");
        if (from != null)
            buf.append(from.toBase64());
        else
            buf.append("unknown");
        buf.append("] expiring in ").append(message.getMessageExpiration()-_context.clock().now()).append("ms");
        addEntry(buf.toString());
    }
    
    public void droppedInboundMessage(long messageId, Hash from, String info) {
        if (!_doLog) return;
        StringBuilder buf = new StringBuilder(512);
        buf.append(getPrefix());
        buf.append("dropped inbound message ").append(messageId);
        buf.append(" from ");
        if (from != null)
            buf.append(from.toBase64());
        else
            buf.append("unknown");
        buf.append(": ").append(info);
        addEntry(buf.toString());
        //if (_log.shouldLog(Log.ERROR))
        //    _log.error(buf.toString(), new Exception("source"));
    }
    
    /**
     * The message wanted a reply but no reply came in the time expected
     *
     * @param sentMessage message sent that didn't receive a reply
     */
    public void replyTimedOut(OutNetMessage sentMessage) {
        if (!_doLog) return;
        if (sentMessage == null) return;
        StringBuilder buf = new StringBuilder(512);
        buf.append(getPrefix());
        buf.append("timed out waiting for a reply to [").append(sentMessage.getMessageType());
        buf.append("] [").append(sentMessage.getMessageId()).append("] expiring on [");
        if (sentMessage != null)
            buf.append(getTime(sentMessage.getReplySelector().getExpiration()));
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
        StringBuilder buf = new StringBuilder(128);
        buf.append(getPrefix());
        buf.append("Error processing [").append(messageType).append("] [").append(messageId).append("] failed with [").append(error).append("]");
        addEntry(buf.toString());
    }
    
    /**
     * We shitlisted the peer
     */
    public void shitlist(Hash peer, String reason) {
        if (!_doLog) return;
        if (peer == null) return;
        addEntry("Shitlist " + peer.toBase64() + ": " + reason);
    }
       
    /**
     * We unshitlisted the peer
     */
    public void unshitlist(Hash peer) {
        if (!_doLog) return;
        if (peer == null) return;
        addEntry("Unshitlist " + peer.toBase64());
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
    public void sendMessage(String messageType, long messageId, long expiration, Hash peer, boolean sentOk, String info) {
        if (!_doLog) return;
        if (false) return;
        StringBuilder buf = new StringBuilder(256);
        buf.append(getPrefix());
        buf.append("send [").append(messageType).append("] message [").append(messageId).append("] ");
        buf.append("to [").append(getName(peer)).append("] ");
        buf.append("expiring on [").append(getTime(expiration)).append("] ");
        if (sentOk)
            buf.append("successfully");
        else
            buf.append("failed");
        if (info != null)
            buf.append(info);
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
    public void receiveMessage(String messageType, long messageId, long expiration, Hash from, boolean isValid) {
        if (!_doLog) return;
        if (false) return;
        StringBuilder buf = new StringBuilder(256);
        buf.append(getPrefix());
        buf.append("receive [").append(messageType).append("] with id [").append(messageId).append("] ");
        if (from != null)
            buf.append("from [").append(getName(from)).append("] ");
        buf.append("expiring on [").append(getTime(expiration)).append("] valid? ").append(isValid);
        addEntry(buf.toString());
    }
    public void receiveMessage(String messageType, long messageId, long expiration, boolean isValid) {
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
        StringBuilder buf = new StringBuilder(128);
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
        StringBuilder buf = new StringBuilder(64);
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
        StringBuilder buf = new StringBuilder(128);
        buf.append(getPrefix());
        buf.append("Send payload message in [").append(messageId).append("] in [").append(timeToSend).append("] successfully? ").append(successfullySent);
        addEntry(buf.toString());
    }
    
    public void receiveTunnelFragment(long messageId, int fragmentId, Object status) {
        if (!_doLog) return;
        if (messageId == -1) throw new IllegalArgumentException("why are you -1?");
        StringBuilder buf = new StringBuilder(48);
        buf.append(getPrefix());
        buf.append("Receive fragment ").append(fragmentId).append(" in ").append(messageId);
        buf.append(" status: ").append(status.toString());
        addEntry(buf.toString());
    }
    public void receiveTunnelFragmentComplete(long messageId) {
        if (!_doLog) return;
        if (messageId == -1) throw new IllegalArgumentException("why are you -1?");
        StringBuilder buf = new StringBuilder(48);
        buf.append(getPrefix());
        buf.append("Receive fragmented message completely: ").append(messageId);
        addEntry(buf.toString());
    }
    public void droppedFragmentedMessage(long messageId, String status) {
        if (!_doLog) return;
        if (messageId == -1) throw new IllegalArgumentException("why are you -1?");
        StringBuilder buf = new StringBuilder(48);
        buf.append(getPrefix());
        buf.append("Fragmented message dropped: ").append(messageId);
        buf.append(" ").append(status);
        addEntry(buf.toString());
    }
    public void fragmentMessage(long messageId, int numFragments, int totalLength, List messageIds, String msg) {
        if (!_doLog) return;
        //if (messageId == -1) throw new IllegalArgumentException("why are you -1?");
        StringBuilder buf = new StringBuilder(48);
        buf.append(getPrefix());
        buf.append("Break message ").append(messageId).append(" into fragments: ").append(numFragments);
        buf.append(" total size ").append(totalLength);
        buf.append(" contained in ").append(messageIds);
        if (msg != null)
            buf.append(": ").append(msg);
        addEntry(buf.toString());
    }
    public void fragmentMessage(long messageId, int numFragments, int totalLength, List messageIds, Object tunnel, String msg) {
        if (!_doLog) return;
        //if (messageId == -1) throw new IllegalArgumentException("why are you -1?");
        StringBuilder buf = new StringBuilder(48);
        buf.append(getPrefix());
        buf.append("Break message ").append(messageId).append(" into fragments: ").append(numFragments);
        buf.append(" total size ").append(totalLength);
        buf.append(" contained in ").append(messageIds);
        if (tunnel != null)
            buf.append(" on ").append(tunnel.toString());
        if (msg != null)
            buf.append(": ").append(msg);
        addEntry(buf.toString());
    }
    public void droppedTunnelDataMessageUnknown(long msgId, long tunnelId) {
        if (!_doLog) return;
        if (msgId == -1) throw new IllegalArgumentException("why are you -1?");
        StringBuilder buf = new StringBuilder(48);
        buf.append(getPrefix());
        buf.append("Dropped data message ").append(msgId).append(" for unknown tunnel ").append(tunnelId);
        addEntry(buf.toString());
    }
    public void droppedTunnelGatewayMessageUnknown(long msgId, long tunnelId) {
        if (!_doLog) return;
        if (msgId == -1) throw new IllegalArgumentException("why are you -1?");
        StringBuilder buf = new StringBuilder(48);
        buf.append(getPrefix());
        buf.append("Dropped gateway message ").append(msgId).append(" for unknown tunnel ").append(tunnelId);
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
        StringBuilder buf = new StringBuilder(48);
        buf.append(getTime(_context.clock().now()));
        buf.append(' ').append(_localIdent).append(": ");
        return buf.toString();
    }
    
    private final String getTime(long when) {
        synchronized (_fmt) {
            return _fmt.format(new Date(when));
        }
    }
    
    /**
     * Responsible for adding the entry, flushing if necessary.  
     * This is the only thing that adds to _unwrittenEntries.
     *
     */
    private void addEntry(String entry) {
        if (entry == null) return;
        _unwrittenEntries.offer(entry);
        int sz = _unwrittenEntries.size();
        if (sz > FLUSH_SIZE)
            flushEntries();
    }
    
    /**
     * Write out any unwritten entries, and clear the pending list
     */
    private void flushEntries() {
        if (!_doLog)
            _unwrittenEntries.clear();
        else if ((!_unwrittenEntries.isEmpty()) && !_doPause)
            writeEntries();
    }
    
    /**
     * Actually write the specified entries
     *
     */
    private synchronized void writeEntries() {
        File f = new File(_historyFile);
        if (!f.isAbsolute())
            f = new File(_context.getLogDir(), _historyFile);
        FileOutputStream fos = null;
        try {
            fos = new SecureFileOutputStream(f, true);
            String entry;
            while ((entry = _unwrittenEntries.poll()) != null) {
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
        public String getName() { return _doLog ? "Message debug log" : "Message debug log (disabled)"; }
        public void runJob() {
            flushEntries();
            updateSettings();
            requeue(WRITE_DELAY);
        }
    }
    
/****
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
****/
}
