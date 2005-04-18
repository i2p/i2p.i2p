
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


/**
 * an object which is used to invoke methods on remote I2P XML-RPC
 * servers. You should not instantiate these objects directly, but
 * create them through
 * {@link net.i2p.aum.I2PXmlRpcClientFactory#newClient(Destination) I2PXmlRpcClientFactory.newClient()}
 * Note that this is really just a thin wrapper around XmlRpcClient, mostly for reasons
 * of consistency with I2PXmlRpcServer[Factory].
 */

public class I2PXmlRpcClient extends XmlRpcClient
{
    public static boolean debug = false;
    
    protected static Log _log;
    
    /**
     * Construct an I2P XML-RPC client with this URL.
     * Note that you should not
     * use this constructor directly - use I2PXmlRpcClientFactory.newClient() instead
     */
    public I2PXmlRpcClient(URL url)
    {
        super(url);
        _log = new Log("I2PXmlRpcClient");
    
    }
    
    /**
     * Construct a XML-RPC client for the URL represented by this String.
     * Note that you should not
     * use this constructor directly - use I2PXmlRpcClientFactory.newClient() instead
     */
    public I2PXmlRpcClient(String url) throws MalformedURLException
    {
        super(url);
        _log = new Log("I2PXmlRpcClientFactory");
    
    }
    
    /**
     * Construct a XML-RPC client for the specified hostname and port.
     * Note that you should not
     * use this constructor directly - use I2PXmlRpcClientFactory.newClient() instead
     */
    public I2PXmlRpcClient(String hostname, int port) throws MalformedURLException
    {
        super(hostname, port);
        _log = new Log("I2PXmlRpcClient");
    
    }
    
}

