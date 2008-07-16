package net.i2p.aum;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.util.Date;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;

import org.apache.xmlrpc.XmlRpcServer;


/**
 * An XML-RPC server which works completely within I2P, listening
 * on a dest for requests.
 * You should not instantiate this class directly, but instead create
 * an I2PXmlRpcServerFactory object, and use its .newServer() method
 * to create a server object.
 */
public class I2PXmlRpcServer extends XmlRpcServer implements Runnable
{
    public class I2PXmlRpcServerWorkerThread extends Thread {

        I2PSocket _sock;
        
        public I2PXmlRpcServerWorkerThread(I2PSocket sock) {
            _sock = sock;
        }
        
        public void run() {
            
            try {
                System.out.println("I2PXmlRpcServer.run: got inbound XML-RPC I2P conn");
                
                log.info("run: Got client connection, creating streams");
                
                InputStream socketIn = _sock.getInputStream();
                OutputStreamWriter socketOut = new OutputStreamWriter(_sock.getOutputStream());
                
                log.info("run: reading http headers");
                
                // read headers, determine size of req
                int size = readHttpHeaders(socketIn);
                
                if (size <= 0) {
                    // bad news
                    log.info("read req failed, terminating session");
                    _sock.close();
                    return;
                }
                
                log.info("run: reading request body of "+size+" bytes");
                
                // get raw request body
                byte [] reqBody = new byte[size];
                for (int i=0; i<size; i++) {
                    int b = socketIn.read();
                    reqBody[i] = (byte)b;
                }
                //socketIn.read(reqBody);
                
                //log.info("reqBody='" + (new String(reqBody)) + "'");
                //System.out.println("reqBody='" + (new String(reqBody)) + "'");
                //System.out.println("reqBody:");
                //for (int ii=0; ii<reqBody.length; ii++) {
                //    System.out.println("i=" + ii + " ch="+reqBody[ii]);
                //}
                
                ByteArrayInputStream reqBodyStream = new ByteArrayInputStream(reqBody);
                
                log.info("run: executing request");
                
                System.out.println("run: executing request");
                
                // read and execute full request
                byte [] result;
                try {
                    result = execute(reqBodyStream);
                } catch (Exception e) {
                    System.out.println("run: execute failed, closing socket");
                    _sock.close();
                    System.out.println("run: closed socket");
                    throw e;
                }
                
                log.info("run: sending response");
                
                
                // fudge - manual header and response generation
                socketOut.write(
                "HTTP/1.0 200 OK\r\n" +
                "Server: I2P XML-RPC server by aum\r\n" +
                "Date: " + (new Date().toString()) + "\r\n" +
                "Content-type: text/xml\r\n" +
                "Content-length: " + String.valueOf(result.length) + "\r\n" +
                "\r\n");
                socketOut.write(new String(result));
                //socketOut.write(result);
                socketOut.flush();
                
                log.info("closing socket");
                System.out.println("closing socket");
                
                //response.setContentType ("text/xml");
                //response.setContentLength (result.length());
                //OutputStream out = response.getOutputStream();
                //out.write (result);
                //out.flush ();
                
                _sock.close();
                
                log.info("session complete");
            } catch (Exception e) {
                try {
                    e.printStackTrace();
                    _sock.close();
                } catch (Exception e1) {
                    e1.printStackTrace();
                }
            }
        }
    }

    // convenience - dest this server is listening on
    public Destination dest;
    
    // server's socket manager object
    public I2PSocketManager socketMgr;
    
    // server's socket
    public I2PServerSocket serverSocket;

    /** socket of latest incoming connection */
    public I2PSocket sessSocket;

    // set to enable debugging msgs
    public static boolean debug = false;
    
    // stream-proented xmlrpc server
    
    protected net.i2p.util.Log log;
    protected I2PAppContext i2p;

    public Thread serverThread;

    /**
     * (do not use this constructor directly)
     */
    
    public I2PXmlRpcServer(String keyStr, Properties props, I2PAppContext i2p)
        throws DataFormatException, I2PException, IOException
    {
        this(PrivDestination.fromBase64String(keyStr), props, i2p);
    }
    
    /**
     * (do not use this constructor directly)
     */
    
    public I2PXmlRpcServer(PrivDestination privKey, Properties props, I2PAppContext i2p)
        throws DataFormatException, I2PException
    {
        super();
    
        log = i2p.logManager().getLog(this.getClass());
    
        log.info("creating socket manager for key dest "
            + privKey.getDestinationBase64().substring(0, 16)
            + "...");
    
        // start by getting a socket manager
        socketMgr = I2PSocketManagerFactory.createManager(privKey.getInputStream(), props);
        if (socketMgr == null) {
            throw new I2PException("Failed to create socketManager, maybe can't reach i2cp port");
        }
        
        log.info("getting server socket, socketMgr="+socketMgr);
    
        // get a server socket
        serverSocket = socketMgr.getServerSocket();
    
        log.info("got server socket, ready to run");
    
        dest = socketMgr.getSession().getMyDestination();
        
        log.info("full dest="+dest.toBase64());
        System.out.println("full dest="+dest.toBase64());
    
    }
    
    /**
     * Run this server within the current thread of execution.
     * This function never returns. If you want to run the server
     * in a background thread, use the .start() method instead.
     */
    
    public void run()
    {
        log.info("run: listening for inbound XML-RPC requests...");
    
        while (true)
        {
            System.out.println("I2PXmlRpcServer.run: waiting for inbound XML-RPC I2P conn...");

            try {
                sessSocket = serverSocket.accept();

                I2PXmlRpcServerWorkerThread sessThread = new I2PXmlRpcServerWorkerThread(sessSocket);
                sessThread.start();

                /**
                System.out.println("I2PXmlRpcServer.run: got inbound XML-RPC I2P conn");

                log.info("run: Got client connection, creating streams");
    
                InputStream socketIn = sessSocket.getInputStream();
                OutputStreamWriter socketOut = new OutputStreamWriter(sessSocket.getOutputStream());
    
                log.info("run: reading http headers");
    
                // read headers, determine size of req
                int size = readHttpHeaders(socketIn);
    
                if (size <= 0) {
                    // bad news
                    log.info("read req failed, terminating session");
                    sessSocket.close();
                    continue;
                }
    
                log.info("run: reading request body of "+size+" bytes");

                // get raw request body
                byte [] reqBody = new byte[size];
                for (int i=0; i<size; i++) {
                    int b = socketIn.read();
                    reqBody[i] = (byte)b;
                }
                //socketIn.read(reqBody);
    
                //log.info("reqBody='" + (new String(reqBody)) + "'");
                //System.out.println("reqBody='" + (new String(reqBody)) + "'");
                //System.out.println("reqBody:");
                //for (int ii=0; ii<reqBody.length; ii++) {
                //    System.out.println("i=" + ii + " ch="+reqBody[ii]);
                //}
    
                ByteArrayInputStream reqBodyStream = new ByteArrayInputStream(reqBody);

                log.info("run: executing request");
                
                System.out.println("run: executing request");
                
                // read and execute full request
                byte [] result;
                try {
                    result = execute(reqBodyStream);
                } catch (Exception e) {
                    System.out.println("run: execute failed, closing socket");
                    sessSocket.close();
                    System.out.println("run: closed socket");
                    throw e;
                }
    
                log.info("run: sending response");
    
    
                // fudge - manual header and response generation
                socketOut.write(
                    "HTTP/1.0 200 OK\r\n" +
                    "Server: I2P XML-RPC server by aum\r\n" +
                    "Date: " + (new Date().toString()) + "\r\n" +
                    "Content-type: text/xml\r\n" +
                    "Content-length: " + String.valueOf(result.length) + "\r\n" +
                    "\r\n");
                socketOut.write(new String(result));
                socketOut.flush();
    
                log.info("closing socket");
                System.out.println("closing socket");
    
                //response.setContentType ("text/xml");
                //response.setContentLength (result.length());
                //OutputStream out = response.getOutputStream();
                //out.write (result);
                //out.flush (); 
    
                sessSocket.close();
    
                log.info("session complete");
                **/

            } catch (Exception e) {
                e.printStackTrace();
            }
    
        }
    
    }
    
    /**
     * Called as part of an incoming XML-RPC request,
     * reads and parses http headers from input stream.
     * @param in the InputStream of the socket connection from the
     * currently connected client
     * @return value of 'Content-Length' field, as the number of bytes
     * expected in the body of the request, or -1 if the request headers
     * are invalid
     * @throws IOException
     */
    
    protected int readHttpHeaders(InputStream in) throws IOException
    {
        int contentLength = -1;
    
        while (true) {
            // read/parse one line
            String line = readline(in);
    
            String [] flds = line.split(":\\s+", 2);
            log.debug("after split: flds='"+flds+"'");
    
            String hdrKey = flds[0];
    
            if (flds.length == 1) {
                // not an HTTP header
                log.info("skipping non-header, hdrKey='"+hdrKey+"'");
                continue;
            }
    
            System.out.println("I2PXmlRpcServer: '"+flds[0]+"'='"+flds[1]+"'");

            String hdrVal = flds[1];
    
            log.info("hdrKey='"+hdrKey+"', hdrVal='"+hdrVal+"'");
            
            if (hdrKey.equals("Content-Type")) {
                if (!hdrVal.equals("text/xml")) {
                    // barf - not text/xml content type
                    return -1;
                }
            }
    
            if (hdrKey.equals("Content-Length")) {
                // got our length now - done with headers
                contentLength = new Integer(hdrVal).intValue();
                break;
            }
        }
    
        log.info("Got content-length, now read remaining headers");
        
        // read and discard any remaining headers
        while (true) {
            String line = readline(in);
            int lineLen = line.length();
            log.info("line("+lineLen+")='"+line+"'");
            System.out.println("Disccarding superflous header: '"+line+"'");
            if (lineLen == 0) {
                break;
            }
        }
    
        log.info("Content length is "+contentLength);
        
        return contentLength;
    
    }
    
    /**
     * Called as part of an incoming XML-RPC request,
     * reads and parses http headers from input stream.
     * @param in the InputStream of the socket connection from the
     * currently connected client
     * @return the line read, as a string
     * @throws IOException
     */
    
    protected String readline(InputStream in) throws IOException
    {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        
        while (true) {
            int ch = in.read();
            switch (ch) {
                case '\n':
                case -1:
                    String s = os.toString();
                    log.debug("Got line '"+s+"'");
                    return os.toString();
                case '\r':
                    break;
                default:
                    os.write(ch);
            }
        }
    }
    
    /**
     * Launches the server as a background thread.
     * (To run within the calling thread, use the .run() method instead).
     */
    
    public void start()
    {
        log.debug("Starting server as a thread");
        serverThread = new Thread(this);
        serverThread.start();
    }

    /**
    public void stop()
    {
        if (serverThread != null) {
            serverThread.stop();
        }
    }
    **/

}



