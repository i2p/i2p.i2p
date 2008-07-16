// runs EchoServer and EchoClient as threads

package net.i2p.aum;

import java.io.IOException;

import net.i2p.I2PException;
import net.i2p.data.Destination;

/**
 * A simple program which runs the EchoServer and EchoClient
 * demos as threads
 */

public class EchoTest
{
    /**
     * create one instance each of EchoServer and EchoClient,
     * run the server as a thread, run the client in foreground,
     * display detailed results
     */
    public static void main(String [] args)
    {
        EchoServer server;
        EchoClient client;
    
        try {
            server = new EchoServer();
            Destination serverDest = server.getDest();
    
            System.out.println("EchoTest: serverDest=" + serverDest.toBase64());
    
            client = new EchoClient(serverDest);
    
        } catch (I2PException e) {
            e.printStackTrace(); return;
        } catch (IOException e) {
            e.printStackTrace(); return;
        }
    
        System.out.println("Starting server...");
        //server.start();
    
        System.out.println("Starting client...");
        client.run();
    
    }
    
}


