
// I2P equivalent of 'netcat'

package net.i2p.aum;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.naming.HostsTxtNamingService;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.util.Log;

/**
 * A I2P equivalent of the much-beloved 'netcat' utility.
 * This command-line utility can either connect to a remote
 * destination, or listen on a private destination for incoming
 * connections. Once a connection is established, input on stdin
 * is sent to the remote peer, and anything received from the
 * remote peer is printed to stdout
 */

public class I2PCat extends Thread
{
    public I2PSocketManager socketManager;
    public I2PServerSocket serverSocket;
    public I2PSocket sessSocket;
    
    public PrivDestination key;
    public Destination dest;
    
    public InputStream socketIn;
    public OutputStream socketOutStream;
    public OutputStreamWriter socketOut;
    
    public SockInput rxThread;
    
    protected static Log _log;

    public static String defaultHost = "127.0.0.1";
    public static int defaultPort = 7654;
    
    /**
     * a thread for reading from socket and displaying on stdout
     */
    private class SockInput extends Thread {
    
        InputStream _in;
        
        protected Log _log;
        public SockInput(InputStream i) {
        
            _in = i;
        }
        
        public void run()
        {
            // the thread portion, receives incoming bytes on
            // the socket input stream and spits them to stdout
        
            byte [] ch = new byte[1];
        
            print("Receiver thread listening...");
        
            try {
                while (true) {
        
                    //String line = DataHelper.readLine(socketIn);
                    if (_in.read(ch) != 1) {
                        print("failed to receive from socket");
                        break;
                    }
        
                    //System.out.println(line);
                    System.out.write(ch, 0, 1);
                    System.out.flush();
                }
            } catch (IOException e) {
                e.printStackTrace();
                print("Receiver thread crashed, terminating!!");
                System.exit(1);
            }
        
        }
        
        
        void print(String msg)
        {
            System.out.println("-=- I2PCat: "+msg);
        
            if (_log != null) {
                _log.debug(msg);
            }
        
        }
        
        
    }
    
    
    public I2PCat()
    {
        _log = new Log("I2PCat");
    
    }
    
    /**
     * Runs I2PCat in server mode, listening on the given destination
     * for one incoming connection. Once connection is established, 
     * copyies data between the remote peer and
     * the local terminal console.
     */
    public void runServer(String keyStr) throws IOException, DataFormatException
    {
        Properties props = new Properties();
        props.setProperty("inbound.length", "0");
        props.setProperty("outbound.length", "0");
        props.setProperty("inbound.lengthVariance", "0");
        props.setProperty("outbound.lengthVariance", "0");
    
        // generate new key if needed
        if (keyStr.equals("new")) {
    
            try {
                key = PrivDestination.newKey();
            } catch (I2PException e) {
                e.printStackTrace();
                return;
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }
    
            print("Creating new server dest...");
    
            socketManager = I2PSocketManagerFactory.createManager(key.getInputStream(), props);
    
            print("Getting server socket...");
    
            serverSocket = socketManager.getServerSocket();
    
            print("Server socket created, ready to run...");
    
            dest = socketManager.getSession().getMyDestination();
            
            print("private key follows:");
            System.out.println(key.toBase64());
        
            print("dest follows:");
            System.out.println(dest.toBase64());
    
        }
    
        else {
    
            key = PrivDestination.fromBase64String(keyStr);
    
            String dest64Abbrev = key.toBase64().substring(0, 16);
    
            print("Creating server socket manager on dest "+dest64Abbrev+"...");
    
            socketManager = I2PSocketManagerFactory.createManager(key.getInputStream(), props);
    
            serverSocket = socketManager.getServerSocket();
    
            print("Server socket created, ready to run...");
        }

        print("Awaiting client connection...");
    
        I2PSocket sessSocket;
    
        try {
            sessSocket = serverSocket.accept();
        } catch (I2PException e) {
            e.printStackTrace();
            return;
        } catch (ConnectException e) {
            e.printStackTrace();
            return;
        }
                
        print("Got connection from client");
    
        chat(sessSocket);
    
    }

    public void runClient(String destStr)
        throws DataFormatException, IOException
    {
        runClient(destStr, defaultHost, defaultPort);
    }

    /**
     * runs I2PCat in client mode, connecting to a remote
     * destination then copying data between the remote peer and
     * the local terminal console
     */
    public void runClient(String destStr, String host, int port)
        throws DataFormatException, IOException
    {
        // accept 'file:' prefix
        if (destStr.startsWith("file:", 0))
        {
            String path = destStr.substring(5);
            destStr = new SimpleFile(path, "r").read();
        }
    
        else if (destStr.length() < 255) {
            // attempt hosts file lookup
            I2PAppContext ctx = new I2PAppContext();
            HostsTxtNamingService h = new HostsTxtNamingService(ctx);
            Destination dest1 = h.lookup(destStr);
            if (dest1 == null) {
                usage("Cannot resolve hostname: '"+destStr+"'");
            }
            
            // successful lookup
            runClient(dest1, host, port);
        }
    
        else {
            // otherwise, bigger strings are assumed to be base64 dests
    
            Destination dest = new Destination();
            dest.fromBase64(destStr);
            runClient(dest, host, port);
        }
    }

    public void runClient(Destination dest) {
        runClient(dest, "127.0.0.1", 7654);
    }

    /**
     * An alternative constructor which accepts an I2P Destination object
     */
    public void runClient(Destination dest, String host, int port)
    {
        this.dest = dest;
        
        String destAbbrev = dest.toBase64().substring(0, 16)+"...";
    
        print("Connecting via i2cp "+host+":"+port+" to destination "+destAbbrev+"...");
        System.out.flush();
    
        try {
            // get a socket manager
            socketManager = I2PSocketManagerFactory.createManager(host, port);

            // get a client socket
            print("socketManager="+socketManager);

            sessSocket = socketManager.connect(dest);
    
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
    
        print("Successfully connected!");
        print("(Press Control-C to quit)");
    
        // Perform console interaction
        chat(sessSocket);
    
        try {
            sessSocket.close();
    
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
    }

    /**
     * Launch the background thread to copy incoming data to stdout, then
     * loop in foreground copying lines from stdin and sending them to remote peer
     */
    public void chat(I2PSocket sessSocket) {
    
        try {
            socketIn = sessSocket.getInputStream();
            socketOutStream = sessSocket.getOutputStream();
            socketOut = new OutputStreamWriter(socketOutStream);
    
            // launch receiver thread
            start();
            //launchRx();
        
            while (true) {
        
                String line = DataHelper.readLine(System.in);
                print("sent: '"+line+"'");
    
                socketOut.write(line+"\n");
                socketOut.flush();
            }
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
    
    }
    
    /**
     * executes in a thread, receiving incoming bytes on
     * the socket input stream and spitting them to stdout
     */
    public void run()
    {
    
        byte [] ch = new byte[1];
    
        print("Receiver thread listening...");
    
        try {
            while (true) {
    
                //String line = DataHelper.readLine(socketIn);
                if (socketIn.read(ch) != 1) {
                    print("failed to receive from socket");
                    break;
                }
    
                //System.out.println(line);
                System.out.write(ch, 0, 1);
                System.out.flush();
            }
        } catch (IOException e) {
            e.printStackTrace();
            print("Receiver thread crashed, terminating!!");
            System.exit(1);
        }
    
    }
    
    
    public void launchRx() {
    
        rxThread = new SockInput(socketIn);
        rxThread.start();
    
    }
    
    static void print(String msg)
    {
        System.out.println("-=- I2PCat: "+msg);
    
        if (_log != null) {
            _log.debug(msg);
        }
    
    }
    
    public static void usage(String msg)
    {
        usage(msg, 1);
    }
    
    public static void usage(String msg, int ret)
    {
        System.out.println(msg);
        usage(ret);
    }
    
    public static void usage(int ret)
    {
        System.out.print(
            "This utility is an I2P equivalent of the standard *nix 'netcat' utility\n"+
            "usage:\n"+
            "  net.i2p.aum.I2PCat [-h]\n"+
            "    - display this help\n"+
            "  net.i2p.aum.I2PCat dest [host [port]]\n"+
            "    - run in client mode, 'dest' should be one of:\n"+
            "      hostname.i2p - an I2P hostname listed in hosts.txt\n"+
            "         (only works with a hosts.txt in current directory)\n"+
            "      base64dest - a full base64 destination string\n"+
            "      file:b64filename - filename of a file containing base64 dest\n"+
            "  net.i2p.aum.I2PCat -l privkey\n"+
            "    - run in server mode, 'key' should be one of:\n"+
            "      base64privkey - a full base64 private key string\n"+
            "      file:b64filename - filename of a file containing base64 privkey\n"+
            "\n"
            );
        System.exit(ret);
    }
    
    public static void main(String [] args) throws IOException, DataFormatException
    {
        int argc = args.length;
    
        // barf if no args
        if (argc == 0) {
            usage("Missing argument");
        }
    
        // show help on request
        if (args[0].equals("-h") || args[0].equals("--help")) {
            usage(0);
        }
    
        // server or client?
        if (args[0].equals("-l")) {
            if (argc != 2) {
                usage("Bad argument count");
            }
    
            new I2PCat().runServer(args[1]);
        }
        else {
            // client mode - barf if not 1-3 args
            if (argc < 1 || argc > 3) {
                usage("Bad argument count");
            }
    
            try {
                int port = defaultPort;
                String host = defaultHost;
                if (args.length > 1) {
                    host = args[1];
                    if (args.length > 2) {
                        port = new Integer(args[2]).intValue();
                    }
                }
                new I2PCat().runClient(args[0], host, port);

            } catch (DataFormatException e) {
                e.printStackTrace();
            }
        }
    }
    
}



