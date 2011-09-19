package net.i2p.router;

import net.i2p.util.Log;

/**
 * Keep track of the inbound and outbound messages in memory.
 *
 * @deprecated unused
 */
public class MessageStateMonitor {
    private Log _log;
    private RouterContext _context;
    private volatile int _inboundLiveCount;
    private volatile int _inboundReadCount;
    private volatile int _inboundFinalizedCount;
    private volatile int _outboundLiveCount;
    private volatile int _outboundDiscardedCount;
    
    public MessageStateMonitor(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(MessageStateMonitor.class);
        _inboundLiveCount = 0;
        _inboundReadCount = 0;
        _inboundFinalizedCount = 0;
        _outboundLiveCount = 0;
        _outboundDiscardedCount = 0;
    }
    
    public void inboundMessageAdded() {
        _inboundLiveCount++;
        logStatus("inboundAdded     ");
    }
    public void inboundMessageRead() {
        _inboundReadCount++;
        _inboundLiveCount--;
        logStatus("inboundRead      ");
    }
    public void inboundMessageFinalized() {
        _inboundReadCount--;
        _inboundFinalizedCount++;
        logStatus("inboundFinalized ");
    }
    
    public void outboundMessageAdded() {
        _outboundLiveCount++;
        logStatus("outboundAdded    ");
    }
    public void outboundMessageDiscarded() {
        _outboundDiscardedCount++;
        _outboundLiveCount--;
        logStatus("outboundDiscarded");
    }
    public void outboundMessageFinalized() {
        _outboundDiscardedCount--;
        logStatus("outboundFinalized");
    }
    
    private void logStatus(String event) {
        if (false || (_log.shouldLog(Log.DEBUG)))
            _log.debug(event + ": outbound (live: " + _outboundLiveCount 
                       + " discarded:" + _outboundDiscardedCount + ")"
                       + " inbound (live: " + (_inboundLiveCount) 
                       //+ " inbound (live: " + (_inboundLiveCount-_inboundFinalizedCount) 
                       + " read: " + (_inboundReadCount)
                       //+ " completed: " + _inboundFinalizedCount
                       + ")");
    }
    
    public int getInboundLiveCount() { return _inboundLiveCount; }
    public int getInboundReadCount() { return _inboundReadCount; }
    public int getOutboundLiveCount() { return _outboundLiveCount; }
    public int getOutboundDiscardedCount() { return _outboundDiscardedCount; }
}
