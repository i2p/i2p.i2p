package net.i2p.client.streaming;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Listen to a destination, receiving any sockets and writing anything they 
 * send to a new file. See the {@link #main}
 *
 */
public class StreamSinkServer {
    private Log _log;
    private String _sinkDir;
    private String _destFile;
    private String _i2cpHost;
    private int _i2cpPort;
    private int _handlers;
    
    /**
     * Create but do not start the streaming server.  
     *
     * @param sinkDir Directory to store received files in
     * @param ourDestFile filename to write our binary destination to
     */
    public StreamSinkServer(String sinkDir, String ourDestFile) {
        this(sinkDir, ourDestFile, null, -1, 3);
    }
    public StreamSinkServer(String sinkDir, String ourDestFile, String i2cpHost, int i2cpPort, int handlers) {
        _sinkDir = sinkDir;
        _destFile = ourDestFile;
        _i2cpHost = i2cpHost;
        _i2cpPort = i2cpPort;
        _handlers = handlers;
        _log = I2PAppContext.getGlobalContext().logManager().getLog(StreamSinkServer.class);
    }
    
    /**
     * Actually fire up the server - this call blocks forever (or until the server 
     * socket closes)
     * 
     */
    public void runServer() {
        I2PSocketManager mgr = null;
        if (_i2cpHost != null)
            mgr = I2PSocketManagerFactory.createManager(_i2cpHost, _i2cpPort, new Properties());
        else 
            mgr = I2PSocketManagerFactory.createManager();
        Destination dest = mgr.getSession().getMyDestination();
        if (_log.shouldLog(Log.INFO))
            _log.info("Listening for connections on: " + dest.calculateHash().toBase64());
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
        startup(sock);
    }
    
    public void startup(I2PServerSocket sock) {
        for (int i = 0; i < _handlers; i++) {
            I2PThread t = new I2PThread(new ClientRunner(sock));
            t.setName("Handler " + i);
            t.setDaemon(false);
            t.start();
        }
    }
    
    /**
     * Actually deal with a client - pull anything they send us and write it to a file.
     *
     */
    private class ClientRunner implements Runnable {
        private I2PServerSocket _socket;
        public ClientRunner(I2PServerSocket socket) {
            _socket = socket;
        }
        public void run() {
            while (true) {
                try {
                    I2PSocket socket = _socket.accept();
                    if (socket != null)
                        handle(socket);
                } catch (I2PException ie) {
                    _log.error("Error accepting connection", ie);
                    return;
                } catch (ConnectException ce) {
                    _log.error("Connection already dropped", ce);
                    return;
                } catch(SocketTimeoutException ste) {
                    // ignored
                }       
            }
        }
        
        private void handle(I2PSocket sock) {
            FileOutputStream fos = null;
            try {
                File sink = new File(_sinkDir);
                if (!sink.exists())
                    sink.mkdirs();
                File cur = File.createTempFile("clientSink", ".dat", sink);
                fos = new FileOutputStream(cur);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Writing to " + cur.getAbsolutePath());
            } catch (IOException ioe) {
                _log.error("Error creating sink", ioe);
                return;
            }
            
            long start = System.currentTimeMillis();
            try {
                InputStream in = sock.getInputStream();
                byte buf[] = new byte[4096];
                long written = 0;
                int read = 0;
                while ( (read = in.read(buf)) != -1) {
                    //_fos.write(buf, 0, read);
                    written += read;
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("read and wrote " + read + " (" + written + ")");
                }
                fos.write(("written: [" + written + "]\n").getBytes());
                long lifetime = System.currentTimeMillis() - start;
                _log.info("Got EOF from client socket [written=" + written + " lifetime=" + lifetime + "]");
            } catch (IOException ioe) {
                _log.error("Error writing the sink", ioe);
            } finally {
                if (fos != null) try { fos.close(); } catch (IOException ioe) {}
                if (sock != null) try { sock.close(); } catch (IOException ioe) {}
                _log.debug("Client socket closed");
            }
        }
    }
    
    /**
     * Fire up the streaming server.  <code>Usage: StreamSinkServer [i2cpHost i2cpPort] sinkDir ourDestFile [numHandlers]</code><br>
     * <ul>
     *  <li><b>sinkDir</b>: Directory to store received files in</li>
     *  <li><b>ourDestFile</b>: filename to write our binary destination to</li>
     *  <li><b>numHandlers</b>: how many concurrent connections to handle</li>
     * </ul>
     * @param args [i2cpHost i2cpPort] sinkDir ourDestFile [numHandlers]
     */
    public static void main(String args[]) {
        StreamSinkServer server = null;
        switch (args.length) {
            case 0:
                server = new StreamSinkServer("dataDir", "server.key", "localhost", 7654, 3);
                break;
            case 2:
                server = new StreamSinkServer(args[0], args[1]);
                break;
            case 4:
            case 5:
                int handlers = 3;
                if (args.length == 5) {
                    try {
                        handlers = Integer.parseInt(args[4]);
                    } catch (NumberFormatException nfe) {}
                }
                try { 
                    int port = Integer.parseInt(args[1]);
                    server = new StreamSinkServer(args[2], args[3], args[0], port, handlers);
                } catch (NumberFormatException nfe) {
                    System.out.println("Usage: StreamSinkServer [i2cpHost i2cpPort] sinkDir ourDestFile [handlers]");
                }
                break;
            default:
                System.out.println("Usage: StreamSinkServer [i2cpHost i2cpPort] sinkDir ourDestFile [handlers]");
        }
        if (server != null)
            server.runServer();
    }
}
