package net.i2p.internal;

import net.i2p.data.i2cp.I2CPMessage;

/**
 * Contains the methods to talk to a router or client via I2CP,
 * when both are in the same JVM.
 * This interface contains methods to access two queues,
 * one for transmission and one for receiving.
 * The methods are identical to those in java.util.concurrent.BlockingQueue.
 *
 * Reading may be done in a thread using the QueuedI2CPMessageReader class.
 * Non-blocking writing may be done directly with offer().
 *
 * @author zzz
 * @since 0.8.3
 */
public abstract class I2CPMessageQueue {

    /**
     *  Send a message, nonblocking.
     *  @return success (false if no space available)
     */
    public abstract boolean offer(I2CPMessage msg);

    /**
     *  Receive a message, nonblocking.
     *  Unused for now.
     *  @return message or null if none available
     */
    public abstract I2CPMessage poll();

    /**
     *  Send a message, blocking until space is available.
     *  Unused for now.
     */
    public abstract void put(I2CPMessage msg) throws InterruptedException;

    /**
     *  Receive a message, blocking until one is available.
     *  @return message
     */
    public abstract I2CPMessage take() throws InterruptedException;

    /**
     *  == offer(new PoisonI2CPMessage());
     */
    public void close() {
        offer(new PoisonI2CPMessage());
    }
}
