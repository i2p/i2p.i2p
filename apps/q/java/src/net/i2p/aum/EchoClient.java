
// a simple I2P stream client that makes connections to an EchoServer,
// sends in stuff, and gets replies

package net.i2p.aum;

import java.lang.*;
import java.io.*;
import java.util.*;
import java.net.*;

import net.i2p.*;
import net.i2p.client.*;
import net.i2p.client.streaming.*;
import net.i2p.data.*;
import net.i2p.util.*;

/**
 * a simple program which illustrates the use of I2P stream
 * sockets from a client point of view
 */

public class EchoClient extends Thread
{
    public I2PSocketManager socketManager;
    public I2PSocket clientSocket;
    
    public Destination dest;
    
    protected static Log _log;
    
    /**
     * Creates an echoclient, given an I2P Destination object
     */
    public EchoClient(Destination remdest)
    {
        _log = new Log("EchoServer");
    
        _init(remdest);
    }
    
    /**
     * Creates an EchoClient given a destination in base64
     */
    public EchoClient(String destStr) throws DataFormatException
    {
        _log = new Log("EchoServer");
    
        Destination remdest = new Destination();
        remdest.fromBase64(destStr);
        _init(remdest);
    }
    
    private void _init(Destination remdest)
    {
        dest = remdest;
    
        System.out.println("Client: dest="+dest.toBase64());
    
        System.out.println("Client: Creating client socketManager");
    
        // get a socket manager
        socketManager = I2PSocketManagerFactory.createManager();
       
    }

    /**
     * runs the EchoClient demo
     */
    public void run()
    {
        InputStream socketIn;
        OutputStreamWriter socketOut;
        OutputStream socketOutStream;
    
        System.out.println("Client: Creating connected client socket");
        System.out.println("dest="+dest.toBase64());
    
        try {
            // get a client socket
            clientSocket = socketManager.connect(dest);
        } catch (I2PException e) {
            e.printStackTrace();
            return;
        } catch (ConnectException e) {
            e.printStackTrace();
            return;
        } catch (NoRouteToHostException e) {
            e.printStackTrace();
            return;
        } catch (InterruptedIOException e) {
            e.printStackTrace();
            return;
        }
    
        System.out.println("Client: Successfully connected!");
    
        try {
            socketIn = clientSocket.getInputStream();
            socketOutStream = clientSocket.getOutputStream();
            socketOut = new OutputStreamWriter(socketOutStream);
    
            System.out.println("Client: created streams");
        
            socketOut.write("Hi there server!\n");
            socketOut.flush();
            
            System.out.println("Client: sent to server, awaiting reply");
        
            String line = DataHelper.readLine(socketIn);
    
            System.out.println("Got reply: '" + line + "'");            
        
            clientSocket.close();
    
        } catch (NoRouteToHostException e) {
            e.printStackTrace();
            return;
        } catch (IOException e) {
            System.out.println("IOException!!");
            e.printStackTrace();
            return;
        }
    
    }

    /**
     * allows the echo client to be run from a command shell
     */
    public static void main(String [] args)
    {
        String dest64 = args[0];
        System.out.println("dest="+dest64);
    
        Destination d = new Destination();
        try {
            d.fromBase64(dest64);
        } catch (DataFormatException e) {
            e.printStackTrace();
            return;
        }
        
        EchoClient client = new EchoClient(d);
        
        System.out.println("client: running");
        client.run();
    
        System.out.println("client: done");
    
    }
    
}



