package net.i2p.util;

import java.util.LinkedList;
import java.util.List;

/**
 * Offer a glimpse into the last few console messages generated
 *
 */
public class LogConsoleBuffer {
    private final static LogConsoleBuffer _instance = new LogConsoleBuffer();

    public final static LogConsoleBuffer getInstance() {
        return _instance;
    }
    private List _buffer;

    private LogConsoleBuffer() {
        _buffer = new LinkedList();
    }

    void add(String msg) {
        int lim = LogManager.getInstance().getConsoleBufferSize();
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
            return new LinkedList(_buffer);
        }
    }
}