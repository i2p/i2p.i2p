package net.i2p.router.client;

import java.util.List;
import java.util.ArrayList;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;
import net.i2p.data.i2cp.I2CPMessage;

/**
 * Async writer class so that if a client app hangs, they wont take down the
 * whole router with them (since otherwise the JobQueue would block until
 * the client reads from their i2cp socket, causing all sorts of bad shit to
 * happen)
 *
 */
class ClientWriterRunner implements Runnable {
    private List _messagesToWrite;
    private volatile long _lastAdded;
    private ClientConnectionRunner _runner;
    private RouterContext _context;
    private Log _log;
    public ClientWriterRunner(RouterContext context, ClientConnectionRunner runner) {
        _context = context;
        _log = context.logManager().getLog(ClientWriterRunner.class);
        _messagesToWrite = new ArrayList(4);
        _runner = runner;
    }

    /**
     * Add this message to the writer's queue
     *
     */
    public void addMessage(I2CPMessage msg) {
        synchronized (_messagesToWrite) {
            _messagesToWrite.add(msg);
            _lastAdded = _context.clock().now();
            _messagesToWrite.notifyAll();
        }
    }

    /**
     * No more messages - dont even try to send what we have
     *
     */
    public void stopWriting() {
        synchronized (_messagesToWrite) {
            _messagesToWrite.notifyAll();
        }
    }
    public void run() {
        while (!_runner.getIsDead()) {
            List messages = null; 
            long beforeCheckSync = _context.clock().now();
            long inCheckSync = 0;
            int remaining = 0;
            synchronized (_messagesToWrite) {
                inCheckSync = _context.clock().now();
                if (_messagesToWrite.size() > 0) {
                    messages = new ArrayList(_messagesToWrite.size());
                    messages.addAll(_messagesToWrite);
                    _messagesToWrite.clear();
                } else {
                    try {
                        _messagesToWrite.wait();
                    } catch (InterruptedException ie) {}
                    if (_messagesToWrite.size() > 0) {
                        messages = new ArrayList(_messagesToWrite.size());
                        messages.addAll(_messagesToWrite);
                        _messagesToWrite.clear();
                    }
                }
                remaining = _messagesToWrite.size();
            }

            long afterCheckSync = _context.clock().now();

            if (messages != null) {
                for (int i = 0; i < messages.size(); i++) {
                    I2CPMessage msg = (I2CPMessage)messages.get(i);
                    _runner.writeMessage(msg);
                    long afterWriteMessage = _context.clock().now();
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("writeMessage: check sync took " 
                                   + (inCheckSync-beforeCheckSync) + "ms, writemessage took "
                                   + (afterWriteMessage-afterCheckSync) 
                                   + "ms,  time since addMessage(): " 
                                   + (afterCheckSync-_lastAdded) + " for " 
                                   + msg.getClass().getName() + " remaining - " + remaining);
                }
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("dont writeMessage: check sync took " 
                               + (inCheckSync-beforeCheckSync) + "ms, "
                               + "time since addMessage(): " 
                               + (afterCheckSync-_lastAdded) + " remaining - " + remaining);
            }
        }
    }
}
