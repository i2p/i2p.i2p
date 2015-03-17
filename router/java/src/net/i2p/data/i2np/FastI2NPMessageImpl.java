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

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.util.HexDump;
import net.i2p.util.Log;
import net.i2p.util.SimpleByteCache;

/**
 *  Ignore, but save, the SHA-256 checksum in the full 16-byte header when read in.
 *  Use the same checksum when writing out.
 *
 *  This is a savings for NTCP in,
 *  and for NTCP-in to NTCP-out for TunnelDataMessages.
 *  It's also a savings for messages embedded in other messages.
 *  Note that SSU does not use the SHA-256 checksum.
 *
 *  Subclasses must take care to set _hasChecksum to false to invalidate it
 *  if the message payload changes between reading and writing.
 *
 *  It isn't clear where, if anywhere, we actually need to send a checksum.
 *  For point-to-point messages over NTCP where we know the router version
 *  of the peer, we could add a method to skip checksum generation.
 *  For end-to-end I2NP messages embedded in a Garlic, TGM, etc...
 *  we would need a flag day.
 *
 *  @since 0.8.12
 */
public abstract class FastI2NPMessageImpl extends I2NPMessageImpl {
    protected byte _checksum;
    // We skip the fiction that CHECKSUM_LENGTH will ever be anything but 1
    protected boolean _hasChecksum;
    
    public FastI2NPMessageImpl(I2PAppContext context) {
        super(context);
    }
    
    /**
     *  @deprecated unused
     *  @throws UnsupportedOperationException
     */
    @Override
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        throw new UnsupportedOperationException();
    }

    /**
     *  @deprecated unused
     *  @throws UnsupportedOperationException
     */
    @Override
    public int readBytes(InputStream in, int type, byte buffer[]) throws I2NPMessageException, IOException {
        throw new UnsupportedOperationException();
    }

    /**
     *  Ignore, but save, the checksum, to be used later if necessary.
     *
     *  @param maxLen read no more than this many bytes from data starting at offset, even if it is longer
     *                This includes the type byte only if type < 0
     *  @throws IllegalStateException if called twice, to protect saved checksum
     */
    @Override
    public int readBytes(byte data[], int type, int offset, int maxLen) throws I2NPMessageException {
        if (_hasChecksum)
            throw new IllegalStateException(getClass().getSimpleName() + " read twice");
        int headerSize = HEADER_LENGTH;
        if (type >= 0)
            headerSize--;
        if (maxLen < headerSize)
            throw new I2NPMessageException("Payload is too short " + maxLen);
        int cur = offset;
        if (type < 0) {
            type = (int)DataHelper.fromLong(data, cur, 1);
            cur++;
        }
        _uniqueId = DataHelper.fromLong(data, cur, 4);
        cur += 4;
        _expiration = DataHelper.fromLong(data, cur, DataHelper.DATE_LENGTH);
        cur += DataHelper.DATE_LENGTH;
        int size = (int)DataHelper.fromLong(data, cur, 2);
        cur += 2;
        _checksum = data[cur];
        cur++;

        if (cur + size > data.length || headerSize + size > maxLen)
            throw new I2NPMessageException("Payload is too short [" 
                                           + "data.len=" + data.length
                                           + "maxLen=" + maxLen
                                           + " offset=" + offset
                                           + " cur=" + cur 
                                           + " wanted=" + size + "]: " + getClass().getSimpleName());

        int sz = Math.min(size, maxLen - headerSize);
        readMessage(data, cur, sz, type);
        cur += sz;
        _hasChecksum = true;
        if (VERIFY_TEST && _log.shouldLog(Log.INFO))
            _log.info("Ignored c/s " + getClass().getSimpleName());
        return cur - offset;
    }
    
    /**
     *  @deprecated unused
     *  @throws UnsupportedOperationException
     */
    @Override
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        throw new UnsupportedOperationException();
    }
    
    /**
     *  This tests the reuse-checksum feature.
     *  The results are that mostly UnknownI2NPMessages (from inside a TGM),
     *  with a lot of DeliveryStatusMessages,
     *  and a few DatabaseLookupMessages that get reused.
     *  The last two are tiny, but the savings at the gateway should help.
     */
    private static final boolean VERIFY_TEST = false;

    /**
     *  If available, use the previously-computed or previously-read checksum for speed
     */
    @Override
    public int toByteArray(byte buffer[]) {
        if (_hasChecksum)
            return toByteArrayWithSavedChecksum(buffer);
        if (VERIFY_TEST && _log.shouldLog(Log.INFO))
            _log.info("Generating new c/s " + getClass().getSimpleName());
        return super.toByteArray(buffer);
    }
    
    /**
     *  Use a previously-computed checksum for speed
     */
    protected int toByteArrayWithSavedChecksum(byte buffer[]) {
        try {
            int writtenLen = writeMessageBody(buffer, HEADER_LENGTH);
            if (VERIFY_TEST) {
                byte[] h = SimpleByteCache.acquire(32);
                _context.sha().calculateHash(buffer, HEADER_LENGTH, writtenLen - HEADER_LENGTH, h, 0);
                if (h[0] != _checksum) {
                    _log.log(Log.CRIT, "Please report " + getClass().getSimpleName() +
                                       " size " + writtenLen +
                                       " saved c/s " + Integer.toHexString(_checksum & 0xff) +
                                       " calc " + Integer.toHexString(h[0] & 0xff), new Exception());
                    _log.log(Log.CRIT, "DUMP:\n" + HexDump.dump(buffer, HEADER_LENGTH, writtenLen - HEADER_LENGTH));
                    _log.log(Log.CRIT, "RAW:\n" + Base64.encode(buffer, HEADER_LENGTH, writtenLen - HEADER_LENGTH));
                    _checksum = h[0];
                } else if (_log.shouldLog(Log.INFO)) {
                    _log.info("Using saved c/s " + getClass().getSimpleName() + ' ' + _checksum);
                }
                SimpleByteCache.release(h);
            }
            int payloadLen = writtenLen - HEADER_LENGTH;
            int off = 0;
            DataHelper.toLong(buffer, off, 1, getType());
            off += 1;
            DataHelper.toLong(buffer, off, 4, _uniqueId);
            off += 4;
            DataHelper.toLong(buffer, off, DataHelper.DATE_LENGTH, _expiration);
            off += DataHelper.DATE_LENGTH;
            DataHelper.toLong(buffer, off, 2, payloadLen);
            off += 2;
            buffer[off] = _checksum;
            return writtenLen;                     
        } catch (I2NPMessageException ime) {
            _context.logManager().getLog(getClass()).log(Log.CRIT, "Error writing", ime);
            throw new IllegalStateException("Unable to serialize the message " + getClass().getSimpleName(), ime);
        }
    }
}
