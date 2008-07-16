
package net.i2p.aum;

import org.apache.xmlrpc.WebServer;

/**
 * Provides a means for programs in any language to dynamically manage
 * their own I2P <-> TCP tunnels, via simple TCP XML-RPC function calls.
 * This server is presently hardwired to listen on port 22322.
 */

public class I2PTunnelXMLServer
{
    protected WebServer ws;
    protected I2PTunnelXMLObject tunobj;
    
    public int port = 22322;
    
    // constructor
    
    public void _init()
    {
        ws = new WebServer(port);
        tunobj = new I2PTunnelXMLObject();
        ws.addHandler("i2p.tunnel", tunobj);
    
    }
    
    
    // default constructor
    public I2PTunnelXMLServer()
    {
        super();
        _init();
    }
    
    // constructor which takes shell args
    public I2PTunnelXMLServer(String args[])
    {
        super();
        _init();
    }
    
    // run the server
    public void run()
    {
        ws.start();
        System.out.println("I2PTunnel XML-RPC server listening on port "+port);
        ws.run();
    
    }
    
    public static void main(String args[])
    {
        I2PTunnelXMLServer tun;
    
        tun = new I2PTunnelXMLServer();
        tun.run();
    }
    
}


