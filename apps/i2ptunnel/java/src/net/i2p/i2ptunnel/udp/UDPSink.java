/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package net.i2p.i2ptunnel.udp;

// system
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetAddress;

// i2p
import net.i2p.data.Destination;

/**
 *
 * @author welterde
 */
public class UDPSink implements Sink {
    public UDPSink(InetAddress host, int port) {
        // create socket
        try {
            this.sock = new DatagramSocket();
        } catch(Exception e) {
            // TODO: fail better
            throw new RuntimeException("failed to open udp-socket", e);
        }
        
        this.remoteHost = host;
        
        // remote port
        this.remotePort = port;
    }
    
    public void send(Destination src, byte[] data) {
        // if data.length > this.sock.getSendBufferSize() ...

        // create packet
        DatagramPacket packet = new DatagramPacket(data, data.length, this.remoteHost, this.remotePort);
        
        // send packet
        try {
            this.sock.send(packet);
        } catch(Exception e) {
            // TODO: fail a bit better
            e.printStackTrace();
        }
    }
    
    public int getPort() {    
        return this.sock.getLocalPort();    
    }    
    
    /** to pass to UDPSource constructor */
    public DatagramSocket getSocket() {    
        return this.sock;    
    }    
    
    public void stop() {    
        this.sock.close();    
    }    
    
    
    
    
    
    
    
    
    
    
    protected DatagramSocket sock;
    protected InetAddress remoteHost;
    protected int remotePort;

}
