/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import net.i2p.util.Log;

/**
 * Read what i2ptunnel logs, and expose it in a buffer
 *
 */
class BufferLogger implements Logging {
    private final static Log _log = new Log(BufferLogger.class);
    private ByteArrayOutputStream _baos; // should be final and use a factory. LINT
    private boolean _ignore;

    /**
     * Constructs a buffered logger.
     */
    public BufferLogger() {
        _baos = new ByteArrayOutputStream(512);
        _ignore = false;
    }

    private final static String EMPTY = "";

    /**
     * Retrieves the buffer
     * @return the buffer
     */
    public String getBuffer() {
        if (_ignore)
            return EMPTY;

        return new String(_baos.toByteArray());
    }

    /**
     * We don't care about anything else the logger receives.  This is useful
     * for loggers passed in to servers and clients, since they will continue
     * to add info to the logger, but if we're instantiated by the tunnel manager,
     * its likely we only care about the first few messages it sends us.
     *
     */
    public void ignoreFurtherActions() {
        _ignore = true;
        synchronized (_baos) {
            _baos.reset();
        }
        _baos = null;
    }

    /**
     * Pass in some random data 
     * @param s String containing what we're logging.
     */
    public void log(String s) {
        if (_ignore) return;
        if (s != null) {
            _log.debug("logging [" + s + "]");
            try {
                _baos.write(s.getBytes());
                _baos.write('\n');
            } catch (IOException ioe) {
                _log.error("Error logging [" + s + "]");
            }
        }
    }
}