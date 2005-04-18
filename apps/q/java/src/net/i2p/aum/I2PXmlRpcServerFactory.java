package net.i2p.aum;

import java.lang.*;
import java.io.*;
import java.util.*;
import java.net.*;

import org.apache.xmlrpc.*;

import net.i2p.*;
import net.i2p.client.*;
import net.i2p.client.streaming.*;
import net.i2p.data.Base64;
import net.i2p.util.*;
import net.i2p.data.*;
import net.i2p.i2ptunnel.*;


/**
 * Generates I2P-compatible XML-RPC server objects
 * (of class I2PXmlRpcServer). If you instead want to create 
 * instances of your own 
 *
 * Invoke this class from your shell to see a demo
 * @author aum
 */
public class I2PXmlRpcServerFactory
{
    public I2PSocketManager socketManager;
    
    public Properties props;
    
    public static int defaultTunnelLength = 2;

    // set to enable debugging msgs
    public static boolean debug = false;
    
    public static Log log;
    protected I2PAppContext i2p;
    
    // hostname/port of I2P router we're using
    //public static String i2cpHost = "127.0.0.1";
    //public static int i2cpPort = 7654;

    /**
     * Create an I2P XML-RPC server factory using default
     * tunnel settings
     */
    public I2PXmlRpcServerFactory(I2PAppContext i2p)
    {
        // get a socket manager
        this(defaultTunnelLength, defaultTunnelLength,
             defaultTunnelLength, defaultTunnelLength, i2p);
    }
    
    
    /**
     * Create an I2P XML-RPC server factory, using settings provided
     * by arguments
     * @param lengthIn The value of 'inbound.length' property
     * @param lengthOut The value of 'outbound.length' property
     * @param lengthVarianceIn Value of 'inbound.lengthVariance' property
     * @param lengthVarianceOut Value of 'outbound.lengthVariance' property
     * @param log an I2P logger
     */
    public I2PXmlRpcServerFactory(int lengthIn, int lengthOut,
                                  int lengthVarianceIn, int lengthVarianceOut,
                                  I2PAppContext i2p)
    {
        this.i2p = i2p;
        log = i2p.logManager().getLog(this.getClass());
    
        // set up tunnel properties for server objects
        props = new Properties();
        props.setProperty("inbound.length", String.valueOf(lengthIn));
        props.setProperty("outbound.length", String.valueOf(lengthOut));
        props.setProperty("inbound.lengthVariance", String.valueOf(lengthVarianceIn));
        props.setProperty("outbound.lengthVariance", String.valueOf(lengthVarianceIn));
    }
    
    /**
     * Creates a new I2PXmlRpcServer listening on a new randomly created destination
     * @return a new I2PXmlRpcServer object, whose '.addHandler()' method you should
     * invoke to add a handler object.
     * @throws I2PException, IOException, DataFormatException
     */
    public I2PXmlRpcServer newServer() throws I2PException, IOException, DataFormatException
    {
        return newServer(PrivDestination.newKey());
    }
    
    
    /**
     * Creates a new I2PXmlRpcServer listening on a given dest, from key provided
     * as a base64 string
     * @param keyStr base64 representation of full private key for the destination
     * the server is to listen on
     * @return a new I2PXmlRpcServer object
     * @throws DataFormatException
     */
    public I2PXmlRpcServer newServer(String keyStr)
        throws DataFormatException, I2PException, IOException
    {
        return newServer(PrivDestination.fromBase64String(keyStr));
    }
    
    
    /**
     * Creates a new I2PXmlRpcServer listening on a given dest, from key provided
     * as a PrivDestination object
     * @param key a PrivDestination object representing the private destination
     * the server is to listen on
     * @return a new I2PXmlRpcServer object
     * @throws DataFormatException
     */
    public I2PXmlRpcServer newServer(PrivDestination key) throws DataFormatException, I2PException
    {
        // main newServer
        I2PXmlRpcServer server = new I2PXmlRpcServer(key, props, i2p);
        server.debug = debug;
        return server;
    }

    /**
     * Demonstration of I2P XML-RPC server.
     * Creates a server listening on a random destination, and writes the base64
     * destination into a file called "demo.dest64".
     *
     * After launching this program from a command shell, you should
     * launch I2PXmlRpcClientFactory from another command shell
     * to execute the client side of the demo.
     *
     * This program accepts no arguments.
     */
    public static void main(String [] args)
    {
        debug = true;
        I2PXmlRpcServer.debug = true;
        
        I2PAppContext i2p = new I2PAppContext();
    
        I2PXmlRpcServerFactory f = new I2PXmlRpcServerFactory(0,0,0,0, i2p);
    
        try {
    
            f.log.info("Creating new server on a new key");
            I2PXmlRpcServer s = f.newServer();
    
            f.log.info("Creating and adding handler object");
            I2PXmlRpcDemoClass demo = new I2PXmlRpcDemoClass();
            s.addHandler("foo", demo);

            f.log.info("Saving dest for this server in file 'demo.dest64'");
            new SimpleFile("demo.dest64", "rws").write(s.dest.toBase64());

            f.log.info("Running server (Press Ctrl-C to kill)");
            s.run();
    
        } catch (Exception e) { e.printStackTrace(); }
        
    }
}

