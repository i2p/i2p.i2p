package net.i2p.util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

import net.i2p.I2PAppContext;

/**
 * Offer a glimpse into the last few console messages generated.
 * Maintains two buffers, one normal and one critical.
 */
public class LogConsoleBuffer {
    private final int lim;
    private final UIMessages _buffer;
    private final UIMessages _critBuffer;

    /**
     *  Uses default limit from LogManager.
     *  As of 0.8.8, limit is not checked at runtime.
     *
     *  @param context unused
     */
    public LogConsoleBuffer(I2PAppContext context) {
        this(LogManager.DEFAULT_CONSOLEBUFFERSIZE);
    }

    /**
     *  @param limit max size of each buffer
     *  In theory the limit is configurable, but it isn't in the UI,
     *  so set it at construction.
     *
     *  @since 0.8.8
     */
    public LogConsoleBuffer(int limit) {
        lim = Math.max(limit, 4);
        // Add some extra room to minimize the chance of losing a message,
        // since we are doing offer() below.
        _buffer = new UIMessages(lim + 4);
        _critBuffer = new UIMessages(lim + 4);
    }

    void add(String msg) {
        _buffer.addMessageNoEscape(msg);
    }

    /**
     *  Only adds to the critical buffer, not to both.
     *
     */
    void addCritical(String msg) {
        _critBuffer.addMessageNoEscape(msg);
    }

    /**
     * Retrieve the currently buffered messages, earlier values were generated...
     * earlier.  All values are strings with no formatting (as they are written
     * in the logs)
     *
     * @return oldest first
     */
    public List<String> getMostRecentMessages() {
        return _buffer.getMessageStrings();
    }

    /**
     * Retrieve the currently buffered critical messages, earlier values were generated...
     * earlier.  All values are strings with no formatting (as they are written
     * in the logs)
     *
     * @return oldest first
     */
    public List<String> getMostRecentCriticalMessages() {
        return _critBuffer.getMessageStrings();
    }

    /**
     * Retrieve the currently buffered messages, earlier values were generated...
     * earlier.  All values are strings with no formatting (as they are written
     * in the logs)
     *
     * @return oldest first
     * @since 0.9.46
     */
    public UIMessages getUIMessages() {
        return _buffer;
    }

    /**
     * Retrieve the currently buffered critical messages, earlier values were generated...
     * earlier.  All values are strings with no formatting (as they are written
     * in the logs)
     *
     * @return oldest first
     * @since 0.9.46
     */
    public UIMessages getCriticalUIMessages() {
        return _critBuffer;
    }

    /**
     *  @since 0.8.8
     */
    public void clear() {
        _buffer.clear();
        _critBuffer.clear();
    }
}
