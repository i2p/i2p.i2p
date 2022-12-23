package net.i2p.router.transport.udp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.southernstorm.noise.protocol.CipherState;

import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.transport.udp.PacketBuilder.Fragment;
import static net.i2p.router.transport.udp.SSU2Util.*;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 * Small, stub version of PeerState2, for handling destroy acks
 * with possible tokens in them.
 *
 * This is the "closing state" as defined in QUIC RFC-9000 section 10.2.1.
 * Unlike in QUIC, we do increment the packet number for every sent
 * destroy packet. Also, we retain the header and body decryption keys,
 * and we do process any tokens received.
 * We only respond when receiving data or a termination with a non-ack reason.
 *
 * Does not extend PeerState2 or PeerState.
 *
 * @since 0.9.57
 */
class PeerStateDestroyed implements SSU2Payload.PayloadCallback, SSU2Sender {
    private final RouterContext _context;
    private final Log _log;
    private final UDPTransport _transport;
    private final RemoteHostId _remoteHostId;
    private final int _mtu;
    private final long _sendConnID;
    private final long _rcvConnID;
    private final AtomicInteger _packetNumber;
    private final CipherState _sendCha;
    private final CipherState _rcvCha;
    private final byte[] _sendHeaderEncryptKey1;
    private final byte[] _rcvHeaderEncryptKey1;
    private final byte[] _sendHeaderEncryptKey2;
    private final byte[] _rcvHeaderEncryptKey2;
    private final SSU2Bitfield _receivedMessages;
    private final ACKTimer _ackTimer;
    private final KillTimer _killTimer;
    private final long _destroyedOn;
    protected volatile long _wantACKSendSince;
    // This is the mode we are in.
    // If this is 1, we have sent a termination ack.
    // Otherwise, this is the original reason we sent, and may possibly retransmit.
    private volatile int _destroyReason;

    private static final long MAX_LIFETIME = 2*60*1000;
    // retx at 7, 21, 49, 105
    private static final long TERMINATION_RETX_TIME = 7*1000;

    /**
     *  This must be called after the first termination or termination ack
     *  was sent from PeerState2, so the next packet number is correct.
     *
     *  @param peer that just sent (or received and sent) a termination
     */
    public PeerStateDestroyed(RouterContext ctx, UDPTransport transport, PeerState2 peer) {
        _context = ctx;
        _transport = transport;
        _log = ctx.logManager().getLog(PeerStateDestroyed.class);
        _remoteHostId = peer.getRemoteHostId();
        _mtu = peer.getMTU();
        _packetNumber = new AtomicInteger((int) peer.getNextPacketNumberNoThrow());
        _sendConnID = peer.getSendConnID();
        _rcvConnID = peer.getRcvConnID();
        _sendCha = peer.getSendCipher();
        _rcvCha = peer.getRcvCipher();
        _sendHeaderEncryptKey1 = peer.getSendHeaderEncryptKey1();
        _rcvHeaderEncryptKey1 = peer.getRcvHeaderEncryptKey1();
        _sendHeaderEncryptKey2 = peer.getSendHeaderEncryptKey2();
        _rcvHeaderEncryptKey2 = peer.getRcvHeaderEncryptKey2();
        _receivedMessages = peer.getReceivedMessages();
        _destroyReason = peer.getDestroyReason();
        _destroyedOn = _context.clock().now();
        _ackTimer = new ACKTimer();
        // if _destroyReason != 1, schedule ack timer to resend termination
        if (_destroyReason != REASON_TERMINATION)
            _ackTimer.schedule(TERMINATION_RETX_TIME);
        _killTimer = new KillTimer();
        _killTimer.schedule(MAX_LIFETIME);
    }

    /**
     *  Direct from IES2, there was never a PS2.
     *  Caller must send termination after creating.
     */
    public PeerStateDestroyed(RouterContext ctx, UDPTransport transport, RemoteHostId id,
                              long sendID, long rcvID, CipherState sendCha, CipherState rcvCha,
                              byte[] sendKey1, byte[] sendKey2, byte[] rcvKey2,
                              int reason) {
        _context = ctx;
        _transport = transport;
        _log = ctx.logManager().getLog(PeerStateDestroyed.class);
        _remoteHostId = id;
        _mtu = 1280;
        _packetNumber = new AtomicInteger();
        _sendConnID = sendID;
        _rcvConnID = rcvID;
        _sendCha = sendCha;
        _rcvCha = rcvCha;
        _sendHeaderEncryptKey1 = sendKey1;
        _rcvHeaderEncryptKey1 = _transport.getSSU2StaticIntroKey();
        _sendHeaderEncryptKey2 = sendKey2;
        _rcvHeaderEncryptKey2 = rcvKey2;
        _receivedMessages = new SSU2Bitfield(256, 0);
        // ack the Session Confirmed
        _receivedMessages.set(0);
        _destroyReason = reason;
        _destroyedOn = _context.clock().now();
        _ackTimer = new ACKTimer();
        // don't schedule ack timer to resend termination
        _killTimer = new KillTimer();
        _killTimer.schedule(MAX_LIFETIME);
    }

    /**
     *  Call at transport shutdown or cache eviction
     */
    public void kill() {
        _ackTimer.cancel();
        _killTimer.cancel();
        _sendCha.destroy();
        _rcvCha.destroy();
    }

    /// begin SSU2Sender interface ///

    public RemoteHostId getRemoteHostId() { return _remoteHostId; }
    public boolean isIPv6() { return _remoteHostId.getIP().length == 16; }
    public InetAddress getRemoteIPAddress() {
        try {
            return InetAddress.getByAddress(_remoteHostId.getIP());
        } catch (UnknownHostException uhe) {
            return null;
        }
    }
    public int getRemotePort() { return _remoteHostId.getPort(); }
    public int getMTU() { return _mtu; }
    public long getNextPacketNumber() { return _packetNumber.getAndIncrement(); }
    public long getSendConnID() { return _sendConnID; }
    public CipherState getSendCipher() { return _sendCha; }
    public byte[] getSendHeaderEncryptKey1() { return _sendHeaderEncryptKey1; }
    public byte[] getSendHeaderEncryptKey2() { return _sendHeaderEncryptKey2; }
    public void setDestroyReason(int reason) {}
    public SSU2Bitfield getReceivedMessages() { return _receivedMessages; }
    /**
     *  @return null always, we don't care what was acked
     */
    public SSU2Bitfield getAckedMessages() { return null; }
    public void fragmentsSent(long pktNum, int length, List<PacketBuilder.Fragment> fragments) {}
    public byte getFlags() { return 0; }

    /// end SSU2Sender interface ///

    long getRcvConnID() { return _rcvConnID; }

    private synchronized void messagePartiallyReceived() {
        if (_wantACKSendSince <= 0) {
            long now = _context.clock().now();
            if (_destroyedOn - now < MAX_LIFETIME) {
                _wantACKSendSince = now;
                _ackTimer.schedule();
            }
        }
    }

    /**
     *  @param packet fully encrypted, header and body decryption will be done here
     */
    void receivePacket(UDPPacket packet) {
        receivePacket(packet.getRemoteHost(), packet);
    }

    /**
     *  @param from source address
     *  @param packet fully encrypted, header and body decryption will be done here
     *  @since 0.9.55
     */
    void receivePacket(RemoteHostId from, UDPPacket packet) {
        if (!from.equals(_remoteHostId)) {
            // TODO, QUIC says we can respond, or not...
            if (_log.shouldWarn())
                _log.warn("Inbound packet from " + from + " on " + this);
        }
        DatagramPacket dpacket = packet.getPacket();
        byte[] data = dpacket.getData();
        int off = dpacket.getOffset();
        int len = dpacket.getLength();
        try {
            SSU2Header.Header header = SSU2Header.trialDecryptShortHeader(packet, _rcvHeaderEncryptKey1, _rcvHeaderEncryptKey2);
            if (header == null) {
                if (_log.shouldWarn())
                    _log.warn("Inbound packet too short " + len + " on " + this);
                return;
            }
            if (header.getDestConnID() != _rcvConnID) {
                if (_log.shouldWarn())
                    _log.warn("bad Dest Conn id " + header + " size " + len + " on " + this);
                return;
            }
            if (header.getType() != DATA_FLAG_BYTE) {
                // for direct from IES2, these could be retransmitted session confirmed
                if (_log.shouldWarn())
                    _log.warn("bad data pkt type " + header.getType() + " size " + len + " on " + this);
                return;
            }
            long n = header.getPacketNumber();
            SSU2Header.acceptTrialDecrypt(packet, header);
            synchronized (_rcvCha) {
                _rcvCha.setNonce(n);
                _rcvCha.decryptWithAd(header.data, data, off + SHORT_HEADER_SIZE, data, off + SHORT_HEADER_SIZE, len - SHORT_HEADER_SIZE);
            }

            // We do not call set() so we won't ack anything else received
            // We do not call get() or do a dup check at all, because
            // spec says a packet with termination may be retransmitted as-is
            // and we need to respond to it
            //if (_receivedMessages.get(n)) {
            //    // dup...
            //    return;
            //}

            int payloadLen = len - (SHORT_HEADER_SIZE + MAC_LEN);
            if (_log.shouldDebug())
                _log.debug("New " + len + " byte pkt " + n + " rcvd on " + this);
            SSU2Payload.processPayload(_context, this, data, off + SHORT_HEADER_SIZE, payloadLen, false, from);
        } catch (Exception e) {
            if (_log.shouldWarn())
                _log.warn("Bad encrypted packet on: " + this, e);
        }
    }

    /////////////////////////////////////////////////////////
    // begin payload callbacks
    /////////////////////////////////////////////////////////

    public void gotDateTime(long time) {}
    public void gotOptions(byte[] options, boolean isHandshake) {}

    public void gotRI(RouterInfo ri, boolean isHandshake, boolean flood) {
        if (_log.shouldDebug())
            _log.debug("Got RI block on " + this);
        messagePartiallyReceived();
    }

    public void gotRIFragment(byte[] data, boolean isHandshake, boolean flood, boolean isGzipped, int frag, int totalFrags) {
        if (_log.shouldDebug())
            _log.debug("Got RI FRAGMENT block on " + this);
        messagePartiallyReceived();
    }

    public void gotAddress(byte[] ip, int port) {}
    public void gotRelayTagRequest() {}
    public void gotRelayTag(long tag) {}

    public void gotRelayRequest(byte[] data) {
        if (_log.shouldDebug())
            _log.debug("Got RELAY block on " + this);
        messagePartiallyReceived();
    }

    public void gotRelayResponse(int status, byte[] data) {
        if (_log.shouldDebug())
            _log.debug("Got RELAY block on " + this);
        messagePartiallyReceived();
    }

    public void gotRelayIntro(Hash aliceHash, byte[] data) {
        if (_log.shouldDebug())
            _log.debug("Got RELAY block on " + this);
        messagePartiallyReceived();
    }

    public void gotPeerTest(int msg, int status, Hash h, byte[] data) {
        if (_log.shouldDebug())
            _log.debug("Got PEER TEST block on " + this);
        messagePartiallyReceived();
    }

    public void gotToken(long token, long expires) {
        if (_log.shouldDebug())
            _log.debug("Got TOKEN: " + token + " expires " + DataHelper.formatTime(expires) + " on " + this);
        _transport.getEstablisher().addOutboundToken(_remoteHostId, token, expires);
        // do NOT send an ACK for this, as it is likely to be in with the termination ack.
        // gotTermination() will request ack if necessary
    }

    public void gotI2NP(I2NPMessage msg) {
        if (_log.shouldDebug())
            _log.debug("Got I2NP block: " + msg + " on " + this);
            messagePartiallyReceived();
    }

    public void gotFragment(byte[] data, int off, int len, long messageId, int frag, boolean isLast) {
        if (_log.shouldDebug())
            _log.debug("Got FRAGMENT block on " + this);
        messagePartiallyReceived();
    }

    public void gotACK(long ackThru, int acks, byte[] ranges) {}

    public void gotTermination(int reason, long count) {
        if (_log.shouldInfo())
            _log.info("Got TERMINATION block, reason: " + reason + " (our reason " + _destroyReason + ") on " + this);
        if (reason == SSU2Util.REASON_TERMINATION) {
            // prevent any additional tranmissions if other packets come in
            // i2pd has a bug and may send I2NP after termination ack
            _wantACKSendSince = _context.clock().now() + 9999999;
            // cancel termination retx, fire kill timer sooner
            _ackTimer.cancel();
            _killTimer.reschedule(15*1000);

        } else {
            // If we received a destroy besides reason_termination, send reason_termination
            // Note that i2pd before 0.9.57 has a bug and will send a second termination in response to our first ack
            _destroyReason = SSU2Util.REASON_TERMINATION;
            messagePartiallyReceived();
        }
    }

    public void gotPathChallenge(RemoteHostId from, byte[] data) {}
    public void gotPathResponse(RemoteHostId from, byte[] data) {}

    /////////////////////////////////////////////////////////
    // end payload callbacks
    /////////////////////////////////////////////////////////

    /**
     *  A timer to send an ack+destroy packet.
     */
    private class ACKTimer extends SimpleTimer2.TimedEvent {
        private long _delay = TERMINATION_RETX_TIME;

        /**
         *  Caller must schedule
         */
        public ACKTimer() {
            super(_context.simpleTimer2());
        }

        public void schedule() {
            // delay to implement a simple rate limit, as recommended by QUIC
            reschedule(250, true);
        }

        /**
         *  Send a termination+ack+token packet, unless acks were already sent
         *  as indicated by _wantACKSendSince == 0.
         *  Also does retransmission, if we were the one that sent the first termination.
         */
        public void timeReached() {
            synchronized(PeerStateDestroyed.this) {
                if (_wantACKSendSince <= 0 && _destroyReason == REASON_TERMINATION)
                    return;
                _wantACKSendSince = 0;
            }
            // If we received a destroy, send reason_termination
            // otherwise, send the original reason.

            try {
                UDPPacket pkt = _transport.getBuilder2().buildSessionDestroyPacket(_destroyReason, PeerStateDestroyed.this);
                if (_log.shouldDebug())
                    _log.debug("Sending TERMINATION reason " + _destroyReason + " to " + PeerStateDestroyed.this);
                _transport.send(pkt);
            } catch (IOException ioe) {}
            if (_destroyReason != REASON_TERMINATION) {
                _delay *= 2;
                reschedule(_delay);
            }
        }
    }

    /**
     *  A timer to remove us from the transport list.
     */
    private class KillTimer extends SimpleTimer2.TimedEvent {

        /**
         *  Caller must schedule
         */
        public KillTimer() {
            super(_context.simpleTimer2());
        }

        public void timeReached() {
            //if (_log.shouldDebug())
            //    _log.debug("Done listening for " + PeerStateDestroyed.this);
            _ackTimer.cancel();
            _transport.removeRecentlyClosed(PeerStateDestroyed.this);
            _sendCha.destroy();
            _rcvCha.destroy();
        }
    }

    @Override
    public String toString() {
        return "peer destroyed " + DataHelper.formatDuration(_context.clock().now() - _destroyedOn) + " ago: " + _remoteHostId;
    }
}
