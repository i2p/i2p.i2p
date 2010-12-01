package net.i2p.router.client;

import java.io.IOException;

import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.internal.I2CPMessageQueue;
import net.i2p.internal.QueuedI2CPMessageReader;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Zero-copy in-JVM.
 * While super() starts both a reader and a writer thread, we only need a reader thread here.
 *
 * @author zzz
 * @since 0.8.3
 */
public class QueuedClientConnectionRunner extends ClientConnectionRunner {
    private final I2CPMessageQueue queue;
    
    /**
     * Create a new runner with the given queues
     *
     */
    public QueuedClientConnectionRunner(RouterContext context, ClientManager manager, I2CPMessageQueue queue) {
        super(context, manager, null);
        this.queue = queue;
    }
    


    /**
     * Starts the reader thread. Does not call super().
     */
    @Override
    public void startRunning() {
        _reader = new QueuedI2CPMessageReader(this.queue, new ClientMessageEventListener(_context, this));
        _reader.startReading();
    }
    
    /**
     * Calls super() to stop the reader, and sends a poison message to the client.
     */
    @Override
    void stopRunning() {
        super.stopRunning();
        queue.close();
    }
    
    /**
     *  In super(), doSend queues it to the writer thread and
     *  the writer thread calls writeMessage() to write to the output stream.
     *  Since we have no writer thread this shouldn't happen.
     */
    @Override
    void writeMessage(I2CPMessage msg) {
        throw new RuntimeException("huh?");
    }
    
    /**
     * Actually send the I2CPMessage to the client.
     * Nonblocking.
     */
    @Override
    void doSend(I2CPMessage msg) throws I2CPMessageException {
        // This will never fail, for now, as the router uses unbounded queues
        // Perhaps in the future we may want to use bounded queues,
        // with non-blocking writes for the router
        // and blocking writes for the client?
        boolean success = queue.offer(msg);
        if (!success)
            throw new I2CPMessageException("I2CP write to queue failed");
    }
    
}
