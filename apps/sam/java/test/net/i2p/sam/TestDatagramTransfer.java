package net.i2p.sam;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Properties;
import java.util.StringTokenizer;

import net.i2p.util.Log;

public class TestDatagramTransfer {
    private static Log _log = new Log(TestCreateSessionDatagram.class);
    
    private static void runTest(String samHost, int samPort, String conOptions) {
        testTransfer(samHost, samPort, conOptions);
    }
    
    private static void testTransfer(String host, int port, String conOptions) {
        String destName = "TRANSIENT";
        _log.info("\n\nTesting creating a new destination (should come back with 'SESSION STATUS RESULT=OK DESTINATION=someName)\n\n\n");
        try {
            Socket s = new Socket(host, port);
            OutputStream out = s.getOutputStream();
            out.write("HELLO VERSION MIN=1.0 MAX=1.0\n".getBytes());
            BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream()));
            String line = reader.readLine();
            _log.debug("line read for valid version: " + line);
            String req = "SESSION CREATE STYLE=DATAGRAM DESTINATION=" + destName + " " + conOptions + "\n";
            out.write(req.getBytes());
            line = reader.readLine();
            _log.info("Response to creating the session with destination " + destName + ": " + line);
            _log.debug("The above should contain SESSION STATUS RESULT=OK\n\n\n");
            String lookup = "NAMING LOOKUP NAME=ME\n";
            out.write(lookup.getBytes());
            line = reader.readLine();
            _log.info("Response from the lookup for ME: " + line);
            _log.debug("The above should be a NAMING REPLY");
            
            StringTokenizer tok = new StringTokenizer(line);
            String maj = tok.nextToken();
            String min = tok.nextToken();
            Properties props = SAMUtils.parseParams(tok);
            String value = props.getProperty("VALUE");
            if (value == null) {
                _log.error("No value for ME found!  [" + line + "]");
                return;
            } else {
                _log.info("Alice is located at " + value);
            }
            
            String send = "DATAGRAM SEND DESTINATION=" + value + " SIZE=3\nYo!";
            out.write(send.getBytes());
            line = reader.readLine();
            tok = new StringTokenizer(line);
            maj = tok.nextToken();
            min = tok.nextToken();
            props = SAMUtils.parseParams(tok);
            String size = props.getProperty("SIZE");
            String from = props.getProperty("DESTINATION");
            if ( (value == null) || (size == null) ||
                 (!from.equals(value)) || (!size.equals("3")) ) {
                _log.error("Reply of the datagram is incorrect: [" + line + "]");
                return;
            } 
            
            char buf[] = new char[3];
            int read = reader.read(buf);
            if (read != 3) {
                _log.error("Unable to read the full datagram");
                return;
            }
            if (new String(buf).equals("Yo!")) {
                _log.info("Received payload successfully");
            } else {
                _log.error("Payload is incorrect!  [" + new String(buf) + "]");
            }
            
            try { Thread.sleep(5*1000); } catch (InterruptedException ie) {}
            s.close();
        } catch (Exception e) {
            _log.error("Error testing for valid version", e);
        }
    }
    
    public static void main(String args[]) {
        // "i2cp.tcp.host=www.i2p.net i2cp.tcp.port=7765";
        // "i2cp.tcp.host=localhost i2cp.tcp.port=7654 tunnels.inboundDepth=0"; 
        String conOptions = "i2cp.tcp.host=localhost i2cp.tcp.port=7654 tunnels.inboundDepth=0"; // "i2cp.tcp.host=dev.i2p.net i2cp.tcp.port=7002 tunnels.inboundDepth=0";
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