package net.i2p.internal;

import java.io.InputStream;

import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.data.i2cp.I2CPMessageImpl;

/**
 * For marking end-of-queues in a standard manner.
 * Don't actually send it.
 *
 * @author zzz
 * @since 0.8.3
 */
public class PoisonI2CPMessage extends I2CPMessageImpl {
    public final static int MESSAGE_TYPE = 999999;

    public PoisonI2CPMessage() {
        super();
    }

    /**
     *  @deprecated don't do this
     *  @throws I2CPMessageException always
     */
    protected void doReadMessage(InputStream in, int size) throws I2CPMessageException {
        throw new I2CPMessageException("Don't do this");
    }

    /**
     *  @deprecated don't do this
     *  @throws I2CPMessageException always
     */
    protected byte[] doWriteMessage() throws I2CPMessageException {
        throw new I2CPMessageException("Don't do this");
    }

    public int getType() {
        return MESSAGE_TYPE;
    }

    /* FIXME missing hashCode() method FIXME */
    @Override
    public boolean equals(Object object) {
        if ((object != null) && (object instanceof PoisonI2CPMessage)) {
            return true;
        }
        
        return false;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[PoisonMessage]");
        return buf.toString();
    }
}
