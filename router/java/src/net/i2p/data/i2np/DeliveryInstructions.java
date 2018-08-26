package net.i2p.data.i2np;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.DataStructureImpl;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.TunnelId;
//import net.i2p.util.Log;


/**
 * Contains the delivery instructions for garlic cloves.
 * Generic "delivery instructions" are used both in tunnel messages
 * and in garlic cloves, with slight differences.
 * However,
 * the tunnel message generator TrivialPreprocessor and reader FragmentHandler do not use this class,
 * the reading and writing is handled inline there.
 *
 * @author jrandom
 */
public class DeliveryInstructions extends DataStructureImpl {
    //private final static Log _log = new Log(DeliveryInstructions.class);
    //private boolean _encrypted;
    //private SessionKey _encryptionKey;
    private int _deliveryMode;
    public final static int DELIVERY_MODE_LOCAL = 0;
    public final static int DELIVERY_MODE_DESTINATION = 1;
    public final static int DELIVERY_MODE_ROUTER = 2;
    public final static int DELIVERY_MODE_TUNNEL = 3;
    private Hash _destinationHash;
    private Hash _routerHash;
    private TunnelId _tunnelId;
    private boolean _delayRequested;
    private long _delaySeconds;
    
    private final static int FLAG_MODE_LOCAL = 0;
    private final static int FLAG_MODE_DESTINATION = 1;
    private final static int FLAG_MODE_ROUTER = 2;
    private final static int FLAG_MODE_TUNNEL = 3;
    
    /** @deprecated unused */
    @Deprecated
    private final static int FLAG_ENCRYPTED = 128;
    private final static int FLAG_MODE = 96;
    private final static int FLAG_DELAY = 16;
    
    /**
     *  Immutable local instructions, no options
     *
     *  @since 0.9.9
     */
    public static final DeliveryInstructions LOCAL = new LocalInstructions();

    /**
     *  Returns immutable local instructions, or new
     *
     *  @since 0.9.20
     */
    public static DeliveryInstructions create(byte[] data, int offset) throws DataFormatException {
        if (data[offset] == 0)
            return LOCAL;
        DeliveryInstructions rv = new DeliveryInstructions();
        rv.readBytes(data, offset);
        return rv;
    }

    public DeliveryInstructions() {
        _deliveryMode = -1;
    }
    
    /**
     * For cloves only (not tunnels), default false, unused
     * @deprecated unused
     */
    @Deprecated
    public boolean getEncrypted() { return /* _encrypted */ false; }
    
    /**
     * For cloves only (not tunnels), default false, unused
     * @deprecated unused
     */
    @Deprecated
    public void setEncrypted(boolean encrypted) { /* _encrypted = encrypted; */ }

    /**
     * For cloves only (not tunnels), default null, unused
     * @deprecated unused
     */
    @Deprecated
    public SessionKey getEncryptionKey() { return /* _encryptionKey */ null; }

    /**
     * For cloves only (not tunnels), default null, unused
     * @deprecated unused
     */
    @Deprecated
    public void setEncryptionKey(SessionKey key) { /* _encryptionKey = key; */ }

    /** default -1 */
    public int getDeliveryMode() { return _deliveryMode; }

    /** @param mode 0-3 */
    public void setDeliveryMode(int mode) { _deliveryMode = mode; }

    /** default null */
    public Hash getDestination() { return _destinationHash; }

    /** required for DESTINATION */
    public void setDestination(Hash dest) { _destinationHash = dest; }

    /** default null */
    public Hash getRouter() { return _routerHash; }

    /** required for ROUTER or TUNNEL */
    public void setRouter(Hash router) { _routerHash = router; }

    /** default null */
    public TunnelId getTunnelId() { return _tunnelId; }

    /** required for TUNNEL */
    public void setTunnelId(TunnelId id) { _tunnelId = id; }
    
    /**
     * default false, unused
     * @deprecated unused
     */
    @Deprecated
    public boolean getDelayRequested() { return _delayRequested; }
    
    /**
     * default false, unused
     * @deprecated unused
     */
    @Deprecated
    public void setDelayRequested(boolean req) { _delayRequested = req; }
    
    /**
     * default 0, unused
     * @deprecated unused
     */
    @Deprecated
    public long getDelaySeconds() { return _delaySeconds; }
    
    /**
     * default 0, unused
     * @deprecated unused
     */
    @Deprecated
    public void setDelaySeconds(long seconds) { _delaySeconds = seconds; }

    /**
     * @deprecated unused
     * @throws UnsupportedOperationException always
     */
    @Deprecated
    public void readBytes(InputStream in) {
        throw new UnsupportedOperationException();
    }
    
    public int readBytes(byte data[], int offset) throws DataFormatException {
        int cur = offset;
        int flags = data[cur] & 0xff;
        cur++;
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Read flags: " + flags + " mode: " +  flagMode(flags));
        
     /****
        if (flagEncrypted(flags)) {
            byte kd[] = new byte[SessionKey.KEYSIZE_BYTES];
            System.arraycopy(data, cur, kd, 0, SessionKey.KEYSIZE_BYTES);
            cur += SessionKey.KEYSIZE_BYTES;
            setEncryptionKey(new SessionKey(kd));
            setEncrypted(true);
        } else {
            setEncrypted(false);
        }
      ****/
        
        setDeliveryMode(flagMode(flags));
        switch (flagMode(flags)) {
            case FLAG_MODE_LOCAL:
                break;
            case FLAG_MODE_DESTINATION:
                //byte destHash[] = new byte[Hash.HASH_LENGTH];
                //System.arraycopy(data, cur, destHash, 0, Hash.HASH_LENGTH);
                Hash dh = Hash.create(data, cur);
                cur += Hash.HASH_LENGTH;
                setDestination(dh);
                break;
            case FLAG_MODE_ROUTER:
                //byte routerHash[] = new byte[Hash.HASH_LENGTH];
                //System.arraycopy(data, cur, routerHash, 0, Hash.HASH_LENGTH);
                Hash rh = Hash.create(data, cur);
                cur += Hash.HASH_LENGTH;
                setRouter(rh);
                break;
            case FLAG_MODE_TUNNEL:
                //byte tunnelRouterHash[] = new byte[Hash.HASH_LENGTH];
                //System.arraycopy(data, cur, tunnelRouterHash, 0, Hash.HASH_LENGTH);
                Hash trh = Hash.create(data, cur);
                cur += Hash.HASH_LENGTH;
                setRouter(trh);
                setTunnelId(new TunnelId(DataHelper.fromLong(data, cur, 4)));
                cur += 4;
                break;
        }
        
        if (flagDelay(flags)) {
            long delay = DataHelper.fromLong(data, cur, 4);
            cur += 4;
            setDelayRequested(true);
            setDelaySeconds(delay);
        } else {
            setDelayRequested(false);
        }
        return cur - offset;
    }
    
    
    /**
     * For cloves only (not tunnels), default false, unused
     * @deprecated unused
     */
/****
    private static boolean flagEncrypted(long flags) {
        return (0 != (flags & FLAG_ENCRYPTED));
    }
****/
    
    /** high bits */
    private static int flagMode(int flags) {
        int v = flags & FLAG_MODE;
        v >>>= 5;
        return v;
    }
    
    /**  unused */
    private static boolean flagDelay(int flags) {
        return (0 != (flags & FLAG_DELAY));
    }
    
    private int getFlags() {
        int val = 0;
     /****
        if (getEncrypted())
            val = val | FLAG_ENCRYPTED;
      ****/
        switch (getDeliveryMode()) {
            case FLAG_MODE_LOCAL:
                break;
            case FLAG_MODE_DESTINATION:
                val = FLAG_MODE_DESTINATION << 5;
                break;
            case FLAG_MODE_ROUTER:
                val = FLAG_MODE_ROUTER << 5;
                break;
            case FLAG_MODE_TUNNEL:
                val = FLAG_MODE_TUNNEL << 5;
                break;
        }
        if (getDelayRequested())
            val |= FLAG_DELAY;
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("getFlags() = " + val);
        return val;
    }
    
    private int getAdditionalInfoSize() {
        int additionalSize = 0;
     /****
        if (getEncrypted()) {
            if (_encryptionKey == null) throw new IllegalStateException("Encryption key is not set");
            additionalSize += SessionKey.KEYSIZE_BYTES;
        }
      ****/
        switch (getDeliveryMode()) {
            case FLAG_MODE_LOCAL:
                //if (_log.shouldLog(Log.DEBUG))
                //    _log.debug("mode = local");
                break;
            case FLAG_MODE_DESTINATION:
                if (_destinationHash == null) throw new IllegalStateException("Destination hash is not set");
                additionalSize += Hash.HASH_LENGTH;
                break;
            case FLAG_MODE_ROUTER:
                if (_routerHash == null) throw new IllegalStateException("Router hash is not set");
                additionalSize += Hash.HASH_LENGTH;
                break;
            case FLAG_MODE_TUNNEL:
                if ( (_routerHash == null) || (_tunnelId == null) ) throw new IllegalStateException("Router hash or tunnel ID is not set");
                additionalSize += Hash.HASH_LENGTH;
                additionalSize += 4; // tunnelId
                break;
        }
        
        if (getDelayRequested()) {
            additionalSize += 4;
        }
        return additionalSize;
    }
    
/****
    private byte[] getAdditionalInfo() {
        int additionalSize = getAdditionalInfoSize();
        byte rv[] = new byte[additionalSize];
        int offset = 0;
        offset += getAdditionalInfo(rv, offset);
        if (offset != additionalSize)
            //_log.log(Log.CRIT, "size mismatch, additionalSize = " + additionalSize + ", offset = " + offset);
            throw new IllegalStateException("size mismatch, additionalSize = " + additionalSize + ", offset = " + offset);
        return rv;
    }
****/

    /** */
    private int getAdditionalInfo(byte rv[], int offset) {
        int origOffset = offset;

      /****
        if (getEncrypted()) {
            if (_encryptionKey == null) throw new IllegalStateException("Encryption key is not set");
            System.arraycopy(_encryptionKey.getData(), 0, rv, offset, SessionKey.KEYSIZE_BYTES);
            offset += SessionKey.KEYSIZE_BYTES;
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("IsEncrypted");
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Is NOT Encrypted");
        }
      ****/

        switch (getDeliveryMode()) {
            case FLAG_MODE_LOCAL:
                //if (_log.shouldLog(Log.DEBUG))
                //    _log.debug("mode = local");
                break;
            case FLAG_MODE_DESTINATION:
                if (_destinationHash == null) throw new IllegalStateException("Destination hash is not set");
                System.arraycopy(_destinationHash.getData(), 0, rv, offset, Hash.HASH_LENGTH);
                offset += Hash.HASH_LENGTH;
                //if (_log.shouldLog(Log.DEBUG))
                //    _log.debug("mode = destination, hash = " + _destinationHash);
                break;
            case FLAG_MODE_ROUTER:
                if (_routerHash == null) throw new IllegalStateException("Router hash is not set");
                System.arraycopy(_routerHash.getData(), 0, rv, offset, Hash.HASH_LENGTH);
                offset += Hash.HASH_LENGTH;
                //if (_log.shouldLog(Log.DEBUG))
                //    _log.debug("mode = router, routerHash = " + _routerHash);
                break;
            case FLAG_MODE_TUNNEL:
                if ( (_routerHash == null) || (_tunnelId == null) ) throw new IllegalStateException("Router hash or tunnel ID is not set");
                System.arraycopy(_routerHash.getData(), 0, rv, offset, Hash.HASH_LENGTH);
                offset += Hash.HASH_LENGTH;
                DataHelper.toLong(rv, offset, 4, _tunnelId.getTunnelId());
                offset += 4;
                //if (_log.shouldLog(Log.DEBUG))
                //    _log.debug("mode = tunnel, tunnelId = " + _tunnelId.getTunnelId() 
                //               + ", routerHash = " + _routerHash);
                break;
        }
        if (getDelayRequested()) {
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("delay requested: " + getDelaySeconds());
            DataHelper.toLong(rv, offset, 4, getDelaySeconds());
            offset += 4;
        } else {
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("delay NOT requested");
        }
        return offset - origOffset;
    }
    
    /**
     * @deprecated unused
     * @throws UnsupportedOperationException always
     */
    @Deprecated
    public void writeBytes(OutputStream out) {
        throw new UnsupportedOperationException();
    }
    
    /**
     * @return the number of bytes written to the target
     */
    public int writeBytes(byte target[], int offset) {
        if ( (_deliveryMode < 0) || (_deliveryMode > FLAG_MODE_TUNNEL) ) throw new IllegalStateException("Invalid data: mode = " + _deliveryMode);
        int flags = getFlags();
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Write flags: " + flags + " mode: " + getDeliveryMode() 
        //               + " =?= " + flagMode(flags));
        int origOffset = offset;
        target[offset++] = (byte) flags;
        offset += getAdditionalInfo(target, offset);
        return offset - origOffset;
    }
    
    public int getSize() {
        return 1 // flags
               + getAdditionalInfoSize();
    }
    
    @Override
    public boolean equals(Object obj) {
        if ( (obj == null) || !(obj instanceof DeliveryInstructions))
            return false;
        DeliveryInstructions instr = (DeliveryInstructions)obj;
        return (getDelayRequested() == instr.getDelayRequested()) &&
               (getDelaySeconds() == instr.getDelaySeconds()) &&
               (getDeliveryMode() == instr.getDeliveryMode()) &&
               //(getEncrypted() == instr.getEncrypted()) &&
               DataHelper.eq(getDestination(), instr.getDestination()) &&
               DataHelper.eq(getEncryptionKey(), instr.getEncryptionKey()) &&
               DataHelper.eq(getRouter(), instr.getRouter()) &&
               DataHelper.eq(getTunnelId(), instr.getTunnelId());
    }
    
    @Override
    public int hashCode() {
        return (int)getDelaySeconds() +
                    getDeliveryMode() +
                    DataHelper.hashCode(getDestination()) +
                    DataHelper.hashCode(getEncryptionKey()) +
                    DataHelper.hashCode(getRouter()) +
                    DataHelper.hashCode(getTunnelId());
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(128);
        buf.append("[DeliveryInstructions: ");
        buf.append("\n\tDelivery mode: ");
        switch (getDeliveryMode()) {
            case DELIVERY_MODE_LOCAL:
                buf.append("local");
                break;
            case DELIVERY_MODE_DESTINATION:
                buf.append("destination");
                break;
            case DELIVERY_MODE_ROUTER:
                buf.append("router");
                break;
            case DELIVERY_MODE_TUNNEL:
                buf.append("tunnel");
                break;
        }
        buf.append("\n\tDelay requested: ").append(getDelayRequested());
        buf.append("\n\tDelay seconds: ").append(getDelaySeconds());
        buf.append("\n\tDestination: ").append(getDestination());
        //buf.append("\n\tEncrypted: ").append(getEncrypted());
        buf.append("\n\tEncryption key: ").append(getEncryptionKey());
        buf.append("\n\tRouter: ").append(getRouter());
        buf.append("\n\tTunnelId: ").append(getTunnelId());
        
        return buf.toString();
    }

    /**
     *  An immutable local delivery instructions with no options
     *  for efficiency.
     *
     *  @since 0.9.9
     */
    private static final class LocalInstructions extends DeliveryInstructions {
        //private static final byte flag = DELIVERY_MODE_LOCAL << 5;  // 0

        @Override
        public void setEncrypted(boolean encrypted) {
            throw new RuntimeException("immutable");
        }

        @Override
        public void setEncryptionKey(SessionKey key) {
            throw new RuntimeException("immutable");
        }

        @Override
        public int getDeliveryMode() { return DELIVERY_MODE_LOCAL; }

        @Override
        public void setDeliveryMode(int mode) {
            throw new RuntimeException("immutable");
        }

        @Override
        public void setDestination(Hash dest) {
            throw new RuntimeException("immutable");
        }

        @Override
        public void setRouter(Hash router) {
            throw new RuntimeException("immutable");
        }

        @Override
        public void setTunnelId(TunnelId id) {
            throw new RuntimeException("immutable");
        }

        @Override
        public void setDelayRequested(boolean req) {
            throw new RuntimeException("immutable");
        }

        @Override
        public void setDelaySeconds(long seconds) {
            throw new RuntimeException("immutable");
        }

        @Override
        public int readBytes(byte data[], int offset) throws DataFormatException {
            throw new RuntimeException("immutable");
        }

        @Override
        public int writeBytes(byte target[], int offset) {
            target[offset] = 0;
            return 1;
        }

        @Override
        public int getSize() {
            return 1;
        }

        @Override
        public String toString() {
            return "[DeliveryInstructions: " +
                   "\n\tDelivery mode: " +
                   "local]";
        }
    }
}
