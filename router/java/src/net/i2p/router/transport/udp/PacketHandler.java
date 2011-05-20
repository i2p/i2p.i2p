package net.i2p.router.transport.udp;

import java.util.Date;

import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.data.DataHelper;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Pull inbound packets from the inbound receiver's queue, figure out what
 * peer session they belong to (if any), authenticate and decrypt them 
 * with the appropriate keys, and push them to the appropriate handler.  
 * Data and ACK packets go to the InboundMessageFragments, the various 
 * establishment packets go to the EstablishmentManager, and, once implemented,
 * relay packets will go to the relay manager.  At the moment, this is 
 * an actual pool of packet handler threads, each pulling off the inbound
 * receiver's queue and pushing them as necessary.
 *
 */
class PacketHandler {
    private final RouterContext _context;
    private final Log _log;
    private final UDPTransport _transport;
    private final UDPEndpoint _endpoint;
    private final EstablishmentManager _establisher;
    private final InboundMessageFragments _inbound;
    private final PeerTestManager _testManager;
    private final IntroductionManager _introManager;
    private boolean _keepReading;
    private final Handler[] _handlers;
    
    private static final int MIN_NUM_HANDLERS = 2;  // unless < 32MB
    private static final int MAX_NUM_HANDLERS = 5;
    /** let packets be up to 30s slow */
    private static final long GRACE_PERIOD = Router.CLOCK_FUDGE_FACTOR + 30*1000;
    
    PacketHandler(RouterContext ctx, UDPTransport transport, UDPEndpoint endpoint, EstablishmentManager establisher,
                  InboundMessageFragments inbound, PeerTestManager testManager, IntroductionManager introManager) {
        _context = ctx;
        _log = ctx.logManager().getLog(PacketHandler.class);
        _transport = transport;
        _endpoint = endpoint;
        _establisher = establisher;
        _inbound = inbound;
        _testManager = testManager;
        _introManager = introManager;

        long maxMemory = Runtime.getRuntime().maxMemory();
        if (maxMemory == Long.MAX_VALUE)
            maxMemory = 96*1024*1024l;
        int num_handlers;
        if (maxMemory < 32*1024*1024)
            num_handlers = 1;
        else if (maxMemory < 64*1024*1024)
            num_handlers = 2;
        else
            num_handlers = Math.max(MIN_NUM_HANDLERS, Math.min(MAX_NUM_HANDLERS, ctx.bandwidthLimiter().getInboundKBytesPerSecond() / 20));
        _handlers = new Handler[num_handlers];
        for (int i = 0; i < num_handlers; i++) {
            _handlers[i] = new Handler();
        }

        _context.statManager().createRateStat("udp.handleTime", "How long it takes to handle a received packet after its been pulled off the queue", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.queueTime", "How long after a packet is received can we begin handling it", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receivePacketSkew", "How long ago after the packet was sent did we receive it", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.droppedInvalidUnkown", "How old the packet we dropped due to invalidity (unkown type) was", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.droppedInvalidReestablish", "How old the packet we dropped due to invalidity (doesn't use existing key, not an establishment) was", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.droppedInvalidEstablish", "How old the packet we dropped due to invalidity (establishment, bad key) was", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.droppedInvalidEstablish.inbound", "How old the packet we dropped due to invalidity (even though we have an active inbound establishment with the peer) was", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.droppedInvalidEstablish.outbound", "How old the packet we dropped due to invalidity (even though we have an active outbound establishment with the peer) was", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.droppedInvalidEstablish.new", "How old the packet we dropped due to invalidity (even though we do not have any active establishment with the peer) was", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.droppedInvalidInboundEstablish", "How old the packet we dropped due to invalidity (inbound establishment, bad key) was", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.droppedInvalidSkew", "How skewed the packet we dropped due to invalidity (valid except bad skew) was", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.packetDequeueTime", "How long it takes the UDPReader to pull a packet off the inbound packet queue (when its slow)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.packetVerifyTime", "How long it takes the PacketHandler to verify a data packet after dequeueing (period is dequeue time)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.packetVerifyTimeSlow", "How long it takes the PacketHandler to verify a data packet after dequeueing when its slow (period is dequeue time)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.packetValidateMultipleCount", "How many times we validate a packet, if done more than once (period = afterValidate-enqueue)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.packetNoValidationLifetime", "How long packets that are never validated are around for", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receivePacketSize.sessionRequest", "Packet size of the given inbound packet type (period is the packet's lifetime)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receivePacketSize.sessionConfirmed", "Packet size of the given inbound packet type (period is the packet's lifetime)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receivePacketSize.sessionCreated", "Packet size of the given inbound packet type (period is the packet's lifetime)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receivePacketSize.dataKnown", "Packet size of the given inbound packet type (period is the packet's lifetime)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receivePacketSize.dataKnownAck", "Packet size of the given inbound packet type (period is the packet's lifetime)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receivePacketSize.dataUnknown", "Packet size of the given inbound packet type (period is the packet's lifetime)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receivePacketSize.dataUnknownAck", "Packet size of the given inbound packet type (period is the packet's lifetime)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receivePacketSize.test", "Packet size of the given inbound packet type (period is the packet's lifetime)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receivePacketSize.relayRequest", "Packet size of the given inbound packet type (period is the packet's lifetime)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receivePacketSize.relayIntro", "Packet size of the given inbound packet type (period is the packet's lifetime)", "udp", UDPTransport.RATES);
        _context.statManager().createRateStat("udp.receivePacketSize.relayResponse", "Packet size of the given inbound packet type (period is the packet's lifetime)", "udp", UDPTransport.RATES);
    }
    
    public void startup() { 
        _keepReading = true;
        for (int i = 0; i < _handlers.length; i++) {
            I2PThread t = new I2PThread(_handlers[i], "UDP Packet handler " + (i+1) + '/' + _handlers.length, true);
            t.start();
        }
    }
    
    public void shutdown() { 
        _keepReading = false; 
    }

    String getHandlerStatus() {
        StringBuilder rv = new StringBuilder();
        rv.append("Handlers: ").append(_handlers.length);
        for (int i = 0; i < _handlers.length; i++) {
            Handler handler = _handlers[i];
            rv.append(" handler ").append(i).append(" state: ").append(handler._state);
        }
        return rv.toString();
    }

    /** the packet is from a peer we are establishing an outbound con to, but failed validation, so fallback */
    private static final short OUTBOUND_FALLBACK = 1;
    /** the packet is from a peer we are establishing an inbound con to, but failed validation, so fallback */
    private static final short INBOUND_FALLBACK = 2;
    /** the packet is not from anyone we know */
    private static final short NEW_PEER = 3;
    
    private class Handler implements Runnable { 
        private UDPPacketReader _reader;
        public volatile int _state;
        public Handler() {
            _reader = new UDPPacketReader(_context);
            _state = 0;
        }
        
        public void run() {
            _state = 1;
            while (_keepReading) {
                _state = 2;
                UDPPacket packet = _endpoint.receive();
                _state = 3;
                if (packet == null) break; // keepReading is probably false, or bind failed...

                packet.received();
                if (_log.shouldLog(Log.INFO))
                    _log.info("Received the packet " + packet);
                _state = 4;
                long queueTime = packet.getLifetime();
                long handleStart = _context.clock().now();
                try {
                    _state = 5;
                    handlePacket(_reader, packet);
                    _state = 6;
                } catch (Exception e) {
                    _state = 7;
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("Crazy error handling a packet: " + packet, e);
                }
                long handleTime = _context.clock().now() - handleStart;
                packet.afterHandling();
                _context.statManager().addRateData("udp.handleTime", handleTime, packet.getLifetime());
                _context.statManager().addRateData("udp.queueTime", queueTime, packet.getLifetime());
                _state = 8;

                if (_log.shouldLog(Log.INFO))
                    _log.info("Done receiving the packet " + packet);
                
                if (handleTime > 1000) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Took " + handleTime + " to process the packet " 
                                  + packet + ": " + _reader);
                }
                
                long enqueueTime = packet.getEnqueueTime();
                long recvTime = packet.getReceivedTime();
                long beforeValidateTime = packet.getBeforeValidate();
                long afterValidateTime = packet.getAfterValidate();
                int validateCount = packet.getValidateCount();
                
                long timeToDequeue = recvTime - enqueueTime;
                long timeToValidate = 0;
                long authTime = 0;
                if (afterValidateTime > 0) {
                    timeToValidate = afterValidateTime - enqueueTime;
                    authTime = afterValidateTime - beforeValidateTime;
                }
                if (timeToDequeue > 50)
                    _context.statManager().addRateData("udp.packetDequeueTime", timeToDequeue, timeToDequeue);
                if (authTime > 50)
                    _context.statManager().addRateData("udp.packetAuthRecvTime", authTime, beforeValidateTime-recvTime);
                if (afterValidateTime > 0) {
                    _context.statManager().addRateData("udp.packetVerifyTime", timeToValidate, authTime);
                    if (timeToValidate > 50)
                        _context.statManager().addRateData("udp.packetVerifyTimeSlow", timeToValidate, authTime);
                }
                if (validateCount > 1)
                    _context.statManager().addRateData("udp.packetValidateMultipleCount", validateCount, timeToValidate);
                else if (validateCount <= 0)
                    _context.statManager().addRateData("udp.packetNoValidationLifetime", packet.getLifetime(), 0);
                
                // back to the cache with thee!
                packet.release();
                _state = 9;
            }
        }
    //}

        /**
         * Initial handling, called for every packet
         * Find the state and call the correct receivePacket() variant
         */
        private void handlePacket(UDPPacketReader reader, UDPPacket packet) {
            if (packet == null) return;

            _state = 10;
            
            RemoteHostId rem = packet.getRemoteHost();
            PeerState state = _transport.getPeerState(rem);
            if (state == null) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Packet received is not for a connected peer");
                _state = 11;
                InboundEstablishState est = _establisher.getInboundState(rem);
                if (est != null) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Packet received IS for an inbound establishment");
                    _state = 12;
                    receivePacket(reader, packet, est);
                } else {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Packet received is not for an inbound establishment");
                    _state = 13;
                    OutboundEstablishState oest = _establisher.getOutboundState(rem);
                    if (oest != null) {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Packet received IS for an outbound establishment");
                        _state = 14;
                        receivePacket(reader, packet, oest);
                    } else {
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Packet received is not for an inbound or outbound establishment");
                        // ok, not already known establishment, try as a new one
                        _state = 15;
                        receivePacket(reader, packet, NEW_PEER);
                    }
                }
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Packet received IS for an existing peer");
                _state = 16;
                receivePacket(reader, packet, state);
            }
        }

        /**
         * Established conn
         * Decrypt and validate the packet then call handlePacket()
         */
        private void receivePacket(UDPPacketReader reader, UDPPacket packet, PeerState state) {
            _state = 17;
            boolean isValid = packet.validate(state.getCurrentMACKey());
            if (!isValid) {
                _state = 18;
                if (state.getNextMACKey() != null)
                    isValid = packet.validate(state.getNextMACKey());
                if (!isValid) {
                    _state = 19;
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Failed validation with existing con, trying as new con: " + packet);

                    isValid = packet.validate(_transport.getIntroKey());
                    if (isValid) {
                        _state = 20;
                        // this is a stray packet from an inbound establishment
                        // process, so try our intro key
                        // (after an outbound establishment process, there wouldn't
                        //  be any stray packets)
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("Validation with existing con failed, but validation as reestablish/stray passed");
                        packet.decrypt(_transport.getIntroKey());
                    } else {
                        _state = 21;
                        InboundEstablishState est = _establisher.getInboundState(packet.getRemoteHost());
                        if (est != null) {
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug("Packet from an existing peer IS for an inbound establishment");
                            _state = 22;
                            receivePacket(reader, packet, est, false);
                        } else {
                            if (_log.shouldLog(Log.WARN))
                                _log.warn("Validation with existing con failed, and validation as reestablish failed too.  DROP");
                            _context.statManager().addRateData("udp.droppedInvalidReestablish", packet.getLifetime(), packet.getExpiration());
                        }
                        return;
                    }
                } else {
                    _state = 23;
                    packet.decrypt(state.getNextCipherKey());
                }
            } else {
                _state = 24;
                packet.decrypt(state.getCurrentCipherKey());
            }

            _state = 25;
            handlePacket(reader, packet, state, null, null);
            _state = 26;
        }

        /**
         * New conn or failed validation
         * Decrypt and validate the packet then call handlePacket()
         *
         * @param peerType OUTBOUND_FALLBACK, INBOUND_FALLBACK, or NEW_PEER
         */
        private void receivePacket(UDPPacketReader reader, UDPPacket packet, short peerType) {
            _state = 27;
            boolean isValid = packet.validate(_transport.getIntroKey());
            if (!isValid) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Invalid introduction packet received: " + packet, new Exception("path"));
                _context.statManager().addRateData("udp.droppedInvalidEstablish", packet.getLifetime(), packet.getExpiration());
                switch (peerType) {
                    case INBOUND_FALLBACK:
                        _context.statManager().addRateData("udp.droppedInvalidEstablish.inbound", packet.getLifetime(), packet.getTimeSinceReceived());
                        break;
                    case OUTBOUND_FALLBACK:
                        _context.statManager().addRateData("udp.droppedInvalidEstablish.outbound", packet.getLifetime(), packet.getTimeSinceReceived());
                        break;
                    case NEW_PEER:
                        _context.statManager().addRateData("udp.droppedInvalidEstablish.new", packet.getLifetime(), packet.getTimeSinceReceived());
                        break;
                }
                _state = 28;
                return;
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Valid introduction packet received: " + packet);
            }

            _state = 29;
            packet.decrypt(_transport.getIntroKey());
            handlePacket(reader, packet, null, null, null);
            _state = 30;
        }

        /**
         * @param state non-null
         */
        private void receivePacket(UDPPacketReader reader, UDPPacket packet, InboundEstablishState state) {
            receivePacket(reader, packet, state, true);
        }

        /**
         * Inbound establishing conn
         * Decrypt and validate the packet then call handlePacket()
         *
         * @param state non-null
         * @param allowFallback if it isn't valid for this establishment state, try as a non-establishment packet
         */
        private void receivePacket(UDPPacketReader reader, UDPPacket packet, InboundEstablishState state, boolean allowFallback) {
            _state = 31;
            if (_log.shouldLog(Log.DEBUG)) {
                StringBuilder buf = new StringBuilder(128);
                buf.append("Attempting to receive a packet on a known inbound state: ");
                buf.append(state);
                buf.append(" MAC key: ").append(state.getMACKey());
                buf.append(" intro key: ").append(_transport.getIntroKey());
                _log.debug(buf.toString());
            }
            boolean isValid = false;
            if (state.getMACKey() != null) {
                isValid = packet.validate(state.getMACKey());
                if (isValid) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Valid introduction packet received for inbound con: " + packet);

                    _state = 32;
                    packet.decrypt(state.getCipherKey());
                    handlePacket(reader, packet, null, null, null);
                    return;
                } else {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Invalid introduction packet received for inbound con, falling back: " + packet);

                    _state = 33;
                }
            }
            if (allowFallback) { 
                // ok, we couldn't handle it with the established stuff, so fall back
                // on earlier state packets
                _state = 34;
                receivePacket(reader, packet, INBOUND_FALLBACK);
            } else {
                _context.statManager().addRateData("udp.droppedInvalidInboundEstablish", packet.getLifetime(), packet.getExpiration());
            }
        }

        /**
         * Outbound establishing conn
         * Decrypt and validate the packet then call handlePacket()
         *
         * @param state non-null
         */
        private void receivePacket(UDPPacketReader reader, UDPPacket packet, OutboundEstablishState state) {
            _state = 35;
            if (_log.shouldLog(Log.DEBUG)) {
                StringBuilder buf = new StringBuilder(128);
                buf.append("Attempting to receive a packet on a known outbound state: ");
                buf.append(state);
                buf.append(" MAC key: ").append(state.getMACKey());
                buf.append(" intro key: ").append(state.getIntroKey());
                _log.debug(buf.toString());
            }

            boolean isValid = false;
            if (state.getMACKey() != null) {
                _state = 36;
                isValid = packet.validate(state.getMACKey());
                if (isValid) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Valid introduction packet received for outbound established con: " + packet);

                    _state = 37;
                    packet.decrypt(state.getCipherKey());
                    handlePacket(reader, packet, null, state, null);
                    _state = 38;
                    return;
                }
            }

            // keys not yet exchanged, lets try it with the peer's intro key
            isValid = packet.validate(state.getIntroKey());
            if (isValid) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Valid introduction packet received for outbound established con with old intro key: " + packet);
                _state = 39;
                packet.decrypt(state.getIntroKey());
                handlePacket(reader, packet, null, state, null);
                _state = 40;
                return;
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Invalid introduction packet received for outbound established con with old intro key, falling back: " + packet);
            }

            // ok, we couldn't handle it with the established stuff, so fall back
            // on earlier state packets
            _state = 41;
            receivePacket(reader, packet, OUTBOUND_FALLBACK);
            _state = 42;
        }

        /**
         * Parse out the interesting bits and honor what it says
         *
         * @param state non-null if fully established
         * @param outState non-null if outbound establishing in process
         * @param inState unused always null
         */
        private void handlePacket(UDPPacketReader reader, UDPPacket packet, PeerState state, OutboundEstablishState outState, InboundEstablishState inState) {
            _state = 43;
            reader.initialize(packet);
            _state = 44;
            long recvOn = packet.getBegin();
            long sendOn = reader.readTimestamp() * 1000;
            long skew = recvOn - sendOn;

            // update skew whether or not we will be dropping the packet for excessive skew
            if (state != null) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Received packet from " + state.getRemoteHostId().toString() + " with skew " + skew);
                state.adjustClockSkew(skew);
            }
            _context.statManager().addRateData("udp.receivePacketSkew", skew, packet.getLifetime());

            if (!_context.clock().getUpdatedSuccessfully()) {
                // adjust the clock one time in desperation
                // this doesn't seem to work for big skews, we never get anything back,
                // so we have to wait for NTCP to do it
                _context.clock().setOffset(0 - skew, true);
                if (skew != 0)
                    _log.logAlways(Log.WARN, "NTP failure, UDP adjusting clock by " + DataHelper.formatDuration(Math.abs(skew)));
            }

            if (skew > GRACE_PERIOD) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Packet too far in the past: " + new Date(sendOn) + ": " + packet);
                _context.statManager().addRateData("udp.droppedInvalidSkew", skew, packet.getExpiration());
                return;
            } else if (skew < 0 - GRACE_PERIOD) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Packet too far in the future: " + new Date(sendOn) + ": " + packet);
                _context.statManager().addRateData("udp.droppedInvalidSkew", 0-skew, packet.getExpiration());
                return;
            }

            //InetAddress fromHost = packet.getPacket().getAddress();
            //int fromPort = packet.getPacket().getPort();
            //RemoteHostId from = new RemoteHostId(fromHost.getAddress(), fromPort);
            _state = 45;
            RemoteHostId from = packet.getRemoteHost();
            _state = 46;
            
            switch (reader.readPayloadType()) {
                case UDPPacket.PAYLOAD_TYPE_SESSION_REQUEST:
                    _state = 47;
                    _establisher.receiveSessionRequest(from, reader);
                    _context.statManager().addRateData("udp.receivePacketSize.sessionRequest", packet.getPacket().getLength(), packet.getLifetime());
                    break;
                case UDPPacket.PAYLOAD_TYPE_SESSION_CONFIRMED:
                    _state = 48;
                    _establisher.receiveSessionConfirmed(from, reader);
                    _context.statManager().addRateData("udp.receivePacketSize.sessionConfirmed", packet.getPacket().getLength(), packet.getLifetime());
                    break;
                case UDPPacket.PAYLOAD_TYPE_SESSION_CREATED:
                    _state = 49;
                    _establisher.receiveSessionCreated(from, reader);
                    _context.statManager().addRateData("udp.receivePacketSize.sessionCreated", packet.getPacket().getLength(), packet.getLifetime());
                    break;
                case UDPPacket.PAYLOAD_TYPE_DATA:
                    _state = 50;
                    if (outState != null)
                        state = _establisher.receiveData(outState);
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Received new DATA packet from " + state + ": " + packet);
                    if (state != null) {
                        UDPPacketReader.DataReader dr = reader.getDataReader();
                        if (_log.shouldLog(Log.INFO)) {
                            StringBuilder msg = new StringBuilder();
                            msg.append("Receive ").append(System.identityHashCode(packet));
                            msg.append(" from ").append(state.getRemotePeer().toBase64()).append(" ").append(state.getRemoteHostId());
                            for (int i = 0; i < dr.readFragmentCount(); i++) {
                                msg.append(" msg ").append(dr.readMessageId(i));
                                msg.append(":").append(dr.readMessageFragmentNum(i));
                                if (dr.readMessageIsLast(i))
                                    msg.append("*");
                            }
                            msg.append(": ").append(dr.toString());
                            _log.info(msg.toString());
                        }
                        packet.beforeReceiveFragments();
                        _inbound.receiveData(state, dr);
                        _context.statManager().addRateData("udp.receivePacketSize.dataKnown", packet.getPacket().getLength(), packet.getLifetime());
                        if (dr.readFragmentCount() <= 0)
                            _context.statManager().addRateData("udp.receivePacketSize.dataKnownAck", packet.getPacket().getLength(), packet.getLifetime());
                    } else {
                        _context.statManager().addRateData("udp.receivePacketSize.dataUnknown", packet.getPacket().getLength(), packet.getLifetime());
                        UDPPacketReader.DataReader dr = reader.getDataReader();
                        if (dr.readFragmentCount() <= 0)
                            _context.statManager().addRateData("udp.receivePacketSize.dataUnknownAck", packet.getPacket().getLength(), packet.getLifetime());
                    }
                    break;
                case UDPPacket.PAYLOAD_TYPE_TEST:
                    _state = 51;
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Received test packet: " + reader + " from " + from);
                    _testManager.receiveTest(from, reader);
                    _context.statManager().addRateData("udp.receivePacketSize.test", packet.getPacket().getLength(), packet.getLifetime());
                    break;
                case UDPPacket.PAYLOAD_TYPE_RELAY_REQUEST:
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Received relay request packet: " + reader + " from " + from);
                    _introManager.receiveRelayRequest(from, reader);
                    _context.statManager().addRateData("udp.receivePacketSize.relayRequest", packet.getPacket().getLength(), packet.getLifetime());
                    break;
                case UDPPacket.PAYLOAD_TYPE_RELAY_INTRO:
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Received relay intro packet: " + reader + " from " + from);
                    _introManager.receiveRelayIntro(from, reader);
                    _context.statManager().addRateData("udp.receivePacketSize.relayIntro", packet.getPacket().getLength(), packet.getLifetime());
                    break;
                case UDPPacket.PAYLOAD_TYPE_RELAY_RESPONSE:
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Received relay response packet: " + reader + " from " + from);
                    _establisher.receiveRelayResponse(from, reader);
                    _context.statManager().addRateData("udp.receivePacketSize.relayResponse", packet.getPacket().getLength(), packet.getLifetime());
                    break;
                case UDPPacket.PAYLOAD_TYPE_SESSION_DESTROY:
                    _state = 53;
                    if (outState != null)
                        _establisher.receiveSessionDestroy(from, outState);
                    else if (state != null)
                        _establisher.receiveSessionDestroy(from, state);
                    else
                        _establisher.receiveSessionDestroy(from);
                    break;
                default:
                    _state = 52;
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Unknown payload type: " + reader.readPayloadType());
                    _context.statManager().addRateData("udp.droppedInvalidUnknown", packet.getLifetime(), packet.getExpiration());
                    return;
            }
        }
    }
}
