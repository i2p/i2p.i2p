package net.i2p.router.client;

import java.util.ArrayList;
import java.util.List;

import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Async writer class so that if a client app hangs, they wont take down the
 * whole router with them (since otherwise the JobQueue would block until
 * the client reads from their i2cp socket, causing all sorts of bad shit to
 * happen)
 *
 */
class ClientWriterRunner implements Runnable {
    private List _messagesToWrite;
    private List _messagesToWriteTimes;
    private ClientConnectionRunner _runner;
    private RouterContext _context;
    private Log _log;
    private long _id;
    private static long __id = 0;
    
    private static final long MAX_WAIT = 5*1000;
    
    /** notify this lock when there are messages to write */
    private Object _activityLock = new Object();
    /** lock on this when updating the class level data structs */
    private Object _dataLock = new Object();
    
    public ClientWriterRunner(RouterContext context, ClientConnectionRunner runner) {
        _context = context;
        _log = context.logManager().getLog(ClientWriterRunner.class);
        _messagesToWrite = new ArrayList(4);
        _messagesToWriteTimes = new ArrayList(4);
        _runner = runner;
        _id = ++__id;
    }

    /**
     * Add this message to the writer's queue
     *
     */
    public void addMessage(I2CPMessage msg) {
        synchronized (_dataLock) {
            _messagesToWrite.add(msg);
            _messagesToWriteTimes.add(new Long(_context.clock().now()));
        }
        synchronized (_activityLock) {
            _activityLock.notifyAll();
        }
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("["+_id+"] addMessage completed for " + msg.getClass().getName());
    }

    /**
     * No more messages - dont even try to send what we have
     *
     */
    public void stopWriting() {
        synchronized (_activityLock) {
            _activityLock.notifyAll();
        }
    }
    public void run() {
        while (!_runner.getIsDead()) {
            List messages = null; 
            List messageTimes = null;
            
            synchronized (_dataLock) {
                if (_messagesToWrite.size() > 0) {
                    messages = new ArrayList(_messagesToWrite.size());
                    messageTimes = new ArrayList(_messagesToWriteTimes.size());
                    messages.addAll(_messagesToWrite);
                    messageTimes.addAll(_messagesToWriteTimes);
                    _messagesToWrite.clear();
                    _messagesToWriteTimes.clear();
                } 
            }
            
            if (messages == null) {
                try {
                    synchronized (_activityLock) {
                        _activityLock.wait();
                    }
                } catch (InterruptedException ie) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Interrupted while waiting for activity", ie);
                }
            } else {
                for (int i = 0; i < messages.size(); i++) {
                    I2CPMessage msg = (I2CPMessage)messages.get(i);
                    Long when = (Long)messageTimes.get(i);
                    _runner.writeMessage(msg);
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("["+_id+"] writeMessage time since addMessage(): " 
                                   + (_context.clock().now()-when.longValue()) + " for " 
                                   + msg.getClass().getName());
                }
            } 
        }
    }
}
