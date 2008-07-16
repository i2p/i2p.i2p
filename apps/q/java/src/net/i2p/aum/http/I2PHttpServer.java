/*
 * I2PHttpServer.java
 *
 * Created on April 8, 2005, 11:39 PM
 */

package net.i2p.aum.http;

import java.io.IOException;
import java.util.Properties;

import net.i2p.I2PException;
import net.i2p.aum.PrivDestination;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.data.DataFormatException;

/**
 *
 * @author  david
 */
public class I2PHttpServer extends MiniHttpServer {

    PrivDestination privKey;
    I2PSocketManager socketMgr;

    public I2PHttpServer(PrivDestination key)
        throws DataFormatException, IOException, I2PException
    {
        this(key, I2PHttpRequestHandler.class, null, null);
    }

    public I2PHttpServer(PrivDestination key, Class hdlrClass)
        throws DataFormatException, IOException, I2PException
    {
        this(key, hdlrClass, null, null);
    }

    public I2PHttpServer(PrivDestination key, Class hdlrClass, Properties props)
        throws DataFormatException, IOException, I2PException
    {
        this(key, hdlrClass, null, props);
    }

    /** Creates a new instance of I2PHttpServer */
    public I2PHttpServer(PrivDestination key, Class hdlrClass, Object hdlrArg, Properties props)
        throws DataFormatException, IOException, I2PException
    {
        super(hdlrClass, hdlrArg);

        if (key != null) {
            privKey = key;
        } else {
            privKey = new PrivDestination();
        }
        
        // get a socket manager
    //    socketManager = I2PSocketManagerFactory.createManager(key);
        if (props == null) {
            socketMgr = I2PSocketManagerFactory.createManager(privKey.getInputStream());
        } else {
            socketMgr = I2PSocketManagerFactory.createManager(privKey.getInputStream(), props);
        }

        if (socketMgr == null) {
            throw new I2PException("I2PHttpServer: Failed to create socketManager");
        }

        String d = privKey.getDestination().toBase64();
        System.out.println("Server: getting server socket for dest "+d);
    
        // get a server socket
        //serverSocket = socketManager.getServerSocket();
    }
    
    public void getServerSocket() throws IOException {

        I2PServerSocket sock;
        sock = socketMgr.getServerSocket();
        serverSocket = sock;
        System.out.println("listening on dest: "+privKey.getDestination().toBase64());
    }

    /**
     * Listens on our 'serverSocket' object for an incoming connection,
     * and returns a connected socket object. You should override this
     * if you're using non-standard socket objects
     */
    public Object acceptConnection() throws IOException {

        I2PSocket sock;

        try {
            sock = ((I2PServerSocket)serverSocket).accept();
        } catch (I2PException e) {
            throw new IOException(e.toString());
        }
        
        System.out.println("Got connection from: "+sock.getPeerDestination().toBase64());

        //System.out.println("New connection accepted" +
        //                    sock.getInetAddress() +
        //                    ":" + sock.getPort());
        return sock;
    }

    public static void main(String [] args) {
        try {
            System.out.println("I2PHttpServer: starting up with new random key");
            I2PHttpServer server = new I2PHttpServer((PrivDestination)null);
            System.out.println("I2PHttpServer: running server");
            server.run();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

