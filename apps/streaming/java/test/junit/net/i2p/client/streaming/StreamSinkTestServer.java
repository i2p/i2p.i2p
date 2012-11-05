package net.i2p.client.streaming;


/**
 *
 */
public class StreamSinkTestServer {
    public static void main(String args[]) {
        System.setProperty(I2PSocketManagerFactory.PROP_MANAGER, I2PSocketManagerFull.class.getName());
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
