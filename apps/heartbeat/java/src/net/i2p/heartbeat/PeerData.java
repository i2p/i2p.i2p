package net.i2p.heartbeat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;
import net.i2p.util.Clock;
import net.i2p.util.Log;

/**
 * Contain the current window of data for a particular series of ping/pong stats 
 * sent to a peer.  This should be periodically kept clean by calling cleanup()
 * to timeout expired pings and to drop data outside the window.
 *
 */
public class PeerData {
    private final static Log _log = new Log(PeerData.class);
    /** peer / sequence / config in this data series */
    private ClientConfig _peer;
    /** date sent (Long) to EventDataPoint containing the datapoints sent in the current period */
    private Map _dataPoints;
    /** date sent (Long) to EventDataPoint containing pings that haven't yet timed out or been ponged */
    private TreeMap _pendingPings;
    private long _sessionStart;
    private long _lifetimeSent;
    private long _lifetimeReceived;
    /** rate averaging the time to send over a variety of periods */
    private RateStat _sendRate;
    /** rate averaging the time to receive over a variety of periods */
    private RateStat _receiveRate;
    /** rate averaging the frequency of lost messages over a variety of periods */
    private RateStat _lostRate;

    /** how long we wait before timing out pending pings (30 seconds) */
    private static final long TIMEOUT_PERIOD = 60 * 1000;

    /** synchronize on this when updating _dataPoints or _pendingPings */
    private Object _updateLock = new Object();

    /**
     * Creates a PeerData . . .
     * @param config configuration to load from
     */
    public PeerData(ClientConfig config) {
        _peer = config;
        _dataPoints = new TreeMap();
        _pendingPings = new TreeMap();
        _sessionStart = Clock.getInstance().now();
        _lifetimeSent = 0;
        _lifetimeReceived = 0;
        _sendRate = new RateStat("sendRate", "How long it takes to send", "peer",
                                 getPeriods(config.getAveragePeriods()));
        _receiveRate = new RateStat("receiveRate", "How long it takes to receive", "peer",
                                    getPeriods(config.getAveragePeriods()));
        _lostRate = new RateStat("lostRate", "How frequently we lose messages", "peer",
                                 getPeriods(config.getAveragePeriods()));
    }

    /**
     * turn the periods (# minutes) into rate periods (# milliseconds)
     * @param periods (in minutes)
     * @return an array of periods (in milliseconds)
     */
    private static long[] getPeriods(int periods[]) {
        long rv[] = null;
        if (periods == null) periods = new int[0];
        rv = new long[periods.length];
        for (int i = 0; i < periods.length; i++)
            rv[i] = (long) periods[i] * 60 * 1000; // they're in minutes
        Arrays.sort(rv);
        return rv;
    }

    /** 
     * how many pings are still outstanding?
     * @return the number of pings outstanding
     */
    public int getPendingCount() {
        synchronized (_updateLock) {
            return _pendingPings.size();
        }
    }

    /**
     * how many data points are available in the current window?
     * @return the number of datapoints available 
     */
    public int getDataPointCount() {
        synchronized (_updateLock) {
            return _dataPoints.size();
        }
    }

    /**
     * when did this test begin?
     * @return when the test began  
     */
    public long getSessionStart() { return _sessionStart; }
    
    /**
     * sets when the test began
     * @param when when it began
     */
    public void setSessionStart(long when) { _sessionStart = when; }

    /**
     * how many pings have we sent for this test?
     * @return the number of pings sent
     */
    public long getLifetimeSent() { return _lifetimeSent; }

    /**
     * how many pongs have we received for this test?
     * @return the number of pings received
     */
    public long getLifetimeReceived() { return _lifetimeReceived; }

    /**
     * @return the client configuration
     */
    public ClientConfig getConfig() {
        return _peer;
    }

    /** 
     * What periods are we averaging the data over (in minutes)?
     * @return the periods as an array of ints (in minutes)
     */
    public int[] getAveragePeriods() {
        return (_peer.getAveragePeriods() != null ? _peer.getAveragePeriods() : new int[0]);
    }

    /** 
     * average time to send over the given period.
     *
     * @param period number of minutes to retrieve the average for
     * @return milliseconds average, or -1 if we dont track that period
     */
    public double getAverageSendTime(int period) {
        return getAverage(_sendRate, period);
    }

    /** 
     * average time to receive over the given period.
     *
     * @param period number of minutes to retrieve the average for
     * @return milliseconds average, or -1 if we dont track that period
     */
    public double getAverageReceiveTime(int period) {
        return getAverage(_receiveRate, period);
    }

    /** 
     * number of lost messages over the given period.
     *
     * @param period number of minutes to retrieve the average for
     * @return number of lost messages in the period, or -1 if we dont track that period
     */
    public double getLostMessages(int period) {
        Rate rate = _lostRate.getRate(period * 60 * 1000);
        if (rate == null) return -1;
        return rate.getCurrentTotalValue();
    }

    private double getAverage(RateStat stat, int period) {
        Rate rate = stat.getRate(period * 60 * 1000);
        if (rate == null) return -1;
        return rate.getAverageValue();
    }

    /**
     * Return an ordered list of data points in the current window (after doing a cleanup)
     *
     * @return list of EventDataPoint objects
     */
    public List getDataPoints() {
        cleanup();
        synchronized (_updateLock) {
            return new ArrayList(_dataPoints.values());
        }
    }

    /**
     * We have sent the peer a ping on this series (using the send time as given)
     * @param dateSent when the ping was sent
     */
    public void addPing(long dateSent) {
        EventDataPoint sent = new EventDataPoint(dateSent);
        synchronized (_updateLock) {
            _pendingPings.put(new Long(dateSent), sent);
        }
        _lifetimeSent++;
    }

    /** 
     * we have received a pong from the peer on this series 
     * 
     * @param dateSent when we sent the ping
     * @param pongSent when the peer received the ping and sent the pong
     */
    public void pongReceived(long dateSent, long pongSent) {
        long now = Clock.getInstance().now();
        synchronized (_updateLock) {
            if (_pendingPings.size() <= 0) {
                _log.warn("Pong received (sent at " + dateSent + ", " + (now-dateSent) 
                          + "ms ago, pong delay " + (pongSent-dateSent) + "ms, pong receive delay "
                          + (now-pongSent) + "ms)");
                return;
            }
            Long first = (Long)_pendingPings.firstKey();
            EventDataPoint data = (EventDataPoint)_pendingPings.remove(new Long(dateSent));
            
            if (data != null) {
                data.setPongReceived(now);
                data.setPongSent(pongSent);
                data.setWasPonged(true);
                locked_addDataPoint(data);
                
                if (dateSent != first.longValue()) {
                    _log.error("Out of order delivery: received " + dateSent 
                               + " but the first pending is " + first.longValue()
                               + " (delta " + (dateSent - first.longValue()) + ")");
                } else {
                    _log.info("In order delivery for " + dateSent + " in ping " 
                               + _peer.getComment());
                }
            } else {
                _log.warn("Pong received, but no matching ping?  ping sent at = " + dateSent);
                return;
            }
        }
        _sendRate.addData(pongSent - dateSent, 0);
        _receiveRate.addData(now - pongSent, 0);
        _lifetimeReceived++;
    }
    
    protected void addDataPoint(EventDataPoint data) {
        synchronized (_updateLock) {
            locked_addDataPoint(data);
        }
    }
    
    private void locked_addDataPoint(EventDataPoint data) {
        Object val = _dataPoints.put(new Long(data.getPingSent()), data);
        if (val != null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Duplicate data point received: " + data);
        }
    }

    /** 
     * drop all datapoints outside the window we're watching, and timeout all
     * pending pings not ponged in the TIMEOUT_PERIOD, both updating the lost message
     * rate and coallescing all of the rates.
     *
     */
    public void cleanup() {
        long dropBefore = Clock.getInstance().now() - _peer.getStatDuration() * 60 * 1000;
        long timeoutBefore = Clock.getInstance().now() - TIMEOUT_PERIOD;
        long numDropped = 0;
        long numTimedOut = 0;

        synchronized (_updateLock) {
            numDropped = locked_dropExpired(dropBefore);
            numTimedOut = locked_timeoutPending(timeoutBefore);
        }

        _lostRate.addData(numTimedOut, 0);

        _receiveRate.coallesceStats();
        _sendRate.coallesceStats();
        _lostRate.coallesceStats();

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Peer data cleaned up " + numTimedOut + " timed out pings and removed " + numDropped
                       + " old entries");
    }
    
    /**
     * Drop all data points that are already too old for us to be interested in
     *
     * @param when the earliest ping send time we care about
     * @return number of data points dropped
     */
    private int locked_dropExpired(long when) {
        Set toDrop = new HashSet(4);
        // drop the failed and really old
        for (Iterator iter = _dataPoints.keySet().iterator(); iter.hasNext(); ) {
            Long pingTime = (Long)iter.next();
            if (pingTime.longValue() < when)
                toDrop.add(pingTime);
        }
        for (Iterator iter = toDrop.iterator(); iter.hasNext(); ) {
            _dataPoints.remove(iter.next());
        }
        return toDrop.size();
    }
    
    /** 
     * timeout and remove all pings that were sent before the given time, 
     * moving them from the set of pending pings to the set of data points
     *
     * @param when the earliest ping send time we care about
     * @return number of pings timed out
     */
    private int locked_timeoutPending(long when) {
        Set toDrop = new HashSet(4);
        for (Iterator iter = _pendingPings.keySet().iterator(); iter.hasNext(); ) {
            Long pingTime = (Long)iter.next();
            if (pingTime.longValue() < when) {
                toDrop.add(pingTime);
                EventDataPoint point = (EventDataPoint)_pendingPings.get(pingTime);
                point.setWasPonged(false);
                locked_addDataPoint(point);
            }
        }
        for (Iterator iter = toDrop.iterator(); iter.hasNext(); ) {
            _pendingPings.remove(iter.next());
        }
        return toDrop.size();
    }

    /** actual data point for the peer */
    public class EventDataPoint {
        private boolean _wasPonged;
        private long _pingSent;
        private long _pongSent;
        private long _pongReceived;

        /**
         * Creates an EventDataPoint
         */
        public EventDataPoint() { this(-1); }

        /**
         * Creates an EventDataPoint with pingtime associated with it =)
         * @param pingSentOn the time a ping was sent
         */
        public EventDataPoint(long pingSentOn) {
            _wasPonged = false;
            _pingSent = pingSentOn;
            _pongSent = -1;
            _pongReceived = -1;
        }

        /** 
         * when did we send this ping?
         * @return the time the ping was sent
         */
        public long getPingSent() { return _pingSent; }
        
        /**
         * sets when we sent this ping
         * @param when when we sent the ping
         */
        public void setPingSent(long when) { _pingSent = when; }

        /**
         * when did the peer receive the ping?
         * @return the time the ping was receieved 
         */
        public long getPongSent() {
            return _pongSent;
        }

        /**
         * Set the time the peer received the ping
         * @param when the time to set
         */
        public void setPongSent(long when) {
            _pongSent = when;
        }

        /** 
         * when did we receive the peer's pong?
         * @return the time we receieved the pong
         */
        public long getPongReceived() {
            return _pongReceived;
        }

        /**
         * Set the time the peer's pong was receieved
         * @param when the time to set
         */
        public void setPongReceived(long when) {
            _pongReceived = when;
        }

        /** 
         * did the peer reply in time? 
         * @return true or false, whether we got a reply in time */
        public boolean getWasPonged() {
            return _wasPonged;
        }

        /**
         * Set whether we receieved the peer's reply in time
         * @param pong true or false
         */
        public void setWasPonged(boolean pong) {
            _wasPonged = pong;
        }
    }
}