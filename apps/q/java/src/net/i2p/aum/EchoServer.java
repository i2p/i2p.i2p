
package net.i2p.aum;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.ConnectException;
import java.util.Properties;

import net.i2p.I2PException;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.util.Log;

/**
 * a simple program which illustrates the use of I2P stream
 * sockets from a server point of view
 */
public class EchoServer extends Thread
{
    //public I2PClient client;
    //public PrivDestination privDest;
    //public I2PSession serverSession;
    
    public I2PSocketManager socketManager;
    public I2PServerSocket serverSocket;
    
    public PrivDestination key;
    public Destination dest;
    
    protected static Log _log;
    
    public EchoServer() throws I2PException, IOException
    {
        _log = new Log("EchoServer");
    
        System.out.println("Server: creating new key");
    
    //    key = PrivDestination.newKey();
    //    System.out.println("Server: dest=" + key.toDestinationBase64());
    
        System.out.println("Server: creating socket manager");
    
        Properties props = new Properties();
        props.setProperty("inbound.length", "0");
        props.setProperty("outbound.length", "0");
        props.setProperty("inbound.lengthVariance", "0");
        props.setProperty("outbound.lengthVariance", "0");
    
        PrivDestination key = PrivDestination.newKey();
    
        // get a socket manager
    //    socketManager = I2PSocketManagerFactory.createManager(key);
        socketManager = I2PSocketManagerFactory.createManager(key.getInputStream(), props);
    
        System.out.println("Server: getting server socket");
    
        // get a server socket
        serverSocket = socketManager.getServerSocket();
    
        System.out.println("Server: got server socket, ready to run");
    
        dest = socketManager.getSession().getMyDestination();
        
        System.out.println("Server: getMyDestination->"+dest.toBase64());
        
        start();
    
    }

    /**
     * run this EchoServer
     */
    public void run()
    {
        System.out.println("Server: listening on dest:");
    
    /**
        try {
            System.out.println(key.toDestinationBase64());
        } catch (DataFormatException e) {
            e.printStackTrace();
        }
    */
    
        System.out.println(dest.toBase64());
    
        while (true)
        {
            try {
                I2PSocket sessSocket = serverSocket.accept();
                
                System.out.println("Server: Got connection from client");
    
                InputStream socketIn = sessSocket.getInputStream();
                OutputStreamWriter socketOut = new OutputStreamWriter(sessSocket.getOutputStream());
    
                System.out.println("Server: created streams");
    
                // read a line from input, and echo it back
                String line = DataHelper.readLine(socketIn);
                
                System.out.println("Server: got '" + line + "'");
    
                String reply = "EchoServer: got '" + line + "'\n";
                socketOut.write(reply);
                socketOut.flush();
    
                System.out.println("Server: sent trply");
    
                sessSocket.close();
    
                System.out.println("Server: closed socket");
    
            } catch (ConnectException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (I2PException e) {
                e.printStackTrace();
            }
    
        }
    
    }
    
    public Destination getDest() throws DataFormatException
    {
    //    return key.toDestination();
        return dest;
    }
    
    public String getDestBase64() throws DataFormatException
    {
    //    return key.toDestinationBase64();
        return dest.toBase64();
    }

    /**
     * runs EchoServer from the command shell
     */
    public static void main(String [] args)
    {
        System.out.println("Constructing an EchoServer");
    
        try {
            EchoServer myServer = new EchoServer();
            System.out.println("Got an EchoServer");
            System.out.println("Here's the dest:");
            System.out.println(myServer.getDestBase64());
    
            myServer.run();
    
        } catch (I2PException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    
    }
}

