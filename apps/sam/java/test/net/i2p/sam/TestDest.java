package net.i2p.sam;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;

import net.i2p.util.Log;

public class TestDest {
    private static Log _log = new Log(TestDest.class);
    
    private static void runTest(String samHost, int samPort, String conOptions) {
        test(samHost, samPort, conOptions);
    }
    
    private static void test(String host, int port, String conOptions) {
        _log.info("\n\nTesting a DEST generate (should come back with 'DEST REPLY PUB=val PRIV=val')\n\n\n");
        try {
            Socket s = new Socket(host, port);
            OutputStream out = s.getOutputStream();
            out.write("HELLO VERSION MIN=1.0 MAX=1.0\n".getBytes());
            BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream()));
            String line = reader.readLine();
            _log.debug("line read for valid version: " + line);
            String req = "SESSION CREATE STYLE=STREAM DESTINATION=testNaming " + conOptions + "\n";
            out.write(req.getBytes());
            line = reader.readLine();
            _log.debug("Response to creating the session with destination testNaming: " + line);
            _log.debug("The above should contain SESSION STATUS RESULT=OK\n\n\n");
            String lookup = "DEST GENERATE\n";
            out.write(lookup.getBytes());
            line = reader.readLine();
            _log.info("Response from the dest generate: " + line);
            _log.debug("The abouve should be a DEST REPLY");
            try { Thread.sleep(5*1000); } catch (InterruptedException ie) {}
            s.close();
        } catch (Exception e) {
            _log.error("Error testing for valid version", e);
        }
    }
    
    public static void main(String args[]) {
        // "i2cp.tcp.host=www.i2p.net i2cp.tcp.port=7765 tunnels.inboundDepth=0";
        // "i2cp.tcp.host=localhost i2cp.tcp.port=7654 tunnels.inboundDepth=0"; 
        String conOptions = "i2cp.tcp.host=www.i2p.net i2cp.tcp.port=7765 tunnels.inboundDepth=0"; // "i2cp.tcp.host=localhost i2cp.tcp.port=7654 tunnels.inboundDepth=0";
        if (args.length > 0) {
            conOptions = "";
            for (int i = 0; i < args.length; i++)
                conOptions = conOptions + " " + args[i];
        }
        try {
            TestUtil.startupBridge(6000);
            runTest("localhost", 6000, conOptions);
        } catch (Throwable t) {
            _log.error("Error running test", t);
        }
        try { Thread.sleep(5*1000); } catch (InterruptedException ie) {}
        System.exit(0);
    }
}