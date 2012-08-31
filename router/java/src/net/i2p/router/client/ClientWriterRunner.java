package net.i2p.router.client;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.internal.PoisonI2CPMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Async writer class so that if a client app hangs, they wont take down the
 * whole router with them (since otherwise the JobQueue would block until
 * the client reads from their i2cp socket, causing all sorts of bad shit to
 * happen)
 *
 * @author zzz modded to use concurrent
 */
class ClientWriterRunner implements Runnable {
    private final BlockingQueue<I2CPMessage> _messagesToWrite;
    private final ClientConnectionRunner _runner;
    private final Log _log;
    private final long _id;
    private static long __id = 0;
    
    public ClientWriterRunner(RouterContext context, ClientConnectionRunner runner) {
        _log = context.logManager().getLog(ClientWriterRunner.class);
        _messagesToWrite = new LinkedBlockingQueue();
        _runner = runner;
        _id = ++__id;
    }

    /**
     * Add this message to the writer's queue
     *
     */
    public void addMessage(I2CPMessage msg) {
        try {
            _messagesToWrite.put(msg);
        } catch (InterruptedException ie) {}
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("["+_id+"] addMessage completed for " + msg.getClass().getName());
    }

    /**
     * No more messages - dont even try to send what we have
     *
     */
    public void stopWriting() {
        _messagesToWrite.clear();
        try {
            _messagesToWrite.put(new PoisonI2CPMessage());
        } catch (InterruptedException ie) {}
    }

    public void run() {
        I2CPMessage msg;
        while (!_runner.getIsDead()) {
            try {
                msg = _messagesToWrite.take();
            } catch (InterruptedException ie) {
                continue;
            }
            if (msg.getType() == PoisonI2CPMessage.MESSAGE_TYPE)
                break;
            _runner.writeMessage(msg);
        }
    }
}
