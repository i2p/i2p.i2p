package net.i2p.sam;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.HashMap;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.util.Iterator;
import java.util.Properties;
import java.util.Set;

import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.I2PException;
import net.i2p.util.HexDump;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * SAM STREAM session class.
 *
 * @author human
 */
public class SAMStreamSession {

    private final static Log _log = new Log(SAMStreamSession.class);

    private final static int SOCKET_HANDLER_BUF_SIZE = 32768;

    private SAMStreamReceiver recv = null;

    private SAMStreamSessionServer server = null;

    private I2PSocketManager socketMgr = null;

    private Object handlersMapLock = new Object();
    private HashMap handlersMap = new HashMap();

    private Object idLock = new Object();
    private int lastNegativeId = 0;

    // Can we create outgoing connections?
    private boolean canCreate = false;

    /**
     * Create a new SAM STREAM session.
     *
     * @param dest Base64-encoded destination (private key)
     * @param dir Session direction ("RECEIVE", "CREATE" or "BOTH")
     * @param props Properties to setup the I2P session
     * @param recv Object that will receive incoming data
     */
    public SAMStreamSession(String dest, String dir, Properties props,
                            SAMStreamReceiver recv) throws IOException, DataFormatException, SAMException {
        ByteArrayInputStream bais;

        bais = new ByteArrayInputStream(Base64.decode(dest));

        initSAMStreamSession(bais, dir, props, recv);
    }

    /**
     * Create a new SAM STREAM session.
     *
     * @param destStream Input stream containing the destination keys
     * @param dir Session direction ("RECEIVE", "CREATE" or "BOTH")
     * @param props Properties to setup the I2P session
     * @param recv Object that will receive incoming data
     */
    public SAMStreamSession(InputStream destStream, String dir,
                            Properties props,  SAMStreamReceiver recv) throws IOException, DataFormatException, SAMException {
        initSAMStreamSession(destStream, dir, props, recv);
    }

    private void initSAMStreamSession(InputStream destStream, String dir,
                                      Properties props, SAMStreamReceiver recv) throws IOException, DataFormatException, SAMException{
        this.recv = recv;

        _log.debug("SAM STREAM session instantiated");

        Properties allprops = new Properties();
        allprops.putAll(System.getProperties());
        allprops.putAll(props);

        // FIXME: we should setup I2CP host and port, too
        _log.debug("Creating I2PSocketManager...");
        socketMgr = I2PSocketManagerFactory.createManager(destStream,
                                                          "127.0.0.1",
                                                          7654, allprops);
        if (socketMgr == null) {
            throw new SAMException("Error creating I2PSocketManager");
        }

        boolean canReceive = false;
        if (dir.equals("BOTH")) {
            canCreate = true;
            canReceive = true;
        } else if (dir.equals("CREATE")) {
            canCreate = true;
        } else if (dir.equals("RECEIVE")) {
            canReceive = true;
        } else {
            _log.error("BUG! Wrong direction passed to SAMStreamSession: "
                       + dir);
            throw new SAMException("BUG! Wrong direction specified!");
        }

        if (canReceive) {
            server = new SAMStreamSessionServer();
            Thread t = new I2PThread(server, "SAMStreamSessionServer");

            t.start();
        }
    }

    /**
     * Get the SAM STREAM session Destination.
     *
     * @return The SAM STREAM session Destination.
     */
    public Destination getDestination() {
        return socketMgr.getSession().getMyDestination();
    }

    /**
     * Connect the SAM STREAM session to the specified Destination
     *
     * @param id Unique id for the connection
     * @param dest Base64-encoded Destination to connect to
     * @param props Options to be used for connection
     *
     * @throws DataFormatException if the destination is not valid
     * @throws SAMInvalidDirectionException if trying to connect through a
     *                                      receive-only session
     * @throws ConnectException if the destination refuses connections
     * @throws NoRouteToHostException if the destination can't be reached
     * @throws InterruptedIOException if the connection timeouts
     * @throws I2PException if there's another I2P-related error
     */
    public boolean connect(int id, String dest, Properties props) throws I2PException, ConnectException, NoRouteToHostException, DataFormatException, InterruptedIOException, SAMInvalidDirectionException {
        if (!canCreate) {
            _log.debug("Trying to create an outgoing connection using a receive-only session");
            throw new SAMInvalidDirectionException("Trying to create connections through a receive-only session");
        }

        if (checkSocketHandlerId(id)) {
            _log.debug("The specified id (" + id + ") is already in use");
            return false;
        }

        Destination d = new Destination();
        d.fromBase64(dest);

        // FIXME: we should config I2PSocketOptions here
        I2PSocketOptions opts = new I2PSocketOptions();
        opts.setConnectTimeout(60 * 1000);

        _log.debug("Connecting new I2PSocket...");
        I2PSocket i2ps = socketMgr.connect(d, opts);

        createSocketHandler(i2ps, id);

        return true;
    }

    /**
     * Send bytes through a SAM STREAM session.
     *
     * @param data Bytes to be sent
     *
     * @return True if the data was sent, false otherwise
     */
    public boolean sendBytes(int id, byte[] data) {
        Destination d = new Destination();
        SAMStreamSessionSocketHandler handler = getSocketHandler(id);
        
        if (handler == null) {
            _log.error("Trying to send bytes through inexistent handler " +id);
            return false;
        }

        return handler.sendBytes(data);
    }

    /**
     * Close a SAM STREAM session.
     *
     */
    public void close() {
        if (server != null) {
            server.stopRunning();
        }
        removeAllSocketHandlers();
        recv.stopStreamReceiving();
        socketMgr.destroySocketManager();
    }

    /**
     * Close a connection managed by the SAM STREAM session.
     *
     * @param id Connection id
     */
    public boolean closeConnection(int id) {
        if (!checkSocketHandlerId(id)) {
            _log.debug("The specified id (" + id + ") does not exist!");
            return false;
        }
        removeSocketHandler(id);

        return true;
    }

    /** 
     * Create a new SAM STREAM session socket handler, detaching its thread.
     *
     * @param s Socket to be handled
     * @param id Socket id, or 0 if it must be auto-generated
     *
     * @return An id associated to the socket handler
     */
    private int createSocketHandler(I2PSocket s, int id) {
        SAMStreamSessionSocketHandler handler;
        if (id == 0) {
            id = createUniqueId();
        }

        try {
            handler = new SAMStreamSessionSocketHandler(s, id);
        } catch (IOException e) {
            _log.error("IOException when creating SAM STREAM session socket handler", e);
            recv.stopStreamReceiving();
            return 0;
        }

        synchronized (handlersMapLock) {
            handlersMap.put(new Integer(id), handler);
        }

        I2PThread t = new I2PThread(handler, "SAMStreamSessionSocketHandler");
        t.start();

        return id;
    }

    /* Create an unique id, either positive or negative */
    private int createUniqueId() {
        synchronized (idLock) {
            return --lastNegativeId;
        }
    }

    /**
     * Get a SAM STREAM session socket handler.
     *
     * @param id Handler id
     */
    private SAMStreamSessionSocketHandler getSocketHandler(int id) {
        synchronized (handlersMapLock) {
            return (SAMStreamSessionSocketHandler)handlersMap.get(new Integer(id));
        }
    }

    /**
     * Check whether a SAM STREAM session socket handler id is still in use.
     *
     * @param id Handler id
     */
    private boolean checkSocketHandlerId(int id) {
        synchronized (handlersMapLock) {
            return (!(handlersMap.get(new Integer(id)) == null));
        }
    }

    /**
     * Remove and close a SAM STREAM session socket handler.
     *
     * @param id Handler id to be removed
     */
    private void removeSocketHandler(int id) {
        SAMStreamSessionSocketHandler removed;

        synchronized (handlersMapLock) {
            removed = (SAMStreamSessionSocketHandler)handlersMap.remove(new Integer(id));
        }

        if (removed == null) {
            _log.error("BUG! Trying to remove inexistent SAM STREAM session socket handler " + id);
            recv.stopStreamReceiving();
        } else {
            removed.stopRunning();
            _log.debug("Removed SAM STREAM session socket handler " + id);
        }
    }

    /**
     * Remove and close all the socket handlers managed by this SAM
     * STREAM session.
     *
     */
    private void removeAllSocketHandlers() {
        Integer id;
        Set keySet;
        Iterator iter;

        synchronized (handlersMapLock) {
            keySet = handlersMap.keySet();
            iter = keySet.iterator();
            
            while (iter.hasNext()) {
                 id = (Integer)iter.next();
                 ((SAMStreamSessionSocketHandler)handlersMap.get(id)).stopRunning();
            }
            handlersMap.clear();
        }
    }

    /**
     * SAM STREAM session server, running in its own thread.  It will
     * wait for incoming connections from the I2P network.
     *
     * @author human
     */
    public class SAMStreamSessionServer implements Runnable {

        private Object runningLock = new Object();
        private boolean stillRunning = true;

        private I2PServerSocket serverSocket = null;

        /**
         * Create a new SAM STREAM session server
         *
         */
        public SAMStreamSessionServer() {
            _log.debug("Instantiating new SAM STREAM session server");

            serverSocket = socketMgr.getServerSocket();
        }

        /**
         * Stop a SAM STREAM session server
         *
         */
        public void stopRunning() {
            _log.debug("SAMStreamSessionServer.stopRunning() invoked");
            synchronized (runningLock) {
                if (stillRunning) {
                    stillRunning = false;
                    try {
                        serverSocket.close();
                    } catch (I2PException e) {
                        _log.error("I2PException caught", e);
                    }
                }
            }
        }

        public void run() {
            _log.debug("SAM STREAM session server running");
            I2PSocket i2ps;

            while (stillRunning) {
                try {
                    i2ps = serverSocket.accept();

                    _log.debug("New incoming connection");

                    int id = createSocketHandler(i2ps, 0);
                    if (id == 0) {
                        _log.error("SAM STREAM session handler not created!");
                        i2ps.close();
                        continue;
                    }

                    _log.debug("New connection id: " + id);
                    recv.notifyStreamConnection(id, i2ps.getPeerDestination());
                } catch (I2PException e) {
                    _log.debug("Caught I2PException", e);
                    break;
                } catch (IOException e) {
                    _log.debug("Caught IOException", e);
                    break;
                }
            }

            try {
                serverSocket.close(); // In case it wasn't closed, yet
            } catch (I2PException e) {
                _log.debug("Caught I2PException", e);
            }

            _log.debug("Shutting down SAM STREAM session server");
        }
                
    }

    /**
     * SAM STREAM socket handler, running in its own thread.  It forwards
     * forward data to/from an I2P socket.
     *
     * @author human
     */
    public class SAMStreamSessionSocketHandler implements Runnable {
        
        private I2PSocket i2pSocket = null;
        private OutputStream i2pSocketOS = null;

        private Object runningLock = new Object();
        private boolean stillRunning = true;

        private int id;
                
        /**
         * Create a new SAM STREAM session socket handler
         *
         * @param s Socket to be handled
         * @param id Unique id assigned to the handler
         */
        public SAMStreamSessionSocketHandler(I2PSocket s, int id) throws IOException {
            _log.debug("Instantiating new SAM STREAM session socket handler");

            i2pSocket = s;
            i2pSocketOS = s.getOutputStream();
            this.id = id;
        }

        /**
         * Send bytes through the SAM STREAM session socket handler
         *
         * @param data Data to be sent
         *
         * @return True if data has been sent without errors, false otherwise
         */
        public boolean sendBytes(byte[] data) {
            if (_log.shouldLog(Log.DEBUG)) {
                _log.debug("Handler " + id + ": sending " + data.length
                           + " bytes");
            }
            try {
                i2pSocketOS.write(data);
            } catch (IOException e) {
                _log.error("Error sending data through I2P socket", e);
                return false;
            }

            return true;
        }

        /**
         * Stop a SAM STREAM session socket handler
         *
         */
        public void stopRunning() {
            _log.debug("stopRunning() invoked on socket handler " + id);
            synchronized (runningLock) {
                if (stillRunning) {
                    stillRunning = false;
                    try {
                        i2pSocket.close();
                    } catch (IOException e) {
                        _log.debug("Caught IOException", e);
                    }
                }
            }
        }

        public void run() {
            _log.debug("SAM STREAM session socket handler running");

            int read = -1;
            byte[] data = new byte[SOCKET_HANDLER_BUF_SIZE];

            try {
                InputStream in = i2pSocket.getInputStream();

                while (stillRunning) {
                    read = in.read(data);
                    if (read == -1) {
                        _log.debug("Handler " + id + ": connection closed");
                        break;
                    }
                    
                    recv.receiveStreamBytes(id, data, read);
                }
            } catch (IOException e) {
                _log.debug("Caught IOException", e);
            }
            
            try {
                i2pSocket.close();
            } catch (IOException e) {
                _log.debug("Caught IOException", e);
            }

            if (stillRunning) {
                removeSocketHandler(id);
                // FIXME: we need error reporting here!
                try {
                    recv.notifyStreamDisconnection(id, "OK", null);
                } catch (IOException e) {
                    _log.debug("Error sending disconnection notice for handler "
                               + id, e);
                }
            }

            _log.debug("Shutting down SAM STREAM session socket handler " +id);
        }
    }
}
