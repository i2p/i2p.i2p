package net.i2p.sam;


public class TestUtil {
    public static void startupBridge(int listenPort) {
        // Usage: SAMBridge [listenHost listenPortNum[ name=val]*]
        SAMBridge.main(new String[] { "0.0.0.0", listenPort+"" });
    }
}