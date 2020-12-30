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
import net.i2p.data.Certificate;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.DataStructureImpl;
import net.i2p.util.Log;

/**
 * Contains one deliverable message encrypted to a router along with instructions
 * and a certificate 'paying for' the delivery.
 *
 * Note that certificates are always the null certificate at this time, others are unimplemented.
 *
 * @author jrandom
 */
public class GarlicClove extends DataStructureImpl {

    private static final long serialVersionUID = 1L;
    private transient final I2PAppContext _context;
    private DeliveryInstructions _instructions;
    private I2NPMessage _msg;
    private long _cloveId;
    private long _expiration;
    private Certificate _certificate;
    
    public GarlicClove(I2PAppContext context) {
        _context = context;
        _cloveId = -1;
    }
    
    public DeliveryInstructions getInstructions() { return _instructions; }
    public void setInstructions(DeliveryInstructions instr) { _instructions = instr; }
    public I2NPMessage getData() { return _msg; }
    public void setData(I2NPMessage msg) { _msg = msg; }
    public long getCloveId() { return _cloveId; }
    public void setCloveId(long id) { _cloveId = id; }
    public long getExpiration() { return _expiration; }
    public void setExpiration(long exp) { _expiration = exp; }
    public Certificate getCertificate() { return _certificate; }
    public void setCertificate(Certificate cert) { _certificate = cert; }
    
    /**
     *  @deprecated unused, use byte array method to avoid copying
     *  @throws UnsupportedOperationException always
     */
    @Deprecated
    public void readBytes(InputStream in) {
        throw new UnsupportedOperationException();
    }

    /**
     *  @return length read
     */
    public int readBytes(byte source[], int offset) throws DataFormatException {
        int cur = offset;
        _instructions = DeliveryInstructions.create(source, offset);
        cur += _instructions.getSize();
        try {
            I2NPMessageHandler handler = new I2NPMessageHandler(_context);
            cur += handler.readMessage(source, cur);
            _msg = handler.lastRead();
        } catch (I2NPMessageException ime) {
            throw new DataFormatException("Unable to read the message from a garlic clove", ime);
        }
        _cloveId = DataHelper.fromLong(source, cur, 4);
        cur += 4;
        _expiration = DataHelper.fromLong(source, cur, 8);
        cur += DataHelper.DATE_LENGTH;
        _certificate = Certificate.create(source, cur);
        cur += _certificate.size();
        return cur - offset;
    }

    /**
     *  Short format for ECIES-Ratchet, saves 22 bytes.
     *  NTCP2-style header, no ID, no separate expiration, no cert.
     *
     *  @since 0.9.44
     */
    public void readBytesRatchet(byte source[], int offset, int len) throws DataFormatException {
        _instructions = DeliveryInstructions.create(source, offset);
        int isz = _instructions.getSize();
        try {
            I2NPMessageHandler handler = new I2NPMessageHandler(_context);
            _msg = I2NPMessageImpl.fromRawByteArrayNTCP2(_context, source, offset + isz, len - isz, handler);
            _cloveId = _msg.getUniqueId();
            _expiration = _msg.getMessageExpiration();
            _certificate = Certificate.NULL_CERT;
        } catch (I2NPMessageException ime) {
            throw new DataFormatException("Unable to read the message from a garlic clove", ime);
        }
    }

    /**
     *  @deprecated unused, use byte array method to avoid copying
     *  @throws UnsupportedOperationException always
     */
    @Deprecated
    public void writeBytes(OutputStream out) {
        throw new UnsupportedOperationException();
    }

    /**
     *
     */
    @Override
    public byte[] toByteArray() {
        byte rv[] = new byte[estimateSize()];
        int offset = _instructions.writeBytes(rv, 0);
        offset = _msg.toByteArray(rv, offset);
        DataHelper.toLong(rv, offset, 4, _cloveId);
        offset += 4;
        DataHelper.toLong(rv, offset, 8, _expiration);
        offset += DataHelper.DATE_LENGTH;
        offset += _certificate.writeBytes(rv, offset);
        if (offset != rv.length) {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(GarlicClove.class);
            log.error("Clove offset: " + offset + " but estimated length: " + rv.length);
        }
        return rv;
    }

    /**
     *  Short format for ECIES-Ratchet, saves 22 bytes.
     *  NTCP2-style header, no ID, no separate expiration, no cert.
     *
     *  @return new offset
     *  @since 0.9.44
     */
    public int writeBytesRatchet(byte[] tgt, int offset) {
        // returns length written
        offset += _instructions.writeBytes(tgt, offset);
        // returns new offset
        offset = _msg.toRawByteArrayNTCP2(tgt, offset);
        return offset;
    }

    /**
     *  @since 0.9.44
     */
    public int getSizeRatchet() {
        return _instructions.getSize() + _msg.getMessageSize() - 7;
    }
    
    public int estimateSize() {
        return _instructions.getSize()
               + _msg.getMessageSize() 
               + 4 // cloveId
               + DataHelper.DATE_LENGTH
               + _certificate.size(); // certificate
    }
    
    @Override
    public boolean equals(Object obj) {
        if ( (obj == null) || !(obj instanceof GarlicClove))
            return false;
        GarlicClove clove = (GarlicClove)obj;
        return DataHelper.eq(_certificate, clove._certificate) &&
               _cloveId == clove._cloveId &&
               DataHelper.eq(_msg, clove._msg) &&
               _expiration == clove._expiration &&
               DataHelper.eq(_instructions,  clove._instructions);
    }
    
    @Override
    public int hashCode() {
        return DataHelper.hashCode(_certificate) ^
               (int) _cloveId ^
               DataHelper.hashCode(_msg) ^
               (int) _expiration ^
               DataHelper.hashCode(_instructions);
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(128);
        buf.append("[GarlicClove: ");
        buf.append("\n\tInstructions: ").append(_instructions);
        buf.append("\n\tCertificate: ").append(_certificate);
        buf.append("\n\tClove ID: ").append(_cloveId);
        buf.append("\n\tExpiration: ").append(DataHelper.formatTime(_expiration));
        buf.append("\n\tData: ").append(_msg);
        buf.append("]");
        return buf.toString();
    }
}
