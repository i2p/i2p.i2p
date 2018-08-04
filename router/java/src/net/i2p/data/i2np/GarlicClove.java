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
import java.util.Date;

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

    //private final Log _log;
    private static final long serialVersionUID = 1L;
    private transient final I2PAppContext _context;
    private DeliveryInstructions _instructions;
    private I2NPMessage _msg;
    private long _cloveId;
    private Date _expiration;
    private Certificate _certificate;
    
    public GarlicClove(I2PAppContext context) {
        _context = context;
        //_log = context.logManager().getLog(GarlicClove.class);
        _cloveId = -1;
    }
    
    public DeliveryInstructions getInstructions() { return _instructions; }
    public void setInstructions(DeliveryInstructions instr) { _instructions = instr; }
    public I2NPMessage getData() { return _msg; }
    public void setData(I2NPMessage msg) { _msg = msg; }
    public long getCloveId() { return _cloveId; }
    public void setCloveId(long id) { _cloveId = id; }
    public Date getExpiration() { return _expiration; }
    public void setExpiration(Date exp) { _expiration = exp; }
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
     *
     */
    public int readBytes(byte source[], int offset) throws DataFormatException {
        int cur = offset;
        _instructions = DeliveryInstructions.create(source, offset);
        cur += _instructions.getSize();
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Read instructions: " + _instructions);
        try {
            I2NPMessageHandler handler = new I2NPMessageHandler(_context);
            cur += handler.readMessage(source, cur);
            _msg = handler.lastRead();
        } catch (I2NPMessageException ime) {
            throw new DataFormatException("Unable to read the message from a garlic clove", ime);
        }
        _cloveId = DataHelper.fromLong(source, cur, 4);
        cur += 4;
        _expiration = DataHelper.fromDate(source, cur);
        cur += DataHelper.DATE_LENGTH;
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("CloveID read: " + _cloveId + " expiration read: " + _expiration);
        //_certificate = new Certificate();
        //cur += _certificate.readBytes(source, cur);
        _certificate = Certificate.create(source, cur);
        cur += _certificate.size();
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Read cert: " + _certificate);
        return cur - offset;
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
        int offset = 0;
        offset += _instructions.writeBytes(rv, offset);
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Wrote instructions: " + _instructions);
        //offset += _msg.toByteArray(rv);
        try {
            byte m[] = _msg.toByteArray();
            System.arraycopy(m, 0, rv, offset, m.length);
            offset += m.length;
        } catch (RuntimeException e) { throw new RuntimeException("Unable to write: " + _msg + ": " + e.getMessage()); }
        DataHelper.toLong(rv, offset, 4, _cloveId);
        offset += 4;
        DataHelper.toDate(rv, offset, _expiration.getTime());
        offset += DataHelper.DATE_LENGTH;
        offset += _certificate.writeBytes(rv, offset);
        if (offset != rv.length) {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(GarlicClove.class);
            log.error("Clove offset: " + offset + " but estimated length: " + rv.length);
        }
        return rv;
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
               DataHelper.eq(_expiration, clove._expiration) &&
               DataHelper.eq(_instructions,  clove._instructions);
    }
    
    @Override
    public int hashCode() {
        return DataHelper.hashCode(_certificate) ^
               (int) _cloveId ^
               DataHelper.hashCode(_msg) ^
               DataHelper.hashCode(_expiration) ^
               DataHelper.hashCode(_instructions);
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(128);
        buf.append("[GarlicClove: ");
        buf.append("\n\tInstructions: ").append(_instructions);
        buf.append("\n\tCertificate: ").append(_certificate);
        buf.append("\n\tClove ID: ").append(_cloveId);
        buf.append("\n\tExpiration: ").append(_expiration);
        buf.append("\n\tData: ").append(_msg);
        buf.append("]");
        return buf.toString();
    }
}
