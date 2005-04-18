/*
 * MiniHttpServer.java
 *
 * adapted and expanded from pont.net's httpServer.java
 * Created on April 8, 2005, 3:13 PM
 */

package net.i2p.aum.http;

//***************************************
  // HTTP Server 
  // fpont June 2000
  // server implements HTTP GET method
  //***************************************

import java.net.*;
import java.io.*;
import java.util.*;
import java.lang.*;
import java.lang.reflect.*;

import org.apache.xmlrpc.*;

public class MiniHttpServer extends Thread
{
    public Object serverSocket;
    public static int defaultPort = 18000;
    public int port;
    public static Class defaultReqHandlerClass = MiniHttpRequestHandler.class;
    public Class reqHandlerClass;
    public Object reqHandlerArg = null;
    public XmlRpcServer xmlRpcServer;

    public MiniHttpServer() {
        this(defaultReqHandlerClass, defaultPort);
    }

    public MiniHttpServer(Class reqHandlerClass) {
        this(reqHandlerClass, defaultPort);
    }

    public MiniHttpServer(Class reqHandlerClass, Object hdlrArg) {
        this(reqHandlerClass, defaultPort, hdlrArg);
    }

    public MiniHttpServer(int port) {
        this(defaultReqHandlerClass, port);
    }

    public MiniHttpServer(Class reqHandlerClass, int port) {
        this(reqHandlerClass, port, null);
    }
    
    public MiniHttpServer(Class reqHandlerClass, int port, Object hdlrArg) {
        super();
        this.port = port;
        this.reqHandlerClass = reqHandlerClass;
        this.reqHandlerArg = hdlrArg;
        xmlRpcServer = new XmlRpcServer();
    }

    // override these following methods if you're using sockets other than ServerSocket

    /**
     * Gets a server socket object, and assigns it to property 'serverSocket'.
     * You should override this if you're using non-socket objects
     */
    public void getServerSocket() throws IOException {

        serverSocket = new ServerSocket(port);

        try {
            System.out.println("httpServer running on port "
                + ((ServerSocket)serverSocket).getLocalPort());
        } catch (Exception e) {
            
        }
    }

    /**
     * Listens on our 'serverSocket' object for an incoming connection,
     * and returns a connected socket object. You should override this
     * if you're using non-standard socket objects
     */
    public Object acceptConnection() throws IOException {

        Socket sock = ((ServerSocket)serverSocket).accept();
        //System.out.println("New connection accepted" +
        //                    sock.getInetAddress() +
        //                    ":" + sock.getPort());
        return sock;
    }

    /**
     * Invokes constructor on the 'reqHandlerClass' with which 
     * this server was constructed.
     */
    public MiniHttpRequestHandler getRequestHandler(Object sock) throws Exception {
        //return new MiniHttpRequestHandlerImpl(sock);
        Class [] consArgTypes = {MiniHttpServer.class, Object.class, Object.class};
        Object [] consArgs = {this, sock, reqHandlerArg};
        Constructor cons = reqHandlerClass.getConstructor(consArgTypes);

        try {
            MiniHttpRequestHandler reqHdlr = (MiniHttpRequestHandler)(cons.newInstance(consArgs));
            return reqHdlr;
        } catch (InvocationTargetException e) {
            Throwable e1 = e.getTargetException();
            e1.printStackTrace();
            throw e;
        }
    }

    /**
     * Adds an xmlrpc handler.
     * Ideally, the handler object should have been constructed
     * with 'this' as an argument.
     */
    public void addXmlRpcHandler(String name, Object handler) {
        xmlRpcServer.addHandler(name, handler);
    }

    /**
     * starts up the server and enters an infinite loop,
     * forever servicing requests
     */
    public void run() {

        Object server_socket;
	
        try {
            String clsName = getClass().getName();
	    // impl-dependent - procure a server socket
            System.out.println(clsName + ": calling getServerSocket...");
            getServerSocket();
            System.out.println(clsName + ": got server socket");
	    
            // server infinite loop
            while(true) {

                System.out.println(clsName + ": awaiting inbound connection...");

                // impl-dependent - accept incoming connection
                Object sock = acceptConnection();
		
                // Construct handler to process the HTTP request message.
                try {
                    MiniHttpRequestHandler request = getRequestHandler(sock);

                    // Create a new thread to process the request.
                    Thread thread = new Thread(request);
		    
                    // Start the thread.
                    thread.start();
                }
                catch(Exception e) {
                    //System.out.println(e);
                    e.printStackTrace();
                }
            }
        }
	
        catch (IOException e) {
            System.out.println(e);
        }
    }

    public static void main(String [] args) {

        /** create a simple request handler */
        class DemoHandler extends MiniHttpRequestPage {
            public DemoHandler(MiniHttpServer server, Object sock, Object arg)
                throws Exception
            {
                super(server, sock, arg);
            }

            public void on_GET() {
                on_hit();
            }
            
            public void on_POST() {
                on_hit();
            }

            public void on_hit() {
                setContentType("text/html");
                page.head.nest("title").raw("DemoHandler");
                page.body
                    .nest("h3")
                        .raw("DemoHandler")
                        .end
                    .hr()
                    .raw("reqFile="+reqFile)
                    .br()
                    .raw("reqType="+reqType)
                    .hr()
                    .add(dumpVars())
                ;
            }
        }

        MiniHttpServer server;
        int port = defaultPort;
        if (args.length > 0) {
            try {
                port = new Integer(args[0]).intValue();
            } catch (NumberFormatException e) {
                System.out.println("Invalid port: "+args[0]);
                System.exit(1);
            }

            server = new MiniHttpServer(DemoHandler.class, port);
        }
        else {
            server = new MiniHttpServer(DemoHandler.class);
        }

        MiniDemoXmlRpcHandler hdlr = new MiniDemoXmlRpcHandler(server);
        server.addXmlRpcHandler("foo", hdlr);
        
        server.run();
    }
}

