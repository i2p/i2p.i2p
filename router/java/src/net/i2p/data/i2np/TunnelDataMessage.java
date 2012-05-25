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

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.data.TunnelId;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;

/**
 * Defines the message sent between routers as part of the tunnel delivery
 *
 */
public class TunnelDataMessage extends I2NPMessageImpl {
    private Log _log;
    private long _tunnelId;
    private TunnelId _tunnelIdObj;
    private byte[] _data;
    private ByteArray _dataBuf;
    
    public final static int MESSAGE_TYPE = 18;
    private static final int DATA_SIZE = 1024;
    /** if we can't deliver a tunnel message in 10s, fuck it */
    private static final int EXPIRATION_PERIOD = 10*1000;
    
    private static final ByteCache _cache = ByteCache.getInstance(512, DATA_SIZE);
    /**
     * When true, it means this tunnelDataMessage is being used as part of a tunnel
     * processing pipeline, where the byte array is acquired during the TunnelDataMessage's
     * creation (per readMessage), held onto through several transitions (updating and
     * moving that array between different TunnelDataMessage instances or the fragment 
     * handler's cache, etc), until it is finally released back into the cache when written
     * to the next peer (or explicitly by the fragment handler's completion).
     * Setting this to false just increases memory churn
     */
    private static final boolean PIPELINED_CACHE = true;

    public TunnelDataMessage(I2PAppContext context) {
        super(context);
        _log = context.logManager().getLog(TunnelDataMessage.class);
        setMessageExpiration(context.clock().now() + EXPIRATION_PERIOD);
    }
    
    public long getTunnelId() { return _tunnelId; }
    public void setTunnelId(long id) { _tunnelId = id; }
    public TunnelId getTunnelIdObj() { 
        if (_tunnelIdObj == null)
            _tunnelIdObj = new TunnelId(_tunnelId); // not thread safe, but immutable, so who cares
        return _tunnelIdObj;
    }
    public void setTunnelId(TunnelId id) {
        _tunnelIdObj = id;
        _tunnelId = id.getTunnelId();
    }
    
    public byte[] getData() { return _data; }
    public void setData(byte data[]) { 
        if ( (data == null) || (data.length <= 0) )
            throw new IllegalArgumentException("Empty tunnel payload?");
        _data = data; 
    }
    
    public void readMessage(byte data[], int offset, int dataSize, int type) throws I2NPMessageException, IOException {
        if (type != MESSAGE_TYPE) throw new I2NPMessageException("Message type is incorrect for this message");
        int curIndex = offset;
        
        _tunnelId = DataHelper.fromLong(data, curIndex, 4);
        curIndex += 4;
        
        if (_tunnelId <= 0) 
            throw new I2NPMessageException("Invalid tunnel Id " + _tunnelId);
        
        // we cant cache it in trivial form, as other components (e.g. HopProcessor)
        // call getData() and use it as the buffer to write with.  it is then used
        // again to pass to the 'receiver', which may even cache it in a FragmentMessage.
        if (PIPELINED_CACHE) {
            _dataBuf = _cache.acquire();
            _data = _dataBuf.getData();
        } else {
            _data = new byte[DATA_SIZE];
        }
        System.arraycopy(data, curIndex, _data, 0, DATA_SIZE);
    }
    
    /** calculate the message body's length (not including the header and footer */
    protected int calculateWrittenLength() { return 4 + DATA_SIZE; }
    /** write the message body to the output array, starting at the given index */
    protected int writeMessageBody(byte out[], int curIndex) throws I2NPMessageException {
        if ( (_tunnelId <= 0) || (_data == null) )
            throw new I2NPMessageException("Not enough data to write out (id=" + _tunnelId + " data=" + _data + ")");
        if (_data.length <= 0) 
            throw new I2NPMessageException("Not enough data to write out (data.length=" + _data.length + ")");

        DataHelper.toLong(out, curIndex, 4, _tunnelId);
        curIndex += 4;
        System.arraycopy(_data, 0, out, curIndex, DATA_SIZE);
        curIndex += _data.length;
        if (PIPELINED_CACHE)
            _cache.release(_dataBuf);
        return curIndex;
    }
    
    public int getType() { return MESSAGE_TYPE; }
    
    @Override
    public int hashCode() {
        return (int)_tunnelId +
               DataHelper.hashCode(_data);
    }
    
    @Override
    public boolean equals(Object object) {
        if ( (object != null) && (object instanceof TunnelDataMessage) ) {
            TunnelDataMessage msg = (TunnelDataMessage)object;
            return DataHelper.eq(getTunnelId(),msg.getTunnelId()) &&
                   DataHelper.eq(getData(),msg.getData());
        } else {
            return false;
        }
    }
    
    @Override
    public byte[] toByteArray() {
        byte rv[] = super.toByteArray();
        if (rv == null)
            throw new RuntimeException("unable to toByteArray(): " + toString());
        return rv;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("[TunnelDataMessage:");
        buf.append(" MessageId: ").append(getUniqueId());
        buf.append(" Tunnel ID: ").append(getTunnelId());
        buf.append("]");
        return buf.toString();
    }
}
