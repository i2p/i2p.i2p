package net.i2p.heartbeat.gui;

import java.util.HashMap;
import java.util.Map;

import net.i2p.heartbeat.ClientConfig;
import net.i2p.heartbeat.PeerData;

/**
 * Raw data points for a test
 */
class StaticPeerData extends PeerData {
    private int _pending;
    /** Integer (period, in minutes) to Integer (milliseconds) for sending a ping */
    private Map _averageSendTimes;
    /** Integer (period, in minutes) to Integer (milliseconds) for receiving a pong */
    private Map _averageReceiveTimes;
    /** Integer (period, in minutes) to Integer (num messages) of how many messages were lost on average */
    private Map _lostMessages;
    
    /**
     * Creates a static peer data with a specified client config ... duh
     * @param config the client config
     */
    public StaticPeerData(ClientConfig config) {
        super(config);
        _averageSendTimes = new HashMap(4);
        _averageReceiveTimes = new HashMap(4);
        _lostMessages = new HashMap(4);
    }
    
    /**
     * Adds averaged data
     * @param minutes the minutes (averaged over)
     * @param sendMs the send time (ping) in milliseconds
     * @param recvMs the receive time (pong) in milliseconds
     * @param lost the number lost
     */
    public void addAverage(int minutes, int sendMs, int recvMs, int lost) {
        _averageSendTimes.put(new Integer(minutes), new Integer(sendMs));
        _averageReceiveTimes.put(new Integer(minutes), new Integer(recvMs));
        _lostMessages.put(new Integer(minutes), new Integer(lost));
    }
    
    /**
     * Sets the number pending
     * @param numPending the number pending
     */
    public void setPendingCount(int numPending) { _pending = numPending; }

    /* (non-Javadoc)
     * @see net.i2p.heartbeat.PeerData#setSessionStart(long)
     */
    public void setSessionStart(long when) { super.setSessionStart(when); }
    
    /**
     * Adds data
     * @param sendTime the time it was sent
     * @param sendMs the send time (ping) in milliseconds
     * @param recvMs the receive time (pong) in milliseconds
     */
    public void addData(long sendTime, int sendMs, int recvMs) {
        PeerData.EventDataPoint dataPoint = new PeerData.EventDataPoint(sendTime);
        dataPoint.setPongSent(sendTime + sendMs);
        dataPoint.setPongReceived(sendTime + sendMs + recvMs);
        dataPoint.setWasPonged(true);
        addDataPoint(dataPoint);
    }
    
    /**
     * Adds data
     * @param sendTime the time it was sent
     */
    public void addData(long sendTime) {
        PeerData.EventDataPoint dataPoint = new PeerData.EventDataPoint(sendTime);
        dataPoint.setWasPonged(false);
        addDataPoint(dataPoint);
    }

    /** 
     * how many pings are still outstanding?
     * @return the number of pings outstanding
     */
    public int getPendingCount() { return _pending; }
    
    
    /** 
     * average time to send over the given period.
     *
     * @param period number of minutes to retrieve the average for
     * @return milliseconds average, or -1 if we dont track that period
     */
    public double getAverageSendTime(int period) { 
        Integer i = (Integer)_averageSendTimes.get(new Integer(period));
        if (i == null)
            return -1;

        return i.doubleValue();
    }
    
    
    /** 
     * average time to receive over the given period.
     *
     * @param period number of minutes to retrieve the average for
     * @return milliseconds average, or -1 if we dont track that period
     */
    public double getAverageReceiveTime(int period) {
        Integer i = (Integer)_averageReceiveTimes.get(new Integer(period));
        if (i == null)
            return -1;

        return i.doubleValue();
    }
       
    /** 
     * number of lost messages over the given period.
     *
     * @param period number of minutes to retrieve the average for
     * @return number of lost messages in the period, or -1 if we dont track that period
     */
    public double getLostMessages(int period) {
        Integer i = (Integer)_lostMessages.get(new Integer(period));
        if (i == null)
            return -1;

        return i.doubleValue();
    }
        
    /* (non-Javadoc)
     * @see net.i2p.heartbeat.PeerData#cleanup()
     */
    public void cleanup() {}
}