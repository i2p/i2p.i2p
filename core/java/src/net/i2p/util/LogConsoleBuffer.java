package net.i2p.util;

import java.util.ArrayList;
import java.util.List;
import net.i2p.I2PAppContext;

/**
 * Offer a glimpse into the last few console messages generated
 *
 */
public class LogConsoleBuffer {
    private I2PAppContext _context;
    private List _buffer;

    public LogConsoleBuffer(I2PAppContext context) {
        _context = context;
        _buffer = new ArrayList();
    }

    void add(String msg) {
        int lim = _context.logManager().getConsoleBufferSize();
        synchronized (_buffer) {
            while (_buffer.size() >= lim)
                _buffer.remove(0);
            _buffer.add(msg);
        }
    }

    /**
     * Retrieve the currently bufferd messages, earlier values were generated...
     * earlier.  All values are strings with no formatting (as they are written
     * in the logs)
     *
     */
    public List getMostRecentMessages() {
        synchronized (_buffer) {
            return new ArrayList(_buffer);
        }
    }
}