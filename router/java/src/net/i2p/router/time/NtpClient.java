package net.i2p.router.time;
/*
 * Copyright (c) 2004, Adam Buckley
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without 
 * modification, are permitted provided that the following conditions are met:
 * 
 * - Redistributions of source code must retain the above copyright notice, 
 *   this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, 
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 * - Neither the name of Adam Buckley nor the names of its contributors may be 
 *   used to endorse or promote products derived from this software without 
 *   specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" 
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE 
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE 
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE 
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR 
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF 
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS 
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN 
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) 
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE 
 * POSSIBILITY OF SUCH DAMAGE.
 *
 */
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.Addresses;
import net.i2p.util.DNSOverHTTPS;
import net.i2p.util.HexDump;
import net.i2p.util.Log;

/**
 * NtpClient - an NTP client for Java.  This program connects to an NTP server
 * and prints the response to the console.
 *
 * The local clock offset calculation is implemented according to the SNTP
 * algorithm specified in RFC 2030.
 *
 * Note that on windows platforms, the curent time-of-day timestamp is limited
 * to an resolution of 10ms and adversely affects the accuracy of the results.
 *
 * Public only for main(), not a public API, not for external use.
 *
 * TODO NOT 2036-compliant, see RFC 4330
 *
 * @author Adam Buckley
 * (minor refactoring by jrandom)
 * @since 0.9.1 moved from net.i2p.time
 */
public class NtpClient {
    /** difference between the unix epoch and jan 1 1900 (NTP uses that) */
    final static double SECONDS_1900_TO_EPOCH = 2208988800.0;
    private final static int NTP_PORT = 123;
    private static final int DEFAULT_TIMEOUT = 10*1000;
    private static final int OFF_ORIGTIME = 24;
    private static final int OFF_TXTIME = 40;
    private static final int MIN_PKT_LEN = 48;
    // IP:reason for servers that sent us a kiss of death
    private static final Map<String, String> kisses = new ConcurrentHashMap<String, String>(2);
    private static final String PROP_USE_DNS_OVER_HTTPS = "time.useDNSOverHTTPS";
    private static final boolean DEFAULT_USE_DNS_OVER_HTTPS = false;

    /**
     * Query the ntp servers, returning the current time from first one we find
     *
     * @return milliseconds since january 1, 1970 (UTC)
     * @throws IllegalArgumentException if none of the servers are reachable
     */
/****
    public static long currentTime(String serverNames[]) {
        if (serverNames == null) 
            throw new IllegalArgumentException("No NTP servers specified");
        ArrayList<String> names = new ArrayList<String>(serverNames.length);
        for (int i = 0; i < serverNames.length; i++)
            names.add(serverNames[i]);
        Collections.shuffle(names);
        for (int i = 0; i < names.size(); i++) {
            long now = currentTime(names.get(i));
            if (now > 0)
                return now;
        }
        throw new IllegalArgumentException("No reachable NTP servers specified");
    }
****/
    
    /**
     * Query the ntp servers, returning the current time from first one we find
     * Hack to return time and stratum
     *
     * @param log may be null
     * @return time in rv[0] and stratum in rv[1]
     * @throws IllegalArgumentException if none of the servers are reachable
     * @since 0.7.12
     */
    static long[] currentTimeAndStratum(String serverNames[], int perServerTimeout, boolean preferIPv6, Log log) {
        if (serverNames == null) 
            throw new IllegalArgumentException("No NTP servers specified");
        ArrayList<String> names = new ArrayList<String>(serverNames.length);
        for (int i = 0; i < serverNames.length; i++)
            names.add(serverNames[i]);
        Collections.shuffle(names);
        for (int i = 0; i < names.size(); i++) {
            long[] rv = currentTimeAndStratum(names.get(i), perServerTimeout, preferIPv6, log);
            if (rv != null && rv[0] > 0)
                return rv;
        }
        throw new IllegalArgumentException("No reachable NTP servers specified");
    }
    
    /**
     * Query the given NTP server, returning the current internet time
     *
     * @return milliseconds since january 1, 1970 (UTC), or -1 on error
     */
/****
    public static long currentTime(String serverName) {
         long[] la = currentTimeAndStratum(serverName, DEFAULT_TIMEOUT);
         if (la != null)
             return la[0];
         return -1;
    }
****/

    /**
     * Hack to return time and stratum
     *
     * @param log may be null
     * @return time in rv[0] and stratum in rv[1], or null for error
     * @since 0.7.12
     */
    private static long[] currentTimeAndStratum(String serverName, int timeout, boolean preferIPv6, Log log) {
        DatagramSocket socket = null;
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        boolean useDNSOverHTTPS = ctx.getProperty(PROP_USE_DNS_OVER_HTTPS, DEFAULT_USE_DNS_OVER_HTTPS);
        try {
            // Send request
            InetAddress address;
            if (preferIPv6) {
                String ip = null;
                if (useDNSOverHTTPS) {
                    DNSOverHTTPS doh = new DNSOverHTTPS(ctx);
                    ip = doh.lookup(serverName, DNSOverHTTPS.Type.V6_PREFERRED);
                }
                if (ip != null) {
                    address = InetAddress.getByName(ip);
                } else {
                    // fallback to regular DNS
                    InetAddress[] addrs = InetAddress.getAllByName(serverName);
                    if (addrs == null || addrs.length == 0)
                        throw new UnknownHostException();
                    address = null;
                    for (int i = 0; i < addrs.length; i++) {
                        if (addrs[i] instanceof Inet6Address) {
                            address = addrs[i];
                            break;
                        }
                        if (address == null)
                            address = addrs[0];
                    }
                }
            } else {
                if (useDNSOverHTTPS) {
                    DNSOverHTTPS doh = new DNSOverHTTPS(ctx);
                    String ip = doh.lookup(serverName, DNSOverHTTPS.Type.V4_ONLY);
                    if (ip != null)
                        serverName = ip;
                }
                // fallback to regular DNS
                address = InetAddress.getByName(serverName);
            }
            String who = address.getHostAddress();
            String why = kisses.get(who);
            if (why != null) {
                if (log != null)
                    log.warn("Not querying, previous KoD from NTP server " + serverName + " (" + who + ") " + why);
                return null;
            }
            byte[] buf = new NtpMessage().toByteArray();
            DatagramPacket packet = new DatagramPacket(buf, buf.length, address, NTP_PORT);
            byte[] txtime = new byte[8];

            socket = new DatagramSocket();
            // Set the transmit timestamp *just* before sending the packet
            // ToDo: Does this actually improve performance or not?
            NtpMessage.encodeTimestamp(packet.getData(), OFF_TXTIME,
                                       (System.currentTimeMillis()/1000.0) 
                                       + SECONDS_1900_TO_EPOCH);

            socket.send(packet);
            // save for check
            System.arraycopy(packet.getData(), OFF_TXTIME, txtime, 0, 8);
            if (log != null && log.shouldDebug())
                log.debug("Sent to " + serverName + " (" + who + ")\n" + HexDump.dump(buf));

            // Get response
            packet = new DatagramPacket(buf, buf.length);
            socket.setSoTimeout(timeout);
            socket.receive(packet);

            // Immediately record the incoming timestamp
            double destinationTimestamp = (System.currentTimeMillis()/1000.0) + SECONDS_1900_TO_EPOCH;

            if (packet.getLength() < MIN_PKT_LEN) {
                if (log != null && log.shouldWarn())
                    log.warn("Short packet length " + packet.getLength());
                return null;
            }

            // Process response
            NtpMessage msg = new NtpMessage(packet.getData());

            String from = packet.getAddress().getHostAddress();
            int port = packet.getPort();
            if (log != null && log.shouldDebug())
                log.debug("Received from: " + from + " port " + port +
                          '\n' + msg + '\n' + HexDump.dump(packet.getData()));

            // spoof check
            if (port != NTP_PORT || !who.equals(from)) {
                if (log != null && log.shouldWarn())
                    log.warn("Sent to " + who + " port " + NTP_PORT+ " but received from " + packet.getSocketAddress());
                return null;
            }

            // Stratum must be between 1 (atomic) and 15 (maximum defined value)
            // Anything else is right out, treat such responses like errors
            // KoD (stratum 0) processing is below, after origin time check
            if (msg.stratum > 15) {
                if (log != null && log.shouldWarn())
                    log.warn("NTP server " + serverName + " bad stratum " + msg.stratum);
                return null;
            }

            // spoof check
            if (!DataHelper.eq(txtime, 0, packet.getData(), OFF_ORIGTIME, 8)) {
                if (log != null && log.shouldWarn())
                    log.warn("Origin time mismatch sent:\n" + HexDump.dump(txtime) +
                             "rcvd:\n" + HexDump.dump(packet.getData(), OFF_ORIGTIME, 8));
                return null;
            }

            // More sanity checks
            // See http://doolittle.icarus.com/ntpclient/README
            // See RFC 4330 Sec. 5
            if (msg.leapIndicator == 3 ||
                msg.version < 3 ||
                // 4 for server. Above reference is wrong, it says 3 which is client.
                msg.mode != 4 ||
                msg.transmitTimestamp <= 0 ||
                // following values are in seconds, vs. 1/65536 seconds in above reference
                Math.abs(msg.rootDelay) > 1.0d ||
                Math.abs(msg.rootDispersion) > 1.0d) {
                if (log != null && log.shouldWarn())
                    log.warn("Failed sanity checks:\n" + msg);
                return null;
            }

            // KoD check (AFTER spoof checks)
            if (msg.stratum == 0) {
                why = msg.referenceIdentifierToString();
                // Remember the specific IP, not the server name, although RFC 4330
                // probably wants us to block the name
                kisses.put(who, why);
                if (log != null)
                    log.logAlways(Log.WARN, "KoD from NTP server " + serverName + " (" + who + ") " + why);
                return null;
            }


            double localClockOffset = ((msg.receiveTimestamp - msg.originateTimestamp) +
                                       (msg.transmitTimestamp - destinationTimestamp)) / 2;
            
            long[] rv = new long[2];
            rv[0] = (long)(System.currentTimeMillis() + localClockOffset*1000);
            rv[1] = msg.stratum;
            if (log != null && log.shouldInfo()) {
                double roundTripDelay = (destinationTimestamp-msg.originateTimestamp) -
                                        (msg.receiveTimestamp-msg.transmitTimestamp);
                log.info("host: " + packet.getAddress().getHostAddress() + " rtt: " +
                         roundTripDelay + " offset: " + localClockOffset + " seconds");
            }
            return rv;
        } catch (IOException ioe) {
            if (log != null && log.shouldWarn())
                log.warn("NTP failure from " + serverName, ioe);
            return null;
        } finally {
            if (socket != null)
                socket.close();
        }
    }
    
    /**
     * Usage: NtpClient [-6] [servers...]
     * default pool.ntp.org
     */
    public static void main(String[] args) throws IOException {
        boolean ipv6 = false;
        if (args.length > 0 && args[0].equals("-6")) {
            ipv6 = true;
            if (args.length == 1)
                args = new String[0];
            else
                args = Arrays.copyOfRange(args, 1, args.length);
        }
        if (args.length <= 0) {
           args = new String[] { "pool.ntp.org" };
        } 
        System.out.println("Querying " + Arrays.toString(args));

        Log log = new Log(NtpClient.class);
        try {
            long[] rv = currentTimeAndStratum(args, DEFAULT_TIMEOUT, ipv6, log);
            System.out.println("Current time: " + new java.util.Date(rv[0]) + " (stratum " + rv[1] +
                               ") offset " + (rv[0] - System.currentTimeMillis()) + "ms");
        } catch (IllegalArgumentException iae) {
            System.out.println("Failed: " + iae.getMessage());
        }
    }
    
/****
    private static void printUsage() {
        System.out.println(
        "NtpClient - an NTP client for Java.\n" +
        "\n" +
        "This program connects to an NTP server and prints the current time to the console.\n" +
        "\n" +
        "\n" +
        "Usage: java NtpClient server[ server]*\n" +
        "\n" +
        "\n" +
        "This program is copyright (c) Adam Buckley 2004 and distributed under the terms\n" +
        "of the GNU General Public License.  This program is distributed in the hope\n" +
        "that it will be useful, but WITHOUT ANY WARRANTY; without even the implied\n" +
        "warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU\n" +
        "General Public License available at http://www.gnu.org/licenses/gpl.html for\n" +
        "more details.");
        
    }
****/
}
