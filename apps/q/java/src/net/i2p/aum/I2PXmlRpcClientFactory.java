
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
 * Creates I2P XML-RPC client objects, which you can use
 * to issue XML-RPC function calls over I2P.
 * Instantiating this class causes the vm-wide http proxy system
 * properties to be set to the address of the I2P eepProxy host/port.
 * I2PXmlRpcClient objects need to communicate with the I2P
 * eepProxy. If your eepProxy is at the standard localhost:4444 address,
 * you can use the default constructor. Otherwise, you can set this
 * eepProxy address by either (1) passing eepProxy hostname/port to the
 * constructor, or (2) running the jvm with 'eepproxy.tcp.host' and
 * 'eepproxy.tcp.port' system properties set. Note that (1) takes precedence.
 * Failure to set up EepProxy host/port correctly will result in an IOException
 * when you invoke .execute() on your client objects.
 * Invoke this class from your shell to see a demo
 */

public class I2PXmlRpcClientFactory
{
    public static boolean debug = false;
    
    public static String _defaultEepHost = "127.0.0.1";
    public static int    _defaultEepPort = 4444;

    protected static Log _log;
    
    /**
     * Create an I2P XML-RPC client factory, and set it to create
     * clients of a given class.
     * @param clientClass a class to use when creating new clients
     */
    public I2PXmlRpcClientFactory()
    {
        this(null, 0);
    }

    /**
     * Create an I2P XML-RPC client factory, and set it to create
     * clients of a given class, and dispatch calls through a non-standard
     * eepProxy.
     * @param eepHost the eepProxy TCP hostname
     * @param eepPort the eepProxy TCP port number
     */
    public I2PXmlRpcClientFactory(String eepHost, int eepPort)
    {
        String eepPortStr;

        _log = new Log("I2PXmlRpcClientFactory");
        _log.shouldLog(Log.DEBUG);
    
        Properties p = System.getProperties();

        // determine what actual eepproxy host/port we're using
        if (eepHost == null) {
            eepHost = p.getProperty("eepproxy.tcp.host", _defaultEepHost);
        }
        if (eepPort > 0) {
            eepPortStr = String.valueOf(eepPort);
        }
        else {
            eepPortStr = p.getProperty("eepproxy.tcp.port");
            if (eepPortStr == null) {
                eepPortStr = String.valueOf(_defaultEepPort);
            }
        }
        
        p.put("proxySet", "true");
        p.put("http.proxyHost", eepHost);
        p.put("http.proxyPort", eepPortStr);
    }

    /**
     * Create an I2P XML-RPC client object, which is subsequently used for
     * dispatching XML-RPC requests.
     * @param dest - an I2P destination object, comprising the
     * destination of the remote
     * I2P XML-RPC server.
     * @return a new XmlRpcClient object (refer org.apache.xmlrpc.XmlRpcClient).
     */
    public I2PXmlRpcClient newClient(Destination dest) throws MalformedURLException {

        return newClient(new URL("http", "i2p/"+dest.toBase64(), "/"));
    }
    
    /**
     * Create an I2P XML-RPC client object, which is subsequently used for
     * dispatching XML-RPC requests.
     * @param hostOrDest - an I2P hostname (listed in hosts.txt) or a
     * destination base64 string, for the remote I2P XML-RPC server
     * @return a new XmlRpcClient object (refer org.apache.xmlrpc.XmlRpcClient).
     */
    public I2PXmlRpcClient newClient(String hostOrDest)
        throws DataFormatException, MalformedURLException
    {
        String hostname;
        URL u;
    
        try {
            // try to make a dest out of the string
            Destination dest = new Destination();
            dest.fromBase64(hostOrDest);
    
            // converted ok, treat as valid dest, form i2p/blahblah url from it
            I2PXmlRpcClient client = newClient(new URL("http", "i2p/"+hostOrDest, "/"));
            client.debug = debug;
            return client;
    
        } catch (DataFormatException e) {
    
            if (debug) {
                e.printStackTrace();
                print("hostOrDest length="+hostOrDest.length());
            }
    
            // failed to load up a dest, test length
            if (hostOrDest.length() < 255) {
                // short-ish, assume a hostname
                u = new URL("http", hostOrDest, "/");
                I2PXmlRpcClient client = newClient(u);
                client.debug = debug;
                return client;
            }
            else {
                // too long for a host, barf
                throw new DataFormatException("Bad I2P hostname/dest:\n"+hostOrDest);
            }
        }
    }

    /**
     * Create an I2P XML-RPC client object, which is subsequently used for
     * dispatching XML-RPC requests. This method is not recommended.
     * @param u - a URL object, containing the URL of the remote
     * I2P XML-RPC server, for example, "http://xmlrpc.aum.i2p" (assuming
     * there's a hosts.txt entry for 'xmlrpc.aum.i2p'), or
     * "http://i2p/base64destblahblah...". Note that if you use this method
     * directly, the created XML-RPC client object will ONLY work if you
     * instantiate the URL object as 'new URL("http", "i2p/"+host-or-dest, "/")'.
     */
    protected I2PXmlRpcClient newClient(URL u)
    {
        Object [] args = { u };
        //return new I2PXmlRpcClient(u);
        
        // construct and return a client object of required class
        return new I2PXmlRpcClient(u);
    }
    
    /**
     * Runs a demo of an I2P XML-RPC client. Assumes you have already
     * launched an I2PXmlRpcServerFactory demo, because it gets its
     * dest from the file 'demo.dest64' created by I2PXmlRpcServerFactory demo.
     *
     * Ensure you have first launched net.i2p.aum.I2PXmlRpcServerFactory
     * from your command line.
     */
    public static void main(String [] args) {
    
        String destStr;
    
        debug = true;
    
        try {
            print("Creating client factory...");
            
            I2PXmlRpcClientFactory f = new I2PXmlRpcClientFactory();
        
            print("Creating new client...");
    
            if (args.length == 0) {
                print("Reading dest from demo.dest64");
                destStr = new SimpleFile("demo.dest64", "r").read();
            }
            else {
                destStr = args[0];
            }
                
            XmlRpcClient c = f.newClient(destStr);
        
            print("Invoking foo...");
            
            Vector v = new Vector();
            v.add("one");
            v.add("two");
        
            Object res = c.execute("foo.bar", v);
            
            print("Got back object: " + res);
    
        } catch (Exception e) {
            e.printStackTrace();
        }
        
    }
    /**
     * Used for internal debugging
     */
    protected static void print(String msg)
    {
        if (debug) {
            System.out.println("I2PXmlRpcClient: " + msg);
    
            if (_log != null) {
                System.out.println("LOGGING SOME SHIT");
                _log.debug(msg);
            }
        }
    }
}



