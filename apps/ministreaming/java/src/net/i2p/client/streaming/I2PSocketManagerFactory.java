package net.i2p.client.streaming;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import net.i2p.I2PException;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.data.Destination;
import net.i2p.util.Log;

/**
 * Simplify the creation of I2PSession and transient I2P Destination objects if 
 * necessary to create a socket manager.  This class is most likely how classes
 * will begin their use of the socket library
 *
 */
public class I2PSocketManagerFactory {
    private final static Log _log = new Log(I2PSocketManagerFactory.class);

    /**
     * Create a socket manager using a brand new destination connected to the
     * I2CP router on the local machine on the default port (7654).
     * 
     * @return the newly created socket manager, or null if there were errors
     */
    public static I2PSocketManager createManager() {
        String i2cpHost = System.getProperty(I2PClient.PROP_TCP_HOST, "localhost");
        int i2cpPort = 7654;
        String i2cpPortStr = System.getProperty(I2PClient.PROP_TCP_PORT);
        if (i2cpPortStr != null) {
            try {
                i2cpPort = Integer.parseInt(i2cpPortStr);
            } catch (NumberFormatException nfe) {
                // gobble gobble
            }
        }

        return createManager(i2cpHost, i2cpPort, System.getProperties());
    }

    /**
     * Create a socket manager using a brand new destination connected to the
     * I2CP router on the given machine reachable through the given port.
     *
     * @return the newly created socket manager, or null if there were errors
     */
    public static I2PSocketManager createManager(String i2cpHost, int i2cpPort, Properties opts) {
        I2PClient client = I2PClientFactory.createClient();
        ByteArrayOutputStream keyStream = new ByteArrayOutputStream(512);
        try {
            Destination dest = client.createDestination(keyStream);
            ByteArrayInputStream in = new ByteArrayInputStream(keyStream.toByteArray());
            return createManager(in, i2cpHost, i2cpPort, opts);
        } catch (IOException ioe) {
            _log.error("Error creating the destination for socket manager", ioe);
            return null;
        } catch (I2PException ie) {
            _log.error("Error creating the destination for socket manager", ie);
            return null;
        }
    }

    /**
     * Create a socket manager using the destination loaded from the given private key
     * stream and connected to the I2CP router on the specified machine on the given
     * port
     *
     * @return the newly created socket manager, or null if there were errors
     */
    public static I2PSocketManager createManager(InputStream myPrivateKeyStream, String i2cpHost, int i2cpPort,
                                                 Properties opts) {
        I2PClient client = I2PClientFactory.createClient();
        opts.setProperty(I2PClient.PROP_RELIABILITY, I2PClient.PROP_RELIABILITY_GUARANTEED);
        opts.setProperty(I2PClient.PROP_TCP_HOST, i2cpHost);
        opts.setProperty(I2PClient.PROP_TCP_PORT, "" + i2cpPort);
        try {
            I2PSession session = client.createSession(myPrivateKeyStream, opts);
            session.connect();
            return createManager(session);
        } catch (I2PSessionException ise) {
            _log.error("Error creating session for socket manager", ise);
            return null;
        }
    }

    private static I2PSocketManager createManager(I2PSession session) {
        I2PSocketManagerImpl mgr = new I2PSocketManagerImpl();
        mgr.setSession(session);
        mgr.setDefaultOptions(new I2PSocketOptions());
        return mgr;
    }
}