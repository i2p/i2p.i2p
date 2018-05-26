/*
 * I2PTunnel
 * (c) 2003 - 2004 mihi
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2, or (at
 * your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; see the file COPYING.  If not, write to
 * the Free Software Foundation, Inc., 59 Temple Place - Suite 330,
 * Boston, MA 02111-1307, USA.
 *
 * In addition, as a special exception, mihi gives permission to link
 * the code of this program with the proprietary Java implementation
 * provided by Sun (or other vendors as well), and distribute linked
 * combinations including the two. You must obey the GNU General
 * Public License in all respects for all of the code used other than
 * the proprietary Java implementation. If you modify this file, you
 * may extend this exception to your version of the file, but you are
 * not obligated to do so. If you do not wish to do so, delete this
 * exception statement from your version.
 */

package net.i2p.i2ptunnel;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

import gnu.getopt.Getopt;
import gnu.getopt.LongOpt;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.I2PSimpleClient;
import net.i2p.client.naming.NamingService;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.i2ptunnel.socks.I2PSOCKSIRCTunnel;
import net.i2p.i2ptunnel.socks.I2PSOCKSTunnel;
import net.i2p.i2ptunnel.streamr.StreamrConsumer;
import net.i2p.i2ptunnel.streamr.StreamrProducer;
import net.i2p.util.EventDispatcherImpl;
import net.i2p.util.Log;
import net.i2p.util.OrderedProperties;

/**
 *  An I2PTunnel tracks one or more I2PTunnelTasks and one or more I2PSessions.
 *  Usually one of each.
 *
 *  TODO: Most events are not listened to elsewhere, so error propagation is poor
 */
public class I2PTunnel extends EventDispatcherImpl implements Logging {
    private final Log _log;
    private final I2PAppContext _context;
    private static final AtomicLong __tunnelId = new AtomicLong();
    private final long _tunnelId;
    private final Properties _clientOptions;
    private final Set<I2PSession> _sessions;

    public static final int PACKET_DELAY = 100;

    public boolean ownDest = false;

    /** the I2CP port, non-null */
    public String port = System.getProperty(I2PClient.PROP_TCP_PORT, "7654");
    /** the I2CP host, non-null */
    public String host = System.getProperty(I2PClient.PROP_TCP_HOST, "127.0.0.1");
    /** the listen-on host. Sadly the listen-on port does not have a field. */
    public String listenHost = host;

    public long readTimeout = -1;

    private static final String nocli_args[] = { "-nocli", "-die"};

    private final List<I2PTunnelTask> tasks = new CopyOnWriteArrayList<I2PTunnelTask>();
    private int next_task_id = 1;

    private final Set<ConnectionEventListener> listeners = new CopyOnWriteArraySet<ConnectionEventListener>();

    private static final int NOGUI = 99999;
    private static final LongOpt[] longopts = new LongOpt[] {
        new LongOpt("cli", LongOpt.NO_ARGUMENT, null, 'c'),
        new LongOpt("die", LongOpt.NO_ARGUMENT, null, 'd'),
        new LongOpt("gui", LongOpt.NO_ARGUMENT, null, 'g'),
        new LongOpt("help", LongOpt.NO_ARGUMENT, null, 'h'),
        new LongOpt("nocli", LongOpt.NO_ARGUMENT, null, 'w'),
        new LongOpt("nogui", LongOpt.NO_ARGUMENT, null, NOGUI),
        new LongOpt("wait", LongOpt.NO_ARGUMENT, null, 'w')
    };

    /** @since 0.9.17 */
    private enum CloseMode { NORMAL, FORCED, DESTROY }

    public static void main(String[] args) {
        try {
            new I2PTunnel(args);
        } catch (IllegalArgumentException iae) {
            System.err.println(iae.toString());
            System.exit(1);
        }
    }

    /**
     *  Standard constructor for embedded, uses args "-nocli -die" to return immediately
     */
    public I2PTunnel() {
        this(nocli_args);
    }

    /**
     *  See usage() for options
     *  @throws IllegalArgumentException
     */
    public I2PTunnel(String[] args) {
        this(args, null);
    }

    /**
     *  See usage() for options
     *  @param lsnr may be null
     *  @throws IllegalArgumentException
     */
    public I2PTunnel(String[] args, ConnectionEventListener lsnr) {
        super();
        _context = I2PAppContext.getGlobalContext(); // new I2PAppContext();
        _tunnelId = __tunnelId.incrementAndGet();
        _log = _context.logManager().getLog(I2PTunnel.class);
        // as of 0.8.4, include context properties
        Properties p = _context.getProperties();
        _clientOptions = p;
        _sessions = new CopyOnWriteArraySet<I2PSession>();
        
        addConnectionEventListener(lsnr);
        boolean gui = true;
        boolean checkRunByE = true;
        boolean cli = true;
        boolean dontDie = true;
        boolean error = false;
        List<String> eargs = null;
        Getopt g = new Getopt("i2ptunnel", args, "d::n:c::w::e:h::", longopts);
        int c;
        while ((c = g.getopt()) != -1) {
          switch (c) {
            case 'd':  // -d, -die, --die
                dontDie = false;
                gui = false;
                cli = false;
                checkRunByE = false;
                break;

            case 'n':  // -noc, -nog, -nocli, -nogui
                String a = g.getOptarg();
                if (a.startsWith("oc")) {
                    gui = false;
                    cli = false;
                    checkRunByE = false;
                    break;
                } else if (a.startsWith("og")) {
                    // fall thru
                } else {
                    error = true;
                    break;
                }
                // fall thru for -nogui only

            case NOGUI:  // --nogui
                gui = false;
                if (_log.shouldLog(Log.WARN))
                    _log.warn(getPrefix() + "The `-nogui' option of I2PTunnel is deprecated.\n"
                          + "Use `-cli', `-nocli' (aka `-wait') or `-die' instead.");

            case 'c':  // -c, -cli, --cli
                gui = false;
                cli = true;
                checkRunByE = false;
                break;

            case 'w':  // -w, -wait, --nocli
                gui = false;
                cli = false;
                checkRunByE = false;
                break;

            case 'e':
                if (eargs == null)
                    eargs = new ArrayList<String>(4);
                eargs.add(g.getOptarg());
                if (checkRunByE) {
                    checkRunByE = false;
                    cli = false;
                }
                break;

            case 'h':
            case '?':
            case ':':
            default:
              error = true;
          }
        }

        int remaining = args.length - g.getOptind();

        if (error || remaining > 1) {
            System.err.println(usage());
            throw new IllegalArgumentException();
        }

        if (eargs != null) {
            for (String arg : eargs) {
                runCommand(arg, this);
            }
        }

        if (remaining == 1) {
            String f = args[g.getOptind()];
            File file = new File(f);
            // This is probably just a problem with the options, so
            // throw from here
            if (!file.exists()) {
                System.err.println(usage());
                throw new IllegalArgumentException("Command file does not exist: " + f);
            }
            runCommand("run " + f, this);
        }

        if (gui) {
            // removed from source, now in i2p.scripts
            //new I2PTunnelGUI(this);
            try {
                Class<?> cls = Class.forName("net.i2p.i2ptunnel.I2PTunnelGUI");
                Constructor<?> con = cls.getConstructor(I2PTunnel.class);
                con.newInstance(this);
            } catch (Throwable t) {
                throw new UnsupportedOperationException("GUI is not available, try -cli", t);
            }
        } else if (cli) {
            try {
                System.out.println("Enter 'help' for help.");
                BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
                while (true) {
                    System.out.print("I2PTunnel> ");
                    String cmd = r.readLine();
                    if (cmd == null) break;
                    if (cmd.length() <= 0) continue;
                    try {
                        runCommand(cmd, this);
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        } else if (eargs == null && remaining == 0 && dontDie) {
            System.err.println(usage());
            throw new IllegalArgumentException("Waiting for nothing! Specify gui, cli, command, command file, or die");
        }

        while (dontDie) {
            synchronized (this) {
                try {
                    wait();
                } catch (InterruptedException ie) {
                }
            }
        }
    }

    /** with newlines except for last line */
    private static String usage() {
        // not sure this all makes sense, just documenting what's above
        return
            "Usage: i2ptunnel [options] [commandFile]\n" +
            "  Default is to run the GUI.\n" +
            "  commandFile: run all commands in this file\n" +
            "  Options:\n" +
            "    -c, -cli, --cli     :  run the command line interface\n" +
            "    -d, -die, --die     :  exit immediately, do not wait for commands to finish\n" +
            "    -e 'command [args]' :  run the command\n" +
            "    -h, --help          :  display this help\n" +
            "    -nocli, --nocli     :  do not run the command line interface or GUI\n" +
            "    -nogui, --nogui     :  do not run the GUI\n" +
            "    -w, -wait, --wait   :  do not run the command line interface or GUI";
    }

    /**
     *  @return A copy, unmodifiable, non-null
     */
    List<I2PSession> getSessions() { 
        if (_sessions.isEmpty())
            return Collections.emptyList();
        return new ArrayList<I2PSession>(_sessions); 
    }

    /**
     *  @param session null ok
     */
    void addSession(I2PSession session) { 
        if (session == null) return;
        boolean added = _sessions.add(session);
        if (added && _log.shouldLog(Log.INFO))
            _log.info(getPrefix() + " session added: " + session, new Exception());
    }

    /**
     *  @param session null ok
     */
    void removeSession(I2PSession session) { 
        if (session == null) return;
        boolean removed = _sessions.remove(session);
        if (removed && _log.shouldLog(Log.INFO))
            _log.info(getPrefix() + " session removed: " + session, new Exception());
    }
    
    /**
     *  Generic options used for clients and servers.
     *  NOT a copy, Do NOT modify for per-connection options, make a copy.
     *  @return non-null, NOT a copy, do NOT modify for per-connection options
     */
    public Properties getClientOptions() { return _clientOptions; }
    
    private void addtask(I2PTunnelTask tsk) {
        tsk.setTunnel(this);
        if (tsk.isOpen()) {
            tsk.setId(next_task_id);
            next_task_id++;
            tasks.add(tsk);
            if (_log.shouldLog(Log.INFO))
                _log.info(getPrefix() + " adding task: " + tsk);
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info(getPrefix() + " not adding task that isn't open: " + tsk);
        }
    }

    /** java 1.3 vs 1.4 :)
     */
    private static String[] split(String src, String delim) {
        StringTokenizer tok = new StringTokenizer(src, delim);
        String vals[] = new String[tok.countTokens()];
        for (int i = 0; i < vals.length; i++)
            vals[i] = tok.nextToken();
        return vals;
    }

    public void runCommand(String cmd, Logging l) {
        if (cmd.indexOf(' ') == -1) cmd += ' ';
        int iii = cmd.indexOf(' ');
        String cmdname = cmd.substring(0, iii).toLowerCase(Locale.US);
        String allargs = cmd.substring(iii + 1);
        String[] args = split(allargs, " "); // .split(" "); // java 1.4

        if ("help".equals(cmdname)) {
            runHelp(l);
        } else if ("clientoptions".equals(cmdname)) {
            runClientOptions(args, l);
        } else if ("server".equals(cmdname)) {
            runServer(args, l);
        } else if ("httpserver".equals(cmdname)) {
            runHttpServer(args, l);
        } else if ("httpbidirserver".equals(cmdname)) {
            runHttpBidirServer(args, l);
        } else if ("ircserver".equals(cmdname)) {
            runIrcServer(args, l);
        } else if ("textserver".equals(cmdname)) {
            runTextServer(args, l);
        } else if ("client".equals(cmdname)) {
            runClient(args, l);
        } else if ("httpclient".equals(cmdname)) {
            runHttpClient(args, l);
        } else if ("ircclient".equals(cmdname)) {
            runIrcClient(args, l);
        } else if ("sockstunnel".equals(cmdname)) {
            runSOCKSTunnel(args, l);
        } else if ("socksirctunnel".equals(cmdname)) {
            runSOCKSIRCTunnel(args, l);
        } else if ("connectclient".equals(cmdname)) {
            runConnectClient(args, l);
        } else if ("streamrclient".equals(cmdname)) {
            runStreamrClient(args, l);
        } else if ("streamrserver".equals(cmdname)) {
            runStreamrServer(args, l);
        } else if ("config".equals(cmdname)) {
            runConfig(args, l);
        } else if ("listen_on".equals(cmdname)) {
            runListenOn(args, l);
        } else if ("read_timeout".equals(cmdname)) {
            runReadTimeout(args, l);
        } else if ("genkeys".equals(cmdname)) {
            runGenKeys(args, l);
        } else if ("gentextkeys".equals(cmdname)) {
            runGenTextKeys(l);
        } else if (cmdname.equals("quit")) {
            runQuit(l);
        } else if (cmdname.equals("list")) {
            runList(l);
        } else if (cmdname.equals("close")) {
            runClose(args, l);
        } else if (cmdname.equals("run")) {
            runRun(args, l);
        } else if (cmdname.equals("lookup")) {
            runLookup(args, l);
        } else if (cmdname.equals("ping")) {
            runPing(allargs, l);
        } else if (cmdname.equals("owndest")) {
            runOwnDest(args, l);
        } else if (cmdname.equals("auth")) {
            runAuth(args, l);
        } else {
            l.log("Unknown command [" + cmdname + "]");
        }
    }

    /**
     * Display help information through the given logger.
     *
     * Does not fire any events to the logger
     *
     * @param l logger to receive events and output
     */
    private static void runHelp(Logging l) {
        l.log("Command list:\n" +
        // alphabetical please...
              "  auth <username> <password>\n" +
              "  client <port> <pubkey>[,<pubkey,...]|file:<pubkeyfile> [<sharedClient>]\n" +
              "  clientoptions [-acx] [key=value ]*\n" +
              "  close [forced|destroy] <jobnumber>|all\n" +
              "  config [-s] <I2CPhost> <I2CPport>\n" +
              "  connectclient <port> [<sharedClient>] [<proxy>]\n" +
              "  genkeys <privkeyfile> [<pubkeyfile>]\n" +
              "  gentextkeys\n" +
              "  httpbidirserver <host> <port> <proxyport> <spoofedhost> <privkeyfile>\n" +
              "  httpclient <port> [<sharedClient>] [<proxy>]\n" +
              "  httpserver <host> <port> <spoofedhost> <privkeyfile>\n" +
              "  ircclient <port> <pubkey>[,<pubkey,...]|file:<pubkeyfile> [<sharedClient>]\n" +
              "  list\n" +
              "  listen_on <ip>\n" +
              "  lookup <name>\n" +
              "  owndest yes|no\n" +
              "  ping <args>\n" +
              "  quit\n" +
              "  read_timeout <msecs>\n" +
              "  run <commandfile>\n" +
              "  server <host> <port> <privkeyfile>\n" +
              "  socksirctunnel <port> [<sharedClient> [<privKeyFile>]]\n" +
              "  sockstunnel <port>\n" +
              "  streamrclient <host> <port> <destination>\n" +
              "  streamrserver <port> <privkeyfile>\n" +
              "  textserver <host> <port> <privkey>\n");
    }
    
    /**
     * Configure the extra I2CP options to use in any subsequent I2CP sessions.
     * Generic options used for clients and servers
     * Usage: "clientoptions[ key=value]*" .  
     *
     * Sets the event "clientoptions_onResult" = "ok" after completion.
     *
     * Deprecated To be made private, use setClientOptions().
     * This does NOT update a running TunnelTask.
     *
     * @param args each args[i] is a key=value pair to add to the options
     * @param l logger to receive events and output
     */
    public void runClientOptions(String args[], Logging l) {
        if (args != null && args.length > 0) {
            int i = 0;
            if (args[0].equals("-a")) {
                i++;
            } else if (args[0].equals("-c")) {
                _clientOptions.clear();
                l.log("Client options cleared");
                return;
            } else if (args[0].equals("-x")) {
                i++;
                for ( ; i < args.length; i++) {
                     if (_clientOptions.remove(args[i]) != null)
                        l.log("Removed " + args[i]);
                }
                return;
            } else {
                _clientOptions.clear();
            }
            for ( ; i < args.length; i++) {
                int index = args[i].indexOf('=');
                if (index <= 0) continue;
                String key = args[i].substring(0, index);
                String val = args[i].substring(index+1);
                _clientOptions.setProperty(key, val);
            }
        } else {
            l.log("Usage:\n" +
                  "  clientoptions                   // show help and list current options\n" +
                  "  clientoptions [key=value ]*     // sets current options\n" +
                  "  clientoptions -a [key=value ]*  // adds to current options\n" +
                  "  clientoptions -c                // clears current options\n" +
                  "  clientoptions -x [key ]*        // removes listed options\n" +
                  "\nCurrent options:");
            Properties p = new OrderedProperties();
            p.putAll(_clientOptions);
            for (Map.Entry<Object, Object> e : p.entrySet()) {
                l.log("  [" + e.getKey() + "] = [" + e.getValue() + ']');
            }
        }
        notifyEvent("clientoptions_onResult", "ok");
    }

    /**
     * Generic options used for clients and servers.
     * This DOES update a running TunnelTask, but NOT the session.
     * A more efficient runClientOptions().
     *
     * Defaults in opts properties are not recommended, they may or may not be honored.
     *
     * @param opts non-null
     * @since 0.9.1
     */
    public void setClientOptions(Properties opts) {
        for (Iterator<Object> iter = _clientOptions.keySet().iterator(); iter.hasNext();) {
            Object key = iter.next();
            if (!opts.containsKey(key))
                iter.remove();
        }
        _clientOptions.putAll(opts);
        for (I2PTunnelTask task : tasks) {
            task.optionsUpdated(this);
        }
        notifyEvent("clientoptions_onResult", "ok");
    }

    /**
     * Run the server pointing at the host and port specified using the private i2p
     * destination loaded from the specified file. <p>
     *
     * Sets the event "serverTaskId" = Integer(taskId) after the tunnel has been started (or -1 on error)
     * Also sets the event "openServerResult" = "ok" or "error" (displaying "Ready!" on the logger after
     * 'ok').  So, success = serverTaskId != -1 and openServerResult = ok.
     *
     * @param args {hostname, portNumber, privKeyFilename}
     * @param l logger to receive events and output
     * @throws IllegalArgumentException on config problem
     */
    public void runServer(String args[], Logging l) {
        if (args.length == 3) {
            InetAddress serverHost = null;
            int portNum = -1;
            File privKeyFile = null;
            try {
                serverHost = InetAddress.getByName(args[0]);
            } catch (UnknownHostException uhe) {
                l.log("unknown host");
                _log.error(getPrefix() + "Error resolving " + args[0], uhe);
                notifyEvent("serverTaskId", Integer.valueOf(-1));
                throw new IllegalArgumentException(getPrefix() + "Error resolving " + args[0] + uhe.getMessage());
            }

            try {
                portNum = Integer.parseInt(args[1]);
            } catch (NumberFormatException nfe) {
                l.log("invalid port");
                _log.error(getPrefix() + "Port specified is not valid: " + args[1], nfe);
                notifyEvent("serverTaskId", Integer.valueOf(-1));
            }
            if (portNum <= 0)
                throw new IllegalArgumentException(getPrefix() + "Bad port " + args[1]);

            privKeyFile = new File(args[2]);
            if (!privKeyFile.isAbsolute())
                privKeyFile = new File(_context.getConfigDir(), args[2]);
            if (!privKeyFile.canRead()) {
                l.log(getPrefix() + "Private key file does not exist or is not readable: " + args[2]);
                _log.error(getPrefix() + "Private key file does not exist or is not readable: " + args[2]);
                notifyEvent("serverTaskId", Integer.valueOf(-1));
                throw new IllegalArgumentException(getPrefix() + "Cannot open private key file " + args[2]);
            }
            I2PTunnelServer serv = new I2PTunnelServer(serverHost, portNum, privKeyFile, args[2], l, this, this);
            serv.setReadTimeout(readTimeout);
            serv.startRunning();
            addtask(serv);
            notifyEvent("serverTaskId", Integer.valueOf(serv.getId()));
            return;
        } else {
            l.log("server <host> <port> <privkeyfile>\n" +
                  "  creates a server that sends all incoming data\n" + "  of its destination to host:port.");
            notifyEvent("serverTaskId", Integer.valueOf(-1));
        }
    }

    /**
     * Same args as runServer
     * (we should stop duplicating all this code...)
     * @throws IllegalArgumentException on config problem
     */
    public void runIrcServer(String args[], Logging l) {
        if (args.length == 3) {
            InetAddress serverHost = null;
            int portNum = -1;
            File privKeyFile = null;
            try {
                serverHost = InetAddress.getByName(args[0]);
            } catch (UnknownHostException uhe) {
                l.log("unknown host");
                _log.error(getPrefix() + "Error resolving " + args[0], uhe);
                notifyEvent("serverTaskId", Integer.valueOf(-1));
                throw new IllegalArgumentException(getPrefix() + "Error resolving " + args[0] + uhe.getMessage());
            }

            try {
                portNum = Integer.parseInt(args[1]);
            } catch (NumberFormatException nfe) {
                l.log("invalid port");
                _log.error(getPrefix() + "Port specified is not valid: " + args[1], nfe);
                notifyEvent("serverTaskId", Integer.valueOf(-1));
            }
            if (portNum <= 0)
                throw new IllegalArgumentException(getPrefix() + "Bad port " + args[1]);

            privKeyFile = new File(args[2]);
            if (!privKeyFile.isAbsolute())
                privKeyFile = new File(_context.getConfigDir(), args[2]);
            if (!privKeyFile.canRead()) {
                l.log(getPrefix() + "Private key file does not exist or is not readable: " + args[2]);
                _log.error(getPrefix() + "Private key file does not exist or is not readable: " + args[2]);
                notifyEvent("serverTaskId", Integer.valueOf(-1));
                throw new IllegalArgumentException(getPrefix() + "Cannot open private key file " + args[2]);
            }
            I2PTunnelServer serv = new I2PTunnelIRCServer(serverHost, portNum, privKeyFile, args[2], l, this, this);
            serv.setReadTimeout(readTimeout);
            serv.startRunning();
            addtask(serv);
            notifyEvent("serverTaskId", Integer.valueOf(serv.getId()));
            return;
        } else {
            l.log("server <host> <port> <privkeyfile>\n" +
                  "  creates a server that sends all incoming data\n" + "  of its destination to host:port.");
            notifyEvent("serverTaskId", Integer.valueOf(-1));
        }
    }

    /**
     * Run the HTTP server pointing at the host and port specified using the private i2p
     * destination loaded from the specified file, replacing the HTTP headers
     * so that the Host: specified is the one spoofed. <p>
     *
     * Sets the event "serverTaskId" = Integer(taskId) after the tunnel has been started (or -1 on error)
     * Also sets the event "openServerResult" = "ok" or "error" (displaying "Ready!" on the logger after
     * 'ok').  So, success = serverTaskId != -1 and openServerResult = ok.
     *
     * @param args {hostname, portNumber, spoofedHost, privKeyFilename}
     * @param l logger to receive events and output
     * @throws IllegalArgumentException on config problem
     */
    public void runHttpServer(String args[], Logging l) {
        if (args.length == 4) {
            InetAddress serverHost = null;
            int portNum = -1;
            File privKeyFile = null;
            try {
                serverHost = InetAddress.getByName(args[0]);
            } catch (UnknownHostException uhe) {
                l.log("unknown host");
                _log.error(getPrefix() + "Error resolving " + args[0], uhe);
                notifyEvent("serverTaskId", Integer.valueOf(-1));
                throw new IllegalArgumentException(getPrefix() + "Error resolving " + args[0] + uhe.getMessage());
            }

            try {
                portNum = Integer.parseInt(args[1]);
            } catch (NumberFormatException nfe) {
                l.log("invalid port");
                _log.error(getPrefix() + "Port specified is not valid: " + args[1], nfe);
                notifyEvent("serverTaskId", Integer.valueOf(-1));
            }
            if (portNum <= 0)
                throw new IllegalArgumentException(getPrefix() + "Bad port " + args[1]);

            String spoofedHost = args[2];
            
            privKeyFile = new File(args[3]);
            if (!privKeyFile.isAbsolute())
                privKeyFile = new File(_context.getConfigDir(), args[3]);
            if (!privKeyFile.canRead()) {
                l.log(getPrefix() + "Private key file does not exist or is not readable: " + args[3]);
                _log.error(getPrefix() + "Private key file does not exist or is not readable: " + args[3]);
                notifyEvent("serverTaskId", Integer.valueOf(-1));
                throw new IllegalArgumentException(getPrefix() + "Cannot open private key file " + args[3]);
            }
            I2PTunnelHTTPServer serv = new I2PTunnelHTTPServer(serverHost, portNum, privKeyFile, args[3], spoofedHost, l, this, this);
            serv.setReadTimeout(readTimeout);
            serv.startRunning();
            addtask(serv);
            notifyEvent("serverTaskId", Integer.valueOf(serv.getId()));
            return;
        } else {
            l.log("httpserver <host> <port> <spoofedhost> <privkeyfile>\n" +
                  "  creates an HTTP server that sends all incoming data\n" 
                  + "  of its destination to host:port., filtering the HTTP\n" 
                  + "  headers so it looks like the request is to the spoofed host.");
            notifyEvent("serverTaskId", Integer.valueOf(-1));
        }
    }

    /**
     * Run the HTTP server pointing at the host and port specified using the private i2p
     * destination loaded from the specified file, replacing the HTTP headers
     * so that the Host: specified is the one spoofed. Also runs an HTTP proxy for
     * bidirectional communications on the same tunnel destination.<p>
     *
     * Sets the event "serverTaskId" = Integer(taskId) after the tunnel has been started (or -1 on error)
     * Also sets the event "openServerResult" = "ok" or "error" (displaying "Ready!" on the logger after
     * 'ok').  So, success = serverTaskId != -1 and openServerResult = ok.
     *
     * @param args {hostname, portNumber, proxyPortNumber, spoofedHost, privKeyFilename}
     * @param l logger to receive events and output
     * @throws IllegalArgumentException on config problem
     */
    public void runHttpBidirServer(String args[], Logging l) {
        if (args.length == 5) {
            InetAddress serverHost = null;
            int portNum = -1;
            int port2Num = -1;
            File privKeyFile = null;
            try {
                serverHost = InetAddress.getByName(args[0]);
            } catch (UnknownHostException uhe) {
                l.log("unknown host");
                _log.error(getPrefix() + "Error resolving " + args[0], uhe);
                notifyEvent("serverTaskId", Integer.valueOf(-1));
                throw new IllegalArgumentException(getPrefix() + "Error resolving " + args[0] + uhe.getMessage());
            }

            try {
                portNum = Integer.parseInt(args[1]);
            } catch (NumberFormatException nfe) {
                l.log("invalid port");
                _log.error(getPrefix() + "Port specified is not valid: " + args[1], nfe);
                notifyEvent("serverTaskId", Integer.valueOf(-1));
            }

            try {
                port2Num = Integer.parseInt(args[2]);
            } catch (NumberFormatException nfe) {
                l.log("invalid port");
                _log.error(getPrefix() + "Port specified is not valid: " + args[2], nfe);
                notifyEvent("serverTaskId", Integer.valueOf(-1));
            }
            if (portNum <= 0)
                throw new IllegalArgumentException(getPrefix() + "Bad port " + args[1]);
            if (port2Num <= 0)
                throw new IllegalArgumentException(getPrefix() + "Bad port " + args[2]);

            String spoofedHost = args[3];

            privKeyFile = new File(args[4]);
            if (!privKeyFile.isAbsolute())
                privKeyFile = new File(_context.getConfigDir(), args[4]);
            if (!privKeyFile.canRead()) {
                l.log(getPrefix() + "Private key file does not exist or is not readable: " + args[4]);
                _log.error(getPrefix() + "Private key file does not exist or is not readable: " + args[4]);
                notifyEvent("serverTaskId", Integer.valueOf(-1));
                throw new IllegalArgumentException(getPrefix() + "Cannot open private key file " + args[4]);
            }

            I2PTunnelHTTPBidirServer serv = new I2PTunnelHTTPBidirServer(serverHost, portNum, port2Num, privKeyFile, args[3], spoofedHost, l, this, this);
            serv.setReadTimeout(readTimeout);
            serv.startRunning();
            addtask(serv);
            notifyEvent("serverTaskId", Integer.valueOf(serv.getId()));
            return;
        } else {
            l.log("httpserver <host> <port> <proxyport> <spoofedhost> <privkeyfile>\n" +
                  "  creates a bidirectional HTTP server that sends all incoming data\n"
                  + "  of its destination to host:port., filtering the HTTP\n"
                  + "  headers so it looks like the request is to the spoofed host,"
                  + "  and listens to host:proxyport to proxy HTTP requests.");
            notifyEvent("serverTaskId", Integer.valueOf(-1));
        }
    }

    /**
     * Run the server pointing at the host and port specified using the private i2p
     * destination loaded from the given base64 stream. <p>
     *
     * Deprecated? Why run a server with a private destination?
     * Not available from the war GUI
     *
     * Sets the event "serverTaskId" = Integer(taskId) after the tunnel has been started (or -1 on error)
     * Also sets the event "openServerResult" = "ok" or "error" (displaying "Ready!" on the logger after
     * 'ok').  So, success = serverTaskId != -1 and openServerResult = ok.
     *
     * @param args {hostname, portNumber, privKeyBase64}
     * @param l logger to receive events and output
     * @throws IllegalArgumentException on config problem
     */
    public void runTextServer(String args[], Logging l) {
        if (args.length == 3) {
            InetAddress serverHost = null;
            int portNum = -1;
            try {
                serverHost = InetAddress.getByName(args[0]);
            } catch (UnknownHostException uhe) {
                l.log("unknown host");
                _log.error(getPrefix() + "Error resolving " + args[0], uhe);
                notifyEvent("serverTaskId", Integer.valueOf(-1));
                throw new IllegalArgumentException(getPrefix() + "Error resolving " + args[0] + uhe.getMessage());
            }

            try {
                portNum = Integer.parseInt(args[1]);
            } catch (NumberFormatException nfe) {
                l.log("invalid port");
                _log.error(getPrefix() + "Port specified is not valid: " + args[1], nfe);
                notifyEvent("serverTaskId", Integer.valueOf(-1));
            }
            if (portNum <= 0)
                throw new IllegalArgumentException(getPrefix() + "Bad port " + args[1]);

            I2PTunnelServer serv = new I2PTunnelServer(serverHost, portNum, args[2], l, this, this);
            serv.setReadTimeout(readTimeout);
            serv.startRunning();
            addtask(serv);
            notifyEvent("serverTaskId", Integer.valueOf(serv.getId()));
        } else {
            l.log("textserver <host> <port> <privkey>\n" +
                  "  creates a server that sends all incoming data\n" + "  of its destination to host:port.");
            notifyEvent("textserverTaskId", Integer.valueOf(-1));
        }
    }

    /**
     * Run the client on the given port number pointing at the specified destination
     * (either the base64 of the destination or file:fileNameContainingDestination).
     *
     * Sets the event "clientTaskId" = Integer(taskId) after the tunnel has been started (or -1 on error)
     * Also sets the event "openClientResult" = "error" or "ok" (before setting the value to "ok" it also
     * adds "Ready! Port #" to the logger as well).  In addition, it will also set "clientLocalPort" =
     * Integer port number if the client is listening
     * sharedClient parameter is a String "true" or "false"
     *
     * @param args {portNumber, destinationBase64 or "file:filename"[, sharedClient [, privKeyFile]]}
     * @param l logger to receive events and output
     * @throws IllegalArgumentException on config problem
     */
    public void runClient(String args[], Logging l) {
        boolean isShared = true;
        if (args.length >= 3)
            isShared = Boolean.parseBoolean(args[2].trim());
        if (args.length >= 2) {
            int portNum = -1;
            try {
                portNum = Integer.parseInt(args[0]);
            } catch (NumberFormatException nfe) {
                l.log("invalid port");
                _log.error(getPrefix() + "Port specified is not valid: " + args[0], nfe);
                notifyEvent("clientTaskId", Integer.valueOf(-1));
            }
            if (portNum <= 0)
                throw new IllegalArgumentException(getPrefix() + "Bad port " + args[0]);

            ownDest = !isShared;
            try {
                String privateKeyFile = null;
                if (args.length >= 4)
                    privateKeyFile = args[3];
                I2PTunnelClientBase task = new I2PTunnelClient(portNum, args[1], l, ownDest, this, this, privateKeyFile);
                task.startRunning();
                addtask(task);
                notifyEvent("clientTaskId", Integer.valueOf(task.getId()));
            } catch (IllegalArgumentException iae) {
                String msg = "Invalid I2PTunnel configuration to create a standard client tunnel connecting to the router at " + host + ':'+ port +
                             " and listening on " + listenHost + ':' + portNum;
                _log.error(getPrefix() + msg, iae);
                l.log(msg);
                notifyEvent("clientTaskId", Integer.valueOf(-1));
                // Since nothing listens to TaskID events, use this to propagate the error to TunnelController
                // Otherwise, the tunnel stays up even though the port is down
                // This doesn't work for CLI though... and the tunnel doesn't close itself after error,
                // so this probably leaves the tunnel open if called from the CLI
                throw iae;
            }
        } else {
            l.log("client <port> <pubkey>[,<pubkey>]|file:<pubkeyfile>[ <sharedClient>] [<privKeyFile>]\n" +
                    "  Creates a standard client that listens on the port and forwards to the pubkey.\n"
                  + "  With a comma delimited list of pubkeys, it will rotate among them randomly.\n"
                  + "  sharedClient indicates if this client shares tunnels with other clients (true or false)");
            notifyEvent("clientTaskId", Integer.valueOf(-1));
        }
    }

    /**
     * Run an HTTP client on the given port number 
     *
     * Sets the event "httpclientTaskId" = Integer(taskId) after the tunnel has been started (or -1 on error).
     * Also sets "httpclientStatus" = "ok" or "error" after the client tunnel has started.
     * parameter sharedClient is a String, either "true" or "false"
     *
     * @param args {portNumber[, sharedClient][, proxy to be used for the WWW]}
     * @param l logger to receive events and output
     * @throws IllegalArgumentException on config problem
     */
    public void runHttpClient(String args[], Logging l) {
        if (args.length >= 1 && args.length <= 3) {
            int clientPort = -1;
            try {
                clientPort = Integer.parseInt(args[0]);
            } catch (NumberFormatException nfe) {
                l.log("invalid port");
                _log.error(getPrefix() + "Port specified is not valid: " + args[0], nfe);
                notifyEvent("httpclientTaskId", Integer.valueOf(-1));
            }
            if (clientPort <= 0)
                throw new IllegalArgumentException(getPrefix() + "Bad port " + args[0]);
            
            String proxy = "";
            boolean isShared = true;
            if (args.length > 1) {
                if (Boolean.parseBoolean(args[1].trim())) {
                    isShared = true;
                    if (args.length == 3)
                        proxy = args[2];
                } else if ("false".equalsIgnoreCase(args[1].trim())) {
                    _log.warn("args[1] == [" + args[1] + "] and rejected explicitly");
                    isShared = false;
                    if (args.length == 3)
                        proxy = args[2];
                } else if (args.length == 3) {
                    isShared = false; // not "true"
                    proxy = args[2];
                    _log.warn("args[1] == [" + args[1] + "] but rejected");
                } else {
                    // isShared not specified, default to true
                    isShared = true;
                    proxy = args[1];
                }
            }

            ownDest = !isShared;
            try {
                I2PTunnelClientBase task = new I2PTunnelHTTPClient(clientPort, l, ownDest, proxy, this, this);
                task.startRunning();
                addtask(task);
                notifyEvent("httpclientTaskId", Integer.valueOf(task.getId()));
            } catch (IllegalArgumentException iae) {
                String msg = "Invalid I2PTunnel configuration to create an HTTP Proxy connecting to the router at " + host + ':'+ port +
                             " and listening on " + listenHost + ':' + clientPort;
                _log.error(getPrefix() + msg, iae);
                l.log(msg);
                notifyEvent("httpclientTaskId", Integer.valueOf(-1));
                // Since nothing listens to TaskID events, use this to propagate the error to TunnelController
                // Otherwise, the tunnel stays up even though the port is down
                // This doesn't work for CLI though... and the tunnel doesn't close itself after error,
                // so this probably leaves the tunnel open if called from the CLI
                throw iae;
            }
        } else {
            l.log("httpclient <port> [<sharedClient>] [<proxy>]\n" +
                  "  Creates a HTTP client proxy on the specified port.\n" +
                  "  <sharedClient> (optional) Indicates if this client shares tunnels with other clients (true or false)\n" +
                  "  <proxy> (optional) Indicates a proxy server to be used\n" +
                  "  when trying to access an address out of the .i2p domain");
            notifyEvent("httpclientTaskId", Integer.valueOf(-1));
        }
    }

    /**
     * Run a CONNECT client on the given port number 
     *
     * @param args {portNumber[, sharedClient][, proxy to be used for the WWW]}
     * @param l logger to receive events and output
     * @throws IllegalArgumentException on config problem
     */
    public void runConnectClient(String args[], Logging l) {
        if (args.length >= 1 && args.length <= 3) {
            int _port = -1;
            try {
                _port = Integer.parseInt(args[0]);
            } catch (NumberFormatException nfe) {
                _log.error(getPrefix() + "Port specified is not valid: " + args[0], nfe);
            }
            if (_port <= 0)
                throw new IllegalArgumentException(getPrefix() + "Bad port " + args[0]);
            
            String proxy = "";
            boolean isShared = true;
            if (args.length > 1) {
                if (Boolean.parseBoolean(args[1].trim())) {
                    isShared = true;
                    if (args.length == 3)
                        proxy = args[2];
                } else if ("false".equalsIgnoreCase(args[1].trim())) {
                    _log.warn("args[1] == [" + args[1] + "] and rejected explicitly");
                    isShared = false;
                    if (args.length == 3)
                        proxy = args[2];
                } else if (args.length == 3) {
                    isShared = false; // not "true"
                    proxy = args[2];
                    _log.warn("args[1] == [" + args[1] + "] but rejected");
                } else {
                    // isShared not specified, default to true
                    isShared = true;
                    proxy = args[1];
                }
            }

            ownDest = !isShared;
            try {
                I2PTunnelClientBase task = new I2PTunnelConnectClient(_port, l, ownDest, proxy, this, this);
                task.startRunning();
                addtask(task);
            } catch (IllegalArgumentException iae) {
                String msg = "Invalid I2PTunnel configuration to create a CONNECT client connecting to the router at " + host + ':'+ port +
                             " and listening on " + listenHost + ':' + _port;
                _log.error(getPrefix() + msg, iae);
                l.log(msg);
                // Since nothing listens to TaskID events, use this to propagate the error to TunnelController
                // Otherwise, the tunnel stays up even though the port is down
                // This doesn't work for CLI though... and the tunnel doesn't close itself after error,
                // so this probably leaves the tunnel open if called from the CLI
                throw iae;
            }
        } else {
            l.log("connectclient <port> [<sharedClient>] [<proxy>]\n" +
                  "  creates a client that for SSL/HTTPS requests.\n" +
                  "  <sharedClient> (optional) indicates if this client shares tunnels with other clients (true or false)\n" +
                  "  <proxy> (optional) indicates a proxy server to be used\n" +
                  "  when trying to access an address out of the .i2p domain\n");
        }
    }

    /**
     * Run an IRC client on the given port number 
     *
     * Sets the event "ircclientTaskId" = Integer(taskId) after the tunnel has been started (or -1 on error).
     * Also sets "ircclientStatus" = "ok" or "error" after the client tunnel has started.
     * parameter sharedClient is a String, either "true" or "false"
     *
     * @param args {portNumber,destinationBase64 or "file:filename" [, sharedClient [, privKeyFile]]}
     * @param l logger to receive events and output
     * @throws IllegalArgumentException on config problem
     */
    public void runIrcClient(String args[], Logging l) {
        if (args.length >= 2) {
            int _port = -1;
            try {
                _port = Integer.parseInt(args[0]);
            } catch (NumberFormatException nfe) {
                l.log("invalid port");
                _log.error(getPrefix() + "Port specified is not valid: " + args[0], nfe);
                notifyEvent("ircclientTaskId", Integer.valueOf(-1));
            }
            if (_port <= 0)
                throw new IllegalArgumentException(getPrefix() + "Bad port " + args[0]);
            
            boolean isShared = true;
            if (args.length > 2) {
                if (Boolean.parseBoolean(args[2].trim())) {
                    isShared = true;
                } else if ("false".equalsIgnoreCase(args[2].trim())) {
                    _log.warn("args[2] == [" + args[2] + "] and rejected explicitly");
                    isShared = false;
                } else {
                    // isShared not specified, default to true
                    isShared = true;
                }
            }

            ownDest = !isShared;
            try {
                String privateKeyFile = null;
                if (args.length >= 4)
                    privateKeyFile = args[3];
                I2PTunnelClientBase task = new I2PTunnelIRCClient(_port, args[1], l, ownDest, this, this, privateKeyFile);
                task.startRunning();
                addtask(task);
                notifyEvent("ircclientTaskId", Integer.valueOf(task.getId()));
            } catch (IllegalArgumentException iae) {
                String msg = "Invalid I2PTunnel configuration to create an IRC client connecting to the router at " + host + ':'+ port +
                             " and listening on " + listenHost + ':' + _port;
                _log.error(getPrefix() + msg, iae);
                l.log(msg);
                notifyEvent("ircclientTaskId", Integer.valueOf(-1));
                // Since nothing listens to TaskID events, use this to propagate the error to TunnelController
                // Otherwise, the tunnel stays up even though the port is down
                // This doesn't work for CLI though... and the tunnel doesn't close itself after error,
                // so this probably leaves the tunnel open if called from the CLI
                throw iae;
            }
        } else {
            l.log("ircclient <port> [<sharedClient> [<privKeyFile>]]\n" +
                  "  Creates an IRC client proxy on the specified port.\n" +
                  "  <sharedClient> (optional) Indicates if this client shares tunnels with other clients (true or false)\n");
            notifyEvent("ircclientTaskId", Integer.valueOf(-1));
        }
    }
    
    /**
     * Run an SOCKS tunnel on the given port number 
     *
     * Sets the event "sockstunnelTaskId" = Integer(taskId) after the
     * tunnel has been started (or -1 on error).  Also sets
     * "openSOCKSTunnelResult" = "ok" or "error" after the client tunnel has
     * started.
     *
     * @param args {portNumber [, sharedClient]} or (portNumber, ignored (false), privKeyFile)
     * @param l logger to receive events and output
     * @throws IllegalArgumentException on config problem
     */
    public void runSOCKSTunnel(String args[], Logging l) {
        if (args.length >= 1 && args.length <= 3) {
            int _port = -1;
            try {
                _port = Integer.parseInt(args[0]);
            } catch (NumberFormatException nfe) {
                l.log("invalid port");
                _log.error(getPrefix() + "Port specified is not valid: " + args[0], nfe);
                notifyEvent("sockstunnelTaskId", Integer.valueOf(-1));
            }
            if (_port <= 0)
                throw new IllegalArgumentException(getPrefix() + "Bad port " + args[0]);

            boolean isShared = false;
            if (args.length == 2)
                isShared = Boolean.parseBoolean(args[1].trim());

            ownDest = !isShared;
            String privateKeyFile = null;
            if (args.length == 3)
                privateKeyFile = args[2];
            try {
                I2PTunnelClientBase task = new I2PSOCKSTunnel(_port, l, ownDest, this, this, privateKeyFile);
                task.startRunning();
                addtask(task);
                notifyEvent("sockstunnelTaskId", Integer.valueOf(task.getId()));
            } catch (IllegalArgumentException iae) {
                String msg = "Invalid I2PTunnel configuration to create a SOCKS Proxy connecting to the router at " + host + ':'+ port +
                             " and listening on " + listenHost + ':' + _port;
                _log.error(getPrefix() + msg, iae);
                l.log(msg);
                notifyEvent("sockstunnelTaskId", Integer.valueOf(-1));
                throw iae;
            }
        } else {
            l.log("sockstunnel <port>\n" +
                  "  Creates a SOCKS proxy on the specified port.");
            notifyEvent("sockstunnelTaskId", Integer.valueOf(-1));
        }
    }

    
    /**
     * Run an SOCKS IRC tunnel on the given port number 
     * @param args {portNumber [, sharedClient]} or (portNumber, ignored (false), privKeyFile)
     * @throws IllegalArgumentException on config problem
     * @since 0.7.12
     */
    public void runSOCKSIRCTunnel(String args[], Logging l) {
        if (args.length >= 1 && args.length <= 3) {
            int _port = -1;
            try {
                _port = Integer.parseInt(args[0]);
            } catch (NumberFormatException nfe) {
                l.log("invalid port");
                _log.error(getPrefix() + "Port specified is not valid: " + args[0], nfe);
                notifyEvent("sockstunnelTaskId", Integer.valueOf(-1));
            }
            if (_port <= 0)
                throw new IllegalArgumentException(getPrefix() + "Bad port " + args[0]);

            boolean isShared = false;
            if (args.length == 2)
                isShared = Boolean.parseBoolean(args[1].trim());

            ownDest = !isShared;
            String privateKeyFile = null;
            if (args.length == 3)
                privateKeyFile = args[2];
            try {
                I2PTunnelClientBase task = new I2PSOCKSIRCTunnel(_port, l, ownDest, this, this, privateKeyFile);
                task.startRunning();
                addtask(task);
                notifyEvent("sockstunnelTaskId", Integer.valueOf(task.getId()));
            } catch (IllegalArgumentException iae) {
                String msg = "Invalid I2PTunnel configuration to create a SOCKS IRC Proxy connecting to the router at " + host + ':'+ port +
                             " and listening on " + listenHost + ':' + _port;
                _log.error(getPrefix() + msg, iae);
                l.log(msg);
                notifyEvent("sockstunnelTaskId", Integer.valueOf(-1));
                throw iae;
            }
        } else {
            l.log("socksirctunnel <port> [<sharedClient> [<privKeyFile>]]\n" +
                  "  Creates a SOCKS IRC proxy on the specified port.");
            notifyEvent("sockstunnelTaskId", Integer.valueOf(-1));
        }
    }

    /**
     * Streamr client
     *
     * @param args {targethost, targetport, destinationString}
     * @param l logger to receive events and output
     * @throws IllegalArgumentException on config problem
     */
    public void runStreamrClient(String args[], Logging l) {
        if (args.length == 3) {
            InetAddress _host;
            try {
                _host = InetAddress.getByName(args[0]);
            } catch (UnknownHostException uhe) {
                l.log("unknown host");
                _log.error(getPrefix() + "Error resolving " + args[0], uhe);
                notifyEvent("streamrtunnelTaskId", Integer.valueOf(-1));
                return;
            }

            int _port = -1;
            try {
                _port = Integer.parseInt(args[1]);
            } catch (NumberFormatException nfe) {
                l.log("invalid port");
                _log.error(getPrefix() + "Port specified is not valid: " + args[0], nfe);
                notifyEvent("streamrtunnelTaskId", Integer.valueOf(-1));
            }
            if (_port <= 0)
                throw new IllegalArgumentException(getPrefix() + "Bad port " + args[0]);

            try {
                StreamrConsumer task = new StreamrConsumer(_host, _port, args[2], l, this, this);
                task.startRunning();
                addtask(task);
                notifyEvent("streamrtunnelTaskId", Integer.valueOf(task.getId()));
            } catch (IllegalArgumentException iae) {
                String msg = "Invalid I2PTunnel configuration to create a Streamr Client connecting to the router at " + host + ':'+ port +
                             " and sending to " + _host + ':' + _port;
                _log.error(getPrefix() + msg, iae);
                l.log(msg);
                notifyEvent("streamrtunnnelTaskId", Integer.valueOf(-1));
                throw iae;
            }
        } else {
            l.log("streamrclient <host> <port> <destination>\n" +
                  "  creates a tunnel that receives streaming data.");
            notifyEvent("streamrtunnelTaskId", Integer.valueOf(-1));
        }
    }

    /**
     * Streamr server
     *
     * @param args {port, privkeyfile}
     * @param l logger to receive events and output
     * @throws IllegalArgumentException on config problem
     */
    public void runStreamrServer(String args[], Logging l) {
        if (args.length == 2) {
            int _port = -1;
            try {
                _port = Integer.parseInt(args[0]);
            } catch (NumberFormatException nfe) {
                l.log("invalid port");
                _log.error(getPrefix() + "Port specified is not valid: " + args[0], nfe);
                notifyEvent("streamrtunnelTaskId", Integer.valueOf(-1));
            }
            if (_port <= 0)
                throw new IllegalArgumentException(getPrefix() + "Bad port " + args[0]);

            File privKeyFile = new File(args[1]);
            if (!privKeyFile.isAbsolute())
                privKeyFile = new File(_context.getConfigDir(), args[1]);
            if (!privKeyFile.canRead()) {
                l.log("private key file does not exist");
                _log.error(getPrefix() + "Private key file does not exist or is not readable: " + args[3]);
                notifyEvent("serverTaskId", Integer.valueOf(-1));
                return;
            }

            StreamrProducer task = new StreamrProducer(_port, privKeyFile, args[1], l, this, this);
            task.startRunning();
            addtask(task);
            notifyEvent("streamrtunnelTaskId", Integer.valueOf(task.getId()));
        } else {
            l.log("streamrserver <port> <privkeyfile>\n" +
                  "  creates a tunnel that sends streaming data.");
            notifyEvent("streamrtunnelTaskId", Integer.valueOf(-1));
        }
    }

    /**
     * Specify the i2cp host and port 
     * Deprecated - only used by CLI
     *
     * Sets the event "configResult" = "ok" or "error" after the configuration has been specified
     *
     * @param args {hostname, portNumber}
     * @param l logger to receive events and output
     */
    private void runConfig(String args[], Logging l) {
        if (args.length >= 1) {
            int i = 0;
            boolean ssl = args[0].equals("-s");
            if (ssl) {
                _clientOptions.setProperty("i2cp.SSL", "true");
                i++;
            } else {
                _clientOptions.remove("i2cp.SSL");
            }
            if (i < args.length) {
                host = args[i++];
            }
            if (i < args.length)
                port = args[i];
            l.log("New I2CP settings: " + host + ' ' + port + (ssl ? " SSL" : " non-SSL"));
            notifyEvent("configResult", "ok");
        } else {
            boolean ssl = Boolean.parseBoolean(_clientOptions.getProperty("i2cp.SSL"));
            l.log("Usage:\n" +
                  "  config [-s] [<i2phost>] [<i2pport>]\n" +
                  "  Sets the address and port of the I2P router.\n" +
                  "  Use -s for SSL.\n" +
                  "Current I2CP settings: " + host + ' ' + port + (ssl ? " SSL" : " non-SSL"));
            notifyEvent("configResult", "error");
        }
    }

    /**
     * Specify the i2cp username and password
     *
     * @param args {username, password}
     * @param l logger to receive events and output
     * @since 0.9.11
     */
    private void runAuth(String args[], Logging l) {
        if (args.length == 2) {
            _clientOptions.setProperty("i2cp.username", args[0]);
            _clientOptions.setProperty("i2cp.password", args[1]);
        } else {
            l.log("Usage:\n" +
                  "  auth <username> <password>\n" +
                  "  Sets the i2cp credentials");
        }
    }

    /**
     * Specify whether to use its own destination for each outgoing tunnel
     * Deprecated - only used by CLI
     *
     * Sets the event "owndestResult" = "ok" or "error" after the configuration has been specified
     *
     * @param args {yes or no}
     * @param l logger to receive events and output
     */
    private void runOwnDest(String args[], Logging l) {
        if (args.length == 1 && (args[0].equalsIgnoreCase("yes") || args[0].equalsIgnoreCase("no"))) {
            ownDest = args[0].equalsIgnoreCase("yes");
            notifyEvent("owndestResult", "ok");
        } else {
            l.log("owndest yes|no\n" +
                  "  Specifies whether to use its own destination \n" + "  for each outgoing tunnel");
            notifyEvent("owndestResult", "error");
        }
    }

    /**
     * Specify the hostname / IP address of the interface that the tunnels should bind to
     *
     * Sets the event "listen_onResult" = "ok" or "error" after the interface has been specified
     *
     * @param args {hostname}
     * @param l logger to receive events and output
     */
    public void runListenOn(String args[], Logging l) {
        if (args.length == 1) {
            listenHost = args[0];
            notifyEvent("listen_onResult", "ok");
        } else {
            l.log("listen_on <ip>\n" +
                  "  sets the interface to listen for the I2PClient.");
            notifyEvent("listen_onResult", "error");
        }
    }

    /**
     * Specify the read timeout going to be used for newly-created I2PSockets
     *
     * Sets the event "read_timeoutResult" = "ok" or "error" after the interface has been specified
     *
     * @param args {hostname}
     * @param l logger to receive events and output
     */
    public void runReadTimeout(String args[], Logging l) {
        if (args.length == 1) {
            try {
                readTimeout = Long.parseLong(args[0]);
            } catch (NumberFormatException e) {
                notifyEvent("read_timeoutResult", "error");
            }
            notifyEvent("read_timeoutResult", "ok");
        } else {
            l.log("read_timeout <msecs>\n" +
                  "  sets the read timeout (in milliseconds) for I2P connections\n"
                  +"  Negative values will make the connections wait forever");
            notifyEvent("read_timeoutResult", "error");
        }
    }

    /**
     * Generate a new keypair.
     * Does NOT support non-default sig types.
     * Deprecated - only used by CLI
     *
     * Sets the event "genkeysResult" = "ok" or "error" after the generation is complete
     *
     * @param args {privateKeyFilename, publicKeyFilename} or {privateKeyFilename}
     * @param l logger to receive events and output
     */
    private static void runGenKeys(String args[], Logging l) {
        OutputStream pubdest = null;
        if (args.length == 2) {
            try {
                pubdest = new FileOutputStream(args[1]);
            } catch (IOException ioe) {
                l.log("Error opening output stream");
                //_log.error(getPrefix() + "Error generating keys to out", ioe);
                //notifyEvent("genkeysResult", "error");
                return;
            }
        } else if (args.length != 1) {
            l.log("genkeys <privkeyfile> [<pubkeyfile>]\n" +
                  "   creates a new keypair and prints the public key.\n"
                  + "   if pubkeyfile is given, saves the public key there." + "\n"
                  + "   if the privkeyfile already exists, just print/save" + "the pubkey.");
            //notifyEvent("genkeysResult", "error");
        }
        try {
            File privKeyFile = new File(args[0]);
            if (privKeyFile.exists()) {
                l.log("File already exists.");
                showKey(new FileInputStream(privKeyFile), pubdest, l);
            } else {
                makeKey(new FileOutputStream(privKeyFile), pubdest, l);
            }
            //notifyEvent("genkeysResult", "ok");
        } catch (IOException ioe) {
            l.log("Error generating keys - " + ioe.getMessage());
            //notifyEvent("genkeysResult", "error");
            //_log.error(getPrefix() + "Error generating keys", ioe);
        } finally {
            if(pubdest != null) try { pubdest.close(); } catch(IOException ioe) {}
        }
    }

    /**
     * Generate a new keypair.
     * Does NOT support non-default sig types.
     * Deprecated - only used by CLI
     *
     * Sets the event "privateKey" = base64 of the privateKey stream and
     * sets the event "publicDestination" = base64 of the destination
     *
     * @param l logger to receive events and output
     */
    private static void runGenTextKeys(Logging l) {
        ByteArrayOutputStream privkey = new ByteArrayOutputStream(1024);
        ByteArrayOutputStream pubkey = new ByteArrayOutputStream(512);
        makeKey(privkey, pubkey, l);
        l.log("Private key: " + Base64.encode(privkey.toByteArray()));
        //notifyEvent("privateKey", Base64.encode(privkey.toByteArray()));
        //notifyEvent("publicDestination", Base64.encode(pubkey.toByteArray()));
    }

    /**
     * Exit the JVM if there are no more tasks left running.  If there are tunnels
     * running, it returns.
     * Deprecated - only used by CLI
     *
     * Sets the event "quitResult" = "error" if there are tasks running (but if there
     * aren't, well, there's no point in setting the quitResult to "ok", now is there?)
     *
     * @param l logger to receive events and output
     */
    private void runQuit(Logging l) {
        purgetasks(l);
        if (tasks.isEmpty()) {
            System.exit(0);
        }
        l.log("There are running tasks. Try 'list' or 'close all'.");
        //notifyEvent("quitResult", "error");
    }

    /**
     * Retrieve a list of currently running tasks
     * Deprecated - only used by CLI
     *
     * Sets the event "listDone" = "done" after dumping the tasks to
     * the logger
     *
     * @param l logger to receive events and output
     */
    private void runList(Logging l) {
        purgetasks(l);
        for (I2PTunnelTask t : tasks) {
            l.log("[" + t.getId() + "] " + t.toString());
        }
        notifyEvent("listDone", "done");
    }

    /**
     * Close the given task (or all tasks), optionally forcing them to die a hard
     * death
     *
     * Sets the event "closeResult" = "ok" after the closing is complete
     *
     * @param args {jobNumber}, {"forced", jobNumber}, {"forced", "all"}, {"destroy", jobNumber}, {"destroy", "all"}
     * @param l logger to receive events and output
     */
    public void runClose(String args[], Logging l) {
        if (args.length == 0 || args.length > 2) {
            l.log("close [forced|destroy] <jobnumber>|all\n" +
                  "   stop running tasks. either only one or all.\n"
                  + "   use 'forced' to also stop tasks with active connections.\n"
                  + "   use the 'list' command to show the job numbers");
            notifyEvent("closeResult", "error");
        } else {
            int argindex = 0; // parse optional 'forced' keyword
            CloseMode mode = CloseMode.NORMAL;
            if (args[argindex].equalsIgnoreCase("forced")) {
                mode = CloseMode.FORCED;
                argindex++;
            } else if (args[argindex].equalsIgnoreCase("destroy")) {
                mode = CloseMode.DESTROY;
                argindex++;
            }
            if (args[argindex].equalsIgnoreCase("all")) {
                boolean error = false;
                if (tasks.isEmpty()) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info(getPrefix() + " runClose(all) no tasks");
                }
                for (I2PTunnelTask t : tasks) {
                    if (!closetask(t, mode, l)) {
                        notifyEvent("closeResult", "error");
                        error = true;
                    } else if (!error) { // If there's an error, don't hide it
                        notifyEvent("closeResult", "ok");
                    }
                }
            } else {
                try {
                    if (!closetask(Integer.parseInt(args[argindex]), mode, l)) {
                        notifyEvent("closeResult", "error");
                    } else {
                        notifyEvent("closeResult", "ok");
                    }
                } catch (NumberFormatException ex) {
                    l.log("Incorrect job number: " + args[argindex]);
                    notifyEvent("closeResult", "error");
                }
            }
        }
    }

    /**
     * Run all of the commands in the given file (one command per line)
     * Deprecated - only used by CLI
     *
     * Sets the event "runResult" = "ok" or "error" after the closing is complete
     *
     * @param args {filename}
     * @param l logger to receive events and output
     */
    private void runRun(String args[], Logging l) {
        if (args.length == 1) {
            BufferedReader br = null;
            try {
                br = new BufferedReader(new InputStreamReader(new FileInputStream(args[0]), "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) {
                    runCommand(line, l);
                }
                br.close();
                notifyEvent("runResult", "ok");
            } catch (IOException ioe) {
                l.log("IO error running the file");
                _log.error(getPrefix() + "Error running the file", ioe);
                notifyEvent("runResult", "error");
            } finally {
                if (br != null) try { br.close(); } catch (IOException ioe) {}
            }
        } else {
            l.log("run <commandfile>\n" +
                  "   loads commandfile and runs each line in it. \n"
                  + "   You can also give the filename on the commandline.");
            notifyEvent("runResult", "error");
        }
    }

    /**
     * Perform a lookup of the name specified
     * Deprecated - only used by CLI
     *
     * Sets the event "lookupResult" = base64 of the destination, or an error message 
     *
     * @param args {name}
     * @param l logger to receive events and output
     */
    private void runLookup(String args[], Logging l) {
        if (args.length != 1) {
            l.log("lookup <name>\n" +
                  "   try to resolve the name into a destination key");
            notifyEvent("lookupResult", "invalidUsage");
        } else {
            try {
                boolean ssl = Boolean.parseBoolean(_clientOptions.getProperty("i2cp.SSL"));
                String user = _clientOptions.getProperty("i2cp.username");
                String pw = _clientOptions.getProperty("i2cp.password");
                Destination dest = destFromName(args[0], host, port, ssl, user, pw);
                if (dest == null) {
                    l.log("Unknown host: " + args[0]);
                    notifyEvent("lookupResult", "unkown host");
                } else {
                    l.log(dest.toBase64());
                    notifyEvent("lookupResult", dest.toBase64());
                }
            } catch (DataFormatException dfe) {
                l.log("Unknown or invalid host: " + args[0]);
                notifyEvent("lookupResult", "invalid host");
            }
        }
    }

    /**
     * Start up a ping task with the specified args (currently supporting -ns, -h, -l)
     * Deprecated - only used by CLI
     *
     * Sets the event "pingTaskId" = Integer of the taskId, or -1
     *
     * @param allargs arguments to pass to the I2Ping task
     * @param l logger to receive events and output
     */
    private void runPing(String allargs, Logging l) {
        if (allargs.length() != 0) {
            _clientOptions.setProperty(I2Ping.PROP_COMMAND, allargs);
            if (ownDest) {
                if (!_clientOptions.containsKey("inbound.nickname"))
                    _clientOptions.setProperty("inbound.nickname", "I2Ping");
                if (!_clientOptions.containsKey("outbound.nickname"))
                    _clientOptions.setProperty("outbound.nickname", "I2Ping");
            }
            I2PTunnelClientBase task = new I2Ping(l, ownDest, this, this);
            task.startRunning();
            addtask(task);
            notifyEvent("pingTaskId", Integer.valueOf(task.getId()));
        } else {
            l.log(I2Ping.usage());
            notifyEvent("pingTaskId", Integer.valueOf(-1));
        }
    }

    /**
     * Helper method to actually close the given task number (optionally forcing 
     * closure)
     *
     */
    private boolean closetask(int num, CloseMode mode, Logging l) {
        boolean closed = false;

        _log.debug(getPrefix() + "closetask(): looking for task " + num);
            for (I2PTunnelTask t : tasks) {
                int id = t.getId();
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug(getPrefix() + "closetask(): parsing task " + id + " (" + t.toString() + ")");
                if (id == num) {
                    closed = closetask(t, mode, l);
                    break;
                } else if (id > num) {
                    break;
                }
        }
        return closed;
    }

    /**
     * Helper method to actually close the given task number
     * (optionally forcing closure)
     *
     */
    private boolean closetask(I2PTunnelTask t, CloseMode mode, Logging l) {
        if (_log.shouldLog(Log.INFO))
            _log.info("Closing task " + t.getId() + " mode: " + mode);
        //l.log("Closing task " + t.getId() + (forced ? " forced..." : "..."));
        boolean success;
        if (mode == CloseMode.NORMAL)
            success = t.close(false);
        else if (mode == CloseMode.FORCED)
            success = t.close(true);
        else  // DESTROY
            success = t.destroy();
        if (success) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Task " + t.getId() + " closed.");
            //l.log("Task " + t.getId() + " closed.");
        }
        return success;
    }

    /**
     * Helper task to remove closed / completed tasks.
     *
     */
    private void purgetasks(Logging l) {
            List<I2PTunnelTask> removed = new ArrayList<I2PTunnelTask>();
            for (I2PTunnelTask t : tasks) {
                if (!t.isOpen()) {
                    _log.debug(getPrefix() + "Purging inactive tunnel: [" + t.getId() + "] " + t.toString());
                    removed.add(t);
                }
            }
            tasks.removeAll(removed);
    }

    /**
     * Log the given message (using both the logging subsystem and standard output...)
     *
     */
    public void log(String s) {
        System.out.println(s);
        //if (_log.shouldLog(Log.INFO))
        //    _log.info(getPrefix() + "Display: " + s);
    }

    /**
     * Create a new destination, storing the destination and its private keys where 
     * instructed.
     * Does NOT support non-default sig types.
     * Deprecated - only used by CLI
     *
     * @param writeTo location to store the destination and private keys
     * @param pubDest location to store the destination
     * @param l logger to send messages to
     */
    private static void makeKey(OutputStream writeTo, OutputStream pubDest, Logging l) {
        try {
            l.log("Generating new keys...");
            I2PClient client = I2PClientFactory.createClient();
            Destination d = client.createDestination(writeTo);
            l.log("New destination: " + d.toBase32());
            writeTo.flush();
            writeTo.close();
            writePubKey(d, pubDest, l);
        } catch (I2PException ex) {
            ex.printStackTrace();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Read in the given destination, display it, and write it to the given location
     * Deprecated - only used by CLI
     *
     * @param readFrom stream to read the destination from
     * @param pubDest stream to write the destination to
     * @param l logger to send messages to
     */
    private static void showKey(InputStream readFrom, OutputStream pubDest, Logging l) {
        try {
            Destination d = new Destination();
            d.readBytes(readFrom);
            l.log("Destination: " + d.toBase32());
            readFrom.close();
            writePubKey(d, pubDest, l);
        } catch (I2PException ex) {
            ex.printStackTrace();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Write out the destination to the stream
     * Deprecated - only used by CLI
     *
     * @param d Destination to write
     * @param o stream to write the destination to, or null for noop
     * @param l logger to send messages to
     */
    private static void writePubKey(Destination d, OutputStream o, Logging l) throws I2PException, IOException {
        if (o == null) return;
        d.writeBytes(o);
        l.log("Public key saved.");
    }

    /**
     * Generates a Destination from a name. Now only supports base64
     * names - may support naming servers later. "file:&lt;filename&gt;" is
     * also supported, where filename is a file that either contains a 
     * binary Destination structure or the Base64 encoding of that 
     * structure.
     *
     * Since file:&lt;filename&gt; isn't really used, this method is deprecated,
     * just call context.namingService.lookup() directly.
     * @deprecated Don't use i2ptunnel for lookup! Use I2PAppContext.getGlobalContext().namingService().lookup(name) from i2p.jar
     */
    @Deprecated
    public static Destination destFromName(String name) throws DataFormatException {
        return destFromName(name, null, null, false, null, null);
    }

    /**
     *  @param i2cpHost may be null
     *  @param i2cpPort may be null
     *  @param user may be null
     *  @param pw may be null
     *  @since 0.9.11
     */
    private static Destination destFromName(String name, String i2cpHost,
                                            String i2cpPort, boolean isSSL,
                                            String user, String pw) throws DataFormatException {

        if ((name == null) || (name.trim().length() <= 0)) throw new DataFormatException("Empty destination provided");

        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        Log log = ctx.logManager().getLog(I2PTunnel.class);
        
        if (name.startsWith("file:")) {
            Destination result = new Destination();
            byte content[] = null;
            FileInputStream in = null;
            try {
                in = new FileInputStream(name.substring("file:".length()));
                byte buf[] = new byte[1024];
                int read = DataHelper.read(in, buf);
                content = new byte[read];
                System.arraycopy(buf, 0, content, 0, read);
            } catch (IOException ioe) {
                System.out.println(ioe.getMessage());
                return null;
            } finally {
                if (in != null) try {
                    in.close();
                } catch (IOException io) {
                }
            }
            try {
                result.fromByteArray(content);
                return result;
            } catch (RuntimeException ex) {
                if (log.shouldLog(Log.INFO)) 
                    log.info("File is not a binary destination - trying base64");
                try {
                    byte decoded[] = Base64.decode(new String(content));
                    result.fromByteArray(decoded);
                    return result;
                } catch (DataFormatException dfe) {
                    if (log.shouldLog(Log.WARN)) 
                        log.warn("File is not a base64 destination either - failing!");
                    return null;
                }
            }
        } else {
            // ask naming service
            name = name.trim();
            NamingService inst = ctx.namingService();
            boolean b32 = name.length() == 60 && name.toLowerCase(Locale.US).endsWith(".b32.i2p");
            Destination d = null;
            if (ctx.isRouterContext() || !b32) {
                // Local lookup.
                // Even though we could do b32 outside router ctx here,
                // we do it below instead so we can set the host and port,
                // which we can't do with lookup()
                d = inst.lookup(name);
                if (d != null || ctx.isRouterContext() || name.length() >= 516)
                    return d;
            }
            // Outside router context only,
            // try simple session to ask the router.
            I2PClient client = new I2PSimpleClient();
            Properties opts = new Properties();
            if (i2cpHost != null)
                opts.put(I2PClient.PROP_TCP_HOST, i2cpHost);
            if (i2cpPort != null)
                opts.put(I2PClient.PROP_TCP_PORT, i2cpPort);
            opts.put("i2cp.SSL", Boolean.toString(isSSL));
            if (user != null)
                opts.put("i2cp.username", user);
            if (pw != null)
                opts.put("i2cp.password", pw);
            I2PSession session = null;
            try {
                session = client.createSession(null, opts);
                session.connect();
                d = session.lookupDest(name);
            } catch (I2PSessionException ise) {
                if (log.shouldLog(Log.WARN)) 
                    log.warn("Lookup via router failed", ise);
            } finally {
                if (session != null) {
                    try { session.destroySession(); } catch (I2PSessionException ise) {}
                }
            }
            return d;
        }
    }

    public void addConnectionEventListener(ConnectionEventListener lsnr) {
        if (lsnr == null) return;
        listeners.add(lsnr);
    }

    public void removeConnectionEventListener(ConnectionEventListener lsnr) {
        if (lsnr == null) return;
        listeners.remove(lsnr);
    }
    
    private String getPrefix() { return "[" + _tunnelId + "]: "; }
    
    public I2PAppContext getContext() { return _context; }

    /**
     * Call this whenever we lose touch with the router involuntarily (aka the router
     * is off / crashed / etc)
     *
     */
    void routerDisconnected() {
        _log.error(getPrefix() + "Router disconnected - firing notification events");
            for (ConnectionEventListener lsnr : listeners) {
                if (lsnr != null) lsnr.routerDisconnected();
            }
    }

    /**
     * Callback routine to find out
     */
    public interface ConnectionEventListener {
        public void routerDisconnected();
    }
}
