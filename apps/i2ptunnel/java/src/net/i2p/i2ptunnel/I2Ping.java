/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.data.Destination;
import net.i2p.util.EventDispatcher;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 *  Warning - not necessarily a stable API.
 *  Used by I2PTunnel CLI only. Consider this sample code.
 *  Not for use outside this package.
 */
public class I2Ping extends I2PTunnelClientBase {

    public static final String PROP_COMMAND = "command";

    private static final int PING_COUNT = 3;
    private static final int CPING_COUNT = 5;
    private static final int PING_TIMEOUT = 30*1000;

    private static final long PING_DISTANCE = 1000;

    private int MAX_SIMUL_PINGS = 10; // not really final...


    private volatile boolean finished;

    private final Object simulLock = new Object();
    private int simulPings;
    private long lastPingTime;

    /**
     *  tunnel.getOptions must contain "command".
     *  @throws IllegalArgumentException if it doesn't
     */
    public I2Ping(Logging l, boolean ownDest, EventDispatcher notifyThis, I2PTunnel tunnel) {
        super(-1, ownDest, l, notifyThis, "I2Ping", tunnel);
        if (!tunnel.getClientOptions().containsKey(PROP_COMMAND)) {
            // todo clean up
            throw new IllegalArgumentException("Options does not contain " + PROP_COMMAND);
        }
    }

    /**
     *  Overrides super. No client ServerSocket is created.
     */
    @Override
    public void run() {
        // Notify constructor that port is ready
        synchronized (this) {
            listenerReady = true;
            notify();
        }
        l.log("*** I2Ping results:");
        try {
            runCommand(getTunnel().getClientOptions().getProperty(PROP_COMMAND));
        } catch (InterruptedException ex) {
            l.log("*** Interrupted");
            _log.error("Pinger interrupted", ex);
        } catch (IOException ex) {
            _log.error("Pinger exception", ex);
        }
        l.log("*** Finished.");
        finished = true;
        close(false);
    }

    public void runCommand(String cmd) throws InterruptedException, IOException {
      long timeout = PING_TIMEOUT;
      int count = PING_COUNT;
      boolean countPing = false;
      boolean reportTimes = true;
      while (true) {
        if (cmd.startsWith("-t ")) { // timeout
            cmd = cmd.substring(3);
            int pos = cmd.indexOf(" ");
            if (pos == -1) {
                l.log("Syntax error");
                return;
            } else {
                timeout = Long.parseLong(cmd.substring(0, pos));
                // convenience, convert msec to sec
                if (timeout < 100)
                    timeout *= 1000;
                cmd = cmd.substring(pos + 1);
            }
        } else if (cmd.startsWith("-m ")) { // max simultaneous pings
            cmd = cmd.substring(3);
            int pos = cmd.indexOf(" ");
            if (pos == -1) {
                l.log("Syntax error");
                return;
            } else {
                MAX_SIMUL_PINGS = Integer.parseInt(cmd.substring(0, pos));
                cmd = cmd.substring(pos + 1);
            }
        } else if (cmd.startsWith("-n ")) { // number of pings
            cmd = cmd.substring(3);
            int pos = cmd.indexOf(" ");
            if (pos == -1) {
                l.log("Syntax error");
                return;
            } else {
                count = Integer.parseInt(cmd.substring(0, pos));
                cmd = cmd.substring(pos + 1);
            }
        } else if (cmd.startsWith("-c ")) { // "count" ping
            countPing = true;
            count = CPING_COUNT;
            cmd = cmd.substring(3);
        } else if (cmd.equals("-h")) { // ping all hosts
            cmd = "-l hosts.txt";
        } else if (cmd.startsWith("-l ")) { // ping a list of hosts
            BufferedReader br = new BufferedReader(new FileReader(cmd.substring(3)));
            String line;
            List<PingHandler> pingHandlers = new ArrayList<PingHandler>();
            int i = 0;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("#")) continue; // comments
                if (line.startsWith(";")) continue;
                if (line.startsWith("!")) continue;
                if (line.indexOf("=") != -1) { // maybe file is hosts.txt?
                    line = line.substring(0, line.indexOf("="));
                }
                PingHandler ph = new PingHandler(line, count, timeout, countPing, reportTimes);
                ph.start();
                pingHandlers.add(ph);
                if (++i > 1)
                    reportTimes = false;
            }
            br.close();
            for (Thread t : pingHandlers)
                t.join();
            return;
        } else {
            Thread t = new PingHandler(cmd, count, timeout, countPing, reportTimes);
            t.start();
            t.join();
            return;
        }
      }
    }

    @Override
    public boolean close(boolean forced) {
        if (!open) return true;
        super.close(forced);
        if (!forced && !finished) {
            l.log("There are still pings running!");
            return false;
        }
        l.log("Closing pinger " + toString());
        l.log("Pinger closed.");
        return true;
    }

    private boolean ping(Destination dest, long timeout) throws I2PException {
        try {
            synchronized (simulLock) {
                while (simulPings >= MAX_SIMUL_PINGS) {
                    simulLock.wait();
                }
                simulPings++;
                while (lastPingTime + PING_DISTANCE > System.currentTimeMillis()) {
                    // no wait here, to delay all pingers
                    Thread.sleep(PING_DISTANCE / 2);
                }
                lastPingTime = System.currentTimeMillis();
            }
            boolean sent = sockMgr.ping(dest, timeout);
            synchronized (simulLock) {
                simulPings--;
                simulLock.notifyAll();
            }
            return sent;
        } catch (InterruptedException ex) {
            _log.error("Interrupted", ex);
            return false;
        }
    }

    /**
     *  Does nothing.
     *  @since 0.9.10
     */
    protected void clientConnectionRun(Socket s) {}

    private class PingHandler extends I2PAppThread {
        private final String destination;
        private final int cnt;
        private final long timeout;
        private final boolean countPing;
        private final boolean reportTimes;

        /**
         *  As of 0.9.10, does NOT start itself.
         *  Caller must call start()
         *  @param dest b64 or b32 or host name
         */
        public PingHandler(String dest, int count, long timeout, boolean countPings, boolean report) {
            this.destination = dest;
            cnt = count;
            this.timeout = timeout;
            countPing = countPings;
            reportTimes = report;
            setName("PingHandler for " + dest);
        }

        @Override
        public void run() {
            try {
                Destination dest = lookup(destination);
                if (dest == null) {
                    l.log("Unresolvable: " + destination);
                    return;
                }
                int pass = 0;
                int fail = 0;
                long totalTime = 0;
                StringBuilder pingResults = new StringBuilder(2 * cnt + destination.length() + 3);
                for (int i = 0; i < cnt; i++) {
                    boolean sent;
                    sent = ping(dest, timeout);
                    if (countPing) {
                        if (!sent) {
                            pingResults.append(i).append(" ");
                            break;
                        } else if (i == cnt - 1) {
                            pingResults.append("+ ");
                        }
                    } else {
                        if (reportTimes) {
                            if (sent) {
                                pass++;
                                long rtt = System.currentTimeMillis() - lastPingTime;
                                totalTime += rtt;
                                l.log((i+1) + ": + " + rtt + " ms");
                            } else {
                                fail++;
                                l.log((i+1) + ": -");
                            }
                        } else {
                            pingResults.append(sent ? "+ " : "- ");
                        }
                    }
                    //		    System.out.println(sent+" -> "+destination);
                }
                if (reportTimes) {
                    pingResults.append("  ").append(pass).append(" received ");
                    if (pass > 0)
                        pingResults.append("(average time ").append(totalTime/pass).append(" ms) ");
                    pingResults.append("and ").append(fail).append(" lost for destination: ");
                }
                pingResults.append("  ").append(destination);
                l.log(pingResults.toString());
            } catch (I2PException ex) {
                _log.error("Error pinging " + destination, ex);
            }
        }

        /**
         *  @param name b64 or b32 or host name
         *  @since 0.9.10
         */
        private Destination lookup(String name) {
            I2PAppContext ctx = I2PAppContext.getGlobalContext();
            boolean b32 = name.length() == 60 && name.toLowerCase(Locale.US).endsWith(".b32.i2p");
            if (ctx.isRouterContext() && !b32) {
                // Local lookup.
                // Even though we could do b32 outside router ctx here,
                // we do it below instead so we can use the session,
                // which we can't do with lookup()
                Destination dest = ctx.namingService().lookup(name);
                if (dest != null || ctx.isRouterContext() || name.length() >= 516)
                    return dest;
            }
            try {
                I2PSession sess = sockMgr.getSession();
                return sess.lookupDest(name);
            } catch (I2PSessionException ise) {
                _log.error("Error looking up " + name, ise);
                return null;
            }
        }
    }
}
