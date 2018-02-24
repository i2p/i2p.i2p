package net.i2p.util;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.SocketTimeoutException;

/**
 *  Adds setReadTimeout().
 *  Must be used with a TimeoutPipedOutputStream.
 *
 *  To support InternalSocket.setSoTimeout().
 *  Package private, not a part of the public API, not for general use.
 *
 *  @since 0.9.34
 */
class TimeoutPipedInputStream extends PipedInputStream {

    private int timeout;
    // local version of pkg private in super
    private boolean _closedByWriter;
    // local version of pkg private in super
    private volatile boolean _closedByReader;

    public TimeoutPipedInputStream(int pipeSize) {
        super(pipeSize);
    }

    /**
     * @throws SocketTimeoutException if timeout is reached
     */
    @Override
    public synchronized int read() throws IOException {
        // This is similar to what is done in super, but with a timeout.
        // We use local copies of closedByReader and closedByWriter as
        // those are package private in super.
        // This doesn't add any substantial runtime overhead,
        // as we are just doing the 1-second wait and loop here
        // instead of in super.
        // If a timeout is set, we will always wait here instead of in super.
        if (in < 0 && timeout > 0 && !_closedByReader) {
            long now = System.currentTimeMillis();
            long end = now + timeout;
            while (true) {
                if (_closedByWriter)
                    return -1;
                try {
                    wait(Math.max(1L, Math.min(1000L, end - now)));
                } catch (InterruptedException ex) {
                    throw new InterruptedIOException();
                }
                if (in >= 0 || _closedByReader)
                    break;
                now = System.currentTimeMillis();
                if (now >= end)
                    throw new SocketTimeoutException();
            }
        }
        return super.read();
    }

    /**
     *  Must be called before blocking read call.
     *  @param ms less than or equal to zero means forever
     */
    public void setReadTimeout(int ms) {
        timeout = Math.max(0, ms);
    }

    /**
     *  To save state.
     *  We have to do this because can't get to closedByWriter in super.
     */
    synchronized void x_receivedLast() {
        _closedByWriter = true;
        notifyAll();
    }

    /**
     *  Overridden to save state.
     *  We have to do this because can't get to closedByReader in super
     */
    @Override
    public void close() throws IOException {
        _closedByReader = true;
        super.close();
    }

/****
    public static void main(String[] args) throws IOException {
        TimeoutPipedInputStream in = new TimeoutPipedInputStream(1024);
        TimeoutPipedOutputStream out = new TimeoutPipedOutputStream(in);
        out.write('a');
        in.setReadTimeout(5555);
        long start = System.currentTimeMillis();
        try {
            int a = in.read();
            if (a == 'a')
                System.out.println("got 1 (pass)");
            else
                System.out.println("bad data (fail)");
            in.read();
            System.out.println("got 2 (fail)");
        } catch (IOException ioe) {
            ioe.printStackTrace();
            System.out.println("got ioe (pass)");
        }
        System.out.println("took " + (System.currentTimeMillis() - start));
        in.setReadTimeout(0);
        System.out.println("wait forever");
        in.read();
    }
****/
}
