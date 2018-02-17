package net.i2p.i2ptunnel;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.Properties;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.crypto.SHA256Generator;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.Base32;
import net.i2p.util.EventDispatcher;
import net.i2p.util.Log;

/**
 * Simple extension to the I2PTunnelServer that filters the registration
 * sequence to pass the destination hash of the client through as the hostname,
 * so an IRC Server may track users across nick changes.
 *
 * Of course, this requires the ircd actually use the hostname sent by
 * the client rather than the IP. It is common for ircds to ignore the
 * hostname in the USER message (unless it's coming from another server)
 * since it is easily spoofed. So you have to fix or, if you are lucky,
 * configure your ircd first. At least in unrealircd and ngircd this is
 * not configurable.
 *
 * There are three options for mangling the desthash. Put the option in the
 * "custom options" section of i2ptunnel.
 *   - ircserver.method unset:            Defaults to user.
 *   - ircserver.method=user:             Use method described above.
 *   - ircserver.method=webirc:           Use the WEBIRC protocol.
 *   - ircserver.cloakKey unset:          Cloak with a random value that is persistent for
 *                                        the life of this tunnel. This is the default.
 *   - ircserver.cloakKey=somepassphrase: Cloak with the hash of the passphrase. Use this to
 *                                        have consistent mangling across restarts, or to
 *                                        have multiple IRC servers cloak consistently to
 *                                        be able to track users even when they switch servers.
 *                                        Note: don't quote or put spaces in the passphrase,
 *                                        the i2ptunnel gui can't handle it.
 *   - ircserver.webircPassword=password  The password to use for the WEBIRC protocol.
 *   - ircserver.webircSpoofIP=IP         The IP
 *   - ircserver.fakeHostname=%f.b32.i2p: Set the fake hostname sent by I2PTunnel,
 *                                        %f is the full B32 destination hash
 *                                        %c is the cloaked hash.
 *
 * There is no outbound filtering.
 *
 * @author zzz
 */
public class I2PTunnelIRCServer extends I2PTunnelServer implements Runnable {
    private final byte[] cloakKey; // 32 bytes of stuff to scramble the dest with
    private final String hostname;
    private final String method;
    private final String webircPassword;
    private final String webircSpoofIP;

    public static final String PROP_METHOD="ircserver.method";
    public static final String PROP_METHOD_DEFAULT="user";
    public static final String PROP_CLOAK="ircserver.cloakKey";
    public static final String PROP_WEBIRC_PASSWORD="ircserver.webircPassword";
    public static final String PROP_WEBIRC_SPOOF_IP="ircserver.webircSpoofIP";
    public static final String PROP_WEBIRC_SPOOF_IP_DEFAULT="127.0.0.1";
    public static final String PROP_HOSTNAME="ircserver.fakeHostname";
    public static final String PROP_HOSTNAME_DEFAULT="%f.b32.i2p";
    private static final long HEADER_TIMEOUT = 15*1000;
    private static final long TOTAL_HEADER_TIMEOUT = 2 * HEADER_TIMEOUT;
    private static final int MAX_LINE_LENGTH = 1024;
    
    private final static String ERR_UNAVAILABLE =
        ":ircserver.i2p 499 you :" +
         "This I2P IRC server is unavailable. It may be down or undergoing maintenance. " +
         "Please try again later." +
         "\r\n";

    private final static String ERR_REGISTRATION =
        ":ircserver.i2p 499 you :" +
         "Bad registration." +
         "\r\n";

    private final static String ERR_TIMEOUT =
        ":ircserver.i2p 499 you :" +
         "Timeout registering." +
         "\r\n";

    private final static String ERR_EOF =
        ":ircserver.i2p 499 you :" +
         "EOF while registering." +
         "\r\n";

    private static final String[] BAD_PROTOCOLS = {
        "GET ", "HEAD ", "POST ", "GNUTELLA CONNECT", "\023BitTorrent protocol"
    };


    /**
     * @throws IllegalArgumentException if the I2PTunnel does not contain
     *                                  valid config to contact the router
     */

    public I2PTunnelIRCServer(InetAddress host, int port, File privkey, String privkeyname, Logging l, EventDispatcher notifyThis, I2PTunnel tunnel) {
        super(host, port, privkey, privkeyname, l, notifyThis, tunnel);

        // generate a random 32 bytes, or the hash of the passphrase

        // get the properties of this server-tunnel
        Properties opts = tunnel.getClientOptions();
        
        // get method of host faking
        this.method = opts.getProperty(PROP_METHOD, PROP_METHOD_DEFAULT);
        assert this.method != null;
        
        // get the password for the webirc method
        this.webircPassword = opts.getProperty(PROP_WEBIRC_PASSWORD);

        // get the spoof IP for the webirc method
        this.webircSpoofIP = opts.getProperty(PROP_WEBIRC_SPOOF_IP, PROP_WEBIRC_SPOOF_IP_DEFAULT);
        
        // get the cloaking passphrase
        String passphrase = opts.getProperty(PROP_CLOAK);
        if (passphrase == null) {
            this.cloakKey = new byte[Hash.HASH_LENGTH];
            tunnel.getContext().random().nextBytes(this.cloakKey);
        } else {
            this.cloakKey = SHA256Generator.getInstance().calculateHash(DataHelper.getUTF8(passphrase.trim())).getData();
        }
        
        // get the fake hostmask to use
        this.hostname = opts.getProperty(PROP_HOSTNAME, PROP_HOSTNAME_DEFAULT);
    }
    
    @Override
    protected void blockingHandle(I2PSocket socket) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Incoming connection to '" + toString() + "' port " + socket.getLocalPort() +
                      " from: " + socket.getPeerDestination().calculateHash() + " port " + socket.getPort());
        try {
            String modifiedRegistration;
            if(!this.method.equals("webirc")) {
                // The headers _should_ be in the first packet, but
                // may not be, depending on the client-side options
                modifiedRegistration = filterRegistration(socket, cloakDest(socket.getPeerDestination()));
                socket.setReadTimeout(readTimeout);
            } else {
                StringBuffer buf = new StringBuffer("WEBIRC ");
                buf.append(this.webircPassword);
                buf.append(" cgiirc ");
                buf.append(cloakDest(socket.getPeerDestination()));
                buf.append(' ');
                buf.append(this.webircSpoofIP);
                buf.append("\r\n");
                modifiedRegistration = buf.toString();
            }
            Socket s = getSocket(socket.getPeerDestination().calculateHash(), socket.getLocalPort());
            Thread t = new I2PTunnelRunner(s, socket, slock, null, DataHelper.getUTF8(modifiedRegistration),
                                           null, (I2PTunnelRunner.FailCallback) null);
            // run in the unlimited client pool
            //t.start();
            _clientExecutor.execute(t);
        } catch (RegistrationException ex) {
            try {
                // Send a response so the user doesn't just see a disconnect
                // and blame his router or the network.
                socket.getOutputStream().write(ERR_REGISTRATION.getBytes("ISO-8859-1"));
            } catch (IOException ioe) {
            } finally {
                 try { socket.close(); } catch (IOException ioe) {}
            }
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error while receiving the new IRC Connection", ex);
        } catch (EOFException ex) {
            try {
                // Send a response so the user doesn't just see a disconnect
                // and blame his router or the network.
                socket.getOutputStream().write(ERR_EOF.getBytes("ISO-8859-1"));
            } catch (IOException ioe) {
            } finally {
                 try { socket.close(); } catch (IOException ioe) {}
            }
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error while receiving the new IRC Connection", ex);
        } catch (SocketTimeoutException ex) {
            try {
                // Send a response so the user doesn't just see a disconnect
                // and blame his router or the network.
                socket.getOutputStream().write(ERR_TIMEOUT.getBytes("ISO-8859-1"));
            } catch (IOException ioe) {
            } finally {
                 try { socket.close(); } catch (IOException ioe) {}
            }
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error while receiving the new IRC Connection", ex);
        } catch (SocketException ex) {
            try {
                // Send a response so the user doesn't just see a disconnect
                // and blame his router or the network.
                socket.getOutputStream().write(ERR_UNAVAILABLE.getBytes("ISO-8859-1"));
            } catch (IOException ioe) {}
            try {
                socket.close();
            } catch (IOException ioe) {}
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error connecting to IRC server " + remoteHost + ':' + remotePort, ex);
        } catch (IOException ex) {
            try {
                socket.reset();
            } catch (IOException ioe) {}
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error while receiving the new IRC Connection", ex);
        } catch (OutOfMemoryError oom) {
            try {
                socket.reset();
            } catch (IOException ioe) {}
            if (_log.shouldLog(Log.ERROR))
                _log.error("OOM in IRC server", oom);
        }
    }

    /**
     * (Optionally) append 32 bytes of crap to the destination then return
     * the first few characters of the hash of the whole thing, + ".i2p".
     * Or do we want the full hash if the ircd is going to use this for
     * nickserv auto-login? Or even Base32 if it will be used in a
     * case-insensitive manner?
     *
     */
    private String cloakDest(Destination d) {
        String hf;
        String hc;
        
        byte[] b = new byte[d.size() + this.cloakKey.length];
        System.arraycopy(b, 0, d.toByteArray(), 0, d.size());
        System.arraycopy(b, d.size(), this.cloakKey, 0, this.cloakKey.length);
        hc = Base32.encode(SHA256Generator.getInstance().calculateHash(b).getData());
        
        hf = Base32.encode(d.calculateHash().getData());
        
        return this.hostname.replace("%f", hf).replace("%c", hc);
    }

    /**
     *  Keep reading until we see USER or SERVER.
     *  This modifies the socket readTimeout, caller must save and restore.
     *
     *  @throws SocketTimeoutException if timeout is reached before newline
     *  @throws EOFException if EOF is reached before newline
     *  @throws RegistrationException if line too long
     *  @throws IOException on other errors in the underlying stream
     */
    private static String filterRegistration(I2PSocket socket, String newHostname) throws IOException {
        StringBuilder buf = new StringBuilder(128);
        int lineCount = 0;
        
        // slowloris / darkloris
        long expire = System.currentTimeMillis() + TOTAL_HEADER_TIMEOUT;
        while (true) {
            String s = readLine(socket, expire - System.currentTimeMillis());
            if (s == null)
                throw new EOFException("EOF reached before the end of the headers");
            if (lineCount == 0) {
                for (int i = 0; i < BAD_PROTOCOLS.length; i++) {
                    if (s.startsWith(BAD_PROTOCOLS[i]))
                        throw new RegistrationException("Bad protocol " + BAD_PROTOCOLS[i]);
                }
            }
            if (++lineCount > 10)
                throw new RegistrationException("Too many lines before USER or SERVER, giving up");
            if (System.currentTimeMillis() > expire)
                throw new SocketTimeoutException("Headers took too long");
            s = s.trim();
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Got line: " + s);

            String field[] = DataHelper.split(s, " ", 5);
            String command;
            int idx=0;

            try {
                if (field[0].charAt(0) == ':')
                    idx++;
                command = field[idx++].toUpperCase(Locale.US);
            } catch (IndexOutOfBoundsException ioobe) {
                throw new RegistrationException("Dropping defective message: [" + s + ']');
            }

            if ("USER".equals(command)) {
                if (field.length < idx + 4)
                    throw new RegistrationException("Too few parameters in USER message: " + s);
                // USER zzz1 hostname localhost :zzz
                //  =>
                // USER zzz1 abcd1234.i2p localhost :zzz
                // this whole class is for these two lines...
                buf.append("USER ").append(field[idx]).append(' ').append(newHostname);
                buf.append(' ');
                buf.append(field[idx+2]).append(' ').append(field[idx+3]).append("\r\n");
                break;
            }
            buf.append(s).append("\r\n");
            if ("SERVER".equals(command))
                break;
        }
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("All done, sending: " + buf.toString());
        return buf.toString();
    }
    
    /**
     *  Read a line teriminated by newline, with a total read timeout.
     *
     *  Warning - strips \n but not \r
     *  Warning - 8KB line length limit as of 0.7.13, @throws IOException if exceeded
     *  Warning - not UTF-8
     *
     *  @param timeout throws SocketTimeoutException immediately if zero or negative
     *  @throws SocketTimeoutException if timeout is reached before newline
     *  @throws EOFException if EOF is reached before newline
     *  @throws RegistrationException if line too long
     *  @throws IOException on other errors in the underlying stream
     *  @since 0.9.19 modified from DataHelper and I2PTunnelHTTPServer
     */
    private static String readLine(I2PSocket socket, long timeout) throws IOException {
        StringBuilder buf = new StringBuilder(128);
        if (timeout <= 0)
            throw new SocketTimeoutException();
        long expires = System.currentTimeMillis() + timeout;
        InputStream in = socket.getInputStream();
        int c;
        int i = 0;
        socket.setReadTimeout(timeout);
        while ( (c = in.read()) != -1) {
            if (++i > MAX_LINE_LENGTH)
                throw new RegistrationException("Line too long - max " + MAX_LINE_LENGTH);
            if (c == '\n')
                break;
            long newTimeout = expires - System.currentTimeMillis();
            if (newTimeout <= 0)
                throw new SocketTimeoutException();
            buf.append((char)c);
            if (newTimeout != timeout) {
                timeout = newTimeout;
                socket.setReadTimeout(timeout);
            }
        }
        if (c == -1) {
            if (System.currentTimeMillis() >= expires)
                throw new SocketTimeoutException();
            else
                throw new EOFException();
        }
        return buf.toString();
    }

    /**
     *  @since 0.9.19
     */
    private static class RegistrationException extends IOException {
        public RegistrationException(String s) {
            super(s);
        }
    }
}
