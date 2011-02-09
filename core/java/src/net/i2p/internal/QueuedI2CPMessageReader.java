package net.i2p.internal;

import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.I2CPMessageReader;
import net.i2p.util.I2PThread;

/**
 * Get messages off an In-JVM queue, zero-copy
 *
 * @author zzz
 * @since 0.8.3
 */
public class QueuedI2CPMessageReader extends I2CPMessageReader {
    private /* final */ I2CPMessageQueue in;

    public QueuedI2CPMessageReader(I2CPMessageQueue in, I2CPMessageEventListener lsnr) {
        super(lsnr);
        this.in = in;
        _reader = null;
        _readerThread = null;
        _reader = new QueuedI2CPMessageReaderRunner();
        _readerThread = new I2PThread(_reader, "I2CP Internal Reader " + (++__readerId), true);
    }

    protected class QueuedI2CPMessageReaderRunner extends I2CPMessageReaderRunner implements Runnable {

        public QueuedI2CPMessageReaderRunner() {
            super();
        }

        @Override
        public void cancelRunner() {
            super.cancelRunner();
            _readerThread.interrupt();
        }

        @Override
        public void run() {
            while (_stayAlive) {
                while (_doRun) {
                    // do read
                    I2CPMessage msg = null;
                    try {
                        msg = in.take();
                        if (msg.getType() == PoisonI2CPMessage.MESSAGE_TYPE)
                            cancelRunner();
                        else
                            _listener.messageReceived(QueuedI2CPMessageReader.this, msg);
                    } catch (InterruptedException ie) {}
                }
                // ??? unused
                if (_stayAlive && !_doRun) {
                    // pause .5 secs when we're paused
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        _listener.disconnected(QueuedI2CPMessageReader.this);
                        cancelRunner();
                    }
                }
            }
            super.run(); // Since we are dead, this will do some clean up, nothing else :-)
            in = null;
            _readerThread = null;
            _reader = null;
        }
    }
}
