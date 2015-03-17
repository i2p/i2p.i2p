package net.i2p.router.message;

import java.util.HashSet;
import java.util.Set;

import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.Payload;
import net.i2p.data.TunnelId;
import net.i2p.router.ClientMessage;
import net.i2p.router.RouterContext;

/**
 * Good ol' fashioned struct with the send status
 *
 */
class OutboundClientMessageStatus {
    private RouterContext _context;
    private ClientMessage _msg;
    private PayloadGarlicConfig _clove;
    private LeaseSet _leaseSet;
    private final Set _sent;
    private int _numLookups;
    private boolean _success;
    private boolean _failure;
    private long _start;
    private int _previousSent;

    public OutboundClientMessageStatus(RouterContext ctx, ClientMessage msg) {
        _context = ctx;
        _msg = msg;
        _clove = null;
        _leaseSet = null;
        _sent = new HashSet(4);
        _success = false;
        _failure = false;
        _numLookups = 0;
        _previousSent = 0;
        _start = ctx.clock().now();
    }

    /** raw payload */
    public Payload getPayload() { return _msg.getPayload(); }
    /** clove, if we've built it */
    public PayloadGarlicConfig getClove() { return _clove; }
    public void setClove(PayloadGarlicConfig clove) { _clove = clove; }
    public ClientMessage getMessage() { return _msg; }
    /** date we started the process on */
    public long getStart() { return _start; }

    public int getNumLookups() { return _numLookups; }
    public void incrementLookups() { _numLookups++; }
    public void clearAlreadySent() {
        synchronized (_sent) {
            _previousSent += _sent.size();
            _sent.clear();
        }
    }

    /** who sent the message? */
    public Destination getFrom() { return _msg.getFromDestination(); }
    /** who is the message going to? */
    public Destination getTo() { return _msg.getDestination(); }
    /** what is the target's current leaseSet (or null if we don't know yet) */
    public LeaseSet getLeaseSet() { return _leaseSet; }
    public void setLeaseSet(LeaseSet ls) { _leaseSet = ls; }
    /** have we already sent the message down this tunnel? */
    public boolean alreadySent(Hash gateway, TunnelId tunnelId) {
        Tunnel t = new Tunnel(gateway, tunnelId);
        synchronized (_sent) {
            return _sent.contains(t);
        }
    }
    public void sent(Hash gateway, TunnelId tunnelId) {
        Tunnel t = new Tunnel(gateway, tunnelId);
        synchronized (_sent) {
            _sent.add(t);
        }
    }
    /** how many messages have we sent through various leases? */
    public int getNumSent() {
        synchronized (_sent) {
            return _sent.size() + _previousSent;
        }
    }
    /** did we totally fail? */
    public boolean getFailure() { return _failure; }
    /** we failed.  returns true if we had already failed before */
    public boolean failed() {
        boolean already = _failure;
        _failure = true;
        return already;
    }
    /** have we totally succeeded? */
    public boolean getSuccess() { return _success; }
    /** we succeeded.  returns true if we had already succeeded before */
    public boolean success() {
        boolean already = _success;
        _success = true;
        return already;
    }

    /** represent a unique tunnel at any given time */
    private class Tunnel {
        private Hash _gateway;
        private TunnelId _tunnel;

        public Tunnel(Hash tunnelGateway, TunnelId tunnel) {
            _gateway = tunnelGateway;
            _tunnel = tunnel;
        }

        public Hash getGateway() { return _gateway; }
        public TunnelId getTunnel() { return _tunnel; }

        @Override
        public int hashCode() {
            int rv = 0;
            if (_gateway != null)
                rv += _gateway.hashCode();
            if (_tunnel != null)
                rv += 7*_tunnel.getTunnelId();
            return rv;
        }

        @Override
        public boolean equals(Object o) {
            if (o == null) return false;
            if (o.getClass() != Tunnel.class) return false;
            Tunnel t = (Tunnel)o;
            return (getTunnel() == t.getTunnel()) &&
            getGateway().equals(t.getGateway());
        }
    }
}
