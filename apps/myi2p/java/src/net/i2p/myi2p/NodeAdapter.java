package net.i2p.myi2p;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;

import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionListener;
import net.i2p.client.I2PSessionException;
import net.i2p.client.datagram.I2PDatagramDissector;
import net.i2p.client.datagram.I2PDatagramMaker;
import net.i2p.client.datagram.I2PInvalidDatagramException;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.util.Log;

/**
 * Bind the MyI2P node to the I2P network, handling messages, sessions,
 * etc.
 *
 */
public class NodeAdapter implements I2PSessionListener {
    private I2PAppContext _context;
    private Log _log;
    private Node _node;
    private I2PSession _session;
    
    public NodeAdapter(I2PAppContext context, Node node) {
        _node = node;
        _context = context;
        _log = context.logManager().getLog(NodeAdapter.class);
    }
    
    boolean sendMessage(MyI2PMessage msg) {
        if (_session == null) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Cannot send the message, as we are not connected");
            return false;
        }
        try {
            I2PDatagramMaker builder = new I2PDatagramMaker(_session);
            byte dgram[] = builder.makeI2PDatagram(msg.toRawPayload());
            return _session.sendMessage(msg.getPeer(), dgram);
        } catch (IllegalStateException ise) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("MyI2PMessage was not valid", ise);
            return false;
        } catch (I2PSessionException ise) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error sending to the peer", ise);
            return false;
        }
    }
    
    /**
     * Connect to the network using the current I2CP config and the private 
     * key file specified in the node config.  If the file does not exist, a new 
     * destination will be created.
     *
     * @param config MyI2P node and I2CP configuration
     * @param keyFile file to load the private keystream from (if it doesn't 
     *                exist, a new one will be created and stored at that location)
     *
     * @return true if connection was successful, false otherwise
     */
    boolean connect(Properties config, File keyFile) {
        I2PClient client = I2PClientFactory.createClient();
        if (!keyFile.exists()) {
            File parent = keyFile.getParentFile();
            if (parent != null) parent.mkdirs();
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(keyFile);
                Destination dest = client.createDestination(fos);
                if (_log.shouldLog(Log.INFO))
                    _log.info("New destination created [" 
                              + dest.calculateHash().toBase64() 
                              + "] with keys at " + keyFile);
            } catch (IOException ioe) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Error writing new keystream to " + keyFile, ioe);
                return false;
            } catch (I2PException ie) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Internal error creating new destination", ie);
                return false;
            } finally {
                if (fos != null) try { fos.close(); } catch (IOException ioe) {}
            }
        }
        
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(keyFile);
            _session = client.createSession(fis, config);
            if (_session == null) {
                _log.error("wtf, why did it create a null session?");
                return false;
            }
            _session.setSessionListener(this);
            _session.connect();
            if (_log.shouldLog(Log.INFO))
                _log.info("I2P session created");
            return true;
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Unable to read the keystream from " + keyFile, ioe);
            return false;
        } catch (I2PSessionException ise) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Unable to connect to the router", ise);
            return false;
        } finally {
            if (fis != null) try { fis.close(); } catch (IOException ioe) {}
        }
    }
    
    void disconnect() {
        if (_session != null) {
            try {
                _session.destroySession();
            } catch (I2PSessionException ise) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Error destroying the session in shutdown", ise);
            }
            _session = null;
        }
    }
    
    public void disconnected(I2PSession session) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Session disconnected");
    }
    
    public void errorOccurred(I2PSession session, String message, Throwable error) {
        if (_log.shouldLog(Log.ERROR))
            _log.error("Session error occurred - " + message, error);
    }
    
    public void messageAvailable(I2PSession session, int msgId, long size) {
        if (_log.shouldLog(Log.INFO))
            _log.info("message available [" + msgId + "/"+ size + " bytes]");
        
        try {
            byte data[] = session.receiveMessage(msgId);
            I2PDatagramDissector dissector = new I2PDatagramDissector();
            dissector.loadI2PDatagram(data);
            try {
                MyI2PMessage msg = new MyI2PMessage(dissector.getSender(), dissector.getPayload());
                _node.handleMessage(msg);
            } catch (IllegalArgumentException iae) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Message is a valid datagram but invalid MyI2P message", iae);
            }
        } catch (I2PSessionException ise) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error retrieving message payload for message " + msgId, ise);
        } catch (I2PInvalidDatagramException iide) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Message received was not a valid repliable datagram", iide);
        } catch (DataFormatException dfe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Message received was a corrupt repliable datagram", dfe);
        }
    }
    
    public void reportAbuse(I2PSession session, int severity) {
        if (_log.shouldLog(Log.INFO))
            _log.info("abuse occurred");
    }
}
