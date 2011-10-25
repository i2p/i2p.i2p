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
import net.i2p.util.Log;


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
    private final static Log _log = new Log(DeliveryInstructions.class);
    private boolean _encrypted;
    private SessionKey _encryptionKey;
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
    private final static long FLAG_ENCRYPTED = 128;
    private final static long FLAG_MODE = 96;
    private final static long FLAG_DELAY = 16;
    
    public DeliveryInstructions() {
        setDeliveryMode(-1);
    }
    
    /**
     * For cloves only (not tunnels), default false, unused
     * @deprecated unused
     */
    public boolean getEncrypted() { return _encrypted; }
    
    /**
     * For cloves only (not tunnels), default false, unused
     * @deprecated unused
     */
    public void setEncrypted(boolean encrypted) { _encrypted = encrypted; }

    /**
     * For cloves only (not tunnels), default false, unused
     * @deprecated unused
     */
    public SessionKey getEncryptionKey() { return _encryptionKey; }

    /**
     * For cloves only (not tunnels), default false, unused
     * @deprecated unused
     */
    public void setEncryptionKey(SessionKey key) { _encryptionKey = key; }

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
    public boolean getDelayRequested() { return _delayRequested; }
    
    /**
     * default false, unused
     * @deprecated unused
     */
    public void setDelayRequested(boolean req) { _delayRequested = req; }
    
    /**
     * default 0, unusedx
     * @deprecated unused
     */
    public long getDelaySeconds() { return _delaySeconds; }
    
    /**
     * default 0, unused
     * @deprecated unused
     */
    public void setDelaySeconds(long seconds) { _delaySeconds = seconds; }
    
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        long flags = DataHelper.readLong(in, 1);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Read flags: " + flags + " mode: " +  flagMode(flags));
        
     /****
        if (flagEncrypted(flags)) {
            SessionKey k = new SessionKey();
            k.readBytes(in);
            setEncryptionKey(k);
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
                //Hash destHash = new Hash();
                //destHash.readBytes(in);
                Hash destHash = Hash.create(in);
                setDestination(destHash);
                break;
            case FLAG_MODE_ROUTER:
                //Hash routerHash = new Hash();
                //routerHash.readBytes(in);
                Hash routerHash = Hash.create(in);
                setRouter(routerHash);
                break;
            case FLAG_MODE_TUNNEL:
                //Hash tunnelRouterHash = new Hash();
                //tunnelRouterHash.readBytes(in);
                Hash tunnelRouterHash = Hash.create(in);
                setRouter(tunnelRouterHash);
                TunnelId id = new TunnelId();
                id.readBytes(in);
                setTunnelId(id);
                break;
        }
        
        if (flagDelay(flags)) {
            long delay = DataHelper.readLong(in, 4);
            setDelayRequested(true);
            setDelaySeconds(delay);
        } else {
            setDelayRequested(false);
        }
    }
    
    public int readBytes(byte data[], int offset) throws DataFormatException {
        int cur = offset;
        long flags = DataHelper.fromLong(data, cur, 1);
        cur++;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Read flags: " + flags + " mode: " +  flagMode(flags));
        
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
    private boolean flagEncrypted(long flags) {
        return (0 != (flags & FLAG_ENCRYPTED));
    }
****/
    
    /** high bits */
    private int flagMode(long flags) {
        long v = flags & FLAG_MODE;
        v >>>= 5;
        return (int)v;
    }
    
    /**  unused */
    private boolean flagDelay(long flags) {
        return (0 != (flags & FLAG_DELAY));
    }
    
    private long getFlags() {
        long val = 0L;
     /****
        if (getEncrypted())
            val = val | FLAG_ENCRYPTED;
      ****/
        long fmode = 0;
        switch (getDeliveryMode()) {
            case FLAG_MODE_LOCAL:
                break;
            case FLAG_MODE_DESTINATION:
                fmode = FLAG_MODE_DESTINATION << 5;
                break;
            case FLAG_MODE_ROUTER:
                fmode = FLAG_MODE_ROUTER << 5;
                break;
            case FLAG_MODE_TUNNEL:
                fmode = FLAG_MODE_TUNNEL << 5;
                break;
        }
        val = val | fmode;
        if (getDelayRequested())
            val = val | FLAG_DELAY;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("getFlags() = " + val);
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
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("mode = local");
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
    
    private byte[] getAdditionalInfo() {
        int additionalSize = getAdditionalInfoSize();
        byte rv[] = new byte[additionalSize];
        int offset = 0;
        offset += getAdditionalInfo(rv, offset);
        if (offset != additionalSize)
            _log.log(Log.CRIT, "wtf, additionalSize = " + additionalSize + ", offset = " + offset);
        return rv;
    }

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
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("mode = local");
                break;
            case FLAG_MODE_DESTINATION:
                if (_destinationHash == null) throw new IllegalStateException("Destination hash is not set");
                System.arraycopy(_destinationHash.getData(), 0, rv, offset, Hash.HASH_LENGTH);
                offset += Hash.HASH_LENGTH;
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("mode = destination, hash = " + _destinationHash);
                break;
            case FLAG_MODE_ROUTER:
                if (_routerHash == null) throw new IllegalStateException("Router hash is not set");
                System.arraycopy(_routerHash.getData(), 0, rv, offset, Hash.HASH_LENGTH);
                offset += Hash.HASH_LENGTH;
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("mode = router, routerHash = " + _routerHash);
                break;
            case FLAG_MODE_TUNNEL:
                if ( (_routerHash == null) || (_tunnelId == null) ) throw new IllegalStateException("Router hash or tunnel ID is not set");
                System.arraycopy(_routerHash.getData(), 0, rv, offset, Hash.HASH_LENGTH);
                offset += Hash.HASH_LENGTH;
                DataHelper.toLong(rv, offset, 4, _tunnelId.getTunnelId());
                offset += 4;
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("mode = tunnel, tunnelId = " + _tunnelId.getTunnelId() 
                               + ", routerHash = " + _routerHash);
                break;
        }
        if (getDelayRequested()) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("delay requested: " + getDelaySeconds());
            DataHelper.toLong(rv, offset, 4, getDelaySeconds());
            offset += 4;
        } else {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("delay NOT requested");
        }
        return offset - origOffset;
    }
    
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if ( (_deliveryMode < 0) || (_deliveryMode > FLAG_MODE_TUNNEL) ) throw new DataFormatException("Invalid data: mode = " + _deliveryMode);
        long flags = getFlags();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Write flags: " + flags + " mode: " + getDeliveryMode() 
                       + " =?= " + flagMode(flags));
        byte additionalInfo[] = getAdditionalInfo();
        DataHelper.writeLong(out, 1, flags);
        if (additionalInfo != null) {
            out.write(additionalInfo);
            out.flush();
        }
    }
    
    /**
     * @return the number of bytes written to the target
     */
    public int writeBytes(byte target[], int offset) {
        if ( (_deliveryMode < 0) || (_deliveryMode > FLAG_MODE_TUNNEL) ) throw new IllegalStateException("Invalid data: mode = " + _deliveryMode);
        long flags = getFlags();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Write flags: " + flags + " mode: " + getDeliveryMode() 
                       + " =?= " + flagMode(flags));
        int origOffset = offset;
        DataHelper.toLong(target, offset, 1, flags);
        offset++;
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
}
