package net.i2p.time;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;


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
 *
 * This code is copyright (c) Adam Buckley 2004
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option)
 * any later version.  A HTML version of the GNU General Public License can be
 * seen at http://www.gnu.org/licenses/gpl.html
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * @author Adam Buckley
 * (minor refactoring by jrandom)
 */
public class NtpClient {
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
        for (int i = 0; i < serverNames.length; i++) {
            long now = currentTime(serverNames[i]);
            if (now > 0)
                return now;
        }
        throw new IllegalArgumentException("No reachable NTP servers specified");
    }
    
    /**
     * Query the given NTP server, returning the current internet time
     *
     * @return milliseconds since january 1, 1970 (UTC), or -1 on error
     */
    public static long currentTime(String serverName) {
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
                return -1;
            }

            // Immediately record the incoming timestamp
            double destinationTimestamp = (System.currentTimeMillis()/1000.0) + SECONDS_1900_TO_EPOCH;

            // Process response
            NtpMessage msg = new NtpMessage(packet.getData());
            double roundTripDelay = (destinationTimestamp-msg.originateTimestamp) -
                                    (msg.receiveTimestamp-msg.transmitTimestamp);
            double localClockOffset = ((msg.receiveTimestamp - msg.originateTimestamp) +
                                       (msg.transmitTimestamp - destinationTimestamp)) / 2;
            socket.close();
            
            //System.out.println("host: " + serverName + " rtt: " + roundTripDelay + " offset: " + localClockOffset + " seconds");
            return (long)(System.currentTimeMillis() + localClockOffset*1000);
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return -1;
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
