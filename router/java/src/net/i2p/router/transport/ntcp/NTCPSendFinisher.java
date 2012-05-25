package net.i2p.router.transport.ntcp;

import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
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
public class NTCPSendFinisher {
    private static final int THREADS = 4;
    private I2PAppContext _context;
    private NTCPTransport _transport;
    private Log _log;
    private int _count;
    private ThreadPoolExecutor _executor;

    public NTCPSendFinisher(I2PAppContext context, NTCPTransport transport) {
        _context = context;
        _log = _context.logManager().getLog(NTCPSendFinisher.class);
        _transport = transport;
    }
    
    public void start() {
        _count = 0;
        _executor = new CustomThreadPoolExecutor();
    }

    public void stop() {
        if (_executor != null)
            _executor.shutdownNow();
    }

    public void add(OutNetMessage msg) {
        _executor.execute(new RunnableEvent(msg));
    }
    
    // not really needed for now but in case we want to add some hooks like afterExecute()
    private class CustomThreadPoolExecutor extends ThreadPoolExecutor {
        public CustomThreadPoolExecutor() {
             // use unbounded queue, so maximumPoolSize and keepAliveTime have no effect
             super(THREADS, THREADS, 1000, TimeUnit.MILLISECONDS,
                   new LinkedBlockingQueue(), new CustomThreadFactory());
        }
    }

    private class CustomThreadFactory implements ThreadFactory {
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
        private OutNetMessage _msg;

        public RunnableEvent(OutNetMessage msg) {
            _msg = msg;
        }

        public void run() {
            try {
                _transport.afterSend(_msg, true, false, _msg.getSendTime());
            } catch (Throwable t) {
                _log.log(Log.CRIT, " wtf, afterSend borked", t);
            }
        }
    }
}

