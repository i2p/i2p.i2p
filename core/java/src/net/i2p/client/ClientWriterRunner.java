package net.i2p.client;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.internal.PoisonI2CPMessage;
import net.i2p.util.I2PAppThread;

/**
 * Copied from net.i2p.router.client
 * We need a single thread that writes so we don't have issues with
 * the Piped Streams used in InternalSocket.
 *
 * @author zzz from net.i2p.router.client.ClientWriterRunner
 */
class ClientWriterRunner implements Runnable {
    private OutputStream _out;
    private I2PSessionImpl _session;
    private BlockingQueue<I2CPMessage> _messagesToWrite;
    private static volatile long __Id = 0;
    
    /** starts the thread too */
    public ClientWriterRunner(OutputStream out, I2PSessionImpl session) {
        _out = out;
        _session = session;
        _messagesToWrite = new LinkedBlockingQueue();
        Thread t = new I2PAppThread(this, "I2CP Client Writer " + (++__Id), true);
        t.start();
    }

    /**
     * Add this message to the writer's queue
     *
     */
    public void addMessage(I2CPMessage msg) {
        try {
            _messagesToWrite.put(msg);
        } catch (InterruptedException ie) {}
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
