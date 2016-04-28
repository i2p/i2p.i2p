package net.i2p.client.impl;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSessionException;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.internal.PoisonI2CPMessage;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 * Copied from net.i2p.router.client
 * We need a single thread that writes so we don't have issues with
 * the Piped Streams used in InternalSocket.
 *
 * @author zzz from net.i2p.router.client.ClientWriterRunner
 */
class ClientWriterRunner implements Runnable {
    private final OutputStream _out;
    private final I2PSessionImpl _session;
    private final BlockingQueue<I2CPMessage> _messagesToWrite;
    private static final AtomicLong __Id = new AtomicLong();
    //private final Log _log = I2PAppContext.getGlobalContext().logManager().getLog(ClientWriterRunner.class);

    private static final int MAX_QUEUE_SIZE = 32;
    private static final long MAX_SEND_WAIT = 10*1000;
    
    /**
     *  As of 0.9.11 does not start the thread, caller must call startWriting()
     */
    public ClientWriterRunner(OutputStream out, I2PSessionImpl session) {
        _out = new BufferedOutputStream(out);
        _session = session;
        _messagesToWrite = new LinkedBlockingQueue<I2CPMessage>(MAX_QUEUE_SIZE);
    }

    /**
     *  @since 0.9.11
     */
    public void startWriting() {
        Thread t = new I2PAppThread(this, "I2CP Client Writer " + __Id.incrementAndGet(), true);
        t.start();
    }

    /**
     * Add this message to the writer's queue.
     * Blocking if queue is full.
     * @throws I2PSessionException if we wait too long or are interrupted
     */
    public void addMessage(I2CPMessage msg) throws I2PSessionException {
        try {
            if (!_messagesToWrite.offer(msg, MAX_SEND_WAIT, TimeUnit.MILLISECONDS))
                throw new I2PSessionException("Timed out waiting while write queue was full");
        } catch (InterruptedException ie) {
            throw new I2PSessionException("Interrupted while write queue was full", ie);
        }
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
        while (!_session.isClosed()) {
            try {
                msg = _messagesToWrite.take();
            } catch (InterruptedException ie) {
                continue;
            }
            if (msg.getType() == PoisonI2CPMessage.MESSAGE_TYPE)
                break;
            // only thread, we don't need synchronized
            try {
                msg.writeMessage(_out);
                if (_messagesToWrite.isEmpty())
                    _out.flush();
            } catch (I2CPMessageException ime) {
                _session.propogateError("Error writing out the message", ime);
                _session.disconnect();
                break;
            } catch (IOException ioe) {
                _session.propogateError("Error writing out the message", ioe);
                _session.disconnect();
                break;
            }
        }
        _messagesToWrite.clear();
    }
}
