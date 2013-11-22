package net.i2p.router.client;

import net.i2p.CoreVersion;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.internal.I2CPMessageQueue;
import net.i2p.internal.QueuedI2CPMessageReader;
import net.i2p.router.RouterContext;

/**
 * Zero-copy in-JVM.
 * While super() starts both a reader and a writer thread, we only need a reader thread here.
 *
 * @author zzz
 * @since 0.8.3
 */
class QueuedClientConnectionRunner extends ClientConnectionRunner {
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
    public synchronized void startRunning() {
        _reader = new QueuedI2CPMessageReader(this.queue, new ClientMessageEventListener(_context, this, false));
        _reader.startReading();
    }
    
    /**
     * Calls super() to stop the reader, and sends a poison message to the client.
     */
    @Override
    public synchronized void stopRunning() {
        super.stopRunning();
        queue.close();
        // queue = null;
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
     * @throws I2CPMessageException if queue full or on other errors
     */
    @Override
    void doSend(I2CPMessage msg) throws I2CPMessageException {
        boolean success = queue.offer(msg);
        if (!success)
            throw new I2CPMessageException("I2CP write to queue failed");
    }

    /**
     *  Does nothing. Client version is the core version.
     *  @since 0.9.7
     */
    @Override
    public void setClientVersion(String version) {}

    /**
     *  The client version.
     *  @return CoreVersion.VERSION
     *  @since 0.9.7
     */
    @Override
    public String getClientVersion() {
        return CoreVersion.VERSION;
    }
}
