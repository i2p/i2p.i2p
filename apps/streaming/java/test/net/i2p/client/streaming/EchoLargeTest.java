package net.i2p.client.streaming;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.data.Base64;
import net.i2p.data.Destination;
import net.i2p.util.Log;

/**
 *
 */
public class EchoLargeTest {
    private Log _log;
    private I2PSession _client;
    private I2PSession _server;
    
    public void test() {
        try {
            I2PAppContext context = I2PAppContext.getGlobalContext();
            _log = context.logManager().getLog(ConnectTest.class);
            _log.debug("creating server session");
            _server = createSession();
            _log.debug("running server");
            runServer(context, _server);
            _log.debug("creating client session");
            _client = createSession();
            _log.debug("running client");
            runClient(context, _client);
        } catch (Exception e) {
            _log.error("error running", e);
        }
        try { Thread.sleep(300*1000); } catch (Exception e) {}
    }
    
    private void runClient(I2PAppContext ctx, I2PSession session) {
        Thread t = new Thread(new ClientRunner(ctx, session));
        t.setName("client");
        t.setDaemon(true);
        t.start();
    }
    
    private void runServer(I2PAppContext ctx, I2PSession session) {
        Thread t = new Thread(new ServerRunner(ctx, session));
        t.setName("server");
        t.setDaemon(true);
        t.start();
    }
    
    private class ServerRunner implements Runnable {
        private I2PAppContext _context;
        private I2PSession _session;
        private Log _log;
        public ServerRunner(I2PAppContext ctx, I2PSession session) {
            _context = ctx;
            _session = session;
            _log = ctx.logManager().getLog(ServerRunner.class);
        }
        
        public void run() {
            try {
                Properties opts = new Properties();
                I2PSocketManager mgr = new I2PSocketManagerFull(_context, _session, opts, "client");
                _log.debug("manager created");
                I2PServerSocket ssocket = mgr.getServerSocket();
                _log.debug("server socket created");
                while (true) {
                    I2PSocket socket = ssocket.accept();
                    _log.debug("socket accepted: " + socket);
                    InputStream in = socket.getInputStream();
                    OutputStream out = socket.getOutputStream();
                    _log.debug("server streams built");
                    byte buf[] = new byte[128*1024];
                    while (buf != null) {
                        for (int i = 0; i < buf.length; i++) {
                            int c = in.read();
                            if (c == -1) {
                                buf = null;
                                break;
                            } else {
                                buf[i] = (byte)(c & 0xFF);
                            }
                        }
                        if (buf != null) {
                            _log.debug("* server read the full buffer");
                            out.write(buf);
                            out.flush();
                        }
                    }
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("Closing the received server socket");
                    socket.close();
                }
            } catch (Exception e) {
                _log.error("error running", e);
            }
        }
        
    }
    
    private class ClientRunner implements Runnable {
        private I2PAppContext _context;
        private I2PSession _session;
        private Log _log;
        public ClientRunner(I2PAppContext ctx, I2PSession session) {
            _context = ctx;
            _session = session;
            _log = ctx.logManager().getLog(ClientRunner.class);
        }
        
        public void run() {
            try {
                Properties opts = new Properties();
                I2PSocketManager mgr = new I2PSocketManagerFull(_context, _session, opts, "client");
                _log.debug("manager created");
                I2PSocket socket = mgr.connect(_server.getMyDestination());
                _log.debug("socket created");
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                for (int i = 0; i < 3; i++) {
                    byte buf[] = new byte[128*1024];
                    _context.random().nextBytes(buf);
                    byte orig[] = new byte[buf.length];
                    System.arraycopy(buf, 0, orig, 0, buf.length);
                    out.write(buf);
                    _log.debug("client wrote a buffer");
                    out.flush();
                    _log.debug("client flushed");
                    
                    byte rbuf[] = new byte[buf.length];
                    for (int j = 0; j < buf.length; j++) {
                        int c = in.read();
                        if (c == -1) {
                            buf = null;
                            break;
                        } else {                
                            //_log.debug("client read: " + ((char)c));
                            if (c < 0) c += 256;
                            rbuf[j] = (byte)(c & 0xFF);
                        }
                    }
                    if (buf != null) {
                        _log.debug("* client read a full buffer");
                        int firstOff = -1;
                        for (int k = 0; k < orig.length; k++) {
                            if (orig[k] != rbuf[k]) {
                                firstOff = k;
                                break;
                            }
                        }
                        if (firstOff < 0) {
                            System.out.println("** Read match");
                        } else {
                            System.out.println("** Read does not match: first off = " + firstOff);
                            _log.error("read does not match (first off = " + firstOff + "): \n"
                                        + Base64.encode(orig) + "\n" 
                                        + Base64.encode(rbuf));
                        }
                    }
                }
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Closing the client socket");
                socket.close();
                _log.debug("socket closed");
                
                Thread.sleep(5*1000);
                System.exit(0);
            } catch (Exception e) {
                _log.error("error running", e);
            }
        }
        
    }
    
    private I2PSession createSession() {
        try {
            I2PClient client = I2PClientFactory.createClient();
            ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
            Destination dest = client.createDestination(baos);
            I2PSession sess = client.createSession(new ByteArrayInputStream(baos.toByteArray()), new Properties());
            sess.connect();
            return sess;
        } catch (Exception e) {
            _log.error("error running", e);
            throw new RuntimeException("b0rk b0rk b0rk");
        }
    }
    
    public static void main(String args[]) {
        EchoLargeTest et = new EchoLargeTest();
        et.test();
    }
}
