package net.i2p.sam;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;

import net.i2p.util.Log;

public class TestHello {
    private static Log _log = new Log(TestHello.class);
    
    private static void runTest(String samHost, int samPort) {
        testValidVersion(samHost, samPort);
        testInvalidVersion(samHost, samPort);
        testCorruptLine(samHost, samPort);
    }
    
    private static void testValidVersion(String host, int port) {
        _log.info("\n\nTesting valid version (should come back with an OK)\n\n\n");
        try {
            Socket s = new Socket(host, port);
            OutputStream out = s.getOutputStream();
            out.write("HELLO VERSION MIN=1.0 MAX=1.0\n".getBytes());
            BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream()));
            String line = reader.readLine();
            _log.info("line read for valid version: " + line);
            s.close();
        } catch (Exception e) {
            _log.error("Error testing for valid version", e);
        }
    }
    
    private static void testInvalidVersion(String host, int port) {
        _log.info("\n\nTesting invalid version (should come back with an error)\n\n\n");
        try {
            Socket s = new Socket(host, port);
            OutputStream out = s.getOutputStream();
            out.write("HELLO VERSION MIN=9.0 MAX=8.3\n".getBytes());
            BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream()));
            String line = reader.readLine();
            _log.info("line read for invalid version: " + line);
            s.close();
        } catch (Exception e) {
            _log.error("Error testing for valid version", e);
        }
    }
        
    private static void testCorruptLine(String host, int port) {
        _log.info("\n\nTesting corrupt line (should come back with an error)\n\n\n");
        try {
            Socket s = new Socket(host, port);
            OutputStream out = s.getOutputStream();
            out.write("HELLO h0 h0 h0\n".getBytes());
            BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream()));
            String line = reader.readLine();
            _log.info("line read for valid version: " + line);
            s.close();
        } catch (Exception e) {
            _log.error("Error testing for valid version", e);
        }
    }

    
    public static void main(String args[]) {
        try {
            TestUtil.startupBridge(6000);
            runTest("localhost", 6000);
        } catch (Throwable t) {
            _log.error("Error running test", t);
        }
        try { Thread.sleep(5*1000); } catch (InterruptedException ie) {}
        System.exit(0);
    }
}