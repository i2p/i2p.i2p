package net.i2p.router.transport.phttp;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Date;
import java.util.Properties;

import net.i2p.data.DataHelper;
import net.i2p.data.RouterAddress;
import net.i2p.data.RouterIdentity;
import net.i2p.data.RouterInfo;
import net.i2p.data.SigningPrivateKey;
import net.i2p.router.JobImpl;
import net.i2p.router.JobQueue;
import net.i2p.router.OutNetMessage;
import net.i2p.router.Router;
import net.i2p.router.transport.BandwidthLimiter;
import net.i2p.router.transport.TransportBid;
import net.i2p.router.transport.TransportImpl;
import net.i2p.util.Clock;
import net.i2p.util.Log;

/**
 *
 *
 */
public class PHTTPTransport extends TransportImpl {
    private final static Log _log = new Log(PHTTPTransport.class);
    public final static String STYLE = "PHTTP";
    private RouterIdentity _myIdentity;
    private SigningPrivateKey _signingKey;
    private RouterAddress _myAddress;
    private String _mySendURL;
    private String _myPollURL;
    private String _myRegisterURL;
    private long _timeOffset;
    private long _pollFrequencyMs;
    private int _transportCost;
    private PHTTPPoller _poller;
    private PHTTPSender _sender;
    private boolean _trustTime;
    
    /** how long after a registration failure should we delay?  this gets doubled each time */
    private long _nextRegisterDelay = 1000;
    
    /** if the phttp relay is down, check it up to once every 5 minutes */
    private final static long MAX_REGISTER_DELAY = 5*60*1000;
    
    /** URL to which registration with the server can occur */
    public final static String PROP_TO_REGISTER_URL = "registerURL";
    /** URL to which messages destined for this address can be sent */
    public final static String PROP_TO_SEND_URL = "sendURL";
    
    public final static String PROP_LOCALTIME = "localtime";
    
    /* key=val keys sent back on registration */
    public final static String PROP_STATUS   = "status";
    public final static String PROP_POLL_URL = "pollURL";
    public final static String PROP_SEND_URL = "sendURL";
    public final static String PROP_TIME_OFFSET = "timeOffset"; // ms (remote-local)

    /* values for the PROP_STATUS */
    public final static String STATUS_FAILED     = "failed";
    public final static String STATUS_REGISTERED = "registered";
    
    public final static String CONFIG_POLL_FREQUENCY = "i2np.phttp.pollFrequencySeconds";
    public final static long   DEFAULT_POLL_FREQUENCY = 60*1000; // every 60 seconds
    
    /** 
     * do we want to assume that the relay's clock is sync'ed with NTP and update 
     * our offset according to what they say?
     */
    public final static String CONFIG_TRUST_TIME = "i2np.phttp.trustRelayTime";
    public final static boolean DEFAULT_TRUST_TIME = true;
    
    public PHTTPTransport(RouterIdentity myIdentity, SigningPrivateKey signingKey, RouterAddress myAddress) {
	super();
	_myIdentity = myIdentity;
	_signingKey = signingKey;
	_myAddress = myAddress;
	
	if (myAddress != null) {
	    Properties opts = myAddress.getOptions();
	    _myRegisterURL = opts.getProperty(PROP_TO_REGISTER_URL);
	    _mySendURL = opts.getProperty(PROP_TO_SEND_URL);
	    _pollFrequencyMs = DEFAULT_POLL_FREQUENCY;
	    String pollFreq = Router.getInstance().getConfigSetting(CONFIG_POLL_FREQUENCY);
	    if (pollFreq != null) {
		try {
		    long val = Long.parseLong(pollFreq);
		    _pollFrequencyMs = val*1000;
		    _log.info("PHTTP Polling Frequency specified as once every " + val + " seconds");
		} catch (NumberFormatException nfe) {
		    _log.error("Poll frequency is not valid (" + pollFreq + ")", nfe);
		}
	    } else {
		_log.info("PHTTP Polling Frequency not specified via (" + CONFIG_POLL_FREQUENCY + "), defaulting to once every " + (DEFAULT_POLL_FREQUENCY/1000) + " seconds");
	    }
	    
	    String trustTime = Router.getInstance().getConfigSetting(CONFIG_TRUST_TIME);
	    if (trustTime != null) {
		_trustTime = Boolean.TRUE.toString().equalsIgnoreCase(trustTime);
	    } else {
		_trustTime = DEFAULT_TRUST_TIME;
	    }
	    
	    JobQueue.getInstance().addJob(new RegisterJob());
	}
	_sender = new PHTTPSender(this);
    }
    
    public String getMySendURL() { return _mySendURL; }
    SigningPrivateKey getMySigningKey() { return _signingKey; }
    RouterIdentity getMyIdentity() { return _myIdentity; }
    String getMyPollURL() { return _myPollURL; }
    long getPollFrequencyMs() { return _pollFrequencyMs; }
    
    private class RegisterJob extends JobImpl {
	public String getName() { return "Register with PHTTP relay"; }
	public void runJob() {
	    boolean ok = doRegisterWithRelay();
	    if (ok) {
		_log.debug("Registration successful with the last registration delay of " + _nextRegisterDelay + "ms");
		if (_poller == null) {
		    _poller = new PHTTPPoller(PHTTPTransport.this);
		    _poller.startPolling();
		} 
	    } else {
		_nextRegisterDelay = _nextRegisterDelay * 2;
		if (_nextRegisterDelay > MAX_REGISTER_DELAY)
		    _nextRegisterDelay = MAX_REGISTER_DELAY;
		long nextRegister = Clock.getInstance().now() + _nextRegisterDelay;
		_log.debug("Registration failed, next registration attempt in " + _nextRegisterDelay + "ms");
		requeue(nextRegister);
	    }
	}
    }
    
    boolean registerWithRelay() {
	boolean ok = doRegisterWithRelay();
	if (ok) {
	    _log.info("Registered with PHTTP relay");
	    return ok;
	}
	_log.error("Unable to register with relay");
	return false;
    }
    
    synchronized boolean doRegisterWithRelay() {
	_log.debug("Beginning registration");
	ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
	try {
	    DataHelper.writeDate(baos, new Date(Clock.getInstance().now()));
	    _myIdentity.writeBytes(baos);
	    int postLength = baos.size();
	    
	    BandwidthLimiter.getInstance().delayOutbound(null, postLength+512); // HTTP overhead
	    BandwidthLimiter.getInstance().delayInbound(null, 2048+512); // HTTP overhead
	    
	    long now = Clock.getInstance().now();
	    _log.debug("Before opening " + _myRegisterURL);
	    URL url = new URL(_myRegisterURL);
	    HttpURLConnection con = (HttpURLConnection)url.openConnection();
	    // send the info
	    con.setRequestMethod("POST");
	    con.setUseCaches(false);
	    con.setDoOutput(true);
	    con.setDoInput(true);
	    con.setRequestProperty("Content-length", ""+postLength);
	    baos.writeTo(con.getOutputStream());
	    _log.debug("Data sent, before reading");
	    con.connect();
	    // fetch the results
	    BufferedReader reader = new BufferedReader(new InputStreamReader(con.getInputStream()));
	    String line = null;
	    String stat = null;
	    boolean ok = false;
	    while ( (line = reader.readLine()) != null) {
		if (line.startsWith(PROP_SEND_URL)) {
		    _mySendURL = line.substring(PROP_SEND_URL.length()+1).trim();
		} else if (line.startsWith(PROP_POLL_URL)) {
		    _myPollURL = line.substring(PROP_POLL_URL.length()+1).trim();
		} else if (line.startsWith(PROP_STATUS)) {
		    stat = line.substring(PROP_STATUS.length()+1).trim();
		    if (STATUS_REGISTERED.equals(stat.toLowerCase()))
			ok = true;
		} else if (line.startsWith(PROP_TIME_OFFSET)) {
		    String offset = line.substring(PROP_TIME_OFFSET.length()+1).trim();
		    try {
			_timeOffset = Long.parseLong(offset);
		    } catch (Throwable t) {
			_log.warn("Unable to parse time offset [" + offset + "] - treating as MAX");
			_timeOffset = Long.MAX_VALUE;
		    }
		}
		if ( (_myPollURL != null) && (_mySendURL != null) && (stat != null) )
		    break;
	    }
	    
	    if (_trustTime) {
		_log.info("Setting time offset to " + _timeOffset + " (old offset: " + Clock.getInstance().getOffset() + ")");
		Clock.getInstance().setOffset(_timeOffset);
	    }
	    //if ( (_timeOffset > Router.CLOCK_FUDGE_FACTOR) || (_timeOffset < 0 - Router.CLOCK_FUDGE_FACTOR) ) {
	    //	_log.error("Unable to register with PHTTP relay, as there is too much clock skew!  " + _timeOffset + "ms difference (them-us)", new Exception("Too much clock skew with phttp relay!"));
	    //	return false;
	    //}
	    
	    if (ok) {
		_log.info("Registered with the PHTTP relay [" + _myRegisterURL + "]");
		_log.info("Registered sending url: [" + _mySendURL + "]");
		_log.info("Registered polling url: [" + _myPollURL + "]");
		return true;
	    } else {
		_log.warn("PHTTP relay [" + _myRegisterURL + "] rejected registration");
	    }
	} catch (Throwable t) {
	    _log.warn("Error registering", t);
	}
	
	return false;
    }
    
    protected void outboundMessageReady() {
	OutNetMessage msg = getNextMessage();
	if (msg != null) {
	    JobQueue.getInstance().addJob(new PushNewMessageJob(msg));
	} else {
	    _log.debug("OutboundMessageReady called, but none were available");
	}
    }

    public TransportBid bid(RouterInfo toAddress, long dataSize) {
	if (PHTTPPoller.shouldRejectMessages())
	    return null; // we're not using phttp
	
	long latencyStartup = BandwidthLimiter.getInstance().calculateDelayOutbound(toAddress.getIdentity(), (int)dataSize);
	latencyStartup += _pollFrequencyMs / 2; // average distance until the next poll
	long sendTime = (int)((dataSize)/(16*1024)); // 16K/sec ARBITRARY
	int bytes = (int)dataSize+1024;
	
	// lets seriously penalize phttp to heavily prefer TCP 
	bytes += 1024*100;
	latencyStartup += 1000*600;
	
	TransportBid bid = new TransportBid();
	bid.setBandwidthBytes(bytes);
	bid.setExpiration(new Date(Clock.getInstance().now()+1000*60)); // 1 minute, since the bwlimiter goes per minute
	bid.setLatencyMs((int) (latencyStartup + sendTime)); 
	bid.setMessageSize((int)dataSize);
	bid.setRouter(toAddress);
	bid.setTransport(this);
	
	RouterAddress addr = getTargetAddress(toAddress);
	if (addr == null)
	    return null;
	
	return bid;
    }
    
    public RouterAddress startListening() { 
	_log.debug("Start listening");
	return _myAddress;
    }
    public void stopListening() {
	if (_poller != null)
	    _poller.stopPolling();
    }
    
    
    public void rotateAddresses() {}
    public void addAddressInfo(Properties infoForNewAddress) {}
    public String getStyle() { return STYLE; }
    
    boolean getTrustTime() { return _trustTime; }
    
    private class PushNewMessageJob extends JobImpl {
	private OutNetMessage _msg;
	public PushNewMessageJob(OutNetMessage msg) { _msg = msg; }
	public String getName() { return "Push New PHTTP Message"; }
	public void runJob() {
	    long delay = BandwidthLimiter.getInstance().calculateDelayOutbound(_msg.getTarget().getIdentity(), (int)_msg.getMessageSize());
	    if (delay > 0) {
		getTiming().setStartAfter(delay + Clock.getInstance().now());
		JobQueue.getInstance().addJob(this);
	    } else {
		_sender.send(_msg);
	    }
	}
    }
}
