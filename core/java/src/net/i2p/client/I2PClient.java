package net.i2p.client;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

import net.i2p.I2PException;
import net.i2p.data.Certificate;
import net.i2p.data.Destination;

/**
 * Define the standard means of interacting with the I2P system
 *
 * @author jrandom
 */
public interface I2PClient {
    /** Standard host property, defaulting to localhost if not specified */
    public final static String PROP_TCP_HOST = "i2cp.tcp.host";
    /** Standard port number property */
    public final static String PROP_TCP_PORT = "i2cp.tcp.port";
    /** Reliability property */
    public final static String PROP_RELIABILITY = "i2cp.messageReliability";
    /** Reliability value: best effort */
    public final static String PROP_RELIABILITY_BEST_EFFORT = "BestEffort";
    /** Reliability value: guaranteed */
    public final static String PROP_RELIABILITY_GUARANTEED = "Guaranteed";
    /** @since 0.8.1 */
    public final static String PROP_RELIABILITY_NONE = "none";

    /** protocol flag that must be sent when opening the i2cp connection to the router */
    public final static int PROTOCOL_BYTE = 0x2A;

    /** Create a new client session for the Destination stored at the destKeyStream
     * using the specified options to both connect to the router, to instruct
     * the router how to handle the new session, and to configure the end to end
     * encryption.
     *
     * @param destKeyStream location from which to read the Destination, PrivateKey, and SigningPrivateKey from,
     *                      format is specified in {@link net.i2p.data.PrivateKeyFile PrivateKeyFile}
     * @param options set of options to configure the router with, if null will use System properties
     * @return new session allowing a Destination to recieve all of its messages and send messages to any other Destination.
     */
    public I2PSession createSession(InputStream destKeyStream, Properties options) throws I2PSessionException;

    /** Create a new destination with the default certificate creation properties and store
     * it, along with the private encryption and signing keys at the specified location
     *
     * @param destKeyStream create a new destination and write out the object to the given stream, 
     *			    formatted as Destination, PrivateKey, and SigningPrivateKey
     *                      format is specified in {@link net.i2p.data.PrivateKeyFile PrivateKeyFile}
     * @return new destination
     */
    public Destination createDestination(OutputStream destKeyStream) throws I2PException, IOException;

    /** Create a new destination with the given certificate and store it, along with the private 
     * encryption and signing keys at the specified location
     *
     * @param destKeyStream location to write out the destination, PrivateKey, and SigningPrivateKey,
     *                      format is specified in {@link net.i2p.data.PrivateKeyFile PrivateKeyFile}
     * @param cert certificate to tie to the destination
     * @return newly created destination
     */
    public Destination createDestination(OutputStream destKeyStream, Certificate cert) throws I2PException, IOException;
}
