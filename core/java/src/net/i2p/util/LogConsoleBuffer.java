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
    private List _critBuffer;

    public LogConsoleBuffer(I2PAppContext context) {
        _context = context;
        _buffer = new ArrayList();
        _critBuffer = new ArrayList();
    }

    void add(String msg) {
        int lim = _context.logManager().getConsoleBufferSize();
        synchronized (_buffer) {
            while (_buffer.size() >= lim)
                _buffer.remove(0);
            _buffer.add(msg);
        }
    }
    void addCritical(String msg) {
        int lim = _context.logManager().getConsoleBufferSize();
        synchronized (_critBuffer) {
            while (_critBuffer.size() >= lim)
                _critBuffer.remove(0);
            _critBuffer.add(msg);
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
    /**
     * Retrieve the currently bufferd crutucak messages, earlier values were generated...
     * earlier.  All values are strings with no formatting (as they are written
     * in the logs)
     *
     */
    public List getMostRecentCriticalMessages() {
        synchronized (_critBuffer) {
            return new ArrayList(_critBuffer);
        }
    }
}