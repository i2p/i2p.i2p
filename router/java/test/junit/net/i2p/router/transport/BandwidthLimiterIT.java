package net.i2p.router.transport;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;
import java.util.Random;

import org.junit.BeforeClass;
import org.junit.Test;

import static junit.framework.TestCase.*;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;

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
public class BandwidthLimiterIT {
    private static RouterContext _context;
    private final static int NUM_KB = 256;
    
    @BeforeClass
    public static void setUp() {
        _context = new RouterContext(new Router());
        _context.initAll();
    }
    
    private void prepareLimiter(int inKBps, int outKBps, int inBurst, int outBurst) {
        Properties props = System.getProperties();
        props.setProperty(FIFOBandwidthRefiller.PROP_INBOUND_BANDWIDTH, ""+inKBps);
        props.setProperty(FIFOBandwidthRefiller.PROP_OUTBOUND_BANDWIDTH, ""+outKBps);
        props.setProperty(FIFOBandwidthRefiller.PROP_INBOUND_BANDWIDTH_PEAK, ""+inBurst);
        props.setProperty(FIFOBandwidthRefiller.PROP_OUTBOUND_BANDWIDTH_PEAK, ""+outBurst);
        //props.setProperty(TrivialBandwidthLimiter.PROP_REPLENISH_FREQUENCY, ""+10*1000);
        System.setProperties(props);
        _context.bandwidthLimiter().reinitialize();
    }
    
    /**
     * Using the configured limiter, determine how long it takes to shove 
     * numBytes through a BandwidthLimitedOutputStream (broken up into numBytesPerWrite)
     * chunks.
     *
     */
    private long testOutboundThrottle(int numBytes, int numBytesPerWrite) {
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
    private long testInboundThrottle(int numBytes, int numBytesPerRead) {
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
    @Test
    public void testOutbound() {
    	double error;
    	double predict;
    	
        prepareLimiter(-1, -1, -1, -1);
        long ms = testOutboundThrottle(NUM_KB*1024, 1*1024);
        
        /*prepareLimiter(-1, 4, -1, 4*1024);
        ms = testOutboundThrottle(NUM_KB*1024, 1*1024);
        predict = (NUM_KB/4)*1000;
        error = predict/ms;
        //assertTrue(error>.89);
        assertTrue(error<1.05);*/
        
        prepareLimiter(-1, 32, -1, 32*1024);
        ms = testOutboundThrottle(NUM_KB*1024, 1*1024);
        predict = (NUM_KB/32)*1000;
        error = predict/ms;
        //assertTrue(error>.89);
        assertTrue(error<1.05);
        
        prepareLimiter(-1, 256, -1, 256*1024);
        ms = testOutboundThrottle(NUM_KB*1024, 1*1024);
        predict = (NUM_KB/256)*1000;
        error = predict/ms;
        //assertTrue(error>.89);
        assertTrue(error<1.05);
        
    }
    
    /**
     * Run a series of tests on inbound throttling (pulling lots of data through pipes 
     * with various limits) and log the times.
     *
     */
    @Test
    public void testInbound() {
        double predict;
        double error;
    	
        prepareLimiter(-1, -1, -1, -1);
        long ms = testInboundThrottle(NUM_KB*1024, 1*1024);
        
        /*prepareLimiter(4, -1, 4*1024, -1);
        ms = testInboundThrottle(NUM_KB*1024, 1*1024);
        predict = (NUM_KB/4)*1000;
        error = predict/ms;
        //assertTrue(error>.89);
        assertTrue(error<1.05);*/
        
        prepareLimiter(32, -1, 32*1024, -1);
        ms = testInboundThrottle(NUM_KB*1024, 1*1024);
        predict = (NUM_KB/32)*1000;
        error = predict/ms;
        //assertTrue(error>.89);
        assertTrue(error<1.05);
        
        prepareLimiter(256, -1, 256*1024, -1);
        ms = testInboundThrottle(NUM_KB*1024, 1*1024);
        predict = (NUM_KB/256)*1000;
        error = predict/ms;
        //assertTrue(error>.89);
        assertTrue(error<1.05);
        
    }
    
    
    @Test
    public void testOutboundContention() {
    	double predict;
    	double error;
    	long ms;
    	long end;
    	long start;
    	
        prepareLimiter(-1, -1, -1, -1);
        start = System.currentTimeMillis();
        //long runningTimes[] = testOutboundContention(10, NUM_KB*1024);
        end = System.currentTimeMillis();
        
        //prepareLimiter(-1, 4, -1, 5*1024*1024);
        //start = System.currentTimeMillis();
        //runningTimes = testOutboundContention(10, NUM_KB*1024);
        //end = System.currentTimeMillis();
        
        //prepareLimiter(-1, 32, -1, 32*1024);
        //start = System.currentTimeMillis();
        //runningTimes = testOutboundContention(10, NUM_KB*1024);
        //end = System.currentTimeMillis();
        
        prepareLimiter(-1, 256, -1, 256*1024);
        start = System.currentTimeMillis();
        testOutboundContention(10, NUM_KB*1024);
        end = System.currentTimeMillis();
        ms = end-start;
        predict = (NUM_KB/256)*1000*10;
        error = predict/ms;
        //assertTrue(error>.89);
        assertTrue(error<1.05);
        
    }
    
    private long[] testOutboundContention(int numConcurrent, int numBytes) {
        OutboundRunner threads[] = new OutboundRunner[numConcurrent];
        for (int i = 0; i < numConcurrent; i++) {
            threads[i] = new OutboundRunner(numBytes);
        }
        
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
        }
        public long getRunningTime() { return _runningTime; }
    }
}

class NullOutputStream extends OutputStream {
    public void write(int param) {}
}

class FakeInputStream extends InputStream {
    private volatile int _numRead;
    private int _size;
    
    public FakeInputStream(int size) {
        _size = size;
        _numRead = 0;
    }
    public int read() {
        int rv = 0;
        if (_numRead >= _size) 
            rv = -1;
        else
            rv = 42;
        _numRead++;
        return rv;
    }
}
