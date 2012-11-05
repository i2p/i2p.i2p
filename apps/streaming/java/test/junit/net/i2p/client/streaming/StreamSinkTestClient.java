package net.i2p.client.streaming;


/**
 *
 */
public class StreamSinkTestClient {
    public static void main(String args[]) {
        System.setProperty(I2PSocketManagerFactory.PROP_MANAGER, I2PSocketManagerFull.class.getName());
        //System.setProperty(I2PClient.PROP_TCP_HOST, "dev.i2p.net");
        //System.setProperty(I2PClient.PROP_TCP_PORT, "4501");
        System.setProperty("tunnels.depthInbound", "0");
        
        if (args.length <= 0) {
            send("/home/jrandom/libjbigi.so");
        } else {
            for (int i = 0; i < args.length; i++)
                send(args[i]);
        }
    }
    
    private static void send(final String filename) {
        Thread t = new Thread(new Runnable() { 
            public void run() { 
                StreamSinkSend.main(new String[] { filename, "0", "streamSinkTestLiveServer.key" });
            }
        }, "client " + filename);
        t.start();
        try { t.join(); } catch (Exception e) {}
        System.err.println("Done sending");
        try { Thread.sleep(120*1000); } catch (Exception e) {}
        //System.exit(0);
    }
}
