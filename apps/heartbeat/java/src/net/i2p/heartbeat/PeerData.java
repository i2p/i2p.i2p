package net.i2p.heartbeat;

import net.i2p.util.Clock;
import net.i2p.util.Log;
import net.i2p.stat.Rate;
import net.i2p.stat.RateStat;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
    private Map _pendingPings;
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
    private static final long TIMEOUT_PERIOD = 30*1000;
    
    /** synchronize on this when updating _dataPoints or _pendingPings */
    private Object _updateLock = new Object();
    
    public PeerData(ClientConfig config) {
	_peer = config;
	_dataPoints = new TreeMap();
	_pendingPings = new TreeMap();
	_sessionStart = Clock.getInstance().now();
	_lifetimeSent = 0;
	_lifetimeReceived = 0;
	_sendRate = new RateStat("sendRate", "How long it takes to send", "peer", getPeriods(config.getAveragePeriods()));
	_receiveRate = new RateStat("receiveRate", "How long it takes to receive", "peer", getPeriods(config.getAveragePeriods()));
	_lostRate = new RateStat("lostRate", "How frequently we lose messages", "peer", getPeriods(config.getAveragePeriods()));
    }
    
    /** turn the periods (# minutes) into rate periods (# milliseconds) */
    private static long[] getPeriods(int periods[]) {
	long rv[] = null;
	if (periods == null) periods = new int[0];
	rv = new long[periods.length];
	for (int i = 0; i < periods.length; i++)
	    rv[i] = (long)periods[i] * 60*1000; // they're in minutes
	Arrays.sort(rv);
	return rv;
    }
    
    /** how many pings are still outstanding? */
    public int getPendingCount() { synchronized (_updateLock) { return _pendingPings.size(); } }
    /** how many data points are available in the current window? */
    public int getDataPointCount() { synchronized (_updateLock) { return _dataPoints.size(); } }
    /** when did this test begin? */
    public long getSessionStart() { return _sessionStart; }
    /** how many pings have we sent for this test? */
    public long getLifetimeSent() { return _lifetimeSent; }
    /** how many pongs have we received for this test? */
    public long getLifetimeReceived() { return _lifetimeReceived; }
    public ClientConfig getConfig() { return _peer; }
    
    /** 
     * What periods are we averaging the data over (in minutes)?
     */
    public int[] getAveragePeriods() { return (_peer.getAveragePeriods() != null ? _peer.getAveragePeriods() : new int[0]); }
    /** 
     * average time to send over the given period.
     *
     * @param period number of minutes to retrieve the average for
     * @return milliseconds average, or -1 if we dont track that period
     */
    public double getAverageSendTime(int period) { return getAverage(_sendRate, period); }
    /** 
     * average time to receive over the given period.
     *
     * @param period number of minutes to retrieve the average for
     * @return milliseconds average, or -1 if we dont track that period
     */
    public double getAverageReceiveTime(int period) { return getAverage(_receiveRate, period); }
    /** 
     * number of lost messages over the given period.
     *
     * @param period number of minutes to retrieve the average for
     * @return number of lost messages in the period, or -1 if we dont track that period
     */
    public double getLostMessages(int period) { 
	Rate rate = _lostRate.getRate(period * 60*1000);
	if (rate == null)
	    return -1;
	return rate.getCurrentTotalValue();
    }
    
    private double getAverage(RateStat stat, int period) {
	Rate rate = stat.getRate(period * 60*1000);
	if (rate == null)
	    return -1;
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
     *
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
	    EventDataPoint data = (EventDataPoint)_pendingPings.remove(new Long(dateSent));
	    if (data != null) {
		data.setPongReceived(now);
		data.setPongSent(pongSent);
		data.setWasPonged(true);
		_dataPoints.put(new Long(dateSent), data);
	    }
	}
	_sendRate.addData(pongSent-dateSent, 0);
	_receiveRate.addData(now-pongSent, 0);
	_lifetimeReceived++;
    }
    
    /** 
     * drop all datapoints outside the window we're watching, and timeout all
     * pending pings not ponged in the TIMEOUT_PERIOD, both updating the lost message
     * rate and coallescing all of the rates.
     *
     */
    public void cleanup() {
	long dropBefore = Clock.getInstance().now() - _peer.getStatDuration() * 60*1000;
	long timeoutBefore = Clock.getInstance().now() - TIMEOUT_PERIOD;
	long numDropped = 0;
	long numTimedOut = 0;
	
	synchronized (_updateLock) {
	    List toTimeout = new ArrayList(4);
	    List toDrop = new ArrayList(4);
	    for (Iterator iter = _pendingPings.keySet().iterator(); iter.hasNext(); ) {
		Long when = (Long)iter.next();
		if (when.longValue() < dropBefore)
		    toDrop.add(when);
		else if (when.longValue() < timeoutBefore)
		    toTimeout.add(when);
		else
		    break; // its ordered, so once we are past timeoutBefore, no need
	    }
	    for (Iterator iter = toDrop.iterator(); iter.hasNext(); ) {
		_pendingPings.remove(iter.next());
	    }
	    
	    List toAdd = new ArrayList(toTimeout.size());
	    for (Iterator iter = toTimeout.iterator(); iter.hasNext(); ) {
		Long when = (Long)iter.next();
		EventDataPoint data = (EventDataPoint)_pendingPings.remove(when);
		data.setWasPonged(false);
		toAdd.add(data);
	    }
	    
	    numDropped = toDrop.size();
	    numTimedOut = toDrop.size();
	    toDrop.clear();
	    
	    for (Iterator iter = _dataPoints.keySet().iterator(); iter.hasNext(); ) {
		Long when = (Long)iter.next();
		if (when.longValue() < dropBefore)
		    toDrop.add(when);
		else
		    break; // ordered
	    }
	    for (Iterator iter = toDrop.iterator(); iter.hasNext(); ) {
		_dataPoints.remove(iter.next());
	    }
	    
	    numDropped += toDrop.size();
	    
	    for (Iterator iter = toAdd.iterator(); iter.hasNext(); ) {
		EventDataPoint data = (EventDataPoint)iter.next();
		_dataPoints.put(new Long(data.getPingSent()), data);
	    }
	    
	    numTimedOut += toAdd.size();
	}
	
	_lostRate.addData(numTimedOut, 0);
	
	_receiveRate.coallesceStats();
	_sendRate.coallesceStats();
	_lostRate.coallesceStats();
	
	if (_log.shouldLog(Log.DEBUG))
	    _log.debug("Peer data cleaned up " + numTimedOut + " timed out pings and removed " + numDropped + " old entries");
    }
    
    /** actual data point for the peer */
    public class EventDataPoint {
	private boolean _wasPonged;
	private long _pingSent;
	private long _pongSent;
	private long _pongReceived;
	
	public EventDataPoint() {
	    this(-1);
	}
	public EventDataPoint(long pingSentOn) {
	    _wasPonged = false;
	    _pingSent = pingSentOn;
	    _pongSent = -1;
	    _pongReceived = -1;
	}
	
	/** when did we send this ping? */
	public long getPingSent() { return _pingSent; }
	public void setPingSent(long when) { _pingSent = when; }
	
	/** when did the peer receive the ping? */
	public long getPongSent() { return _pongSent; }
	public void setPongSent(long when) { _pongSent = when; }
	
	/** when did we receive the peer's pong? */
	public long getPongReceived() { return _pongReceived; }
	public void setPongReceived(long when) { _pongReceived = when; }
	
	/** did the peer reply in time? */
	public boolean getWasPonged() { return _wasPonged; }
	public void setWasPonged(boolean pong) { _wasPonged = pong; }
    }
}