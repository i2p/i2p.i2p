package net.i2p.router.transport.udp;

import java.util.concurrent.atomic.AtomicLong;

import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

import static net.i2p.router.transport.TransportUtil.IPv6Config.*;
import static net.i2p.router.transport.udp.PeerTestState.Role.*;

/**
 *  Initiate a test (we are Alice)
 *
 *  @since 0.9.30 moved out of UDPTransport
 */
class PeerTestEvent extends SimpleTimer2.TimedEvent {
    private final RouterContext _context;
    private final Log _log;
    private final UDPTransport  _transport;
    private final PeerTestManager  _testManager;

    private boolean _alive;
    /** when did we last test our reachability */
    private final AtomicLong _lastTested = new AtomicLong();
    private final AtomicLong _lastTestedV6 = new AtomicLong();
    private static final int NO_FORCE = 0, FORCE_IPV4 = 1, FORCE_IPV6 = 2;
    private int _forceRun;
    private boolean _lastTestIPv6 = true;

    private static final int TEST_FREQUENCY = 13*60*1000;
    // must be greater than PeerTestManager.MAX_TEST_TIME
    private static final int MIN_TEST_FREQUENCY = 45*1000;

    PeerTestEvent(RouterContext ctx, UDPTransport udp, PeerTestManager ptmgr) {
        super(ctx.simpleTimer2());
        _context = ctx;
        _log = ctx.logManager().getLog(PeerTestEvent.class);
        _transport = udp;
        _testManager = ptmgr;
    }

    public synchronized void timeReached() {
        if (shouldTest()) {
            long now = _context.clock().now();
            long sinceRunV4 = now - _lastTested.get();
            long sinceRunV6 = now - _lastTestedV6.get();
            boolean configV4fw = _transport.isIPv4Firewalled();
            boolean configV6fw = _transport.isIPv6Firewalled();
            boolean preferV4 = _lastTestIPv6;
            if (!configV4fw && (_forceRun & FORCE_IPV4) != 0 && sinceRunV4 >= MIN_TEST_FREQUENCY) {
                locked_runTest(false);
            } else if (!configV6fw && (_forceRun & FORCE_IPV6) != 0 && _transport.hasIPv6Address() && sinceRunV6 >= MIN_TEST_FREQUENCY) {
                locked_runTest(true);
            } else if (preferV4 && !configV4fw && sinceRunV4 >= TEST_FREQUENCY && _transport.getIPv6Config() != IPV6_ONLY) {
                locked_runTest(false);
            } else if (!configV6fw && _transport.hasIPv6Address() && sinceRunV6 >= TEST_FREQUENCY) {
                locked_runTest(true);
            } else if (!preferV4 && !configV4fw && sinceRunV4 >= TEST_FREQUENCY && _transport.getIPv6Config() != IPV6_ONLY) {
                locked_runTest(false);
            } else {
                if (_log.shouldDebug())
                    _log.debug("Test timer, no test run, last v4 test: " + new java.util.Date(_lastTested.get()) +
                              " last v6 test: " + new java.util.Date(_lastTestedV6.get()));
            }
        }
        if (_alive) {
            long delay;
            if (_forceRun != NO_FORCE) {
                // we still have the other once v4/v6 to test
                delay = MIN_TEST_FREQUENCY;
            } else {
                delay = (TEST_FREQUENCY * 3 / 4) + _context.random().nextInt(TEST_FREQUENCY / 4);
                // if we have 2 addresses, give IPv6 a chance also
                if (_transport.hasIPv6Address() && _transport.getIPv6Config() != IPV6_ONLY)
                    delay /= 2;
            }
            if (_log.shouldDebug())
                _log.debug("Test force? " + _forceRun + " reschedule for " + net.i2p.data.DataHelper.formatDuration(delay), new Exception());
            schedule(delay);
        }
    }

    /**
     *  Just to consolidate the logging
     *  @since 0.9.57
     */
    @Override
    public void reschedule(long delay) {
        if (_log.shouldDebug())
            _log.debug("Test force? " + _forceRun + " reschedule for " + net.i2p.data.DataHelper.formatDuration(delay), new Exception());
        super.reschedule(delay);
    }

    private void locked_runTest(boolean isIPv6) {
        _lastTestIPv6 = isIPv6;
        PeerState bob = _transport.pickTestPeer(BOB, 0, isIPv6, null);
        if (bob != null) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Running periodic test with bob = " + bob);
            boolean started = _testManager.runTest(bob);
            if (started)
                setLastTested(isIPv6);
        } else {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Unable to run peer test, no peers available - v6? " + isIPv6);
        }
        // We switch to NO_FORCE even if no peers,
        // so we don't get stuck running the same test over and over
        _forceRun &= ~(isIPv6 ? FORCE_IPV6 : FORCE_IPV4);
    }

    /**
     *  Run within the next 45 seconds at the latest
     *  @since 0.9.13
     */
    public synchronized void forceRunSoon(boolean isIPv6) {
        forceRunSoon(isIPv6, MIN_TEST_FREQUENCY);
    }

    /**
     *  Run within the specified time at the latest
     *  @since 0.9.39
     */
    public synchronized void forceRunSoon(boolean isIPv6, long delay) {
        if (!isIPv6 && _transport.isIPv4Firewalled())
            return;
        if (isIPv6 && _transport.isIPv6Firewalled())
            return;
        _forceRun |= isIPv6 ? FORCE_IPV6 : FORCE_IPV4;
        reschedule(delay);
    }

    /**
     *
     *  Run within the next 5 seconds at the latest
     *  @since 0.9.13
     */
    public synchronized void forceRunImmediately(boolean isIPv6) {
        forceRunSoon(isIPv6, 5*1000);
    }

    /**
     *  Caller MUST also call schedule(), reschedule(),
     *  forceRunSoon(), or forceRunImmediately()
     */
    public synchronized void setIsAlive(boolean isAlive) {
        _alive = isAlive;
        if (!isAlive)
            cancel();
    }

    /**
     *  Set the last-tested timer to now
     *  @since 0.9.13
     */
    public void setLastTested(boolean isIPv6) {
        // do not synchronize - deadlock with PeerTestManager
        long now = _context.clock().now();
        if (isIPv6)
            _lastTestedV6.set(now);
        else
            _lastTested.set(now);
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("PTE.setLastTested() - v6? " + isIPv6, new Exception());
    }

    private boolean shouldTest() {
        return ! (_context.router().isHidden() ||
                  _context.router().gracefulShutdownInProgress() ||
                  (_transport.isIPv4Firewalled() && _transport.isIPv6Firewalled()));
        //String val = _context.getProperty(PROP_SHOULD_TEST);
        //return ( (val != null) && ("true".equals(val)) );
    }
}
