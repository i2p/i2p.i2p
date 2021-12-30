package net.i2p.i2ptunnel.udp;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionMuxedListener;
import net.i2p.client.datagram.I2PDatagramDissector;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 * Refactored in 0.9.53 to support I2CP protocols and ports
 *
 * @author welterde
 */
public class I2PSource implements Source {

    protected final I2PSession sess;
    protected Sink sink;
    private final Protocol protocol;
    private final int port;
    private final I2PDatagramDissector diss;
    private final Log log;

    /**
     *  @since 0.9.53
     */
    public enum Protocol { REPLIABLE, RAW, BOTH }

    /**
     *  Handles both REPLIABLE and RAW on any port
     */
    public I2PSource(I2PSession sess) {
        this(sess, Protocol.BOTH);
    }

    /**
     *  Listen on all I2CP ports.
     *  No support for arbitrary protocol numbers.
     *
     *  @param protocol REPLIABLE, RAW, or BOTH
     *  @since 0.9.53
     */
    public I2PSource(I2PSession sess, Protocol protocol) {
        this(sess, protocol, I2PSession.PORT_ANY);
    }

    /**
     *  @param port I2CP port or I2PSession.PORT_ANY
     *  @param protocol REPLIABLE, RAW, or BOTH
     *  @since 0.9.53
     */
    public I2PSource(I2PSession sess, Protocol protocol, int port) {
        this.sess = sess;
        this.protocol = protocol;
        this.port = port;
        diss = (protocol != Protocol.RAW) ? new I2PDatagramDissector() : null;
        log = I2PAppContext.getGlobalContext().logManager().getLog(getClass());
    }
    
    public void setSink(Sink sink) {
        this.sink = sink;
    }

    public void start() {
        // create listener
        Listener l = new Listener();
        if (protocol != Protocol.RAW)
            sess.addMuxedSessionListener(l, I2PSession.PROTO_DATAGRAM, port);
        if (protocol != Protocol.REPLIABLE)
            sess.addMuxedSessionListener(l, I2PSession.PROTO_DATAGRAM_RAW, port);
    }
    
    protected class Listener implements I2PSessionMuxedListener {

        public void messageAvailable(I2PSession sess, int id, long size) {
            throw new IllegalStateException("muxed");
        }

        /**
         *  @since 0.9.53
         */
        public void messageAvailable(I2PSession session, int id, long size, int proto, int fromPort, int toPort) {
            if (log.shouldDebug())
                log.debug("Got " + size + " bytes, proto: " + proto + " from port: " + fromPort + " to port: " + toPort);
            try {
                // receive message
                byte[] msg = session.receiveMessage(id);
                if (proto == I2PSession.PROTO_DATAGRAM) {
                    // load datagram into it
                    diss.loadI2PDatagram(msg);
                    // now call sink
                    sink.send(diss.getSender(), fromPort, toPort, diss.getPayload());
                } else if (proto == I2PSession.PROTO_DATAGRAM_RAW) {
                    sink.send(null, fromPort, toPort, msg);
                } else {
                    if (log.shouldWarn())
                        log.warn("dropping message with unknown protocol " + proto);
                }
                //System.out.print("r");
            } catch(Exception e) {
                if (log.shouldWarn())
                    log.warn("error receiving datagram", e);
            }
        }

        public void reportAbuse(I2PSession arg0, int arg1) {
            // ignore
        }

        public void disconnected(I2PSession arg0) {
        }

        public void errorOccurred(I2PSession arg0, String arg1, Throwable arg2) {
            log.error(arg1, arg2);
        }
        
    }
}
