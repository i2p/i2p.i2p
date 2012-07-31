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
import java.util.ArrayList;
import java.util.Collections;


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
 * @author Adam Buckley
 * (minor refactoring by jrandom)
 * @since 0.9.1 moved from net.i2p.time
 */
class NtpClient {
    /** difference between the unix epoch and jan 1 1900 (NTP uses that) */
    private final static double SECONDS_1900_TO_EPOCH = 2208988800.0;
    private final static int NTP_PORT = 123;
    
    /**
     * Query the ntp servers, returning the current time from first one we find
     *
     * @return milliseconds since january 1, 1970 (UTC)
     * @throws IllegalArgumentException if none of the servers are reachable
     */
    public static long currentTime(String serverNames[]) {
        if (serverNames == null) 
            throw new IllegalArgumentException("No NTP servers specified");
        ArrayList names = new ArrayList(serverNames.length);
        for (int i = 0; i < serverNames.length; i++)
            names.add(serverNames[i]);
        Collections.shuffle(names);
        for (int i = 0; i < names.size(); i++) {
            long now = currentTime((String)names.get(i));
            if (now > 0)
                return now;
        }
        throw new IllegalArgumentException("No reachable NTP servers specified");
    }
    
    /**
     * Query the ntp servers, returning the current time from first one we find
     * Hack to return time and stratum
     * @return time in rv[0] and stratum in rv[1]
     * @throws IllegalArgumentException if none of the servers are reachable
     * @since 0.7.12
     */
    public static long[] currentTimeAndStratum(String serverNames[]) {
        if (serverNames == null) 
            throw new IllegalArgumentException("No NTP servers specified");
        ArrayList names = new ArrayList(serverNames.length);
        for (int i = 0; i < serverNames.length; i++)
            names.add(serverNames[i]);
        Collections.shuffle(names);
        for (int i = 0; i < names.size(); i++) {
            long[] rv = currentTimeAndStratum((String)names.get(i));
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
    public static long currentTime(String serverName) {
         long[] la = currentTimeAndStratum(serverName);
         if (la != null)
             return la[0];
         return -1;
    }

    /**
     * Hack to return time and stratum
     * @return time in rv[0] and stratum in rv[1], or null for error
     * @since 0.7.12
     */
    private static long[] currentTimeAndStratum(String serverName) {
        try {
            // Send request
            DatagramSocket socket = new DatagramSocket();
            InetAddress address = InetAddress.getByName(serverName);
            byte[] buf = new NtpMessage().toByteArray();
            DatagramPacket packet = new DatagramPacket(buf, buf.length, address, NTP_PORT);

            // Set the transmit timestamp *just* before sending the packet
            // ToDo: Does this actually improve performance or not?
            NtpMessage.encodeTimestamp(packet.getData(), 40,
                                       (System.currentTimeMillis()/1000.0) 
                                       + SECONDS_1900_TO_EPOCH);

            socket.send(packet);

            // Get response
            packet = new DatagramPacket(buf, buf.length);
            socket.setSoTimeout(10*1000);
            try {
                socket.receive(packet);
            } catch (InterruptedIOException iie) {
                socket.close();
                return null;
            }

            // Immediately record the incoming timestamp
            double destinationTimestamp = (System.currentTimeMillis()/1000.0) + SECONDS_1900_TO_EPOCH;

            // Process response
            NtpMessage msg = new NtpMessage(packet.getData());

            //double roundTripDelay = (destinationTimestamp-msg.originateTimestamp) -
            //                        (msg.receiveTimestamp-msg.transmitTimestamp);
            double localClockOffset = ((msg.receiveTimestamp - msg.originateTimestamp) +
                                       (msg.transmitTimestamp - destinationTimestamp)) / 2;
            socket.close();

            // Stratum must be between 1 (atomic) and 15 (maximum defined value)
            // Anything else is right out, treat such responses like errors
            if ((msg.stratum < 1) || (msg.stratum > 15)) {
                //System.out.println("Response from NTP server of unacceptable stratum " + msg.stratum + ", failing.");
                return null;
            }
            
            long[] rv = new long[2];
            rv[0] = (long)(System.currentTimeMillis() + localClockOffset*1000);
            rv[1] = msg.stratum;
            //System.out.println("host: " + address.getHostAddress() + " rtt: " + roundTripDelay + " offset: " + localClockOffset + " seconds");
            return rv;
        } catch (IOException ioe) {
            //ioe.printStackTrace();
            return null;
        }
    }
    
    public static void main(String[] args) throws IOException {
        // Process command-line args
        if(args.length <= 0) {
            printUsage();
            return;
            // args = new String[] { "ntp1.sth.netnod.se", "ntp2.sth.netnod.se" };
        } 

        long now = currentTime(args);
        System.out.println("Current time: " + new java.util.Date(now));
    }
    
    
    
    /**
     * Prints usage
     */
    static void printUsage() {
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
}
