package net.i2p.router.client;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.internal.I2CPMessageQueue;

/**
 * Contains the methods to talk to a router or client via I2CP,
 * when both are in the same JVM.
 * This interface contains methods to access two queues,
 * one for transmission and one for receiving.
 * The methods are identical to those in java.util.concurrent.BlockingQueue
 *
 * @author zzz
 * @since 0.8.3
 */
class I2CPMessageQueueImpl extends I2CPMessageQueue {
    private final BlockingQueue<I2CPMessage> _in;
    private final BlockingQueue<I2CPMessage> _out;

    public I2CPMessageQueueImpl(BlockingQueue<I2CPMessage> in, BlockingQueue<I2CPMessage> out) {
        _in = in;
        _out = out;
    }

    /**
     *  Send a message, nonblocking
     *  @return success (false if no space available)
     */
    public boolean offer(I2CPMessage msg) {
        return _out.offer(msg);
    }

    /**
     *  Send a message, blocking.
     *  @param timeout how long to wait for space (ms)
     *  @return success (false if no space available or if timed out)
     *  @since 0.9.3
     */
    public boolean offer(I2CPMessage msg, long timeout) throws InterruptedException {
        return _out.offer(msg, timeout, TimeUnit.MILLISECONDS);
    }

    /**
     *  Receive a message, nonblocking
     *  @return message or null if none available
     */
    public I2CPMessage poll() {
        return _in.poll();
    }

    /**
     *  Send a message, blocking until space is available
     */
    public void put(I2CPMessage msg) throws InterruptedException {
        _out.put(msg);
    }

    /**
     *  Receive a message, blocking until one is available
     *  @return message
     */
    public I2CPMessage take() throws InterruptedException {
        return _in.take();
    }
}
