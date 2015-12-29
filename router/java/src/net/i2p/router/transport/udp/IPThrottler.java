package net.i2p.router.transport.udp;

import net.i2p.util.ObjectCounter;
import net.i2p.util.SimpleTimer;
import net.i2p.util.SimpleTimer2;
import net.i2p.util.SipHash;

/**
 * Count IPs
 *
 * @since 0.9.3
 */
class IPThrottler {
    private ObjectCounter<Integer> _counter;
    private final int _max;

    public IPThrottler(int max, long time) {
        _max = max;
        _counter = new ObjectCounter<Integer>();
        SimpleTimer2.getInstance().addPeriodicEvent(new Cleaner(), time);
    }

    /**
     *  Increments before checking
     */
    public boolean shouldThrottle(byte[] ip) {
        // for IPv4 we simply use the IP;
        // for IPv6 we use a secure hash as an attacker could select the lower bytes
        Integer key;
        if (ip.length == 4)
            key = toInt(ip);
        else
            key = Integer.valueOf(SipHash.hashCode(ip));
        return _counter.increment(key) > _max;
    }

    private static Integer toInt(byte ip[]) {
        int rv = 0;
        for (int i = 0; i < 4; i++)
            rv |= (ip[i] & 0xff) << ((3-i)*8);
        return Integer.valueOf(rv);
    }

    private class Cleaner implements SimpleTimer.TimedEvent {
        public void timeReached() {
            _counter.clear();
        }
    }
}
