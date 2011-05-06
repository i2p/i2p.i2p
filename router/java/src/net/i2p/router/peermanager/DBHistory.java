package net.i2p.router.peermanager;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Properties;

import net.i2p.router.RouterContext;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;

/**
 * History of NetDb related activities (lookups, replies, stores, etc)
 *
 */
public class DBHistory {
    private final Log _log;
    private final RouterContext _context;
    private long _successfulLookups;
    private long _failedLookups;
    private RateStat _failedLookupRate;
    private RateStat _invalidReplyRate;
    private long _lookupReplyNew;
    private long _lookupReplyOld;
    private long _lookupReplyDuplicate;
    private long _lookupReplyInvalid;
    private long _lookupsReceived;
    private long _avgDelayBetweenLookupsReceived;
    private long _lastLookupReceived;
    private long _lastLookupSuccessful;
    private long _lastLookupFailed;
    private long _lastStoreSuccessful;
    private long _lastStoreFailed;
    private long _unpromptedDbStoreNew;
    private long _unpromptedDbStoreOld;
    private final String _statGroup;
    
    public DBHistory(RouterContext context, String statGroup) {
        _context = context;
        _log = context.logManager().getLog(DBHistory.class);
        _statGroup = statGroup;
        _lastLookupReceived = -1;
        createRates(statGroup);
    }
    
    /** how many times we have sent them a db lookup and received the value back from them
     *  @deprecated unused
     */
    public long getSuccessfulLookups() { return _successfulLookups; }
    /** how many times we have sent them a db lookup and not received the value or a lookup reply
     *  @deprecated unused
     */
    public long getFailedLookups() { return _failedLookups; }
    /** how many peers that we have never seen before did lookups provide us with?
     *  @deprecated unused
     */
    public long getLookupReplyNew() { return _lookupReplyNew; }
    /** how many peers that we have already seen did lookups provide us with?
     *  @deprecated unused
     */
    public long getLookupReplyOld() { return _lookupReplyOld; }
    /** how many peers that we explicitly asked the peer not to send us did they reply with?
     *  @deprecated unused
     */
    public long getLookupReplyDuplicate() { return _lookupReplyDuplicate; }
    /** how many peers that were incorrectly formatted / expired / otherwise illegal did lookups provide us with?
     *  @deprecated unused
     */
    public long getLookupReplyInvalid() { return _lookupReplyInvalid; }
    /** how many lookups this peer has sent us?
     *  @deprecated unused
     */
    public long getLookupsReceived() { return _lookupsReceived; }
    /** how frequently do they send us lookup requests?
     *  @deprecated unused
     */
    public long getAvgDelayBetweenLookupsReceived() { return _avgDelayBetweenLookupsReceived; }
    /** when did they last send us a request?
     *  @deprecated unused
     */
    public long getLastLookupReceived() { return _lastLookupReceived; }
    public long getLastLookupSuccessful() { return _lastLookupSuccessful; }
    public long getLastLookupFailed() { return _lastLookupFailed; }
    public long getLastStoreSuccessful() { return _lastStoreSuccessful; }
    public long getLastStoreFailed() { return _lastStoreFailed; }

    /** how many times have they sent us data we didn't ask for and that we've never seen? */
    public long getUnpromptedDbStoreNew() { return _unpromptedDbStoreNew; }
    /** how many times have they sent us data we didn't ask for but that we have seen? */
    public long getUnpromptedDbStoreOld() { return _unpromptedDbStoreOld; }
    /**
     * how often does the peer fail to reply to a lookup request, broken into 1 hour and 1 day periods.
     *
     */
    public RateStat getFailedLookupRate() { return _failedLookupRate; }
    
    /** not sure how much this is used, to be investigated */
    public RateStat getInvalidReplyRate() { return _invalidReplyRate; }
    
    /**
     * Note that the peer was not only able to respond to the lookup, but sent us
     * the data we wanted!
     *
     */
    public void lookupSuccessful() {
        _successfulLookups++;
        _failedLookupRate.addData(0, 0);
        _context.statManager().addRateData("peer.failedLookupRate", 0, 0);
        _lastLookupSuccessful = _context.clock().now();
    }

    /**
     * Note that the peer failed to respond to the db lookup in any way
     */
    public void lookupFailed() {
        _failedLookups++;
        _failedLookupRate.addData(1, 0);
        _context.statManager().addRateData("peer.failedLookupRate", 1, 0);
        _lastLookupFailed = _context.clock().now();
    }

    /**
     * Note that we successfully stored to a floodfill peer and verified the result
     * by asking another floodfill peer
     *
     */
    public void storeSuccessful() {
        // Fixme, redefined this to include both lookup and store fails,
        // need to fix the javadocs
        _failedLookupRate.addData(0, 0);
        _context.statManager().addRateData("peer.failedLookupRate", 0, 0);
        _lastStoreSuccessful = _context.clock().now();
    }

    /**
     * Note that floodfill verify failed
     */
    public void storeFailed() {
        // Fixme, redefined this to include both lookup and store fails,
        // need to fix the javadocs
        _failedLookupRate.addData(1, 0);
        _lastStoreFailed = _context.clock().now();
    }

    /**
     * Receive a lookup reply from the peer, where they gave us the specified info
     *
     * @param newPeers number of peers we have never seen before
     * @param oldPeers number of peers we have seen before
     * @param invalid number of peers that are invalid / out of date / otherwise b0rked
     * @param duplicate number of peers we asked them not to give us (though they're allowed to send us
     *                  themselves if they don't know anyone else)
     */
    public void lookupReply(int newPeers, int oldPeers, int invalid, int duplicate) {
        _lookupReplyNew += newPeers;
        _lookupReplyOld += oldPeers;
        _lookupReplyInvalid += invalid;
        _lookupReplyDuplicate += duplicate;
        
        if (invalid > 0) {
            _invalidReplyRate.addData(invalid, 0);
        }
    }
    /**
     * Note that the peer sent us a lookup
     *
     */
    public void lookupReceived() {
        long now = _context.clock().now();
        long delay = now - _lastLookupReceived;
        _lastLookupReceived = now;
        _lookupsReceived++;
        if (_avgDelayBetweenLookupsReceived <= 0) {
            _avgDelayBetweenLookupsReceived = delay;
        } else {
            if (delay > _avgDelayBetweenLookupsReceived)
                _avgDelayBetweenLookupsReceived = _avgDelayBetweenLookupsReceived + (delay / _lookupsReceived);
            else
                _avgDelayBetweenLookupsReceived = _avgDelayBetweenLookupsReceived - (delay / _lookupsReceived);
        }
    }
    /**
     * Note that the peer sent us a data point without us asking for it
     * @param wasNew whether we already knew about this data point or not
     */
    public void unpromptedStoreReceived(boolean wasNew) {
        if (wasNew)
            _unpromptedDbStoreNew++;
        else
            _unpromptedDbStoreOld++;
    }
    
    public void setSuccessfulLookups(long num) { _successfulLookups = num; }
    public void setFailedLookups(long num) { _failedLookups = num; }
    public void setLookupReplyNew(long num) { _lookupReplyNew = num; }
    public void setLookupReplyOld(long num) { _lookupReplyOld = num; }
    public void setLookupReplyInvalid(long num) { _lookupReplyInvalid = num; }
    public void setLookupReplyDuplicate(long num) { _lookupReplyDuplicate = num; }
    public void setLookupsReceived(long num) { _lookupsReceived = num; }
    public void setAvgDelayBetweenLookupsReceived(long ms) { _avgDelayBetweenLookupsReceived = ms; }
    public void setLastLookupReceived(long when) { _lastLookupReceived = when; }
    public void setUnpromptedDbStoreNew(long num) { _unpromptedDbStoreNew = num; }
    public void setUnpromptedDbStoreOld(long num) { _unpromptedDbStoreOld = num; }
    
    public void coalesceStats() {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Coallescing stats");
        _failedLookupRate.coalesceStats();
        _invalidReplyRate.coalesceStats();
    }
    
    private final static String NL = System.getProperty("line.separator");
    
    public void store(OutputStream out) throws IOException {
        StringBuilder buf = new StringBuilder(512);
        buf.append(NL);
        buf.append("#################").append(NL);
        buf.append("# DB history").append(NL);
        buf.append("###").append(NL);
        add(buf, "successfulLookups", _successfulLookups, "How many times have they successfully given us what we wanted when looking for it?");
        add(buf, "failedLookups", _failedLookups, "How many times have we sent them a db lookup and they didn't reply?");
        add(buf, "lookupsReceived", _lookupsReceived, "How many lookups have they sent us?");
        add(buf, "lookupReplyDuplicate", _lookupReplyDuplicate, "How many of their reply values to our lookups were something we asked them not to send us?");
        add(buf, "lookupReplyInvalid", _lookupReplyInvalid, "How many of their reply values to our lookups were invalid (expired, forged, corrupted)?");
        add(buf, "lookupReplyNew", _lookupReplyNew, "How many of their reply values to our lookups were brand new to us?");
        add(buf, "lookupReplyOld", _lookupReplyOld, "How many of their reply values to our lookups were something we had seen before?");
        add(buf, "unpromptedDbStoreNew", _unpromptedDbStoreNew, "How times have they sent us something we didn't ask for and hadn't seen before?");
        add(buf, "unpromptedDbStoreOld", _unpromptedDbStoreOld, "How times have they sent us something we didn't ask for but have seen before?");
        add(buf, "lastLookupReceived", _lastLookupReceived, "When was the last time they send us a lookup?  (milliseconds since the epoch)");
        add(buf, "avgDelayBetweenLookupsReceived", _avgDelayBetweenLookupsReceived, "How long is it typically between each db lookup they send us?  (in milliseconds)");
        out.write(buf.toString().getBytes());
        _failedLookupRate.store(out, "dbHistory.failedLookupRate");
        _invalidReplyRate.store(out, "dbHistory.invalidReplyRate");
    }
    
    private static void add(StringBuilder buf, String name, long val, String description) {
        buf.append("# ").append(name.toUpperCase()).append(NL).append("# ").append(description).append(NL);
        buf.append("dbHistory.").append(name).append('=').append(val).append(NL).append(NL);
    }
    
    
    public void load(Properties props) {
        _successfulLookups = getLong(props, "dbHistory.successfulLookups");
        _failedLookups = getLong(props, "dbHistory.failedLookups");
        _lookupsReceived = getLong(props, "dbHistory.lookupsReceived");
        _lookupReplyDuplicate = getLong(props, "dbHistory.lookupReplyDuplicate");
        _lookupReplyInvalid = getLong(props, "dbHistory.lookupReplyInvalid");
        _lookupReplyNew = getLong(props, "dbHistory.lookupReplyNew");
        _lookupReplyOld = getLong(props, "dbHistory.lookupReplyOld");
        _unpromptedDbStoreNew = getLong(props, "dbHistory.unpromptedDbStoreNew");
        _unpromptedDbStoreOld = getLong(props, "dbHistory.unpromptedDbStoreOld");
        _lastLookupReceived = getLong(props, "dbHistory.lastLookupReceived");
        _avgDelayBetweenLookupsReceived = getLong(props, "dbHistory.avgDelayBetweenLookupsReceived");
        try {
            _failedLookupRate.load(props, "dbHistory.failedLookupRate", true);
            _log.debug("Loading dbHistory.failedLookupRate");
        } catch (IllegalArgumentException iae) {
            _log.warn("DB History failed lookup rate is corrupt, resetting", iae);
        }
        
        try { 
            _invalidReplyRate.load(props, "dbHistory.invalidReplyRate", true);
        } catch (IllegalArgumentException iae) {
            _log.warn("DB History invalid reply rate is corrupt, resetting", iae);
            createRates(_statGroup);
        }
    }
    
    private void createRates(String statGroup) {
        if (_failedLookupRate == null)
            _failedLookupRate = new RateStat("dbHistory.failedLookupRate", "How often does this peer to respond to a lookup?", statGroup, new long[] { 10*60*1000l, 60*60*1000l, 24*60*60*1000l });
        if (_invalidReplyRate == null)
            _invalidReplyRate = new RateStat("dbHistory.invalidReplyRate", "How often does this peer give us a bad (nonexistant, forged, etc) peer?", statGroup, new long[] { 30*60*1000l });
        _failedLookupRate.setStatLog(_context.statManager().getStatLog());
        _invalidReplyRate.setStatLog(_context.statManager().getStatLog());
    }
    
    private final static long getLong(Properties props, String key) {
        String val = props.getProperty(key);
        if (val != null) {
            try {
                return Long.parseLong(val);
            } catch (NumberFormatException nfe) {
                return 0;
            }
        }
        return 0;
    }
}
