/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.i2p.I2PException;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.data.Destination;
import net.i2p.util.EventDispatcher;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;

public class I2Ping extends I2PTunnelTask implements Runnable {
    private final static Log _log = new Log(I2Ping.class);

    private int PING_COUNT = 3;
    private static final int CPING_COUNT = 5;
    private static final int PING_TIMEOUT = 5000;

    private static final long PING_DISTANCE = 1000;

    private int MAX_SIMUL_PINGS = 10; // not really final...

    private boolean countPing = false;
    private boolean reportTimes = true;

    private I2PSocketManager sockMgr;
    private Logging l;
    private boolean finished = false;
    private String command;
    private long timeout = PING_TIMEOUT;

    private final Object simulLock = new Object();
    private int simulPings = 0;
    private long lastPingTime = 0;

    private final Object lock = new Object(), slock = new Object();

    //public I2Ping(String cmd, Logging l,
    //		  boolean ownDest) {
    //	I2Ping(cmd, l, (EventDispatcher)null);
    //}

    public I2Ping(String cmd, Logging l, boolean ownDest, EventDispatcher notifyThis, I2PTunnel tunnel) {
        super("I2Ping [" + cmd + "]", notifyThis, tunnel);
        this.l = l;
        command = cmd;
        synchronized (slock) {
            if (ownDest) {
                sockMgr = I2PTunnelClient.buildSocketManager(tunnel);
            } else {
                sockMgr = I2PTunnelClient.getSocketManager(tunnel);
            }
        }
        Thread t = new I2PThread(this);
        t.setName("Client");
        t.start();
        open = true;
    }

    public void run() {
        l.log("*** I2Ping results:");
        try {
            runCommand(command);
        } catch (InterruptedException ex) {
            l.log("*** Interrupted");
            _log.error("Pinger interrupted", ex);
        } catch (IOException ex) {
            _log.error("Pinger exception", ex);
        }
        l.log("*** Finished.");
        synchronized (lock) {
            finished = true;
        }
        close(false);
    }

    public void runCommand(String cmd) throws InterruptedException, IOException {
      while (true) {
        if (cmd.startsWith("-t ")) { // timeout
            cmd = cmd.substring(3);
            int pos = cmd.indexOf(" ");
            if (pos == -1) {
                l.log("Syntax error");
                return;
            } else {
                timeout = Long.parseLong(cmd.substring(0, pos));
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
                PING_COUNT = Integer.parseInt(cmd.substring(0, pos));
                cmd = cmd.substring(pos + 1);
            }
        } else if (cmd.startsWith("-c ")) { // "count" ping
            countPing = true;
            cmd = cmd.substring(3);
        } else if (cmd.equals("-h")) { // ping all hosts
            cmd = "-l hosts.txt";
        } else if (cmd.startsWith("-l ")) { // ping a list of hosts
            BufferedReader br = new BufferedReader(new FileReader(cmd.substring(3)));
            String line;
            List pingHandlers = new ArrayList();
            int i = 0;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("#")) continue; // comments
                if (line.startsWith(";")) continue;
                if (line.startsWith("!")) continue;
                if (line.indexOf("=") != -1) { // maybe file is hosts.txt?
                    line = line.substring(0, line.indexOf("="));
                }
                pingHandlers.add(new PingHandler(line));
                if (++i > 1)
                    reportTimes = false;
            }
            br.close();
            for (Iterator it = pingHandlers.iterator(); it.hasNext();) {
                Thread t = (Thread) it.next();
                t.join();
            }
            return;
        } else {
            Thread t = new PingHandler(cmd);
            t.join();
            return;
        }
      }
    }

    public boolean close(boolean forced) {
        if (!open) return true;
        synchronized (lock) {
            if (!forced && !finished) {
                l.log("There are still pings running!");
                return false;
            }
            l.log("Closing pinger " + toString());
            l.log("Pinger closed.");
            open = false;
            return true;
        }
    }

    public boolean ping(Destination dest) throws I2PException {
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

    public class PingHandler extends I2PThread {
        private String destination;

        public PingHandler(String dest) {
            this.destination = dest;
            setName("PingHandler for " + dest);
            start();
        }

        @Override
        public void run() {
            try {
                Destination dest = I2PTunnel.destFromName(destination);
                if (dest == null) {
                    synchronized (lock) { // Logger is not thread safe
                        l.log("Unresolvable: " + destination + "");
                    }
                    return;
                }
                int pass = 0;
                int fail = 0;
                long totalTime = 0;
                int cnt = countPing ? CPING_COUNT : PING_COUNT;
                StringBuilder pingResults = new StringBuilder(2 * cnt + destination.length() + 3);
                for (int i = 0; i < cnt; i++) {
                    boolean sent;
                    sent = ping(dest);
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
                synchronized (lock) { // Logger is not thread safe
                    l.log(pingResults.toString());
                }
            } catch (I2PException ex) {
                _log.error("Error pinging " + destination, ex);
            }
        }
    }
}
