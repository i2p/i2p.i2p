package net.i2p.client.streaming;

import net.i2p.client.streaming.StreamSinkServer;
/**
 *
 */
public class StreamSinkTestServer {
    public static void main(String args[]) {
        //System.setProperty(I2PClient.PROP_TCP_HOST, "dev.i2p.net");
        //System.setProperty(I2PClient.PROP_TCP_PORT, "4101");
        System.setProperty("tunnels.depthInbound", "0");
        
        new Thread(new Runnable() { 
            public void run() { 
                StreamSinkServer.main(new String[] { "streamSinkTestLiveDir", "streamSinkTestLiveServer.key" });
            }
        }, "server").start();
    }
}
