package net.i2p.client.streaming;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;

import java.net.ConnectException;
import java.net.NoRouteToHostException;

import java.util.Random;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.data.Destination;
import net.i2p.data.DataFormatException;
import net.i2p.util.Log;

/**
 * Simple streaming lib test app that connects to a given destination and sends 
 * it a particular amount of random data, then disconnects.  See the {@link #main}
 *
 */
public class StreamSinkClient {
    private Log _log;
    private int _sendSize;
    private int _writeDelay;
    private String _peerDestFile;

    
    /**
     * Build the client but don't fire it up.
     * @param sendSize how many KB to send
     * @param writeDelayMs how long to wait between each .write (0 for no delay)
     * @param serverDestFile file containing the StreamSinkServer's binary Destination
     */
    public StreamSinkClient(int sendSize, int writeDelayMs, String serverDestFile) {
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
        I2PSocketManager mgr = I2PSocketManagerFactory.createManager();
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
            if (fis == null) try { fis.close(); } catch (IOException ioe) {}
        }
        
        
        System.out.println("Send " + _sendSize + "KB to " + peer.calculateHash().toBase64());
        
        try {
            I2PSocket sock = mgr.connect(peer);
            byte buf[] = new byte[32*1024];
            Random rand = new Random();
            OutputStream out = sock.getOutputStream();
            long beforeSending = System.currentTimeMillis();
            for (int i = 0; i < _sendSize; i+= 32) {
                rand.nextBytes(buf);
                out.write(buf);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Wrote " + (i+32) + "/" + _sendSize + "KB");
                if (_writeDelay > 0) {
                    try { Thread.sleep(_writeDelay); } catch (InterruptedException ie) {}
                }   
            }
            long afterSending = System.currentTimeMillis();
            System.out.println("Sent " + _sendSize + "KB in " + (afterSending-beforeSending) + "ms");
            sock.close();
        } catch (InterruptedIOException iie) {
            _log.error("Timeout connecting to the peer", iie);
            return;
        } catch (NoRouteToHostException nrthe) {
            _log.error("Unable to connect to the peer", nrthe);
            return;
        } catch (ConnectException ce) {
            _log.error("Connection already dropped", ce);
            return;
        } catch (I2PException ie) {
            _log.error("Error connecting to the peer", ie);
            return;
        } catch (IOException ioe) {
            _log.error("IO error sending", ioe);
            return;
        }
    }

    /**
     * Fire up the client.  <code>Usage: StreamSinkClient sendSizeKB writeDelayMs serverDestFile</code> <br />
     * <ul>
     *  <li><b>sendSizeKB</b>: how many KB to send</li>
     *  <li><b>writeDelayMs</b>: how long to wait between each .write (0 for no delay)</li>
     *  <li><b>serverDestFile</b>: file containing the StreamSinkServer's binary Destination</li>
     * </ul>
     */
    public static void main(String args[]) {
        if (args.length != 3) {
            System.out.println("Usage: StreamSinkClient sendSizeKB writeDelayMs serverDestFile");
        } else {
            int sendSizeKB = -1;
            int writeDelayMs = -1;
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
            StreamSinkClient client = new StreamSinkClient(sendSizeKB, writeDelayMs, args[2]);
            client.runClient();
        }
    }
}
