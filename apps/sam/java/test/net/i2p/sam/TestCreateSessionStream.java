package net.i2p.sam;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;

import net.i2p.util.Log;

public class TestCreateSessionStream {
    private static Log _log = new Log(TestCreateSessionStream.class);
    
    private static void runTest(String samHost, int samPort, String conOptions) {
        testTransient(samHost, samPort, conOptions);
        testNewDest(samHost, samPort, conOptions);
        testOldDest(samHost, samPort, conOptions);
    }
    
    private static void testTransient(String host, int port, String conOptions) {
        testDest(host, port, conOptions, "TRANSIENT");
        _log.debug("\n\nTest of transient complete\n\n\n");
        try { Thread.sleep(10*1000); } catch (InterruptedException ie) {}
    }
    private static void testNewDest(String host, int port, String conOptions) {
        String destName = "Alice" + Math.random();
        testDest(host, port, conOptions, destName);
    }
    private static void testOldDest(String host, int port, String conOptions) {
        String destName = "Alice" + Math.random();
        testDest(host, port, conOptions, destName);
        _log.debug("\n\nTest of initial contact for " + destName + " complete, waiting 90 seconds");
        try { Thread.sleep(90*1000); } catch (InterruptedException ie) {}
        _log.debug("now testing subsequent contact\n\n\n");
        testDest(host, port, conOptions, destName);
        _log.debug("\n\nTest of subsequent contact complete\n\n");
    }
    
    private static void testDest(String host, int port, String conOptions, String destName) {
        _log.info("\n\nTesting creating a new destination (should come back with 'SESSION STATUS RESULT=OK DESTINATION=someName)\n\n\n");
        try {
            Socket s = new Socket(host, port);
            OutputStream out = s.getOutputStream();
            out.write("HELLO VERSION MIN=1.0 MAX=1.0\n".getBytes());
            BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream()));
            String line = reader.readLine();
            _log.debug("line read for valid version: " + line);
            String req = "SESSION CREATE STYLE=STREAM DESTINATION=" + destName + " " + conOptions + "\n";
            out.write(req.getBytes());
            line = reader.readLine();
            _log.info("Response to creating the session with destination " + destName + ": " + line);
            _log.debug("The above should contain SESSION STATUS RESULT=OK\n\n\n");
            try { Thread.sleep(5*1000); } catch (InterruptedException ie) {}
            s.close();
        } catch (Exception e) {
            _log.error("Error testing for valid version", e);
        }
    }
    
    public static void main(String args[]) {
        // "i2cp.tcp.host=www.i2p.net i2cp.tcp.port=7765";
        // "i2cp.tcp.host=localhost i2cp.tcp.port=7654 tunnels.inboundDepth=0"; 
        String conOptions = "i2cp.tcp.host=localhost i2cp.tcp.port=7654 tunnels.inboundDepth=0";
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