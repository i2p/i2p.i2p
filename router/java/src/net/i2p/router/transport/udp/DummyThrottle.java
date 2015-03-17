package net.i2p.router.transport.udp;

import net.i2p.data.Hash;

/**
 * Since the TimedWeightedPriorityMessageQueue.add()
 * was disabled by jrandom in UDPTransport.java
 * on 2006-02-19, and the choke/unchoke was disabled at the same time,
 * all of TWPMQ is pointless, so just do this for now.
 *
 * It appears from his comments that it was a lock contention issue,
 * so perhaps TWPMQ can be converted to concurrent and re-enabled.
 *
 * @since 0.7.12
 */
class DummyThrottle implements OutboundMessageFragments.ActiveThrottle {

    public DummyThrottle() {
    }
    
    public void choke(Hash peer) {
    }

    public void unchoke(Hash peer) {
    }

    public boolean isChoked(Hash peer) {
        return false;
    }
}
    
