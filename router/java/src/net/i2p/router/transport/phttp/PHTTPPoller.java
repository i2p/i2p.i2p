package net.i2p.router.transport.phttp;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Date;

import net.i2p.crypto.DSAEngine;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Signature;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageException;
import net.i2p.data.i2np.I2NPMessageHandler;
import net.i2p.router.Router;
import net.i2p.router.transport.BandwidthLimiter;
import net.i2p.util.Clock;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.util.RandomSource;

class PHTTPPoller {
    private final static Log _log = new Log(PHTTPPoller.class);
    private PHTTPTransport _transport;
    private URL _pollURL;
    private Poller _poller;
    
    public PHTTPPoller(PHTTPTransport transport) {
	_transport = transport;
	_pollURL = null;
	_poller = new Poller();
    }
    
    public void startPolling() {
	try {
	    _pollURL = new URL(_transport.getMyPollURL());
	} catch (MalformedURLException mue) {
	    _log.error("Invalid polling URL [" + _transport.getMyPollURL() + "]", mue);
	    return;
	}
	Thread t = new I2PThread(_poller);
	t.setName("HTTP Poller");
	t.setDaemon(true);
	t.setPriority(I2PThread.MIN_PRIORITY);
	t.start();
    }
    
    public void stopPolling() {
	_poller.stopPolling();
    }
    
    private byte[] getAuthData() {
	try {
	    ByteArrayOutputStream baos = new ByteArrayOutputStream(4);
	    long nonce = RandomSource.getInstance().nextInt(Integer.MAX_VALUE);
	    _log.debug("Creating nonce with value [" + nonce + "]");
	    DataHelper.writeLong(baos, 4, nonce);
	    byte nonceData[] = baos.toByteArray();
	    Signature sig = DSAEngine.getInstance().sign(nonceData, _transport.getMySigningKey());
	    baos = new ByteArrayOutputStream(512);
	    DataHelper.writeLong(baos, 4, nonce);
	    sig.writeBytes(baos);
	    byte data[] = baos.toByteArray();
	    return data;
	} catch (NumberFormatException nfe) {
	    _log.error("Error writing the authentication data", nfe);
	    return null;
	} catch (DataFormatException dfe) {
	    _log.error("Error formatting the authentication data", dfe);
	    return null;
	} catch (IOException ioe) {
	    _log.error("Error writing the authentication data", ioe);
	    return null;
	}
    }
    
    public final static String CONFIG_POLL = "i2np.phttp.shouldPoll";
    public final static boolean DEFAULT_POLL = false;
    
    static boolean shouldRejectMessages() {
	String val = Router.getInstance().getConfigSetting(CONFIG_POLL);
	if (null == val) {
	    return !DEFAULT_POLL;
	} else {
	    return !("true".equals(val));
	}
    }
    
    class Poller implements Runnable {
	private boolean _running;
	private I2NPMessageHandler _handler = new I2NPMessageHandler();
	public void run() {
	    _running = true;
	    // wait 5 seconds before starting to poll so we don't drop too many messages
	    try { Thread.sleep(10*1000); } catch (InterruptedException ie) {}
	    
	    _log.debug("Poller running with delay [" + _transport.getPollFrequencyMs() + "]");
	    try {
		while (_running) {
		    int numRead = getMessages();
		    if (numRead > 0)
			_log.info("# messages found: " + numRead);
		    try { Thread.sleep(_transport.getPollFrequencyMs()); } catch (InterruptedException ie) {}
		}
	    } catch (Throwable t) {
		_log.info("Error while polling", t);
	    }
	}
	
	private int getMessages() { 
	    // open the _pollURL, authenticate ourselves, and get any messages available
	    byte authData[] = getAuthData();
	    if (authData == null) return 0;
	    
	    BandwidthLimiter.getInstance().delayOutbound(null, authData.length + 512); // HTTP overhead
	    
	    try {
		_log.debug("Before opening " + _pollURL.toExternalForm());
		HttpURLConnection con = (HttpURLConnection)_pollURL.openConnection();
		// send the info
		con.setRequestMethod("POST");
		con.setUseCaches(false);
		con.setDoOutput(true);
		con.setDoInput(true);
		
		ByteArrayOutputStream baos = new ByteArrayOutputStream(authData.length + 64);
		String target = _transport.getMyIdentity().getHash().toBase64();
		baos.write("target=".getBytes());
		baos.write(target.getBytes());
		baos.write("&".getBytes());
		baos.write(authData);
		byte data[] = baos.toByteArray();
		//_log.debug("Data to be sent: " + Base64.encode(data));
	    
		con.setRequestProperty("Content-length", ""+data.length);
		con.getOutputStream().write(data);
		_log.debug("Data sent, before reading results of poll for [" + target + "]");
		
		con.connect();
		
		// fetch the results
		int rc = con.getResponseCode();
		_log.debug("Response code: " + rc);
		switch (rc) {
		    case 200: // ok
			_log.debug("Polling can progress");
			break;
		    case 401: // signature failed
			_log.error("Signature failed during polling???");
			return 0;
		    case 404: // not yet registered
			_log.error("Not registered with the relay - reregistering (in case they failed)");
			_transport.registerWithRelay();
			return 0;
		    default: // unknown
			_log.error("Invalid error code returned: " + rc);
			return 0;
		}
		
		InputStream in = con.getInputStream();
		Date peerTime = DataHelper.readDate(in);
		long offset = peerTime.getTime() - System.currentTimeMillis();
		if (_transport.getTrustTime()) {
		    _log.info("Updating time offset to " + offset + " (old offset: " + Clock.getInstance().getOffset() + ")");
		    Clock.getInstance().setOffset(offset);
		}
		
		boolean shouldReject = shouldRejectMessages();
		if (shouldReject) {
		    _log.debug("Rejecting any messages [we just checked in so we could get the time]");
		    return 0;
		}

		int numMessages = (int)DataHelper.readLong(in, 2);
		if ( (numMessages > 100) || (numMessages < 0) ) {
		    _log.error("Invalid # messages specified [" + numMessages + "], skipping");
		    return 0;
		}
		
		int bytesRead = 512; // HTTP overhead
		
		int numSuccessful = 0;
		for (int i = 0; i < numMessages; i++) {
		    _log.debug("Receiving message " + (i+1) + " of "+ numMessages + " pending");
		    long len = DataHelper.readLong(in, 4);
		    byte msgBuf[] = new byte[(int)len];
		    int read = DataHelper.read(in, msgBuf);
		    if (read == -1) {
			_log.error("Unable to read the message as we encountered an EOF");
			return i - 1;
		    } else if (read != len) {
			_log.error("Unable to read the message fully [" + read + " read, " + len + " expected]");
			return i - 1;
		    } else {
			bytesRead += 4 + read;
			try {
			    I2NPMessage msg = _handler.readMessage(new ByteArrayInputStream(msgBuf));
			    if (msg == null) {
				_log.warn("PHTTP couldn't read a message from the peer out of a " + len + " byte buffer");
			    } else {
				_log.info("Receive message " + (i+1) + " of " + numMessages + ": " + msg.getClass().getName());
				_transport.messageReceived(msg, null, null, _handler.getLastReadTime(), (int)len);
				numSuccessful++;
			    }
			} catch (IOException ioe) {
			    _log.warn("Unable to read the message fully", ioe);
			} catch (I2NPMessageException ime) {
			    _log.warn("Poorly formatted message", ime);
			}
		    }
		}
	
		BandwidthLimiter.getInstance().delayInbound(null, bytesRead); 
	    
		return numSuccessful;
	    } catch (Throwable t) {
		_log.debug("Error polling", t);
		return 0;
	    }
	}
	
	public void stopPolling() { _running = false; }
    }
}
