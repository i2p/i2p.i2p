package net.i2p.router.transport;

import net.i2p.router.RouterContext;
import net.i2p.util.Log;

import java.io.IOException;

import java.util.Random;
import java.util.Properties;

/**
 * Stress out the bandwidth limiter by running a series of push and pull tests
 * through bandwidth limited streams.  This includes pushing data through 
 * unthrottled streams, through streams throttled at 4KBps, 32KBps, and 256KBps, 
 * pulling data through those same rates, as well as doing so with 10 concurrent
 * threads (and, in turn, 10 concurrent streams all using the same BandwidthLimiter).
 *
 * Note: this takes a long time to run (~1 hour) since the 4KBps push/pull of 1MB with
 * 10 concurrent threads is, well, slow.
 *
 */
public class BandwidthLimiterTest {
    private RouterContext _context;
    private Log _log;
    private final static int NUM_MB = 1;
    
    public BandwidthLimiterTest() {
        _context = new RouterContext(null);
        _log = _context.logManager().getLog(BandwidthLimiterTest.class);
        //_context.jobQueue().runQueue(1);
    }
    
    public void prepareLimiter(int inKBps, int outKBps, int inBurst, int outBurst) {
        Properties props = new Properties();
        props.setProperty(FIFOBandwidthRefiller.PROP_INBOUND_BANDWIDTH, ""+inKBps);
        props.setProperty(FIFOBandwidthRefiller.PROP_OUTBOUND_BANDWIDTH, ""+outKBps);
        props.setProperty(FIFOBandwidthRefiller.PROP_INBOUND_BANDWIDTH_PEAK, ""+inBurst);
        props.setProperty(FIFOBandwidthRefiller.PROP_OUTBOUND_BANDWIDTH_PEAK, ""+outBurst);
        //props.setProperty(TrivialBandwidthLimiter.PROP_REPLENISH_FREQUENCY, ""+10*1000);
        System.setProperties(props);
        _context.bandwidthLimiter().reinitialize();
        _log.debug("Limiter prepared");
    }
    
    /**
     * Using the configured limiter, determine how long it takes to shove 
     * numBytes through a BandwidthLimitedOutputStream (broken up into numBytesPerWrite)
     * chunks.
     *
     */
    public long testOutboundThrottle(int numBytes, int numBytesPerWrite) {
        byte source[] = new byte[numBytesPerWrite];
        new Random().nextBytes(source);
        NullOutputStream target = new NullOutputStream();
        BandwidthLimitedOutputStream out = new BandwidthLimitedOutputStream(_context, target, null);
        long before = System.currentTimeMillis();
        try {
            for (int i = 0; i < numBytes; i += numBytesPerWrite) {
                int num = numBytesPerWrite;
                if (numBytesPerWrite + i >= numBytes)
                    num = numBytes - i;
                //_log.info("** Writing " + num + " bytes starting at " + i);
                out.write(source, 0, num);
            }
        } catch (IOException ioe) {}
        long after = System.currentTimeMillis();
        return after-before;
    }
    
    /**
     * Using the configured limiter, determine how long it takes to read 
     * numBytes through a BandwidthLimitedInputStream (broken up into numBytesPerRead)
     * chunks.
     *
     */
    public long testInboundThrottle(int numBytes, int numBytesPerRead) {
        FakeInputStream source = new FakeInputStream(numBytes);
        BandwidthLimitedInputStream in = new BandwidthLimitedInputStream(_context, source, null);
        long before = System.currentTimeMillis();
        try {
            byte buf[] = new byte[numBytesPerRead];
            int read = 0;
            while ( (read = in.read(buf)) != -1) {
                //_log.info("** Read " + read + " bytes");
                // gobble the data.  who cares
            }
        } catch (IOException ioe) {}
        long after = System.currentTimeMillis();
        return after-before;
    }
    
    /**
     * Run a series of tests on outbound throttling (shoving lots of data through pipes 
     * with various limits) and log the times.
     *
     */
    public void testOutbound() {
        prepareLimiter(-1, -1, -1, -1);
        _log.info("Begin unlimited push of " + NUM_MB);
        long ms = testOutboundThrottle(NUM_MB*1024*1024, 32*1024);
        _log.info("** Unlimited pushed " + NUM_MB + "MB in " + ms + "ms");
        prepareLimiter(-1, 4, -1, 5*1024*1024);
        ms = testOutboundThrottle(NUM_MB*1024*1024, 4*1024);
        _log.info("** 4KBps pushed " + NUM_MB + "MB in " + ms + "ms");
        prepareLimiter(-1, 32, -1, 5*1024*1024);
        ms = testOutboundThrottle(NUM_MB*1024*1024, 32*1024);
        _log.info("** 32KBps pushed " + NUM_MB + "MB in " + ms + "ms");
        prepareLimiter(-1, 256, -1, 5*1024*1024);
        _log.info("Begin 256KBps push of " + NUM_MB);
        ms = testOutboundThrottle(NUM_MB*1024*1024, 256*1024);
        _log.info("** 256KBps pushed " + NUM_MB + "MB in " + ms + "ms");
    }
    
    /**
     * Run a series of tests on inbound throttling (pulling lots of data through pipes 
     * with various limits) and log the times.
     *
     */
    public void testInbound() {
        prepareLimiter(-1, -1, -1, -1);
        long ms = testInboundThrottle(NUM_MB*1024*1024, 32*1024);
        _log.info("** Unlimited pulled " + NUM_MB + "MB in " + ms + "ms");
        prepareLimiter(4, -1, 5*1024*1024, -1);
        ms = testInboundThrottle(NUM_MB*1024*1024, 32*1024);
        _log.info("** 4KBps pulled " + NUM_MB + "MB in " + ms + "ms");
        prepareLimiter(32, -1, 5*1024*1024, -1);
        ms = testInboundThrottle(NUM_MB*1024*1024, 32*1024);
        _log.info("** 32KBps pulled " + NUM_MB + "MB in " + ms + "ms");
        prepareLimiter(256, -1, 5*1024*1024, -1);
        ms = testInboundThrottle(NUM_MB*1024*1024, 256*1024);
        _log.info("** 256KBps pulled " + NUM_MB + "MB in " + ms + "ms");
    }
    
    
    public void testOutboundContention() {
        prepareLimiter(-1, -1, -1, -1);
        long start = System.currentTimeMillis();
        long runningTimes[] = testOutboundContention(10, NUM_MB*1024*1024);
        long end = System.currentTimeMillis();
        _log.info("** Done with unlimited " + NUM_MB + "MB test with 10 concurrent threads after " + (end-start) + "ms: " + displayTimes(runningTimes));
        //prepareLimiter(-1, 4, -1, 5*1024*1024);
        //start = System.currentTimeMillis();
        //runningTimes = testOutboundContention(10, NUM_MB*1024*1024);
        //end = System.currentTimeMillis();
        //_log.info("** Done with 4KBps " + NUM_MB + "MB test with 10 concurrent threads after " + (end-start) + "ms: " + displayTimes(runningTimes));
        prepareLimiter(-1, 32, -1, 5*1024*1024);
        start = System.currentTimeMillis();
        runningTimes = testOutboundContention(10, NUM_MB*1024*1024);
        end = System.currentTimeMillis();
        _log.info("** Done with 32KBps " + NUM_MB + "MB test with 10 concurrent threads after " + (end-start) + "ms: " + displayTimes(runningTimes));
        prepareLimiter(-1, 256, -1, 5*1024*1024);
        start = System.currentTimeMillis();
        runningTimes = testOutboundContention(10, NUM_MB*1024*1024);
        end = System.currentTimeMillis();
        _log.info("** Done with 256KBps " + NUM_MB + "MB test with 10 concurrent threads after " + (end-start) + "ms: " + displayTimes(runningTimes));
    }
    
    private String displayTimes(long times[]) {
        StringBuffer rv = new StringBuffer();
        for (int i = 0; i < times.length; i++) {
            rv.append(times[i]);
            if (i + 1 <= times.length)
                rv.append(' ');
        }
        return rv.toString();
    }
    
    private long[] testOutboundContention(int numConcurrent, int numBytes) {
        OutboundRunner threads[] = new OutboundRunner[numConcurrent];
        for (int i = 0; i < numConcurrent; i++) {
            threads[i] = new OutboundRunner(numBytes);
        }
        _log.debug("Starting up outbound contention test for " + numBytes + " with " + numConcurrent + " runners");
        for (int i = 0; i < numConcurrent; i++)
            threads[i].start();
        for (int i = 0; i < numConcurrent; i++) {
            try {
                threads[i].join();
            } catch (InterruptedException ie) {}
        }
        long rv[] = new long[numConcurrent];
        for (int i = 0; i < numConcurrent; i++)
            rv[i] = threads[i].getRunningTime();
        return rv;
    }

    private static int __runnerNum = 0;
    private class OutboundRunner extends Thread {
        private int _numBytes;
        private int _runnerNum;
        private long _runningTime;
        public OutboundRunner(int numBytes) {
            _numBytes = numBytes;
            _runnerNum = ++__runnerNum;
        }
        public void run() {
            Thread.currentThread().setName("Out" + _runnerNum);
            _runningTime = testOutboundThrottle(_numBytes, 8*1024);
            _log.debug("Outbound runner " + _runnerNum + " pushed " + _numBytes + " in " + _runningTime + "ms");
        }
        public long getRunningTime() { return _runningTime; }
    }
        
    
    public static void main(String args[]) {
        BandwidthLimiterTest test = new BandwidthLimiterTest();
        //test.testOutbound();
        //test.testInbound();
        test.testOutboundContention();
        System.exit(0);
    }
}