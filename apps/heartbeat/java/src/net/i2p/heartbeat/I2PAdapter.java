package net.i2p.heartbeat;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import java.util.Properties;
import java.util.Date;
import java.util.Arrays;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.I2PSessionListener;
import net.i2p.I2PException;

import net.i2p.data.Destination;
import net.i2p.util.Log;
import net.i2p.util.Clock;

/**
 * Tie-in to the I2P SDK for the Heartbeat system, talking to the I2PSession and
 * dealing with the raw ping and pong messages.
 *
 */
class I2PAdapter {
    private final static Log _log = new Log(I2PAdapter.class);
    /** I2CP host */
    private String _i2cpHost;
    /** I2CP port */
    private int _i2cpPort;
    /** how long do we want our tunnels to be? */
    private int _numHops;
    /** filename containing the heartbeat engine's private destination info */
    private String _privateDestFile;
    /** our destination */
    private Destination _localDest;
    /** who do we tell? */
    private PingPongEventListener _listener;
    /** how do we talk to the router */
    private I2PSession _session;
    /** object that receives our i2cp notifications from the session and tells us */
    private I2PListener _i2pListener;

    /** 
     * This config property tells us where the private destination data for our 
     * connection (or if it doesn't exist, where will we save it)
     */
    private static final String DEST_FILE_PROP = "privateDestinationFile";
    /** by default, the private destination data is in "heartbeat.keys" */
    private static final String DEST_FILE_DEFAULT = "heartbeat.keys";
    /** This config property defines where the I2P router is */
    private static final String I2CP_HOST_PROP = "i2cpHost";
    /** by default, the I2P host is "localhost" */
    private static final String I2CP_HOST_DEFAULT = "localhost";
    /** This config property defines the I2CP port on the router */
    private static final String I2CP_PORT_PROP = "i2cpPort";
    /** by default, the I2CP port is 7654 */
    private static final int I2CP_PORT_DEFAULT = 7654;
    
    /** This property defines how many hops we want in our tunnels. */
    public static final String NUMHOPS_PROP = "numHops";
    /** by default, use 2 hop tunnels */
    public static final int NUMHOPS_DEFAULT = 2;
    
    public I2PAdapter() {
	_privateDestFile = null;
	_i2cpHost = null;
	_i2cpPort = -1;
	_localDest = null;
	_listener = null;
	_session = null;
	_numHops = 0;
    }
    
    /** who are we? */
    public Destination getLocalDestination() { return _localDest; }
    
    /** who gets notified when we receive a ping or a pong? */
    public PingPongEventListener getListener() { return _listener; }
    public void setListener(PingPongEventListener listener) { _listener = listener; }
    
    /** how many hops do we want in our tunnels? */
    public int getNumHops() { return _numHops; }
    
    /** are we connected? */
    public boolean getIsConnected() { return _session != null; }
    
    /**
     * Read in all of the config data
     *
     */
    void loadConfig(Properties props) {
	String privDestFile = props.getProperty(DEST_FILE_PROP, DEST_FILE_DEFAULT);
	String host = props.getProperty(I2CP_HOST_PROP, I2CP_HOST_DEFAULT);
	String port = props.getProperty(I2CP_PORT_PROP, ""+I2CP_PORT_DEFAULT);
	String numHops = props.getProperty(NUMHOPS_PROP, ""+NUMHOPS_DEFAULT);
	
	int portNum = -1;
	try {
	    portNum = Integer.parseInt(port);
	} catch (NumberFormatException nfe) {
	    if (_log.shouldLog(Log.WARN))
		_log.warn("Invalid I2CP port specified [" + port + "]");
	    portNum = I2CP_PORT_DEFAULT;
	}
	int hops = -1;
	try {
	    hops = Integer.parseInt(numHops);
	} catch (NumberFormatException nfe) {
	    if (_log.shouldLog(Log.WARN))
		_log.warn("Invalid # hops specified [" + numHops + "]");
	    hops = NUMHOPS_DEFAULT;
	}
	
	_numHops = hops;
	_privateDestFile = privDestFile;
	_i2cpHost = host;
	_i2cpPort = portNum;
    }
    
    /** write out the config to the props */
    void storeConfig(Properties props) {
	if (_privateDestFile != null)
	    props.setProperty(DEST_FILE_PROP, _privateDestFile);
	else
	    props.setProperty(DEST_FILE_PROP, DEST_FILE_DEFAULT);
	if (_i2cpHost != null)
	    props.setProperty(I2CP_HOST_PROP, _i2cpHost);
	else
	    props.setProperty(I2CP_HOST_PROP, I2CP_HOST_DEFAULT);
	if (_i2cpPort > 0)
	    props.setProperty(I2CP_PORT_PROP, ""+_i2cpPort);
	else
	    props.setProperty(I2CP_PORT_PROP, ""+I2CP_PORT_DEFAULT);
	props.setProperty(NUMHOPS_PROP, ""+_numHops);
    }

    private static final int TYPE_PING = 0;
    private static final int TYPE_PONG = 1;
    
    /** 
     * send a ping message to the peer
     *
     * @param peer peer to ping
     * @param seriesNum id used to keep track of multiple pings (of different size/frequency) to a peer
     * @param now current time to be sent in the ping (so we can watch for it in the pong)
     * @param size total message size to send
     *
     * @throws IllegalStateException if we are not connected to the router
     */
    public void sendPing(Destination peer, int seriesNum, long now, int size) {
	if (_session == null) throw new IllegalStateException("Not connected to the router");
	ByteArrayOutputStream baos = new ByteArrayOutputStream(size);
	try {
	    _localDest.writeBytes(baos);
	    DataHelper.writeLong(baos, 2, seriesNum);
	    DataHelper.writeLong(baos, 1, TYPE_PING);
	    DataHelper.writeDate(baos, new Date(now));
	    int padding = size - baos.size();
	    byte paddingData[] = new byte[padding];
	    Arrays.fill(paddingData, (byte)0x2A);
	    DataHelper.writeLong(baos, 2, padding);
	    baos.write(paddingData);
	    boolean sent = _session.sendMessage(peer, baos.toByteArray());
	    if (!sent) {
		if (_log.shouldLog(Log.ERROR))
		    _log.error("Error sending the ping to " + peer.calculateHash().toBase64() + " for series " + seriesNum);
	    } else {
		if (_log.shouldLog(Log.INFO))
		    _log.info("Ping sent to " + peer.calculateHash().toBase64() + " for series " + seriesNum);
	    }
	} catch (IOException ioe) {
	    if (_log.shouldLog(Log.ERROR))
		_log.error("Error sending the ping", ioe);
	} catch (DataFormatException dfe) {
	    if (_log.shouldLog(Log.ERROR))
		_log.error("Error writing out the ping message", dfe);
	} catch (I2PSessionException ise) {
	    if (_log.shouldLog(Log.ERROR))
		_log.error("Error writing out the ping message", ise);
	} 
    }
    
    /** 
     * send a pong message to the peer
     *
     * @param peer peer to pong
     * @param seriesNum id given to us in the ping
     * @param sentOn date the peer said they sent us the message
     * @param data payload the peer sent us in the ping
     *
     * @throws IllegalStateException if we are not connected to the router
     */
    public void sendPong(Destination peer, int seriesNum, Date sentOn, byte data[]) {
	if (_session == null) throw new IllegalStateException("Not connected to the router");
	ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length + 768);
	try {
	    _localDest.writeBytes(baos);
	    DataHelper.writeLong(baos, 2, seriesNum);
	    DataHelper.writeLong(baos, 1, TYPE_PONG);
	    DataHelper.writeDate(baos, sentOn);
	    DataHelper.writeDate(baos, new Date(Clock.getInstance().now()));
	    DataHelper.writeLong(baos, 2, data.length);
	    baos.write(data);
	    boolean sent = _session.sendMessage(peer, baos.toByteArray());
	    if (!sent) {
		if (_log.shouldLog(Log.ERROR))
		    _log.error("Error sending the pong to " + peer.calculateHash().toBase64() + " for series " + seriesNum + " which was sent on " + sentOn);
	    } else {
		if (_log.shouldLog(Log.INFO))
		    _log.info("Pong sent to " + peer.calculateHash().toBase64() + " for series " + seriesNum + " which was sent on " + sentOn);
	    }
	} catch (IOException ioe) {
	    if (_log.shouldLog(Log.ERROR))
		_log.error("Error sending the ping", ioe);
	} catch (DataFormatException dfe) {
	    if (_log.shouldLog(Log.ERROR))
		_log.error("Error writing out the pong message", dfe);
	} catch (I2PSessionException ise) {
	    if (_log.shouldLog(Log.ERROR))
		_log.error("Error writing out the pong message", ise);
	} 
    }
    
    /**
     * We've received this data from I2P - parse it into a ping or a pong 
     * and notify accordingly
     */
    private void handleMessage(byte data[]) {
	ByteArrayInputStream bais = new ByteArrayInputStream(data);
	try {
	    Destination from = new Destination();
	    from.readBytes(bais);
	    int series = (int)DataHelper.readLong(bais, 2);
	    long type = DataHelper.readLong(bais, 1);
	    Date sentOn = DataHelper.readDate(bais);
	    Date receivedOn = null;
	    if (type == TYPE_PONG) {
		receivedOn = DataHelper.readDate(bais);
	    }
	    int size = (int)DataHelper.readLong(bais, 2);
	    byte payload[] = new byte[size];
	    int read = DataHelper.read(bais, payload);
	    if (read != size)
		throw new IOException("Malformed payload - read " + read + " instead of " + size);
	    
	    if (_listener == null) {
		if (_log.shouldLog(Log.ERROR))
		    _log.error("Listener isn't set, but we received a valid message of type " + type + " sent from " + from.calculateHash().toBase64());
		return;
	    }
	    
	    if (type == TYPE_PING) {
		if (_log.shouldLog(Log.INFO))
		    _log.info("Ping received from " + from.calculateHash().toBase64() + " on series " + series + " sent on " + sentOn + " containing " + size + " bytes");
		_listener.receivePing(from, series, sentOn, payload);
	    } else if (type == TYPE_PONG) {
		if (_log.shouldLog(Log.INFO))
		    _log.info("Pong received from " + from.calculateHash().toBase64() + " on series " + series + " sent on " + sentOn + " with pong sent on " + receivedOn + " containing " + size + " bytes");
		_listener.receivePong(from, series, sentOn, receivedOn, payload);
	    } else {
		throw new IOException("Invalid message type " + type);
	    }
	    
	} catch (IOException ioe) {
	    if (_log.shouldLog(Log.ERROR))
		_log.error("Error handling the message", ioe);
	} catch (DataFormatException dfe) {
	    if (_log.shouldLog(Log.ERROR))
		_log.error("Error parsing the message", dfe);
	}
    }
    
    
    /** 
     * connect to the I2P router and either authenticate ourselves with the 
     * destination we're given, or create a new one and write that to the 
     * destination file.
     *
     * @return true if we connect successfully, false otherwise
     */
    boolean connect() {
	I2PClient client = I2PClientFactory.createClient();
	Destination us = null;
	File destFile = new File(_privateDestFile);
	us = verifyDestination(client, destFile);
	if (us == null) return false;
	
	// if we're here, we got a destination.  lets connect
	FileInputStream fin = null;
	try {
	    fin = new FileInputStream(destFile);
	    Properties options = getOptions();
	    I2PSession session = client.createSession(fin, options);
	    I2PListener lsnr = new I2PListener();
	    session.setSessionListener(lsnr);
	    session.connect();
	    _localDest = session.getMyDestination();
	    if (_log.shouldLog(Log.INFO))
		_log.info("I2CP Session created and connected as " + _localDest.calculateHash().toBase64());
	    _session = session;
	    _i2pListener = lsnr;
	} catch (I2PSessionException ise) {
	    if (_log.shouldLog(Log.ERROR))
		_log.error("Error connecting", ise);
	    return false;
	} catch (IOException ioe) {
	    if (_log.shouldLog(Log.ERROR))
		_log.error("Error loading the destionation", ioe);
	    return false;
	} finally {
	    if (fin != null) try { fin.close(); } catch (IOException ioe) {}
	}
	    
	return true;
    }
    
    /**
     * load, verify, or create a destination 
     *
     * @return the destination loaded, or null if there was an error
     */
    private Destination verifyDestination(I2PClient client, File destFile) {
	Destination us = null;
	FileInputStream fin = null;
	if (destFile.exists()) {
	    try {
		fin = new FileInputStream(destFile);
		us = new Destination();
		us.readBytes(fin);
		if (_log.shouldLog(Log.INFO))
		    _log.info("Existing destination loaded: [" + us.toBase64() + "]");
	    } catch (IOException ioe) {
		if (fin != null) try { fin.close(); } catch (IOException ioe2) {}
		fin = null;
		destFile.delete();
		us = null;
	    } catch (DataFormatException dfe) {
		if (fin != null) try { fin.close(); } catch (IOException ioe2) {}
		fin = null;
		destFile.delete();
		us = null;
	    } finally {
		if (fin != null) try { fin.close(); } catch (IOException ioe2) {}
		fin = null;
	    }
	}
	
	if (us == null) {
	    // need to create a new one
	    FileOutputStream fos = null;
	    try {
		fos = new FileOutputStream(destFile);
		us = client.createDestination(fos);
		if (_log.shouldLog(Log.INFO))
		    _log.info("New destination created: [" + us.toBase64() + "]");
	    } catch (IOException ioe) {
		if (_log.shouldLog(Log.ERROR))
		    _log.error("Error writing out the destination keys being created", ioe);
		return null;
	    } catch (I2PException ie) {
		if (_log.shouldLog(Log.ERROR))
		    _log.error("Error creating the destination", ie);
		return null;
	    } finally {
		if (fos != null) try { fos.close(); } catch (IOException ioe) {}
	    }
	}
	return us;
    }
    
    /**
     * I2PSession connect options
     */
    private Properties getOptions() { 
	Properties props = new Properties(); 
	props.setProperty(I2PClient.PROP_RELIABILITY, I2PClient.PROP_RELIABILITY_BEST_EFFORT);
	props.setProperty(I2PClient.PROP_TCP_HOST, _i2cpHost);
	props.setProperty(I2PClient.PROP_TCP_PORT, _i2cpPort + "");
	props.setProperty("tunnels.depthInbound", ""+_numHops);
	props.setProperty("tunnels.depthOutbound", ""+_numHops);
	return props;
    }
    
    /** disconnect from the I2P router */
    void disconnect() {
	if (_session != null) {
	    try {
		_session.destroySession();
	    } catch (I2PSessionException ise) {
		if (_log.shouldLog(Log.ERROR))
		    _log.error("Error destroying the session", ise);
	    }
	    _session = null;
	}
    }

    /**
     * Defines an event notification system for receiving pings and pongs
     * 
     */
    public interface PingPongEventListener {
	/** 
	 * receive a ping message from the peer
	 *
	 * @param from peer that sent us the ping
	 * @param seriesNum id the peer sent us in the ping
	 * @param sentOn date the peer said they sent us the message
	 * @param data payload from the ping
	 */
	void receivePing(Destination from, int seriesNum, Date sentOn, byte data[]);
	
	/** 
	 * receive a pong message from the peer
	 *
	 * @param from peer that sent us the pong
	 * @param seriesNum id the peer sent us in the pong (that we sent them in the ping)
	 * @param sentOn when we sent out the ping
	 * @param replyOn when they sent out the pong
	 * @param data payload from the ping/pong
	 */
	void receivePong(Destination from, int seriesNum, Date sentOn, Date replyOn, byte data[]);
    }
    
    /**
     * Receive data from the session and pass it along to handleMessage for parsing/dispersal
     *
     */
    private class I2PListener implements I2PSessionListener {
	public void disconnected(I2PSession session) { 
	    if (_log.shouldLog(Log.ERROR))
		_log.error("Session disconnected"); 
	    disconnect();
	}
	public void errorOccurred(I2PSession session, String message, Throwable error) { 
	    if (_log.shouldLog(Log.ERROR))
		_log.error("Error occurred", error); 
	}
	public void reportAbuse(I2PSession session, int severity) { 
	    if (_log.shouldLog(Log.ERROR))
		_log.error("Abuse reported"); 
	}
	
	public void messageAvailable(I2PSession session, int msgId, long size) {
	    try {
		byte data[] = session.receiveMessage(msgId);
		handleMessage(data);
	    } catch (I2PSessionException ise) {
		if (_log.shouldLog(Log.ERROR))
		    _log.error("Error receiving the message", ise);
		disconnect();
	    }
	}
    }
}