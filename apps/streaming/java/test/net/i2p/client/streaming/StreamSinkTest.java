package net.i2p.client.streaming;

/**
 *
 */
public class StreamSinkTest {
    public static void main(String args[]) {
        System.setProperty(I2PSocketManagerFactory.PROP_MANAGER, I2PSocketManagerFull.class.getName());
        
        new Thread(new Runnable() { 
            public void run() { 
                StreamSinkServer.main(new String[] { "streamSinkTestDir", "streamSinkTestServer.key" });
            }
        }, "server").start();
        
        try { Thread.sleep(30*1000); } catch (Exception e) {}
        
        //run(256, 10000);
        //run(256, 1000);
        //run(1024, 10);
        run(32*1024, 1);
        //run("/home/jrandom/streamSinkTestDir/clientSink36766.dat", 1);
        //run(512*1024, 1);
        try { Thread.sleep(10*1000); } catch (InterruptedException e) {}
        System.out.println("Shutting down");
        System.exit(0);
    }
    
    private static void run(final int kb, final int msBetweenWrites) {
        Thread t = new Thread(new Runnable() { 
            public void run() { 
                StreamSinkClient.main(new String[] { kb+"", msBetweenWrites+"", "streamSinkTestServer.key" });
            }
        });
        t.start();
        
        System.out.println("client and server started: size = " + kb + "KB, delay = " + msBetweenWrites);
        try {
            t.join();
        } catch (InterruptedException ie) {}
    }
    
    private static void run(final String filename, final int msBetweenWrites) {
        Thread t = new Thread(new Runnable() { 
            public void run() { 
                StreamSinkSend.main(new String[] { filename, msBetweenWrites+"", "streamSinkTestServer.key" });
            }
        });
        t.start();
        
        System.out.println("client and server started: file " + filename + ", delay = " + msBetweenWrites);
        try {
            t.join();
        } catch (InterruptedException ie) {}
    }
}
