package net.i2p.sam;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;

import net.i2p.util.Log;

public class TestNaming {
    private static Log _log = new Log(TestNaming.class);
    
    private static void runTest(String samHost, int samPort, String conOptions) {
        testMe(samHost, samPort, conOptions);
        testDuck(samHost, samPort, conOptions);
        testUnknown(samHost, samPort, conOptions);
    }
    
    private static void testMe(String host, int port, String conOptions) {
        testName(host, port, conOptions, "ME");
        _log.debug("\n\nTest of ME complete\n\n\n");
        try { Thread.sleep(10*1000); } catch (InterruptedException ie) {}
    }
    
    private static void testDuck(String host, int port, String conOptions) {
        testName(host, port, conOptions, "duck.i2p");
        _log.debug("\n\nTest of duck complete\n\n\n");
        try { Thread.sleep(10*1000); } catch (InterruptedException ie) {}
    }
    
    private static void testUnknown(String host, int port, String conOptions) {
        testName(host, port, conOptions, "www.odci.gov");
        _log.debug("\n\nTest of unknown host complete\n\n\n");
        try { Thread.sleep(10*1000); } catch (InterruptedException ie) {}
    }
    
    private static void testName(String host, int port, String conOptions, String name) {
        _log.info("\n\nTesting a name lookup (should come back with 'NAMING REPLY RESULT=OK VALUE=someName)\n\n\n");
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
            String lookup = "NAMING LOOKUP NAME=" + name + "\n";
            out.write(lookup.getBytes());
            line = reader.readLine();
            _log.info("Response from the lookup for [" + name +"]: " + line);
            _log.debug("The abouve should be a NAMING REPLY");
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