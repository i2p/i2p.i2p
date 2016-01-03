package net.i2p.i2ptunnel.udp;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetAddress;

import net.i2p.data.Destination;

/**
 *
 * @author welterde
 */
public class UDPSink implements Sink {

    /**
     *  @throws IllegalArgumentException on DatagramSocket IOException
     */
    public UDPSink(InetAddress host, int port) {
        // create socket
        try {
            this.sock = new DatagramSocket();
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to open udp-socket", e);
        }
        
        this.remoteHost = host;
        
        // remote port
        this.remotePort = port;
    }
    
    /**
     *  @param src ignored
     *  @throws RuntimeException on DatagramSocket IOException
     */
    public void send(Destination src, byte[] data) {
        // if data.length > this.sock.getSendBufferSize() ...

        // create packet
        DatagramPacket packet = new DatagramPacket(data, data.length, this.remoteHost, this.remotePort);
        
        // send packet
        try {
            this.sock.send(packet);
        } catch (IOException ioe) {
            throw new RuntimeException("failed to send data", ioe);
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
    
    protected final DatagramSocket sock;
    protected final InetAddress remoteHost;
    protected final int remotePort;

}
