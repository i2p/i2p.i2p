package net.i2p.router.transport.udp;

import net.i2p.util.ObjectCounter;
import net.i2p.util.SimpleScheduler;
import net.i2p.util.SimpleTimer;

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
        _counter = new ObjectCounter();
        SimpleScheduler.getInstance().addPeriodicEvent(new Cleaner(), time);
    }

    /**
     *  Increments before checking
     *  @return true if ip.length != 4
     */
    public boolean shouldThrottle(byte[] ip) {
        if (ip.length != 4)
            return true;
        return _counter.increment(toInt(ip)) > _max;
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
