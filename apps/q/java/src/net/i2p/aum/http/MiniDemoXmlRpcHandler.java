/*
 * MiniDemoXmlRpcHandler.java
 *
 * Created on April 13, 2005, 3:20 PM
 */

package net.i2p.aum.http;


public class MiniDemoXmlRpcHandler {

    MiniHttpServer server;
    
    public MiniDemoXmlRpcHandler(MiniHttpServer server) {
        this.server = server;
    }

    public String bar(String arg) {
        return "bar: got '"+arg+"'";
    }
}

