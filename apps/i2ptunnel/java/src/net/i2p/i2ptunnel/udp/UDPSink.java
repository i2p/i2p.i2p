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

    protected final DatagramSocket sock;
    protected final InetAddress remoteHost;
    protected final int remotePort;

    /**
     *  @param host where to send
     *  @param port where to send
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
        this.remotePort = port;
    }
        

    /**
     *  @param socket existing socket
     *  @param host where to send
     *  @param port where to send
     *  @since 0.9.53
     */
    public UDPSink(DatagramSocket socket, InetAddress host, int port) {
        sock = socket;
        this.remoteHost = host;
        this.remotePort = port;
    }
    
    /**
     *  @param src ignored
     *  @param fromPort ignored
     *  @param toPort ignored
     *  @since 0.9.53 added fromPort and toPort parameters, breaking change, sorry
     *  @throws RuntimeException on DatagramSocket IOException
     */
    public void send(Destination src, int fromPort, int toPort, byte[] data) {
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
    
    /**
     *  @return the local port of the DatagramSocket we are sending from
     */
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
}
