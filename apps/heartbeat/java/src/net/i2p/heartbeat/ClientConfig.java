package net.i2p.heartbeat;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.StringTokenizer;

import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.util.Log;

/**
 * Define the configuration for testing against one particular peer as a client
 *  
 */
public class ClientConfig {

    private static final Log _log = new Log(ClientConfig.class);

    private Destination _peer;

    private Destination _us;

    private String _statFile;

    private int _statDuration;

    private int _statFrequency;

    private int _sendFrequency;

    private int _sendSize;

    private int _numHops;

    private String _comment;

    private int _averagePeriods[];

    /**
     * @seeRoutine ClientConfig#load
     * @seeRoutine ClientConfig#store
     */
    public static final String PROP_PREFIX = "peer.";

    /**
     * @seeRoutine ClientConfig#load
     * @seeRoutine ClientConfig#store
     */
    public static final String PROP_PEER = ".peer";

    /**
     * @seeRoutine ClientConfig#load
     * @seeRoutine ClientConfig#store
     */
    public static final String PROP_STATFILE = ".statFile";

    /**
     * @seeRoutine ClientConfig#load
     * @seeRoutine ClientConfig#store
     */
    public static final String PROP_STATDURATION = ".statDuration";

    /**
     * @seeRoutine ClientConfig#load
     * @seeRoutine ClientConfig#store
     */
    public static final String PROP_STATFREQUENCY = ".statFrequency";

    /**
     * @seeRoutine ClientConfig#load
     * @seeRoutine ClientConfig#store
     */
    public static final String PROP_SENDFREQUENCY = ".sendFrequency";

    /**
     * @seeRoutine ClientConfig#load
     * @seeRoutine ClientConfig#store
     */
    public static final String PROP_SENDSIZE = ".sendSize";

    /**
     * @seeRoutine ClientConfig#load
     * @seeRoutine ClientConfig#store
     */
    public static final String PROP_COMMENT = ".comment";

    /**
     * @seeRoutine ClientConfig#load
     * @seeRoutine ClientConfig#store
     */
    public static final String PROP_AVERAGEPERIODS = ".averagePeriods";

    /**
     * Default constructor...
     */
    public ClientConfig() {
        this(null, null, null, -1, -1, -1, -1, 0, null, null);
    }

    /**
     * @param peer
     *            who we will test against
     * @param us
     *            who we are
     * @param duration
     *            how many minutes to keep events for
     * @param statFreq
     *            how often to write out stats
     * @param sendFreq
     *            how often to send pings
     * @param sendSize
     *            how large the pings should be
     * @param numHops
     *            how many hops is the current Heartbeat app using
     * @param comment
     *            describe this test
     * @param averagePeriods
     *            list of minutes to summarize over
     */
    public ClientConfig(Destination peer, Destination us, String statFile,
            int duration, int statFreq, int sendFreq, int sendSize,
            int numHops, String comment, int averagePeriods[]) {
        _peer = peer;
        _us = us;
        _statFile = statFile;
        _statDuration = duration;
        _statFrequency = statFreq;
        _sendFrequency = sendFreq;
        _sendSize = sendSize;
        _numHops = numHops;
        _comment = comment;
        _averagePeriods = averagePeriods;
    }

    /**
     * Retrieves the peer to test against
     * 
     * @return the Destination (peer)
     */
    public Destination getPeer() {
        return _peer;
    }

    /**
     * Sets the peer to test against
     * 
     * @param peer
     *            the Destination (peer)
     */
    public void setPeer(Destination peer) {
        _peer = peer;
    }

    /**
     * Retrieves who we are when we test
     * 
     * @return the Destination (us)
     */
    public Destination getUs() {
        return _us;
    }

    /**
     * Sets who we are when we test
     * 
     * @param us
     *            the Destination (us)
     */
    public void setUs(Destination us) {
        _us = us;
    }

    /**
     * Retrieves the location to write the current stats to
     * 
     * @return the name of the file
     */
    public String getStatFile() {
        return _statFile;
    }

    /**
     * Sets the name of the location we write the current stats to
     * 
     * @param statFile
     *            the name of the file
     */
    public void setStatFile(String statFile) {
        _statFile = statFile;
    }

    /**
     * Retrieves how many minutes of statistics should be maintained within the
     * window for this client
     * 
     * @return the number of minutes
     */
    public int getStatDuration() {
        return _statDuration;
    }

    /**
     * Sets how many minutes of statistics should be maintained within the
     * window for this client
     * 
     * @param durationMinutes
     *            the number of minutes
     */
    public void setStatDuration(int durationMinutes) {
        _statDuration = durationMinutes;
    }

    /**
     * Retrieves how frequently the stats are written out (in seconds)
     * 
     * @return the frequency in seconds
     */
    public int getStatFrequency() {
        return _statFrequency;
    }

    /**
     * Sets how frequently the stats are written out (in seconds)
     * 
     * @param freqSeconds
     *            the frequency in seconds
     */
    public void setStatFrequency(int freqSeconds) {
        _statFrequency = freqSeconds;
    }

    /**
     * Retrieves how frequenty we send messages to the peer (in seconds)
     * 
     * @return the frequency in seconds
     */
    public int getSendFrequency() {
        return _sendFrequency;
    }

    /**
     * Sets how frequenty we send messages to the peer (in seconds)
     * 
     * @param freqSeconds
     *            the frequency in seconds
     */
    public void setSendFrequency(int freqSeconds) {
        _sendFrequency = freqSeconds;
    }

    /**
     * Retrieves how many bytes the ping messages should be (min values ~700,
     * max ~32KB)
     * 
     * @return the size in bytes
     */
    public int getSendSize() {
        return _sendSize;
    }

    /**
     * Sets how many bytes the ping messages should be (min values ~700, max
     * ~32KB)
     * 
     * @param numBytes
     *            the size in bytes
     */
    public void setSendSize(int numBytes) {
        _sendSize = numBytes;
    }

    /**
     * Retrieves the brief, 1 line description of the test. Useful comments are
     * along the lines of "The peer is located on a fast router and connection
     * with 2 hop tunnels".
     * 
     * @return the brief comment
     */
    public String getComment() {
        return _comment;
    }

    /**
     * Sets a brief, 1 line description (comment) of the test.
     * 
     * @param comment
     *            the brief comment
     */
    public void setComment(String comment) {
        _comment = comment;
    }

    /**
     * Retrieves the periods that the client's tests should be averaged over.
     * 
     * @return list of periods (in minutes) that the data should be averaged
     *         over, or null
     */
    public int[] getAveragePeriods() {
        return _averagePeriods;
    }

    /**
     * Sets the periods that the client's tests should be averaged over.
     * 
     * @param periods
     *            the list of periods (in minutes) that the data should be
     *            averaged over, or null
     */
    public void setAveragePeriods(int periods[]) {
        _averagePeriods = periods;
    }

    /**
     * Retrieves how many hops this test engine is configured to use for its
     * outbound and inbound tunnels
     * 
     * @return the number of hops
     */
    public int getNumHops() {
        return _numHops;
    }

    /**
     * Sets how many hops this test engine is configured to use for its outbound
     * and inbound tunnels
     * 
     * @param numHops
     *            the number of hops
     */
    public void setNumHops(int numHops) {
        _numHops = numHops;
    }

    /**
     * Load the client config from the properties specified, deriving the
     * current config entry from the peer number.
     * 
     * @param clientConfig
     *            the properties to load from
     * @param peerNum
     *            the number associated with the peer
     * @return true if it was loaded correctly, false if there were errors
     */
    public boolean load(Properties clientConfig, int peerNum) {
        if ((clientConfig == null) || (peerNum < 0)) return false;
        String peerVal = clientConfig.getProperty(PROP_PREFIX + peerNum
                + PROP_PEER);
        String statFileVal = clientConfig.getProperty(PROP_PREFIX + peerNum
                + PROP_STATFILE);
        String statDurationVal = clientConfig.getProperty(PROP_PREFIX + peerNum
                + PROP_STATDURATION);
        String statFrequencyVal = clientConfig.getProperty(PROP_PREFIX
                + peerNum + PROP_STATFREQUENCY);
        String sendFrequencyVal = clientConfig.getProperty(PROP_PREFIX
                + peerNum + PROP_SENDFREQUENCY);
        String sendSizeVal = clientConfig.getProperty(PROP_PREFIX + peerNum
                + PROP_SENDSIZE);
        String commentVal = clientConfig.getProperty(PROP_PREFIX + peerNum
                + PROP_COMMENT);
        String periodsVal = clientConfig.getProperty(PROP_PREFIX + peerNum
                + PROP_AVERAGEPERIODS);

        if ((peerVal == null) || (statFileVal == null)
                || (statDurationVal == null) || (statFrequencyVal == null)
                || (sendFrequencyVal == null) || (sendSizeVal == null)) {
            if (_log.shouldLog(Log.DEBUG)) {
                _log.debug("Peer number " + peerNum + " does not exist");
            }
            return false;
        }

        try {
            int duration = getInt(statDurationVal);
            int statFreq = getInt(statFrequencyVal);
            int sendFreq = getInt(sendFrequencyVal);
            int sendSize = getInt(sendSizeVal);

            if ((duration <= 0) || (statFreq <= 0) || (sendFreq <= 0)
                    || (sendSize <= 0)) {
                if (_log.shouldLog(Log.WARN)) {
                    _log.warn("Invalid client config: duration ["
                            + statDurationVal + "] stat frequency ["
                            + statFrequencyVal + "] send frequency ["
                            + sendFrequencyVal + "] send size [" + sendSizeVal
                            + "]");
                }
                return false;
            }

            statFileVal = statFileVal.trim();
            if (statFileVal.length() <= 0) {
                if (_log.shouldLog(Log.WARN)) {
                    _log.warn("Stat file is blank for peer " + peerNum);
                }
                return false;
            }

            Destination d = new Destination();
            d.fromBase64(peerVal);

            if (commentVal == null) {
                commentVal = "";
            }

            commentVal = commentVal.trim();
            commentVal = commentVal.replace('\n', '_');

            List periods = new ArrayList(4);
            if (periodsVal != null) {
                StringTokenizer tok = new StringTokenizer(periodsVal);
                while (tok.hasMoreTokens()) {
                    String periodVal = tok.nextToken();
                    int minutes = getInt(periodVal);
                    if (minutes > 0) {
                        periods.add(new Integer(minutes));
                    }
                }
            }
            int avgPeriods[] = new int[periods.size()];
            for (int i = 0; i < periods.size(); i++) {
                avgPeriods[i] = ((Integer) periods.get(i)).intValue();
            }

            _comment = commentVal;
            _statDuration = duration;
            _statFrequency = statFreq;
            _sendFrequency = sendFreq;
            _sendSize = sendSize;
            _statFile = statFileVal;
            _peer = d;
            _averagePeriods = avgPeriods;
            return true;
        } catch (DataFormatException dfe) {
            _log.error("Peer destination for " + peerNum + " was invalid: "
                    + peerVal);
            return false;
        }
    }

    /**
     * Store the client config to the properties specified, deriving the current
     * config entry from the peer number.
     * 
     * @param clientConfig
     *            the properties to store to
     * @param peerNum
     *            the number associated with the peer
     * @return true if it was stored correctly, false if there were errors
     */
    public boolean store(Properties clientConfig, int peerNum) {
        if ((_peer == null) || (_sendFrequency <= 0) || (_sendSize <= 0)
                || (_statDuration <= 0) || (_statFrequency <= 0)
                || (_statFile == null)) { return false; }

        String comment = _comment;
        if (comment == null) {
            comment = "";
        }

        comment = comment.trim();
        comment = comment.replace('\n', '_');

        StringBuffer buf = new StringBuffer(32);
        if (_averagePeriods != null) {
            for (int i = 0; i < _averagePeriods.length; i++) {
                buf.append(_averagePeriods[i]).append(' ');
            }
        }

        clientConfig.setProperty(PROP_PREFIX + peerNum + PROP_PEER, _peer
                .toBase64());
        clientConfig.setProperty(PROP_PREFIX + peerNum + PROP_STATFILE,
                _statFile);
        clientConfig.setProperty(PROP_PREFIX + peerNum + PROP_STATDURATION,
                _statDuration + "");
        clientConfig.setProperty(PROP_PREFIX + peerNum + PROP_STATFREQUENCY,
                _statFrequency + "");
        clientConfig.setProperty(PROP_PREFIX + peerNum + PROP_SENDFREQUENCY,
                _sendFrequency + "");
        clientConfig.setProperty(PROP_PREFIX + peerNum + PROP_SENDSIZE,
                _sendSize + "");
        clientConfig.setProperty(PROP_PREFIX + peerNum + PROP_COMMENT, comment);
        clientConfig.setProperty(PROP_PREFIX + peerNum + PROP_AVERAGEPERIODS,
                buf.toString());
        return true;
    }

    private static final int getInt(String val) {
        if (val == null) return -1;
        try {
            int i = Integer.parseInt(val);
            return i;
        } catch (NumberFormatException nfe) {
            if (_log.shouldLog(Log.DEBUG)) {
                _log.debug("Value [" + val + "] is not a valid integer");
            }
            return -1;
        }
    }
}