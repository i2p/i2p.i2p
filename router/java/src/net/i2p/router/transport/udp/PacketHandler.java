package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.util.Date;

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
public class PacketHandler implements Runnable {
    private RouterContext _context;
    private Log _log;
    private UDPTransport _transport;
    private UDPEndpoint _endpoint;
    private UDPPacketReader _reader;
    private EstablishmentManager _establisher;
    private InboundMessageFragments _inbound;
    private boolean _keepReading;
    
    private static final int NUM_HANDLERS = 3;
    
    public PacketHandler(RouterContext ctx, UDPTransport transport, UDPEndpoint endpoint, EstablishmentManager establisher, InboundMessageFragments inbound) {
        _context = ctx;
        _log = ctx.logManager().getLog(PacketHandler.class);
        _transport = transport;
        _endpoint = endpoint;
        _establisher = establisher;
        _inbound = inbound;
        _reader = new UDPPacketReader(ctx);
        _context.statManager().createRateStat("udp.handleTime", "How long it takes to handle a received packet after its been pulled off the queue", "udp", new long[] { 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("udp.queueTime", "How long after a packet is received can we begin handling it", "udp", new long[] { 10*60*1000, 60*60*1000 });
        _context.statManager().createRateStat("udp.receivePacketSkew", "How long ago after the packet was sent did we receive it", "udp", new long[] { 10*60*1000, 60*60*1000 });
    }
    
    public void startup() { 
        _keepReading = true;
        for (int i = 0; i < NUM_HANDLERS; i++) {
            I2PThread t = new I2PThread(this, "Packet handler " + i + ": " + _endpoint.getListenPort());
            t.setDaemon(true);
            t.start();
        }
    }
    
    public void shutdown() { 
        _keepReading = false; 
    }
    
    public void run() {
        while (_keepReading) {
            UDPPacket packet = _endpoint.receive();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Received the packet " + packet);
            long queueTime = packet.getLifetime();
            long handleStart = _context.clock().now();
            handlePacket(packet);
            long handleTime = _context.clock().now() - handleStart;
            _context.statManager().addRateData("udp.handleTime", handleTime, packet.getLifetime());
            _context.statManager().addRateData("udp.queueTime", queueTime, packet.getLifetime());
            
            if (handleTime > 1000) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Took " + handleTime + " to process the packet " 
                              + packet + ": " + _reader);
            }
            
            // back to the cache with thee!
            packet.release();
        }
    }
    
    private void handlePacket(UDPPacket packet) {
        if (packet == null) return;
        
        InetAddress remAddr = packet.getPacket().getAddress();
        int remPort = packet.getPacket().getPort();
        PeerState state = _transport.getPeerState(remAddr, remPort);
        if (state == null) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Packet received is not for a connected peer");
            InboundEstablishState est = _establisher.getInboundState(remAddr, remPort);
            if (est != null) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Packet received IS for an inbound establishment");
                receivePacket(packet, est);
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Packet received is not for an inbound establishment");
                OutboundEstablishState oest = _establisher.getOutboundState(remAddr, remPort);
                if (oest != null) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Packet received IS for an outbound establishment");
                    receivePacket(packet, oest);
                } else {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Packet received is not for an inbound or outbound establishment");
                    // ok, not already known establishment, try as a new one
                    receivePacket(packet);
                }
            }
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Packet received IS for an existing peer");
            receivePacket(packet, state);
        }
    }
    
    private void receivePacket(UDPPacket packet, PeerState state) {
        boolean isValid = packet.validate(state.getCurrentMACKey());
        if (!isValid) {
            if (state.getNextMACKey() != null)
                isValid = packet.validate(state.getNextMACKey());
            if (!isValid) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Failed validation with existing con, trying as new con: " + packet);
                
                isValid = packet.validate(_transport.getIntroKey());
                if (isValid) {
                    // this is a stray packet from an inbound establishment
                    // process, so try our intro key
                    // (after an outbound establishment process, there wouldn't
                    //  be any stray packets)
                    if (_log.shouldLog(Log.INFO))
                        _log.info("Validation with existing con failed, but validation as reestablish/stray passed");
                    packet.decrypt(_transport.getIntroKey());
                } else {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Validation with existing con failed, and validation as reestablish failed too.  DROP");
                    return;
                }
            } else {
                packet.decrypt(state.getNextCipherKey());
            }
        } else {
            packet.decrypt(state.getCurrentCipherKey());
        }
        
        handlePacket(packet, state, null, null);
    }
    
    private void receivePacket(UDPPacket packet) {
        boolean isValid = packet.validate(_transport.getIntroKey());
        if (!isValid) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Invalid introduction packet received: " + packet, new Exception("path"));
            return;
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info("Valid introduction packet received: " + packet);
        }
        
        packet.decrypt(_transport.getIntroKey());
        handlePacket(packet, null, null, null);
    }

    private void receivePacket(UDPPacket packet, InboundEstablishState state) {
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

                packet.decrypt(state.getCipherKey());
                handlePacket(packet, null, null, null);
                return;
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Invalid introduction packet received for inbound con, falling back: " + packet);

            }
        }
        // ok, we couldn't handle it with the established stuff, so fall back
        // on earlier state packets
        receivePacket(packet);
    }

    private void receivePacket(UDPPacket packet, OutboundEstablishState state) {
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
            isValid = packet.validate(state.getMACKey());
            if (isValid) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Valid introduction packet received for outbound established con: " + packet);
                
                packet.decrypt(state.getCipherKey());
                handlePacket(packet, null, state, null);
                return;
            }
        }
        
        // keys not yet exchanged, lets try it with the peer's intro key
        isValid = packet.validate(state.getIntroKey());
        if (isValid) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Valid introduction packet received for outbound established con with old intro key: " + packet);
            packet.decrypt(state.getIntroKey());
            handlePacket(packet, null, state, null);
            return;
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Invalid introduction packet received for outbound established con with old intro key, falling back: " + packet);
        }
        
        // ok, we couldn't handle it with the established stuff, so fall back
        // on earlier state packets
        receivePacket(packet);
    }

    /** let packets be up to 30s slow */
    private static final long GRACE_PERIOD = Router.CLOCK_FUDGE_FACTOR + 30*1000;
    
    /**
     * Parse out the interesting bits and honor what it says
     */
    private void handlePacket(UDPPacket packet, PeerState state, OutboundEstablishState outState, InboundEstablishState inState) {
        _reader.initialize(packet);
        long now = _context.clock().now();
        long when = _reader.readTimestamp() * 1000;
        long skew = now - when;
        if (skew > GRACE_PERIOD) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Packet too far in the future: " + new Date(when) + ": " + packet);
            return;
        } else if (skew < 0 - GRACE_PERIOD) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Packet too far in the past: " + new Date(when) + ": " + packet);
            return;
        }
        
        _context.statManager().addRateData("udp.receivePacketSkew", skew, packet.getLifetime());
        
        InetAddress fromHost = packet.getPacket().getAddress();
        int fromPort = packet.getPacket().getPort();
        String from = PeerState.calculateRemoteHostString(fromHost.getAddress(), fromPort);
        
        switch (_reader.readPayloadType()) {
            case UDPPacket.PAYLOAD_TYPE_SESSION_REQUEST:
                _establisher.receiveSessionRequest(from, fromHost, fromPort, _reader);
                break;
            case UDPPacket.PAYLOAD_TYPE_SESSION_CONFIRMED:
                _establisher.receiveSessionConfirmed(from, _reader);
                break;
            case UDPPacket.PAYLOAD_TYPE_SESSION_CREATED:
                _establisher.receiveSessionCreated(from, _reader);
                break;
            case UDPPacket.PAYLOAD_TYPE_DATA:
                if (outState != null)
                    state = _establisher.receiveData(outState);
                handleData(packet, state);
                break;
            default:
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Unknown payload type: " + _reader.readPayloadType());
                return;
        }
    }
    
    private void handleData(UDPPacket packet, PeerState peer) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Received new DATA packet from " + peer + ": " + packet);
        _inbound.receiveData(peer, _reader.getDataReader());
    }
}
