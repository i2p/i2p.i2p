package net.i2p.i2ptunnel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.data.Destination;
import net.i2p.util.EventDispatcher;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 * Todo: Can we extend I2PTunnelClient instead and remove some duplicated code?
 */
public class I2PTunnelIRCClient extends I2PTunnelClientBase implements Runnable {

    /** used to assign unique IDs to the threads / clients.  no logic or functionality */
    private static volatile long __clientId = 0;
    
    /** list of Destination objects that we point at */
    protected List<Destination> dests;
    private static final long DEFAULT_READ_TIMEOUT = 5*60*1000; // -1
    protected long readTimeout = DEFAULT_READ_TIMEOUT;

    /**
     * @throws IllegalArgumentException if the I2PTunnel does not contain
     *                                  valid config to contact the router
     */
    public I2PTunnelIRCClient(
                              int localPort,
                              String destinations,
                              Logging l, 
                              boolean ownDest,
                              EventDispatcher notifyThis,
                              I2PTunnel tunnel, String pkf) throws IllegalArgumentException {
        super(localPort, 
              ownDest, 
              l, 
              notifyThis, 
              "IRC Client on " + tunnel.listenHost + ':' + localPort + " #" + (++__clientId), tunnel, pkf);
        
        StringTokenizer tok = new StringTokenizer(destinations, ", ");
        dests = new ArrayList(2);
        while (tok.hasMoreTokens()) {
            String destination = tok.nextToken();
            Destination destN = _context.namingService().lookup(destination);
            if (destN == null)
                l.log("Could not resolve " + destination);
            else
                dests.add(destN);
        }

        if (dests.isEmpty()) {
            l.log("No target destinations found");
            notifyEvent("openClientResult", "error");
            // Nothing is listening for the above event, so it's useless
            // Maybe figure out where to put a waitEventValue("openClientResult") ??
            // In the meantime, let's do this the easy way
            // Note that b32 dests will often not be resolvable at instantiation time;
            // a delayed resolution system would be even better.

            // Don't close() here, because it does a removeSession() and then
            // TunnelController can't acquire() it to release() it.
            //close(true);
            // Unfortunately, super() built the whole tunnel before we get here.
            throw new IllegalArgumentException("No valid target destinations found");
            //return;
        }
        
        setName("IRC Client on " + tunnel.listenHost + ':' + localPort);

        startRunning();

        notifyEvent("openIRCClientResult", "ok");
    }
    
    protected void clientConnectionRun(Socket s) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("got a connection.");
        Destination clientDest = pickDestination();
        I2PSocket i2ps = null;
        try {
            i2ps = createI2PSocket(clientDest);
            i2ps.setReadTimeout(readTimeout);
            StringBuffer expectedPong = new StringBuffer();
            Thread in = new I2PAppThread(new IrcInboundFilter(s,i2ps, expectedPong, _log), "IRC Client " + __clientId + " in", true);
            in.start();
            Thread out = new I2PAppThread(new IrcOutboundFilter(s,i2ps, expectedPong, _log), "IRC Client " + __clientId + " out", true);
            out.start();
        } catch (Exception ex) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error connecting", ex);
            //l.log("Error connecting: " + ex.getMessage());
            closeSocket(s);
            if (i2ps != null) {
                synchronized (sockLock) {
                    mySockets.remove(sockLock);
                }
            }
        }

    }
    
    private final Destination pickDestination() {
        int size = dests.size();
        if (size <= 0) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("No client targets?!");
            return null;
        }
        if (size == 1) // skip the rand in the most common case
            return dests.get(0);
        int index = _context.random().nextInt(size);
        return dests.get(index);
    }

    /*************************************************************************
     *
     */
    public static class IrcInboundFilter implements Runnable {
        
        private Socket local;
        private I2PSocket remote;
        private StringBuffer expectedPong;
        private final Log _log;
                
        public IrcInboundFilter(Socket _local, I2PSocket _remote, StringBuffer pong, Log log) {
            local=_local;
            remote=_remote;
            expectedPong=pong;
            _log = log;
        }

        public void run() {
            // Todo: Don't use BufferedReader - IRC spec limits line length to 512 but...
            BufferedReader in;
            OutputStream output;
            try {
                in = new BufferedReader(new InputStreamReader(remote.getInputStream(), "ISO-8859-1"));
                output=local.getOutputStream();
            } catch (IOException e) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("IrcInboundFilter: no streams",e);
                return;
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("IrcInboundFilter: Running.");
            try {
                while(true)
                {
                    try {
                        String inmsg = in.readLine();
                        if(inmsg==null)
                            break;
                        if(inmsg.endsWith("\r"))
                            inmsg=inmsg.substring(0,inmsg.length()-1);
                        if (_log.shouldLog(Log.DEBUG))
                            _log.debug("in: [" + inmsg + "]");
                        String outmsg = inboundFilter(inmsg, expectedPong);
                        if(outmsg!=null)
                        {
                            if(!inmsg.equals(outmsg)) {
                                if (_log.shouldLog(Log.WARN)) {
                                    _log.warn("inbound FILTERED: "+outmsg);
                                    _log.warn(" - inbound was: "+inmsg);
                                }
                            } else {
                                if (_log.shouldLog(Log.INFO))
                                    _log.info("inbound: "+outmsg);
                            }
                            outmsg=outmsg+"\r\n";   // rfc1459 sec. 2.3
                            output.write(outmsg.getBytes("ISO-8859-1"));
                            // probably doesn't do much but can't hurt
                            output.flush();
                        } else {
                            if (_log.shouldLog(Log.WARN))
                                _log.warn("inbound BLOCKED: "+inmsg);
                        }
                    } catch (IOException e1) {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("IrcInboundFilter: disconnected",e1);
                        break;
                    }
                }
            } catch (RuntimeException re) {
                _log.error("Error filtering inbound data", re);
            } finally {
                if (local != null) try { local.close(); } catch (IOException e) {}
            }
            if(_log.shouldLog(Log.DEBUG))
                _log.debug("IrcInboundFilter: Done.");
            }
            
        }
                
        /*************************************************************************
         *
         */
        public static class IrcOutboundFilter implements Runnable {
                    
            private Socket local;
            private I2PSocket remote;
            private StringBuffer expectedPong;
            private final Log _log;
                
            public IrcOutboundFilter(Socket _local, I2PSocket _remote, StringBuffer pong, Log log) {
                local=_local;
                remote=_remote;
                expectedPong=pong;
                _log = log;
            }
                
            public void run() {
                // Todo: Don't use BufferedReader - IRC spec limits line length to 512 but...
                BufferedReader in;
                OutputStream output;
                try {
                    in = new BufferedReader(new InputStreamReader(local.getInputStream(), "ISO-8859-1"));
                    output=remote.getOutputStream();
                } catch (IOException e) {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("IrcOutboundFilter: no streams",e);
                    return;
                }
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("IrcOutboundFilter: Running.");
                try {
                    while(true)
                    {
                        try {
                            String inmsg = in.readLine();
                            if(inmsg==null)
                                break;
                            if(inmsg.endsWith("\r"))
                                inmsg=inmsg.substring(0,inmsg.length()-1);
                            if (_log.shouldLog(Log.DEBUG))
                                _log.debug("out: [" + inmsg + "]");
                            String outmsg = outboundFilter(inmsg, expectedPong);
                            if(outmsg!=null)
                            {
                                if(!inmsg.equals(outmsg)) {
                                    if (_log.shouldLog(Log.WARN)) {
                                        _log.warn("outbound FILTERED: "+outmsg);
                                        _log.warn(" - outbound was: "+inmsg);
                                    }
                                } else {
                                    if (_log.shouldLog(Log.INFO))
                                        _log.info("outbound: "+outmsg);
                                }
                                outmsg=outmsg+"\r\n";   // rfc1459 sec. 2.3
                                output.write(outmsg.getBytes("ISO-8859-1"));
                                // save 250 ms in streaming
                                output.flush();
                            } else {
                                if (_log.shouldLog(Log.WARN))
                                    _log.warn("outbound BLOCKED: "+"\""+inmsg+"\"");
                            }
                        } catch (IOException e1) {
                            if (_log.shouldLog(Log.WARN))
                                _log.warn("IrcOutboundFilter: disconnected",e1);
                            break;
                        }
                    }
                } catch (RuntimeException re) {
                    _log.error("Error filtering outbound data", re);
                } finally {
                    if (remote != null) try { remote.close(); } catch (IOException e) {}
                }
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("IrcOutboundFilter: Done.");
            }
        }

    
    /*************************************************************************
     *
     */
    
    public static String inboundFilter(String s, StringBuffer expectedPong) {
        
        String field[]=s.split(" ",4);
        String command;
        int idx=0;
        final String[] allowedCommands =
        {
                // "NOTICE", // can contain CTCP
                //"PING",
                //"PONG",
                "MODE",
                "JOIN",
                "NICK",
                "QUIT",
                "PART",
                "WALLOPS",
                "ERROR",
                "KICK",
                "H", // "hide operator status" (after kicking an op)
                "TOPIC"
        };
        
        if(field[0].charAt(0)==':')
            idx++;

        try { command = field[idx++]; }
         catch (IndexOutOfBoundsException ioobe) // wtf, server sent borked command?
        {
           //_log.warn("Dropping defective message: index out of bounds while extracting command.");
           return null;
        }

        idx++; //skip victim

        // Allow numerical responses
        try {
            new Integer(command);
            return s;
        } catch(NumberFormatException nfe){}

        
	if ("PING".equalsIgnoreCase(command))
            return "PING 127.0.0.1"; // no way to know what the ircd to i2ptunnel server con is, so localhost works
	if ("PONG".equalsIgnoreCase(command)) {
            // Turn the received ":irc.freshcoffee.i2p PONG irc.freshcoffee.i2p :127.0.0.1"
            // into ":127.0.0.1 PONG 127.0.0.1 " so that the caller can append the client's extra parameter
            // though, does 127.0.0.1 work for irc clients connecting remotely?  and for all of them?  sure would
            // be great if irc clients actually followed the RFCs here, but i guess thats too much to ask.
            // If we haven't PINGed them, or the PING we sent isn't something we know how to filter, this 
            // is blank.
            //
            // String pong = expectedPong.length() > 0 ? expectedPong.toString() : null;
            // If we aren't going to rewrite it, pass it through
            String pong = expectedPong.length() > 0 ? expectedPong.toString() : s;
            expectedPong.setLength(0);
            return pong;
        }
        
        // Allow all allowedCommands
        for(int i=0;i<allowedCommands.length;i++) {
            if(allowedCommands[i].equalsIgnoreCase(command))
                return s;
        }
        
        // Allow PRIVMSG, but block CTCP.
        if("PRIVMSG".equalsIgnoreCase(command) || "NOTICE".equalsIgnoreCase(command))
        {
            String msg;
            msg = field[idx++];
        
            if(msg.indexOf(0x01) >= 0) // CTCP marker ^A can be anywhere, not just immediately after the ':'
            {
                // CTCP
                msg=msg.substring(2);
                if(msg.startsWith("ACTION ")) {
                    // /me says hello
                    return s;
                }
                return null; // Block all other ctcp
            }
            return s;
        }
        
        // Block the rest
        return null;
    }
    
    public static String outboundFilter(String s, StringBuffer expectedPong) {
        
        String field[]=s.split(" ",3);
        String command;
        final String[] allowedCommands =
        {
                // "NOTICE", // can contain CTCP
                "MODE",
                "JOIN",
                "NICK",
                "WHO",
                "WHOIS",
                "LIST",
                "NAMES",
                "NICK",
                // "QUIT", // replace with a filtered QUIT to hide client quit messages
                "SILENCE",
                "MAP", // seems safe enough, the ircd should protect themselves though
                // "PART", // replace with filtered PART to hide client part messages
                "OPER",
                // "PONG", // replaced with a filtered PING/PONG since some clients send the server IP (thanks aardvax!) 
                // "PING", 
                "KICK",
                "HELPME",
                "RULES",
                "TOPIC",
                "ISON"    // jIRCii uses this for a ping (response is 303)
        };

        if(field[0].length()==0)
	    return null; // W T F?
	
        
        if(field[0].charAt(0)==':')
            return null; // wtf
        
        command = field[0].toUpperCase();

	if ("PING".equals(command)) {
            // Most clients just send a PING and are happy with any old PONG.  Others,
            // like BitchX, actually expect certain behavior.  It sends two different pings:
            // "PING :irc.freshcoffee.i2p" and "PING 1234567890 127.0.0.1" (where the IP is the proxy)
            // the PONG to the former seems to be "PONG 127.0.0.1", while the PONG to the later is
            // ":irc.freshcoffee.i2p PONG irc.freshcoffe.i2p :1234567890".
            // We don't want to send them our proxy's IP address, so we need to rewrite the PING
            // sent to the server, but when we get a PONG back, use what we expected, rather than
            // what they sent.
            //
            // Yuck.

            String rv = null;
            expectedPong.setLength(0);
            if (field.length == 1) { // PING
                rv = "PING";
                // If we aren't rewriting the PING don't rewrite the PONG
                // expectedPong.append("PONG 127.0.0.1");
            } else if (field.length == 2) { // PING nonce
                rv = "PING " + field[1];
                // If we aren't rewriting the PING don't rewrite the PONG
                // expectedPong.append("PONG ").append(field[1]);
            } else if (field.length == 3) { // PING nonce serverLocation
                rv = "PING " + field[1];
                expectedPong.append("PONG ").append(field[2]).append(" :").append(field[1]); // PONG serverLocation nonce
            } else {
                //if (_log.shouldLog(Log.ERROR))
                //    _log.error("IRC client sent a PING we don't understand, filtering it (\"" + s + "\")");
                rv = null;
            }
            
            //if (_log.shouldLog(Log.WARN))
            //    _log.warn("sending ping [" + rv + "], waiting for [" + expectedPong + "] orig was [" + s  + "]");
            
            return rv;
        }
	if ("PONG".equals(command))
            return "PONG 127.0.0.1"; // no way to know what the ircd to i2ptunnel server con is, so localhost works

        // Allow all allowedCommands
        for(int i=0;i<allowedCommands.length;i++)
        {
            if(allowedCommands[i].equals(command))
                return s;
        }
        
        // mIRC sends "NOTICE user :DCC Send file (IP)"
        // in addition to the CTCP version
        if("NOTICE".equals(command))
        {
            String msg = field[2];
            if(msg.startsWith(":DCC "))
                return null;
            // fall through
        }
        
        // Allow PRIVMSG, but block CTCP (except ACTION).
        if("PRIVMSG".equals(command) || "NOTICE".equals(command))
        {
            String msg;
            msg = field[2];
        
            if(msg.indexOf(0x01) >= 0) // CTCP marker ^A can be anywhere, not just immediately after the ':'
            {
                    // CTCP
                msg=msg.substring(2);
                if(msg.startsWith("ACTION ")) {
                    // /me says hello
                    return s;
                }
                return null; // Block all other ctcp
            }
            return s;
        }
        
        if("USER".equals(command)) {
            int idx = field[2].lastIndexOf(":");
            if(idx<0)
                return "USER user hostname localhost :realname";
            String realname = field[2].substring(idx+1);
            String ret = "USER "+field[1]+" hostname localhost :"+realname;
            return ret;
        }

        if ("PART".equals(command)) {
            // hide client message
            return "PART " + field[1] + " :leaving";
        }
        
        if ("QUIT".equals(command)) {
            return "QUIT :leaving";
        }
        
        // Block the rest
        return null;
    }
}
