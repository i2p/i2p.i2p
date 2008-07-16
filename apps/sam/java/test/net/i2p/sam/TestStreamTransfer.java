package net.i2p.sam;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;

import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * <ol>
 *  <li>start up SAM</li>
 *  <li>Alice connects as 'Alice', gets her destination, stashes it away, and 
 *      listens for any streams, echoing back whatever she receives.</li>
 *  <li>Bob connects as 'Bob', establishes a stream to the destination Alice
 *      stashed away, sends a few bundles of data, and closes the stream.</li>
 *  <li>Alice and Bob disconnect from SAM</li>
 *  <li>SAM bridge taken down</li>
 * </ol>
 */
public class TestStreamTransfer {
    private static Log _log = new Log(TestStreamTransfer.class);
    private static String _alice = null;
    private static boolean _dead = false;
    private static Object _counterLock = new Object();
    private static int _recvCounter = 0, _closeCounter = 0;
    
    private static void runTest(String samHost, int samPort, String conOptions) {
        int nTests = 20;
        startAlice(samHost, samPort, conOptions);
        /* Start up nTests different test threads. */
        for (int i = 0; i < nTests; i++) {
            testBob("bob" + i, samHost, samPort, conOptions);
            if (i % 2 == 1)
                try { Thread.sleep(10*1000); } catch (InterruptedException ie) {}
        }
        /* Wait until the correct number of messages have been received
           by Alices and the correct number of streams have been closed
           by Bobs. */
        while (true) {
            synchronized (_counterLock) {
                if (_recvCounter == nTests * 2 && _closeCounter == nTests) {
                    break;
                }
            }
            try { Thread.sleep(1000); } catch (InterruptedException ie) {}
            _log.info("Receive counter is: " + _recvCounter + " Close counter is: " + _closeCounter);
        }
        /* Return, assuming the test has passed. */
        _log.info("Unit test passed.");
    }
    
    private static void startAlice(String host, int port, String conOptions) {
        _log.info("\n\nStarting up Alice");
        try {
            Socket s = new Socket(host, port);
            OutputStream out = s.getOutputStream();
            out.write("HELLO VERSION MIN=1.0 MAX=1.0\n".getBytes());
            BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream()));
            String line = reader.readLine();
            _log.debug("line read for valid version: " + line);
            String req = "SESSION CREATE STYLE=STREAM DESTINATION=Alice " + conOptions + "\n";
            out.write(req.getBytes());
            line = reader.readLine();
            _log.info("Response to creating the session with destination Alice: " + line);
            
            req = "NAMING LOOKUP NAME=ME\n";
            out.write(req.getBytes());
            line = reader.readLine();
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
            _alice = value;
            I2PThread aliceThread = new I2PThread(new AliceRunner(reader, out, s));
            aliceThread.setName("Alice");
            aliceThread.start();
        } catch (Exception e) {
            _log.error("Error testing for valid version", e);
        }
    }
    
    private static class AliceRunner implements Runnable {
        private BufferedReader _reader;
        private OutputStream _out;
        private Socket _s;
        /** ID (string) to base64 destination */
        private Map _streams;
        public AliceRunner(BufferedReader reader, OutputStream out, Socket s) {
            _reader = reader;
            _out = out;
            _s = s;
            _streams = Collections.synchronizedMap(new HashMap(4));
        }
        public void run() {
            while (!_dead) {
                try {
                    doRun();
                } catch (Exception e) {
                    _log.error("Error running alice", e);
                    try { _reader.close(); } catch (IOException ioe) {}
                    try { _out.close(); } catch (IOException ioe) {}
                    try { _s.close(); } catch (IOException ioe) {}
                    _streams.clear();
                    _dead = true;
                }
            }
        }
        private void doRun() throws IOException, SAMException {
            String line = _reader.readLine();
            _log.debug("Read: " + line);
            StringTokenizer tok = new StringTokenizer(line);
            String maj = tok.nextToken();
            String min = tok.nextToken();
            Properties props = SAMUtils.parseParams(tok);
            if ( ("STREAM".equals(maj)) && ("CONNECTED".equals(min)) ) {
                String dest = props.getProperty("DESTINATION");
                String id = props.getProperty("ID");
                if ( (dest == null) || (id == null) ) {
                    _log.error("Invalid STREAM CONNECTED line: [" + line + "]");
                    return;
                }
                dest = dest.trim();
                id = id.trim();
                _streams.put(id, dest);
            } else if ( ("STREAM".equals(maj)) && ("CLOSED".equals(min)) ) {
                String id = props.getProperty("ID");
                if (id == null) {
                    _log.error("Invalid STREAM CLOSED line: [" + line + "]");
                    return;
                }
                _streams.remove(id);
            } else if ( ("STREAM".equals(maj)) && ("RECEIVED".equals(min)) ) {
                String id = props.getProperty("ID");
                String size = props.getProperty("SIZE");
                if ( (id == null) || (size == null) ) {
                    _log.error("Invalid STREAM RECEIVED line: [" + line + "]");
                    return;
                }
                id = id.trim();
                size = size.trim();
                int payloadSize = -1;
                try {
                    payloadSize = Integer.parseInt(size);
                } catch (NumberFormatException nfe) {
                    _log.error("Invalid SIZE in message [" + size + "]");
                    return;
                }
                // i know, its bytes, but this test uses chars
                char payload[] = new char[payloadSize];
                int read = _reader.read(payload);
                if (read != payloadSize) {
                    _log.error("Incorrect size read - expected " + payloadSize + " got " + read);
                    return;
                }
                _log.info("\n== Received from the stream " + id + ": [" + new String(payload) + "]");
                synchronized (_counterLock) {
                    _recvCounter++;
                }
                try { Thread.sleep(5*1000); } catch (InterruptedException ie) {}
                /*
                // now echo it back
                String reply = "STREAM SEND ID=" + id + 
                               " SIZE=" + payloadSize + 
                               "\n" + new String(payload);
                _out.write(reply.getBytes());
                _out.flush();
                _log.info("Reply sent back [" + new String(reply.getBytes()) + "]");
                 */
            } else {
                _log.error("Received unsupported type [" + maj + "/"+ min + "]");
                return;
            }
        }
    }
    
    private static void testBob(String sessionName, String host, int port, String conOptions) {
        I2PThread t = new I2PThread(new TestBob(sessionName, host, port, conOptions), sessionName);
        t.start();
    }
    private static class TestBob implements Runnable {
        private String _sessionName;
        private String _host;
        private int _port;
        private String _opts;
        public TestBob(String name, String host, int port, String opts) {
            _sessionName = name;
            _host = host;
            _port = port;
            _opts = opts;
        }
        public void run() {
            doTestBob(_sessionName, _host, _port, _opts);
        }
    }
    private static void doTestBob(String sessionName, String host, int port, String conOptions) {
        _log.info("\n\nTesting " + sessionName + "\n\n\n");
        try {
            Socket s = new Socket(host, port);
            OutputStream out = s.getOutputStream();
            out.write("HELLO VERSION MIN=1.0 MAX=1.0\n".getBytes());
            BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream()));
            String line = reader.readLine();
            _log.debug("line read for valid version: " + line);
            String req = "SESSION CREATE STYLE=STREAM DESTINATION=" + sessionName + " " + conOptions + "\n";
            out.write(req.getBytes());
            line = reader.readLine();
            _log.info("Response to creating the session with destination "+ sessionName+": " + line);
            req = "STREAM CONNECT ID=42 DESTINATION=" + _alice + "\n";
            out.write(req.getBytes());
            line = reader.readLine();
            _log.info("Response to the stream connect from "+sessionName+" to Alice: " + line);
            StringTokenizer tok = new StringTokenizer(line);
            String maj = tok.nextToken();
            String min = tok.nextToken();
            Properties props = SAMUtils.parseParams(tok);
            _log.info("props = " + props);
            String result = props.getProperty("RESULT");
            if (!("OK".equals(result))) {
                _log.error("Unable to connect!");
                //_dead = true;
                return;
            }
            try { Thread.sleep(5*1000) ; } catch (InterruptedException ie) {}
            req = "STREAM SEND ID=42 SIZE=10\nBlahBlah!!";
            _log.info("\n** Sending BlahBlah!!");
            out.write(req.getBytes());
            out.flush();
            try { Thread.sleep(5*1000) ; } catch (InterruptedException ie) {}
            req = "STREAM SEND ID=42 SIZE=10\nFooBarBaz!";
            _log.info("\n** Sending FooBarBaz!");
            out.write(req.getBytes());
            out.flush();
            /* Don't delay here, so we can test whether all data is
               sent even if we do a STREAM CLOSE immediately. */
            _log.info("Sending close");
            req = "STREAM CLOSE ID=42\n";
            out.write(req.getBytes());
            out.flush();
            synchronized (_counterLock) {
                _closeCounter++;
            }
            try { Thread.sleep(30*1000); } catch (InterruptedException ie) {}
            //_dead = true;
            s.close();
        } catch (Exception e) {
            _log.error("Error testing for valid version", e);
        }
    }
    
    public static void main(String args[]) {
        // "i2cp.tcp.host=www.i2p.net i2cp.tcp.port=7765 tunnels.inboundDepth=0";
        // "i2cp.tcp.host=localhost i2cp.tcp.port=7654 tunnels.inboundDepth=0"; 
        String conOptions = "i2cp.tcp.host=localhost i2cp.tcp.port=10001 tunnels.inboundDepth=0";
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
        //try { Thread.sleep(5*1000); } catch (InterruptedException ie) {}
        //System.exit(0);
    }
}


