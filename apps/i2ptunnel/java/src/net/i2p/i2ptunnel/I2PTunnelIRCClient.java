package net.i2p.i2ptunnel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import net.i2p.I2PAppContext;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.util.EventDispatcher;
import net.i2p.util.Log;

public class I2PTunnelIRCClient extends I2PTunnelClientBase implements Runnable {

    private static final Log _log = new Log(I2PTunnelIRCClient.class);
    
    /** used to assign unique IDs to the threads / clients.  no logic or functionality */
    private static volatile long __clientId = 0;
    
    /** list of Destination objects that we point at */
    protected List dests;
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
                              I2PTunnel tunnel) throws IllegalArgumentException {
        super(localPort, 
              ownDest, 
              l, 
              notifyThis, 
              "IRCHandler " + (++__clientId), tunnel);

        StringTokenizer tok = new StringTokenizer(destinations, ",");
        dests = new ArrayList(1);
        while (tok.hasMoreTokens()) {
            String destination = tok.nextToken();
            try {
                Destination dest = I2PTunnel.destFromName(destination);
                if (dest == null)
                    l.log("Could not resolve " + destination);
                else
                    dests.add(dest);
            } catch (DataFormatException dfe) {
                l.log("Bad format parsing \"" + destination + "\"");
            }
        }

        if (dests.size() <= 0) {
            l.log("No target destinations found");
            notifyEvent("openClientResult", "error");
            return;
        }
        
        setName(getLocalPort() + " -> IRCClient");

        startRunning();

        notifyEvent("openIRCClientResult", "ok");
    }
    
    protected void clientConnectionRun(Socket s) {
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("got a connection.");
        Destination dest = pickDestination();
        I2PSocket i2ps = null;
        try {
            i2ps = createI2PSocket(dest);
            i2ps.setReadTimeout(readTimeout);
            Thread in = new Thread(new IrcInboundFilter(s,i2ps));
            in.start();
            Thread out = new Thread(new IrcOutboundFilter(s,i2ps));
            out.start();
        } catch (Exception ex) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Error connecting", ex);
            l.log(ex.getMessage());
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
            return (Destination)dests.get(0);
        int index = I2PAppContext.getGlobalContext().random().nextInt(size);
        return (Destination)dests.get(index);
    }

    /*************************************************************************
     *
     */
    private class IrcInboundFilter implements Runnable {
        
        private Socket local;
        private I2PSocket remote;
                
        IrcInboundFilter(Socket _local, I2PSocket _remote) {
            local=_local;
            remote=_remote;
        }

        public void run() {
            InputStream input;
            OutputStream output;
            try {
                input=remote.getInputStream();
                output=local.getOutputStream();
            } catch (IOException e) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("IrcInboundFilter: no streams",e);
                return;
            }
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("IrcInboundFilter: Running.");
            while(true)
            {
                try {
                    String inmsg = DataHelper.readLine(input);
                    if(inmsg==null)
                        break;
                    if(inmsg.endsWith("\r"))
                        inmsg=inmsg.substring(0,inmsg.length()-1);
                    String outmsg = inboundFilter(inmsg);
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
                        outmsg=outmsg+"\n";
                        output.write(outmsg.getBytes());
                    } else {
                        if (_log.shouldLog(Log.WARN))
                            _log.warn("inbound BLOCKED: "+inmsg);
                    }
                } catch (IOException e1) {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("IrcInboundFilter: disconnected",e1);
                    break;
                }
            }
            try {
                local.close();
            } catch (IOException e) {
            }
            if(_log.shouldLog(Log.DEBUG))
                _log.debug("IrcInboundFilter: Done.");
            }
            
        }
                
        /*************************************************************************
         *
         */
        private class IrcOutboundFilter implements Runnable {
                    
            private Socket local;
            private I2PSocket remote;
                
            IrcOutboundFilter(Socket _local, I2PSocket _remote) {
                local=_local;
                remote=_remote;
            }
                
            public void run() {
                InputStream input;
                OutputStream output;
                try {
                    input=local.getInputStream();
                    output=remote.getOutputStream();
                } catch (IOException e) {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("IrcOutboundFilter: no streams",e);
                    return;
                }
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("IrcOutboundFilter: Running.");
                while(true)
                {
                    try {
                        String inmsg = DataHelper.readLine(input);
                        if(inmsg==null)
                            break;
                        if(inmsg.endsWith("\r"))
                            inmsg=inmsg.substring(0,inmsg.length()-1);
                        String outmsg = outboundFilter(inmsg);
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
                            outmsg=outmsg+"\n";
                            output.write(outmsg.getBytes());
                        } else {
                            if (_log.shouldLog(Log.WARN))
                                _log.warn("outbound BLOCKED: "+"\""+inmsg+"\"");
                        }
                    } catch (IOException e1) {
                        if (_log.shouldLog(Log.ERROR))
                            _log.error("IrcOutboundFilter: disconnected",e1);
                        break;
                    }
                }
                try {
                    remote.close();
                } catch (IOException e) {
                }
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("IrcOutboundFilter: Done.");
            }
        }

    
    /*************************************************************************
     *
     */
    
    public static String inboundFilter(String s) {
        
        String field[]=s.split(" ",4);
        String command;
        int idx=0;
        final String[] allowedCommands =
        {
                "NOTICE",
                "PING",
                "PONG",
                "MODE",
                "JOIN",
                "NICK",
                "QUIT",
                "PART",
                "WALLOPS",
                "ERROR"
        };
        
        if(field[0].charAt(0)==':')
            idx++;
        
        command = field[idx++];
        
        idx++; //skip victim

        // Allow numerical responses
        try {
            new Integer(command);
            return s;
        } catch(NumberFormatException nfe){}
        
        // Allow all allowedCommands
        for(int i=0;i<allowedCommands.length;i++) {
            if(allowedCommands[i].equals(command))
                return s;
        }
        
        // Allow PRIVMSG, but block CTCP.
        if("PRIVMSG".equals(command))
        {
            String msg;
            msg = field[idx++];
        
            byte[] bytes = msg.getBytes();
            if(bytes[1]==0x01)
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
    
    public static String outboundFilter(String s) {
        
        String field[]=s.split(" ",3);
        String command;
        final String[] allowedCommands =
        {
                "NOTICE",
                "PONG",
                "MODE",
                "JOIN",
                "NICK",
                "WHO",
                "WHOIS",
                "LIST",
                "NAMES",
                "NICK",
                "QUIT",
                "SILENCE",
                "PART",
                "OPER",
                "PING",
                "KICK",
                "HELPME",
                "RULES"
        };
        
        if(field[0].charAt(0)==':')
            return null; // wtf
        
        command = field[0].toUpperCase();

        // Allow all allowedCommands
        for(int i=0;i<allowedCommands.length;i++)
        {
            if(allowedCommands[i].equals(command))
                return s;
        }
        
        // Allow PRIVMSG, but block CTCP (except ACTION).
        if("PRIVMSG".equals(command))
        {
            String msg;
            msg = field[2];
        
            byte[] bytes = msg.getBytes();
            if(bytes[1]==0x01)
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
        
        // Block the rest
        return null;
    }
}
