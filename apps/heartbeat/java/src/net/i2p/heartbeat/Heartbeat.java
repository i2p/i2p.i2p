package net.i2p.heartbeat;

import net.i2p.util.Log;
import net.i2p.util.Clock;
import net.i2p.data.Destination;

import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Iterator;
import java.util.Date;

/**
 * Main driver for the heartbeat engine, loading 0 or more tests, firing
 * up a ClientEngine for each, and serving as a pong server.  If there isn't
 * a configuration file, or if the configuration file doesn't specify any tests,
 * it simply sits around as a pong server, passively responding to whatever is 
 * sent its way.  <p />
 *
 * The config file format is examplified below:
 * <pre>
 *    # where the router is located (default is localhost)
 *    i2cpHost=localhost
 *    # I2CP port for the router (default is 7654)
 *    i2cpPort=4001
 *    # How many hops we want the router to put in our tunnels (default is 2)
 *    numHops=2
 *    # where our private destination keys are located - if this doesn't exist,
 *    # a new one will be created and saved there (by default, heartbeat.keys)
 *    privateDestinationFile=heartbeat_r2.keys
 *
 *    ## peer tests configured below:
 *    
 *    # destination peer for test 0
 *    peer.0.peer=[destination in base64]
 *    # where will we write out the stat data?
 *    peer.0.statFile=heartbeatStat_khWY_30s_1kb.txt
 *    # how many minutes will we keep stats for?
 *    peer.0.statDuration=30
 *    # how often will we write out new stat data (in seconds)?
 *    peer.0.statFrequency=60
 *    # how often will we send a ping to the peer (in seconds)?
 *    peer.0.sendFrequency=30
 *    # how many bytes will be included in the ping?
 *    peer.0.sendSize=1024
 *    # take a guess...
 *    peer.0.comment=Test with localhost sending 1KB of data every 30 seconds
 *    # we can keep track of a few moving averages - this value includes a whitespace
 *    # delimited list of numbers, each specifying a period to calculate the average
 *    # over (in minutes)
 *    peer.0.averagePeriods=1 5 30
 *    ## repeat the peer.0.* for as many tests as desired, incrementing as necessary
 * </pre>
 *
 */
public class Heartbeat  {
    private static final Log _log = new Log(Heartbeat.class);
    /** location containing this heartbeat's config */
    private String _configFile;
    /** clientNum (Integer) to ClientConfig mapping */
    private Map _clientConfigs;
    /** series num (Integer) to ClientEngine mapping */
    private Map _clientEngines;
    /** helper class for managing our I2P send/receive and message formatting */
    private I2PAdapter _adapter;
    /** our own callback that the I2PAdapter notifies on ping or pong messages */
    private PingPongAdapter _eventAdapter;
    
    /** if there are no command line arguments, load the config from "heartbeat.config" */
    public static final String CONFIG_FILE_DEFAULT = "heartbeat.config";
    
    /** build up a new heartbeat manager, but don't actually do anything */
    public Heartbeat(String configFile) {
	_configFile = configFile;
	_clientConfigs = new HashMap();
	_clientEngines = new HashMap();
	_eventAdapter = new PingPongAdapter();
	_adapter = new I2PAdapter();
	_adapter.setListener(_eventAdapter);
    }
    private Heartbeat() {}
    
    /** load up the config data (but don't build any engines or start them up) */
    public void loadConfig() {
	Properties props = new Properties();
	FileInputStream fin = null;
	File configFile = new File (_configFile);
	if (configFile.exists()) {
	    try {
		fin = new FileInputStream(_configFile);
		props.load(fin);
	    } catch (IOException ioe) {
		if (_log.shouldLog(Log.ERROR))
		    _log.error("Error reading the config data", ioe);
	    } finally {
		if (fin != null) try { fin.close(); } catch (IOException ioe) {}
	    }
	}
	
	loadBaseConfig(props);
	loadClientConfigs(props);
    }
    
    
    /** 
     * send a ping message to the peer
     *
     * @param peer peer to ping
     * @param seriesNum id used to keep track of multiple pings (of different size/frequency) to a peer
     * @param now current time to be sent in the ping (so we can watch for it in the pong)
     * @param size total message size to send
     */
    void sendPing(Destination peer, int seriesNum, long now, int size) {
	if (_adapter.getIsConnected())
	    _adapter.sendPing(peer, seriesNum, now, size);
    }
    
    /** load up the base data (I2CP config, etc) */
    private void loadBaseConfig(Properties props) {
	_adapter.loadConfig(props);
    }
    
    /** load up all of the test config data */
    private void loadClientConfigs(Properties props) {
	int i = 0;
	while (true) {
	    ClientConfig config = new ClientConfig();
	    if (!config.load(props, i))
		break;
	    _clientConfigs.put(new Integer(i), config);
	    i++;
	}
    }

    /** connect to the network */
    private void connect() {
	boolean connected = _adapter.connect();
	if (!connected)
	    _log.error("Unable to connect to the router");
    }
    /** disconnect from the network */
    private void disconnect() {
	_adapter.disconnect();
    }
    
    /** start up all of the tests */
    public void startEngines() {
	for (Iterator iter = _clientConfigs.values().iterator(); iter.hasNext(); ) {
	    ClientConfig config = (ClientConfig)iter.next();
	    ClientEngine engine = new ClientEngine(this, config);
	    config.setUs(_adapter.getLocalDestination());
	    config.setNumHops(_adapter.getNumHops());
	    _clientEngines.put(new Integer(engine.getSeriesNum()), engine);
	    engine.startEngine();
	}
    }
    /** stop all of the tests */
    public void stopEngines() {
	for (Iterator iter = _clientEngines.values().iterator(); iter.hasNext(); ) {
	    ClientEngine engine = (ClientEngine)iter.next();
	    engine.stopEngine();
	}
	_clientEngines.clear();
    }
    
    /**
     * Fire up a new heartbeat system, waiting until, well, forever.  Builds
     * a new heartbeat system, loads the config, connects to the network, starts
     * the engines, and then sits back and relaxes, responding to any pings and
     * running any tests. <p />
     *
     * <code> <b>Usage: </b> Heartbeat [<i>configFileName</i>]</code> <p />
     */
    public static void main(String args[]) {
	String configFile = CONFIG_FILE_DEFAULT;
	if (args.length == 1)
	    configFile = args[0];
	
	if (_log.shouldLog(Log.INFO))
	    _log.info("Starting up with config file " + configFile);
	Heartbeat heartbeat = new Heartbeat(configFile);
	heartbeat.loadConfig();
	heartbeat.connect();
	heartbeat.startEngines();
	Object o = new Object();
	while (true) {
	    try {
		synchronized (o) {
		    o.wait();
		}
	    } catch (InterruptedException ie) {}
	}
    }
 
    /**
     * Receive event notification from the I2PAdapter
     *
     */
    private class PingPongAdapter implements I2PAdapter.PingPongEventListener {
	/**
	 * We were pinged, so always just send a pong back.
	 * 
	 * @param from who sent us the ping?
	 * @param seriesNum what series did the sender specify?
	 * @param sentOn when did the sender say they sent their ping?
	 * @param data arbitrary payload data
	 */
	public void receivePing(Destination from, int seriesNum, Date sentOn, byte[] data) {
	    if (_adapter.getIsConnected())
		_adapter.sendPong(from, seriesNum, sentOn, data);
	}

	/**
	 * We received a pong, so find the right client engine and tell it about the pong.
	 *
	 * @param from who sent us the pong
	 * @param seriesNum our client ID
	 * @param sentOn when did we send the ping?
	 * @param replyOn when did they send their pong?
	 * @param data the arbitrary data we sent in the ping (that they sent back in the pong)
	 */
	public void receivePong(Destination from, int seriesNum, Date sentOn, Date replyOn, byte[] data) {
	    ClientEngine engine = (ClientEngine)_clientEngines.get(new Integer(seriesNum));
	    if (engine.getPeer().equals(from))
		engine.receivePong(sentOn.getTime(), replyOn.getTime());
	}
    }
    
}