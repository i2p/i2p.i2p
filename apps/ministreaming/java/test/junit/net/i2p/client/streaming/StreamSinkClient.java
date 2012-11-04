package net.i2p.client.streaming;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.util.Properties;
import java.util.Random;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Simple streaming lib test app that connects to a given destination and sends 
 * it a particular amount of random data, then disconnects.
 * @see #main(java.lang.String[])
 */
public class StreamSinkClient {
    private Log _log;
    private int _sendSize;
    private int _writeDelay;
    private String _peerDestFile;
    private String _i2cpHost;
    private int _i2cpPort;

    
    /**
     * Build the client but don't fire it up.
     * @param sendSize how many KB to send
     * @param writeDelayMs how long to wait between each .write (0 for no delay)
     * @param serverDestFile file containing the StreamSinkServer's binary Destination
     */
    public StreamSinkClient(int sendSize, int writeDelayMs, String serverDestFile) {
        this(null, -1, sendSize, writeDelayMs, serverDestFile);
    }
    public StreamSinkClient(String i2cpHost, int i2cpPort, int sendSize, int writeDelayMs, String serverDestFile) {
        _i2cpHost = i2cpHost;
        _i2cpPort = i2cpPort;
        _sendSize = sendSize;
        _writeDelay = writeDelayMs;
        _peerDestFile = serverDestFile;
        _log = I2PAppContext.getGlobalContext().logManager().getLog(StreamSinkClient.class);
    }
    
    /**
     * Actually connect and run the client - this call blocks until completion.
     *
     */
    public void runClient() {
        I2PSocketManager mgr = null;
        if (_i2cpHost != null)
            mgr = I2PSocketManagerFactory.createManager(_i2cpHost, _i2cpPort, new Properties());
        else
            mgr = I2PSocketManagerFactory.createManager();
        Destination peer = null;
        FileInputStream fis = null;
        try { 
            fis = new FileInputStream(_peerDestFile);
            peer = new Destination();
            peer.readBytes(fis);
        } catch (IOException ioe) {
            _log.error("Error finding the peer destination to contact in " + _peerDestFile, ioe);
            return;
        } catch (DataFormatException dfe) {
            _log.error("Peer destination is not valid in " + _peerDestFile, dfe);
            return;
        } finally {
            if (fis != null) try { fis.close(); } catch (IOException ioe) {}
        }

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Send " + _sendSize + "KB to " + peer.calculateHash().toBase64());

        while (true) {
            try {
                I2PSocket sock = mgr.connect(peer);
                byte buf[] = new byte[Math.min(32*1024, _sendSize*1024)];
                Random rand = new Random();
                OutputStream out = sock.getOutputStream();
                long beforeSending = System.currentTimeMillis();
                for (int i = 0; (_sendSize < 0) || (i < _sendSize); i+= buf.length/1024) {
                    rand.nextBytes(buf);
                    out.write(buf);
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Wrote " + ((1+i*buf.length)/1024) + "/" + _sendSize + "KB");
                    if (_writeDelay > 0) {
                        try { Thread.sleep(_writeDelay); } catch (InterruptedException ie) {}
                    }   
                }
                sock.close();
                long afterSending = System.currentTimeMillis();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Sent " + _sendSize + "KB in " + (afterSending-beforeSending) + "ms");
            } catch (InterruptedIOException iie) {
                _log.error("Timeout connecting to the peer", iie);
                //return;
            } catch (NoRouteToHostException nrthe) {
                _log.error("Unable to connect to the peer", nrthe);
                //return;
            } catch (ConnectException ce) {
                _log.error("Connection already dropped", ce);
                //return;
            } catch (I2PException ie) {
                _log.error("Error connecting to the peer", ie);
                return;
            } catch (IOException ioe) {
                _log.error("IO error sending", ioe);
                return;
            }   
        }
    }

    /**
     * Fire up the client.  <code>Usage: StreamSinkClient [i2cpHost i2cpPort] sendSizeKB writeDelayMs serverDestFile [concurrentSends]</code> <br>
     * <ul>
     *  <li><b>sendSizeKB</b>: how many KB to send, or -1 for unlimited</li>
     *  <li><b>writeDelayMs</b>: how long to wait between each .write (0 for no delay)</li>
     *  <li><b>serverDestFile</b>: file containing the StreamSinkServer's binary Destination</li>
     *  <li><b>concurrentSends</b>: how many concurrent threads should send to the server at once</li>
     * </ul>
     * @param args [i2cpHost i2cpPort] sendSizeKB writeDelayMs serverDestFile [concurrentSends]
     */
    public static void main(String args[]) {
        StreamSinkClient client = null;
        int sendSizeKB = -1;
        int writeDelayMs = -1;
        int concurrent = 1;
        
        switch (args.length) {
            case 3: // fall through
            case 4:
                try {
                    sendSizeKB = Integer.parseInt(args[0]);
                } catch (NumberFormatException nfe) {
                    System.err.println("Send size invalid [" + args[0] + "]");
                    return;
                }
                try {
                    writeDelayMs = Integer.parseInt(args[1]);
                } catch (NumberFormatException nfe) {
                    System.err.println("Write delay ms invalid [" + args[1] + "]");
                    return;
                }
                if (args.length == 4) {
                    try { concurrent = Integer.parseInt(args[3]); } catch (NumberFormatException nfe) {}
                }
                client = new StreamSinkClient(sendSizeKB, writeDelayMs, args[2]);
                break;
            case 5: // fall through
            case 6:
                try { 
                    int port = Integer.parseInt(args[1]);
                    sendSizeKB = Integer.parseInt(args[2]);
                    writeDelayMs = Integer.parseInt(args[3]);
                    client = new StreamSinkClient(args[0], port, sendSizeKB, writeDelayMs, args[4]);
                } catch (NumberFormatException nfe) {
                    System.err.println("arg error");
                }
                if (args.length == 6) {
                    try { concurrent = Integer.parseInt(args[5]); } catch (NumberFormatException nfe) {}
                }
                break;
            default: 
                System.out.println("Usage: StreamSinkClient [i2cpHost i2cpPort] sendSizeKB writeDelayMs serverDestFile [concurrentSends]");
        }
        if (client != null) {
            for (int i = 0; i < concurrent; i++)
                new I2PThread(new Runner(client), "Client " + i).start();
        }   
    }
    
    private static class Runner implements Runnable {
        private StreamSinkClient _client;
        public Runner(StreamSinkClient client) {
            _client = client;
        }
        public void run() {
            _client.runClient();
        }
    }
}
