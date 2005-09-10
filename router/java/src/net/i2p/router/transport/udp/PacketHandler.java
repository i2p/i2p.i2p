package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import net.i2p.data.Base64;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
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
public class PacketHandler {
    private RouterContext _context;
    private Log _log;
    private UDPTransport _transport;
    private UDPEndpoint _endpoint;
    private EstablishmentManager _establisher;
    private InboundMessageFragments _inbound;
    private PeerTestManager _testManager;
    private IntroductionManager _introManager;
    private boolean _keepReading;
    private List _handlers;
    
    private static final int NUM_HANDLERS = 3;
    /** let packets be up to 30s slow */
    private static final long GRACE_PERIOD = Router.CLOCK_FUDGE_FACTOR + 30*1000;
    
    
    public PacketHandler(RouterContext ctx, UDPTransport transport, UDPEndpoint endpoint, EstablishmentManager establisher, InboundMessageFragments inbound, PeerTestManager testManager, IntroductionManager introManager) {
        _context = ctx;
        _log = ctx.logManager().getLog(PacketHandler.class);
        _transport = transport;
        _endpoint = endpoint;
        _establisher = establisher;
        _inbound = inbound;
        _testManager = testManager;
        _introManager = introManager;
        _handlers = new ArrayList(NUM_HANDLERS);
        for (int i = 0; i < NUM_HANDLERS; i++) {
            _handlers.add(new Handler());
        }
        _context.statManager().createRateStat("udp.handleTime", "How long it takes to handle a received packet after its been pulled off the queue", "udp", new long[] { 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("udp.queueTime", "How long after a packet is received can we begin handling it", "udp", new long[] { 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("udp.receivePacketSkew", "How long ago after the packet was sent did we receive it", "udp", new long[] { 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("udp.droppedInvalidUnkown", "How old the packet we dropped due to invalidity (unkown type) was", "udp", new long[] { 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("udp.droppedInvalidReestablish", "How old the packet we dropped due to invalidity (doesn't use existing key, not an establishment) was", "udp", new long[] { 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("udp.droppedInvalidEstablish", "How old the packet we dropped due to invalidity (establishment, bad key) was", "udp", new long[] { 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("udp.droppedInvalidInboundEstablish", "How old the packet we dropped due to invalidity (inbound establishment, bad key) was", "udp", new long[] { 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("udp.droppedInvalidSkew", "How skewed the packet we dropped due to invalidity (valid except bad skew) was", "udp", new long[] { 10*60*1000, 60*60*1000 });
    }
    
    public void startup() { 
        _keepReading = true;
        for (int i = 0; i < _handlers.size(); i++) {
            I2PThread t = new I2PThread((Handler)_handlers.get(i), "Packet handler " + i + ": " + _endpoint.getListenPort());
            t.setDaemon(true);
            t.start();
        }
    }
    
    public void shutdown() { 
        _keepReading = false; 
    }

    String getHandlerStatus() {
        StringBuffer rv = new StringBuffer();
        int size = _handlers.size();
        rv.append("Handlers: ").append(size);
        for (int i = 0; i < size; i++) {
            Handler handler = (Handler)_handlers.get(i);
            rv.append(" handler ").append(i).append(" state: ").append(handler._state);
        }
        return rv.toString();
    }
    
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
                if (packet == null) continue; // keepReading is probably false...
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Received the packet " + packet);
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
                _context.statManager().addRateData("udp.handleTime", handleTime, packet.getLifetime());
                _context.statManager().addRateData("udp.queueTime", queueTime, packet.getLifetime());
                _state = 8;

                if (handleTime > 1000) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Took " + handleTime + " to process the packet " 
                                  + packet + ": " + _reader);
                }

                // back to the cache with thee!
                packet.release();
                _state = 9;
            }
        }
    //}

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
                        receivePacket(reader, packet);
                    }
                }
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Packet received IS for an existing peer");
                _state = 16;
                receivePacket(reader, packet, state);
            }
        }

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

        private void receivePacket(UDPPacketReader reader, UDPPacket packet) {
            _state = 27;
            boolean isValid = packet.validate(_transport.getIntroKey());
            if (!isValid) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Invalid introduction packet received: " + packet, new Exception("path"));
                _context.statManager().addRateData("udp.droppedInvalidEstablish", packet.getLifetime(), packet.getExpiration());
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

        private void receivePacket(UDPPacketReader reader, UDPPacket packet, InboundEstablishState state) {
            receivePacket(reader, packet, state, true);
        }
        /**
         * @param allowFallback if it isn't valid for this establishment state, try as a non-establishment packet
         */
        private void receivePacket(UDPPacketReader reader, UDPPacket packet, InboundEstablishState state, boolean allowFallback) {
            _state = 31;
            if ( (state != null) && (_log.shouldLog(Log.DEBUG)) ) {
                StringBuffer buf = new StringBuffer(128);
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
                receivePacket(reader, packet);
            } else {
                _context.statManager().addRateData("udp.droppedInvalidInboundEstablish", packet.getLifetime(), packet.getExpiration());
            }
        }

        private void receivePacket(UDPPacketReader reader, UDPPacket packet, OutboundEstablishState state) {
            _state = 35;
            if ( (state != null) && (_log.shouldLog(Log.DEBUG)) ) {
                StringBuffer buf = new StringBuffer(128);
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
            receivePacket(reader, packet);
            _state = 42;
        }

        /**
         * Parse out the interesting bits and honor what it says
         */
        private void handlePacket(UDPPacketReader reader, UDPPacket packet, PeerState state, OutboundEstablishState outState, InboundEstablishState inState) {
            _state = 43;
            reader.initialize(packet);
            _state = 44;
            long recvOn = packet.getBegin();
            long sendOn = reader.readTimestamp() * 1000;
            long skew = recvOn - sendOn;
            if (skew > GRACE_PERIOD) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Packet too far in the future: " + new Date(sendOn/1000) + ": " + packet);
                _context.statManager().addRateData("udp.droppedInvalidSkew", skew, packet.getExpiration());
                return;
            } else if (skew < 0 - GRACE_PERIOD) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Packet too far in the past: " + new Date(sendOn/1000) + ": " + packet);
                _context.statManager().addRateData("udp.droppedInvalidSkew", 0-skew, packet.getExpiration());
                return;
            }

            if (state != null) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Received packet from " + state.getRemoteHostId().toString() + " with skew " + skew);
                state.adjustClockSkew((short)skew);
            }

            _context.statManager().addRateData("udp.receivePacketSkew", skew, packet.getLifetime());

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
                    break;
                case UDPPacket.PAYLOAD_TYPE_SESSION_CONFIRMED:
                    _state = 48;
                    _establisher.receiveSessionConfirmed(from, reader);
                    break;
                case UDPPacket.PAYLOAD_TYPE_SESSION_CREATED:
                    _state = 49;
                    _establisher.receiveSessionCreated(from, reader);
                    break;
                case UDPPacket.PAYLOAD_TYPE_DATA:
                    _state = 50;
                    if (outState != null)
                        state = _establisher.receiveData(outState);
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Received new DATA packet from " + state + ": " + packet);
                    _inbound.receiveData(state, reader.getDataReader());
                    break;
                case UDPPacket.PAYLOAD_TYPE_TEST:
                    _state = 51;
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Received test packet: " + reader + " from " + from);
                    _testManager.receiveTest(from, reader);
                    break;
                case UDPPacket.PAYLOAD_TYPE_RELAY_REQUEST:
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Received relay request packet: " + reader + " from " + from);
                    _introManager.receiveRelayRequest(from, reader);
                    break;
                case UDPPacket.PAYLOAD_TYPE_RELAY_INTRO:
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Received relay intro packet: " + reader + " from " + from);
                    _introManager.receiveRelayIntro(from, reader);
                    break;
                case UDPPacket.PAYLOAD_TYPE_RELAY_RESPONSE:
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Received relay response packet: " + reader + " from " + from);
                    _establisher.receiveRelayResponse(from, reader);
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
