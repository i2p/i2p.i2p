package net.i2p.client.streaming.impl;

import java.io.Closeable;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.SendMessageOptions;
import net.i2p.client.SendMessageStatusListener;
import net.i2p.client.streaming.I2PSocketException;
import net.i2p.data.ByteArray;
import net.i2p.data.i2cp.MessageStatusMessage;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 * Queue out packets to be sent through the session.  
 * Well, thats the theory at least... in practice we just
 * send them immediately with no blocking, since the 
 * mode=bestEffort doesnt block in the SDK.
 *<p>
 * MessageOutputStream -&gt; ConnectionDataReceiver -&gt; Connection -&gt; PacketQueue -&gt; I2PSession
 */
class PacketQueue implements SendMessageStatusListener, Closeable {
    private final I2PAppContext _context;
    private final Log _log;
    private final ByteCache _cache = ByteCache.getInstance(64, 36*1024);
    private final Map<Long, Connection> _messageStatusMap;
    private volatile boolean _dead;
    
    private static final int FLAGS_INITIAL_TAGS = Packet.FLAG_SYNCHRONIZE;
    private static final int FLAGS_FINAL_TAGS = Packet.FLAG_CLOSE |
                                              Packet.FLAG_RESET |
                                              Packet.FLAG_ECHO;
    private static final int INITIAL_TAGS_TO_SEND = 32;
    private static final int MIN_TAG_THRESHOLD = 20;
    private static final int TAG_WINDOW_FACTOR = 5;
    private static final int FINAL_TAGS_TO_SEND = 4;
    private static final int FINAL_TAG_THRESHOLD = 2;
    private static final long REMOVE_EXPIRED_TIME = 67*1000;
    private static final boolean ENABLE_STATUS_LISTEN = true;

    public PacketQueue(I2PAppContext context, SimpleTimer2 timer) {
        _context = context;
        _log = context.logManager().getLog(PacketQueue.class);
        _messageStatusMap = new ConcurrentHashMap<Long, Connection>(16);
        new RemoveExpired(timer);
        // all createRateStats in ConnectionManager
    }

    /**
     * Cannot be restarted.
     *
     * @since 0.9.14
     */
    public void close() {
        _dead = true;
        _messageStatusMap.clear();
    }
    
    /**
     * Add a new packet to be sent out ASAP.
     * This updates the acks.
     *
     * keys and tags disabled since dropped in I2PSession
     * @return true if sent
     */
    public boolean enqueue(PacketLocal packet) {
        if (_dead)
            return false;
        
        //SessionKey keyUsed = packet.getKeyUsed();
        //if (keyUsed == null)
        //    keyUsed = new SessionKey();
        //Set tagsSent = packet.getTagsSent();
        //if (tagsSent == null)
        //    tagsSent = new HashSet(0);

        if (packet.getAckTime() > 0) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Not resending " + packet);
            return false;
        }

        Connection con = packet.getConnection();
        if (con != null) {
            // this updates the ack/nack fields
            con.getInputStream().updateAcks(packet);
        }
    
        ByteArray ba = _cache.acquire();
        byte buf[] = ba.getData();

        long begin = 0;
        long end = 0;
        boolean sent = false;
        try {
            int size = 0;
            //long beforeWrite = System.currentTimeMillis();
            if (packet.shouldSign())
                size = packet.writeSignedPacket(buf, 0);
            else
                size = packet.writePacket(buf, 0);
            //long writeTime = System.currentTimeMillis() - beforeWrite;
            //if ( (writeTime > 1000) && (_log.shouldLog(Log.WARN)) )
            //    _log.warn("took " + writeTime + "ms to write the packet: " + packet);

            // last chance to short circuit...
            if (packet.getAckTime() > 0) return false;
            
            // this should not block!
            begin = _context.clock().now();
            long expires = 0;
            Connection.ResendPacketEvent rpe = (Connection.ResendPacketEvent) packet.getResendEvent();
            if (rpe != null)
                // we want the router to expire it a little before we do,
                // so if we retransmit it will use a new tunnel/lease combo
                expires = rpe.getNextSendTime() - 500;
            SendMessageOptions options = new SendMessageOptions();
            if (expires > 0)
                options.setDate(expires);
            boolean listenForStatus = false;
            // FINAL trumps INITIAL, in the case of SYN+CLOSE
            if (packet.isFlagSet(FLAGS_FINAL_TAGS)) {
                if (packet.isFlagSet(Packet.FLAG_ECHO)) {
                    // Send LS for PING, not for PONG
                    if (packet.getSendStreamId() <= 0)  // pong
                        options.setSendLeaseSet(false);
                } else {
                    options.setSendLeaseSet(false);
                }
                int sendTags = FINAL_TAGS_TO_SEND;
                int tagThresh = FINAL_TAG_THRESHOLD;
                if (con != null) {
                    ConnectionOptions copts = con.getOptions();
                    int cSendTags = copts.getTagsToSend();
                    int cTagThresh = copts.getTagThreshold();
                    if (cSendTags < sendTags)
                        sendTags = cSendTags;
                    if (cTagThresh < tagThresh)
                        tagThresh = cTagThresh;
                }
                options.setTagsToSend(sendTags);
                options.setTagThreshold(tagThresh);
            } else if (packet.isFlagSet(FLAGS_INITIAL_TAGS)) {
                if (con != null) {
                    if (con.isInbound())
                        options.setSendLeaseSet(false);
                    else if (ENABLE_STATUS_LISTEN)
                        listenForStatus = true;
                }
                int sendTags = INITIAL_TAGS_TO_SEND;
                int tagThresh = MIN_TAG_THRESHOLD;
                if (con != null) {
                    ConnectionOptions copts = con.getOptions();
                    int cSendTags = copts.getTagsToSend();
                    int cTagThresh = copts.getTagThreshold();
                    if (cSendTags < sendTags)
                        sendTags = cSendTags;
                    if (cTagThresh < tagThresh)
                        tagThresh = cTagThresh;
                }
                options.setTagsToSend(sendTags);
                options.setTagThreshold(tagThresh);
            } else {
                if (con != null) {
                    if (con.isInbound() && con.getLifetime() < 2*60*1000)
                        options.setSendLeaseSet(false);
                    // increase threshold with higher window sizes to prevent stalls
                    // after tag delivery failure
                    ConnectionOptions copts = con.getOptions();
                    int wdw = copts.getWindowSize();
                    int thresh = Math.max(MIN_TAG_THRESHOLD, wdw * TAG_WINDOW_FACTOR);
                    int cTagThresh = copts.getTagThreshold();
                    if (cTagThresh < thresh)
                        thresh = cTagThresh;
                    options.setTagThreshold(thresh);
                }
            }
            I2PSession session = packet.getSession();
            if (listenForStatus) {
                long id = session.sendMessage(packet.getTo(), buf, 0, size,
                                 I2PSession.PROTO_STREAMING, packet.getLocalPort(), packet.getRemotePort(),
                                 options, this);
                _messageStatusMap.put(Long.valueOf(id), con);
                sent = true;
            } else {
                sent = session.sendMessage(packet.getTo(), buf, 0, size,
                                 I2PSession.PROTO_STREAMING, packet.getLocalPort(), packet.getRemotePort(),
                                 options);
            }
            end = _context.clock().now();
            
            if ( (end-begin > 1000) && (_log.shouldLog(Log.WARN)) ) 
                _log.warn("Took " + (end-begin) + "ms to sendMessage(...) " + packet);
            
            _context.statManager().addRateData("stream.con.sendMessageSize", size, packet.getLifetime());
            if (packet.getNumSends() > 1)
                _context.statManager().addRateData("stream.con.sendDuplicateSize", size, packet.getLifetime());
            
            if (con != null) {
                con.incrementBytesSent(size);
                if (packet.getNumSends() > 1)
                    con.incrementDupMessagesSent(1);
            }
        } catch (I2PSessionException ise) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to send the packet " + packet, ise);
        }
        
        _cache.release(ba);
        
        if (!sent) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Send failed for " + packet);
            if (con != null) // handle race on b0rk
                con.disconnect(false);
        } else {
            //packet.setKeyUsed(keyUsed);
            //packet.setTagsSent(tagsSent);
            packet.incrementSends();
            if (con != null && _log.shouldDebug()) {
                String suffix = "wsize " + con.getOptions().getWindowSize() + " rto " + con.getOptions().getRTO();
                con.getConnectionManager().getPacketHandler().displayPacket(packet, "SEND", suffix);
            }
            if (I2PSocketManagerFull.pcapWriter != null &&
                _context.getBooleanProperty(I2PSocketManagerFull.PROP_PCAP))
                packet.logTCPDump();
        }
        
        if ( (packet.getSequenceNum() == 0) && (!packet.isFlagSet(Packet.FLAG_SYNCHRONIZE)) ) {
            // ack only, so release it asap
            packet.releasePayload();
        } else if (packet.isFlagSet(Packet.FLAG_ECHO) && !packet.isFlagSet(Packet.FLAG_SIGNATURE_INCLUDED) ) {
            // pong
            packet.releasePayload();
        } else if (packet.isFlagSet(Packet.FLAG_RESET)) {
            // reset
            packet.releasePayload();
        }
        return sent;
    }

    /**
     * SendMessageStatusListener interface
     *
     * Tell the client of an update in the send status for a message
     * previously sent with I2PSession.sendMessage().
     * Multiple calls for a single message ID are possible.
     *
     * @param session session notifying
     * @param msgId message number returned from a previous sendMessage() call
     * @param status of the message, as defined in MessageStatusMessage and this class.
     * @since 0.9.14
     */
    public void messageStatus(I2PSession session, long msgId, int status) {
        if (_dead)
            return;
        Long id = Long.valueOf(msgId);
        Connection con = _messageStatusMap.get(id);
        if (con == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Rcvd status " + status + " for msg " + msgId + " on unknown connection");
            return;
        }

        switch (status) {
            case MessageStatusMessage.STATUS_SEND_BEST_EFFORT_FAILURE:
            // not really guaranteed
            case MessageStatusMessage.STATUS_SEND_GUARANTEED_FAILURE:
            // no tunnels may fix itself, allow retx
            case MessageStatusMessage.STATUS_SEND_FAILURE_NO_TUNNELS:
            // probably took a long time to open the tunnel, allow retx
            case MessageStatusMessage.STATUS_SEND_FAILURE_EXPIRED:
            // overflow in router-side I2CP queue, sent as of 0.9.29, will be retried
            case MessageStatusMessage.STATUS_SEND_FAILURE_LOCAL:
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Rcvd soft failure status " + status + " for msg " + msgId + " on " + con);
                _messageStatusMap.remove(id);
                break;

            case MessageStatusMessage.STATUS_SEND_FAILURE_NO_LEASESET:
                // Ideally we would like to make this a hard failure,
                // but it caused far too many fast-fails that were then
                // resolved by the user clicking reload in his browser.
                // Until the LS fetch is faster and more reliable,
                // or we increase the timeout for it,
                // we can't treat this one as a hard fail.
                // Let the streaming retransmission paper over the problem.
                if (_log.shouldLog(Log.WARN))
                    _log.warn("LS lookup (soft) failure for msg " + msgId + " on " + con);
                _messageStatusMap.remove(id);
                break;


            case MessageStatusMessage.STATUS_SEND_FAILURE_ROUTER:
            case MessageStatusMessage.STATUS_SEND_FAILURE_NETWORK:
            case MessageStatusMessage.STATUS_SEND_FAILURE_BAD_SESSION:
            case MessageStatusMessage.STATUS_SEND_FAILURE_BAD_MESSAGE:
            case MessageStatusMessage.STATUS_SEND_FAILURE_BAD_OPTIONS:
            case MessageStatusMessage.STATUS_SEND_FAILURE_OVERFLOW:
            case MessageStatusMessage.STATUS_SEND_FAILURE_LOCAL_LEASESET:
            case MessageStatusMessage.STATUS_SEND_FAILURE_UNSUPPORTED_ENCRYPTION:
            case MessageStatusMessage.STATUS_SEND_FAILURE_DESTINATION:
            case MessageStatusMessage.STATUS_SEND_FAILURE_BAD_LEASESET:
            case MessageStatusMessage.STATUS_SEND_FAILURE_EXPIRED_LEASESET:
            case SendMessageStatusListener.STATUS_CANCELLED:
                if (con.getHighestAckedThrough() >= 0) {
                    // a retxed SYN succeeded before the first SYN failed
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Rcvd hard failure but already connected, status " + status + " for msg " + msgId + " on " + con);
                } else if (!con.getIsConnected()) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Rcvd hard failure but already closed, status " + status + " for msg " + msgId + " on " + con);
                } else {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Rcvd hard failure status " + status + " for msg " + msgId + " on " + con);
                    _messageStatusMap.remove(id);
                    IOException ioe = new I2PSocketException(status);
                    con.getOutputStream().streamErrorOccurred(ioe);
                    con.getInputStream().streamErrorOccurred(ioe);
                    con.setConnectionError(ioe.getLocalizedMessage());
                    con.disconnect(false);
                }
                break;

            case MessageStatusMessage.STATUS_SEND_BEST_EFFORT_SUCCESS:
            case MessageStatusMessage.STATUS_SEND_GUARANTEED_SUCCESS:
            case MessageStatusMessage.STATUS_SEND_SUCCESS_LOCAL:
                if (_log.shouldLog(Log.INFO))
                    _log.info("Rcvd success status " + status + " for msg " + msgId + " on " + con);
                _messageStatusMap.remove(id);
                break;

            case MessageStatusMessage.STATUS_SEND_ACCEPTED:
                if (_log.shouldLog(Log.INFO))
                    _log.info("Rcvd accept status " + status + " for msg " + msgId + " on " + con);
                break;

            default:
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Rcvd unknown status " + status + " for msg " + msgId + " on " + con);
                _messageStatusMap.remove(id);
                break;
        }
    }

    /**
     *  Check for expired message states, without wastefully setting a timer for each
     *  message.
     *  @since 0.9.14
     */
    private class RemoveExpired extends SimpleTimer2.TimedEvent {
        
        public RemoveExpired(SimpleTimer2 timer) {
             super(timer, REMOVE_EXPIRED_TIME);
        }

        public void timeReached() {
            if (_dead)
                return;
            if (!_messageStatusMap.isEmpty()) {
                for (Iterator<Connection> iter = _messageStatusMap.values().iterator(); iter.hasNext(); ) {
                    Connection con = iter.next();
                    if (!con.getIsConnected() || con.getLifetime() > 2*60*1000L)
                        iter.remove();
                }
            }
            schedule(REMOVE_EXPIRED_TIME);
        }
    }
}
