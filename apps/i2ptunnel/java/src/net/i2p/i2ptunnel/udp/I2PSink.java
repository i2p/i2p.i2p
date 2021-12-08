package net.i2p.i2ptunnel.udp;

import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.data.Destination;
import net.i2p.client.datagram.I2PDatagramMaker;

/**
 * Producer
 *
 * This sends to a fixed destination specified in the constructor
 *
 * @author welterde
 */
public class I2PSink implements Sink {

    protected final boolean raw;
    protected final I2PSession sess;
    protected final Destination dest;
    protected final I2PDatagramMaker maker;
    /**
     *  @since 0.9.53
     */
    protected final int toPort;

    /**
     *  repliable (not raw)
     */
    public I2PSink(I2PSession sess, Destination dest) {
        this(sess, dest, false);
    }

    /**
     *  @param raw false for repliable
     */
    public I2PSink(I2PSession sess, Destination dest, boolean raw) {
        this(sess, dest, raw, I2PSession.PORT_UNSPECIFIED);
    }

    /**
     *  @param raw false for repliable
     *  @param fromPort I2CP source port, 0-65535
     *  @param toPort I2CP destination port, 0-65535
     *  @since 0.9.53
     */
    public I2PSink(I2PSession sess, Destination dest, boolean raw, int toPort) {
        this.sess = sess;
        this.dest = dest;
        this.raw = raw;
        this.toPort = toPort;
        
        // create maker
        if (raw) {
            this.maker = null;
        } else {
            this.maker = new I2PDatagramMaker();
            this.maker.setI2PDatagramMaker(this.sess);
        }
    }
    
    /**
     *  @param src ignored
     *  @param fromPort I2CP port
     *  @param ign_toPort ignored
     *  @since 0.9.53 added fromPort and toPort parameters, breaking change, sorry
     *  @throws RuntimeException if session is closed
     */
    public synchronized void send(Destination src, int fromPort, int ign_toPort, byte[] data) {
        //System.out.print("w");
        // create payload
        byte[] payload;
        if (!this.raw) {
            synchronized(this.maker) {
                payload = this.maker.makeI2PDatagram(data);
            }
        } else {
            payload = data;
        }
        
        // send message
        try {
            this.sess.sendMessage(this.dest, payload,
                                  (this.raw ? I2PSession.PROTO_DATAGRAM_RAW : I2PSession.PROTO_DATAGRAM),
                                  fromPort, toPort);
        } catch (I2PSessionException ise) {
            throw new RuntimeException("failed to send data", ise);
        }
    }
}
