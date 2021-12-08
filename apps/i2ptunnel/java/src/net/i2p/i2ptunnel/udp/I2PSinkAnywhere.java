package net.i2p.i2ptunnel.udp;

import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.data.Destination;
import net.i2p.client.datagram.I2PDatagramMaker;

/**
 * Producer
 *
 * This sends to any destination specified in send()
 *
 * @author zzz modded from I2PSink by welterde
 */
public class I2PSinkAnywhere implements Sink {

    protected final boolean raw;
    protected final I2PSession sess;
    protected final I2PDatagramMaker maker;

    public I2PSinkAnywhere(I2PSession sess) {
        this(sess, false);
    }

    public I2PSinkAnywhere(I2PSession sess, boolean raw) {
        this.sess = sess;
        this.raw = raw;
        
        // create maker
        if (raw) {
            this.maker = null;
        } else {
            this.maker = new I2PDatagramMaker();
            this.maker.setI2PDatagramMaker(this.sess);
        }
    }
    
    /**
     *  @param to - where it's going
     *  @throws RuntimeException if session is closed
     */
    public void send(Destination to, byte[] data) {
        send(to, I2PSession.PORT_UNSPECIFIED, I2PSession.PORT_UNSPECIFIED, data);
    }
    
    /**
     *  @param to - where it's going
     *  @param fromPort I2CP port 0 - 65535
     *  @param toPort I2CP port 0 - 65535
     *  @since 0.9.53
     *  @throws RuntimeException if session is closed
     */
    public synchronized void send(Destination to, int fromPort, int toPort, byte[] data) {
        // create payload
        byte[] payload;
        if(!this.raw) {
            synchronized(this.maker) {
                payload = this.maker.makeI2PDatagram(data);
            }
        } else {
            payload = data;
        }
        
        // send message
        try {
            this.sess.sendMessage(to, payload,
                                  (this.raw ? I2PSession.PROTO_DATAGRAM_RAW : I2PSession.PROTO_DATAGRAM),
                                  fromPort, toPort);
        } catch (I2PSessionException ise) {
            throw new RuntimeException("failed to send data", ise);
        }
    }
}
