package net.i2p.client;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

import net.i2p.util.I2PThread;
import net.i2p.util.Log;

/**
 * Implement a local only router for testing purposes.  This router is minimal
 * in that it doesn't verify signatures, communicate with other routers, or handle 
 * failures very gracefully.  It is simply a test harness to allow I2CP based 
 * applications to run.
 *
 * @author jrandom
 */
public class TestServer implements Runnable {
    private final static Log _log = new Log(TestServer.class);
    private ServerSocket _socket;
    public static int LISTEN_PORT = 7654;

    protected void setPort(int port) {
        LISTEN_PORT = port;
    }

    /** 
     * Start up the socket listener, listens for connections, and
     * fires those connections off via {@link #runConnection runConnection}.  
     * This only returns if the socket cannot be opened or there is a catastrophic
     * failure.
     *
     */
    public void runServer() {
        try {
            _socket = new ServerSocket(LISTEN_PORT);
        } catch (IOException ioe) {
            _log.error("Error listening", ioe);
            return;
        }
        while (true) {
            try {
                Socket socket = _socket.accept();
                runConnection(socket);
            } catch (IOException ioe) {
                _log.error("Server error accepting", ioe);
            }
        }
    }

    /**
     * Handle the connection by passing it off to a ConnectionRunner
     *
     */
    protected void runConnection(Socket socket) throws IOException {
        ConnectionRunner runner = new ConnectionRunner(socket);
        runner.doYourThing();
    }

    public void run() {
        runServer();
    }

    /** 
     * Fire up the router
     */
    public static void main(String args[]) {
        if (args.length == 1) {
        } else if (args.length == 2) {
            try {
                LISTEN_PORT = Integer.parseInt(args[1]);
            } catch (NumberFormatException nfe) {
                _log.error("Invalid port number specified (" + args[1] + "), using " + LISTEN_PORT, nfe);
            }
        }
        TestServer server = new TestServer();
        Thread t = new I2PThread(server);
        t.start();
    }
}