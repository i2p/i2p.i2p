package net.i2p.router.transport.ntcp;

import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;

import net.i2p.I2PAppContext;
import net.i2p.router.OutNetMessage;
import net.i2p.util.Log;

/**
 * Previously, NTCP was using SimpleTimer with a delay of 0, which
 * was a real abuse.
 *
 * Here we use the non-scheduled, lockless ThreadPoolExecutor with
 * a fixed pool size and an unbounded queue.
 *
 * The old implementation was having problems with lock contention;
 * this should work a lot better - and not clog up the SimpleTimer queue.
 *
 * @author zzz
 */
class NTCPSendFinisher {
    private static final int MIN_THREADS = 1;
    private static final int MAX_THREADS = 4;
    private final I2PAppContext _context;
    private final NTCPTransport _transport;
    private final Log _log;
    private static int _count;
    private ThreadPoolExecutor _executor;
    private static final int THREADS;
    static {
        long maxMemory = Runtime.getRuntime().maxMemory();
        if (maxMemory == Long.MAX_VALUE)
            maxMemory = 96*1024*1024l;
        THREADS = (int) Math.max(MIN_THREADS, Math.min(MAX_THREADS, 1 + (maxMemory / (32*1024*1024))));
    }

    public NTCPSendFinisher(I2PAppContext context, NTCPTransport transport) {
        _context = context;
        _log = _context.logManager().getLog(NTCPSendFinisher.class);
        _transport = transport;
        //_context.statManager().createRateStat("ntcp.sendFinishTime", "How long to queue and excecute msg.afterSend()", "ntcp", new long[] {5*1000});
    }
    
    public void start() {
        _count = 0;
        _executor = new CustomThreadPoolExecutor(THREADS);
    }

    public void stop() {
        if (_executor != null)
            _executor.shutdownNow();
    }

    public void add(OutNetMessage msg) {
        try {
            _executor.execute(new RunnableEvent(msg));
        } catch (RejectedExecutionException ree) {
            // race with stop()
            _log.warn("NTCP send finisher stopped, discarding msg.afterSend()");
        }
    }
    
    // not really needed for now but in case we want to add some hooks like afterExecute()
    private static class CustomThreadPoolExecutor extends ThreadPoolExecutor {
        public CustomThreadPoolExecutor(int num) {
             // use unbounded queue, so maximumPoolSize and keepAliveTime have no effect
             super(num, num, 1000, TimeUnit.MILLISECONDS,
                   new LinkedBlockingQueue(), new CustomThreadFactory());
        }
    }

    private static class CustomThreadFactory implements ThreadFactory {
        public Thread newThread(Runnable r) {
            Thread rv = Executors.defaultThreadFactory().newThread(r);
            rv.setName("NTCPSendFinisher " + (++_count) + '/' + THREADS);
            rv.setDaemon(true);
            return rv;
        }
    }

    /**
     * Call afterSend() for the message
     */
    private class RunnableEvent implements Runnable {
        private final OutNetMessage _msg;
        //private final long _queued;

        public RunnableEvent(OutNetMessage msg) {
            _msg = msg;
            //_queued = _context.clock().now();
        }

        public void run() {
            try {
                _transport.afterSend(_msg, true, false, _msg.getSendTime());
                // appx 0.1 ms
                //_context.statManager().addRateData("ntcp.sendFinishTime", _context.clock().now() - _queued, 0);
            } catch (Throwable t) {
                _log.log(Log.CRIT, " wtf, afterSend borked", t);
            }
        }
    }
}

