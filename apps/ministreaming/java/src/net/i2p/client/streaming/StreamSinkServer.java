package net.i2p.client.streaming;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import java.net.ConnectException;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Listen to a destination, receiving any sockets and writing anything they 
 * send to a new file.
 *
 */
public class StreamSinkServer {
    private Log _log;
    private String _sinkDir;
    private String _destFile;
    
    /**
     * Create but do not start the streaming server.  
     *
     * @param sinkDir Directory to store received files in
     * @param ourDestFile filename to write our binary destination to
     */
    public StreamSinkServer(String sinkDir, String ourDestFile) {
        _sinkDir = sinkDir;
        _destFile = ourDestFile;
        _log = I2PAppContext.getGlobalContext().logManager().getLog(StreamSinkServer.class);
    }
    
    /**
     * Actually fire up the server - this call blocks forever (or until the server 
     * socket closes)
     * 
     */
    public void runServer() {
        I2PSocketManager mgr = I2PSocketManagerFactory.createManager();
        Destination dest = mgr.getSession().getMyDestination();
        System.out.println("Listening for connections on: " + dest.calculateHash().toBase64());
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(_destFile);
            dest.writeBytes(fos);
        } catch (IOException ioe) {
            _log.error("Error writing out our destination to " + _destFile, ioe);
            return;
        } catch (DataFormatException dfe) {
            _log.error("Error formatting the destination", dfe);
            return;
        } finally {
            if (fos != null) try { fos.close(); } catch (IOException ioe) {}
        }
        
        I2PServerSocket sock = mgr.getServerSocket();
        while (true) {
            try {
                I2PSocket curSock = sock.accept();
                handle(curSock);
            } catch (I2PException ie) {
                _log.error("Error accepting connection", ie);
                return;
            } catch (ConnectException ce) {
                _log.error("Connection already dropped", ce);
                return;
            }
        }
    }
    
    private void handle(I2PSocket socket) {
        I2PThread t = new I2PThread(new ClientRunner(socket));
        t.setName("Handle " + socket.getPeerDestination().calculateHash().toBase64().substring(0,4));
        t.start();
    }
    
    /**
     * Actually deal with a client - pull anything they send us and write it to a file.
     *
     */
    private class ClientRunner implements Runnable {
        private I2PSocket _sock;
        private FileOutputStream _fos;
        public ClientRunner(I2PSocket socket) {
            _sock = socket;
            try {
                File sink = new File(_sinkDir);
                if (!sink.exists())
                    sink.mkdirs();
                File cur = File.createTempFile("clientSink", ".dat", sink);
                _fos = new FileOutputStream(cur);
            } catch (IOException ioe) {
                _log.error("Error creating sink", ioe);
                _fos = null;
            }
        }
        public void run() {
            if (_fos == null) return;
            try {
                InputStream in = _sock.getInputStream();
                byte buf[] = new byte[4096];
                int read = 0;
                while ( (read = in.read(buf)) != -1) {
                    _fos.write(buf, 0, read);
                }
            } catch (IOException ioe) {
                _log.error("Error writing the sink", ioe);
            } finally {
                if (_fos != null) try { _fos.close(); } catch (IOException ioe) {}
            }
        }
    }
    
    /**
     * Fire up the streaming server.  <code>Usage: StreamSinkServer sinkDir ourDestFile</code><br />
     * <ul>
     *  <li><b>sinkDir</b>: Directory to store received files in</li>
     *  <li><b>ourDestFile</b>: filename to write our binary destination to</li>
     * </ul>
     */
    public static void main(String args[]) {
        if (args.length != 2) {
            System.out.println("Usage: StreamSinkServer sinkDir ourDestFile");
        } else {
            StreamSinkServer server = new StreamSinkServer(args[0], args[1]);
            server.runServer();
        }
    }
}
