package net.i2p.client;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

import net.i2p.data.Destination;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Quick and dirty test harness for sending messages from one destination to another.
 * This will print out some debugging information and containg the statement
 * "Hello other side.  I am dest1" if the router and the client libraries work.
 * This class bootstraps itself each time - creating new keys and destinations
 *
 * @author jrandom
 */
public class TestClient implements I2PSessionListener {
    private final static Log _log = new Log(TestClient.class);
    private static Destination _dest1;
    private static Destination _dest2;
    private boolean _stillRunning;
    
    public void runTest(String keyfile, boolean isDest1) {
	_stillRunning = true;
        try {
            I2PClient client = I2PClientFactory.createClient();
            File file = new File(keyfile);
            Destination d = client.createDestination(new FileOutputStream(file));
            if (isDest1)
                _dest1 = d;
            else
                _dest2 = d;
            _log.debug("Destination written to " + file.getAbsolutePath());
            Properties options = new Properties();

            if (System.getProperty(I2PClient.PROP_TCP_HOST) == null)
                options.setProperty(I2PClient.PROP_TCP_HOST, "localhost");
            else
                options.setProperty(I2PClient.PROP_TCP_HOST, System.getProperty(I2PClient.PROP_TCP_HOST));
            if (System.getProperty(I2PClient.PROP_TCP_PORT) == null)
                options.setProperty(I2PClient.PROP_TCP_PORT, "7654");
            else
                options.setProperty(I2PClient.PROP_TCP_PORT, System.getProperty(I2PClient.PROP_TCP_PORT));

            I2PSession session = client.createSession(new FileInputStream(file), options);
	    session.setSessionListener(this);
	    _log.debug("Before connect...");
            session.connect();
	    _log.debug("Connected");

            // wait until the other one is connected
            while ( (_dest1 == null) || (_dest2 == null) )
                try { Thread.sleep(500); } catch (InterruptedException ie) {}

	    if (isDest1) {
		Destination otherD = (isDest1 ? _dest2 : _dest1);
		boolean accepted = session.sendMessage(otherD, ("Hello other side.  I am" + (isDest1 ? "" : " NOT") + " dest1").getBytes());
	    } else {
		while (_stillRunning) {
		    try {
			_log.debug("waiting for a message...");
			Thread.sleep(1000);
		    } catch (InterruptedException ie) {}
		}
		try { Thread.sleep(5000); } catch (InterruptedException ie) {}
		System.exit(0);
	    }
            //session.destroySession();
        } catch (Exception e) {
            _log.error("Error running the test for isDest1? " + isDest1, e);
        }
    }
    
    public static void main(String args[]) {
	doTest();
	try { Thread.sleep(30*1000); } catch (InterruptedException ie) {}
    }
    static void doTest() {
        Thread test1 = new I2PThread(new Runnable() { public void run() { (new TestClient()).runTest("test1.keyfile", true); } } );
        Thread test2 = new I2PThread(new Runnable() { public void run() { (new TestClient()).runTest("test2.keyfile", false); } } );
        test1.start();
        test2.start();
        _log.debug("Test threads started");
    }
    
    public void disconnected(I2PSession session) {
	_log.debug("Disconnected");
	_stillRunning = false;
    }
    
    public void errorOccurred(I2PSession session, String message, Throwable error) {
	_log.debug("Error occurred: " + message, error);
    }
    
    public void messageAvailable(I2PSession session, int msgId, long size) {
	_log.debug("Message available for us!  id = " + msgId + " of size " + size);
	try {
	    byte msg[] = session.receiveMessage(msgId);
	    _log.debug("Content of message " + msgId+ ":\n"+new String(msg));
	    _stillRunning = false;
	} catch (I2PSessionException ise) {
	    _log.error("Error fetching available message", ise);
	}
    }
    
    public void reportAbuse(I2PSession session, int severity) {
	_log.debug("Abuse reported of severity " + severity);
    }
    
}
