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

import net.i2p.data.Certificate;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.DataStructureImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Contains one deliverable message encrypted to a router along with instructions
 * and a certificate 'paying for' the delivery.
 *
 * @author jrandom
 */
public class GarlicClove extends DataStructureImpl {
    private Log _log;
    private RouterContext _context;
    private DeliveryInstructions _instructions;
    private I2NPMessage _msg;
    private long _cloveId;
    private Date _expiration;
    private Certificate _certificate;
    private int _replyAction;
    private SourceRouteBlock _sourceRouteBlock;
    private I2NPMessageHandler _handler;
    
    /** No action requested with the source route block */
    public final static int ACTION_NONE = 0;
    /**
     * A DeliveryStatusMessage is requested with the source route block using
     * the cloveId as the id received
     *
     */
    public final static int ACTION_STATUS = 1;
    /**
     * No DeliveryStatusMessage is requested, but the source route block is
     * included for message specific replies
     *
     */
    public final static int ACTION_MESSAGE_SPECIFIC = 2;
    
    public GarlicClove(RouterContext context) {
        _context = context;
        _log = context.logManager().getLog(GarlicClove.class);
        _handler = new I2NPMessageHandler(context);
        setInstructions(null);
        setData(null);
        setCloveId(-1);
        setExpiration(null);
        setCertificate(null);
        setSourceRouteBlockAction(ACTION_NONE);
        setSourceRouteBlock(null);
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
    public int getSourceRouteBlockAction() { return _replyAction; }
    public void setSourceRouteBlockAction(int action) { _replyAction = action; }
    public SourceRouteBlock getSourceRouteBlock() { return _sourceRouteBlock; }
    public void setSourceRouteBlock(SourceRouteBlock block) { _sourceRouteBlock = block; }
    
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        _instructions = new DeliveryInstructions();
        _instructions.readBytes(in);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Read instructions: " + _instructions);
        try {
            _msg = _handler.readMessage(in);
        } catch (I2NPMessageException ime) {
            throw new DataFormatException("Unable to read the message from a garlic clove", ime);
        }
        _cloveId = DataHelper.readLong(in, 4);
        _expiration = DataHelper.readDate(in);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("CloveID read: " + _cloveId + " expiration read: " + _expiration);
        _certificate = new Certificate();
        _certificate.readBytes(in);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Read cert: " + _certificate);
        int replyStyle = (int)DataHelper.readLong(in, 1);
        setSourceRouteBlockAction(replyStyle);
        if (replyStyle != ACTION_NONE) {
            _sourceRouteBlock = new SourceRouteBlock();
            _sourceRouteBlock.readBytes(in);
        }
    }
    
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        StringBuffer error = new StringBuffer();
        if (_instructions == null)
            error.append("No instructions ");
        if (_msg == null)
            error.append("No message ");
        if (_cloveId < 0)
            error.append("CloveID < 0 [").append(_cloveId).append("] ");
        if (_expiration == null)
            error.append("Expiration is null ");
        if (_certificate == null)
            error.append("Certificate is null ");
        if (_replyAction < 0)
            error.append("Reply action is < 0 [").append(_replyAction).append("] ");;
            if (error.length() > 0)
                throw new DataFormatException(error.toString());
            if ( (_replyAction != 0) && (_sourceRouteBlock == null) )
                throw new DataFormatException("Source route block must be specified for non-null action");
            _instructions.writeBytes(out);
            
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Wrote instructions: " + _instructions);
            _msg.writeBytes(out);
            DataHelper.writeLong(out, 4, _cloveId);
            DataHelper.writeDate(out, _expiration);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("CloveID written: " + _cloveId + " expiration written: " 
                           + _expiration);
            _certificate.writeBytes(out);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Written cert: " + _certificate);
            DataHelper.writeLong(out, 1, _replyAction);
            if ( (_replyAction != 0) && (_sourceRouteBlock != null) )
                _sourceRouteBlock.writeBytes(out);
    }
    
    public boolean equals(Object obj) {
        if ( (obj == null) || !(obj instanceof GarlicClove))
            return false;
        GarlicClove clove = (GarlicClove)obj;
        return DataHelper.eq(getCertificate(), clove.getCertificate()) &&
        DataHelper.eq(getCloveId(), clove.getCloveId()) &&
        DataHelper.eq(getData(), clove.getData()) &&
        DataHelper.eq(getExpiration(), clove.getExpiration()) &&
        DataHelper.eq(getInstructions(),  clove.getInstructions()) &&
        DataHelper.eq(getSourceRouteBlock(), clove.getSourceRouteBlock()) &&
        (getSourceRouteBlockAction() == clove.getSourceRouteBlockAction());
    }
    
    public int hashCode() {
        return DataHelper.hashCode(getCertificate()) +
        (int)getCloveId() +
        DataHelper.hashCode(getData()) +
        DataHelper.hashCode(getExpiration()) +
        DataHelper.hashCode(getInstructions()) +
        DataHelper.hashCode(getSourceRouteBlock()) +
        getSourceRouteBlockAction();
    }
    
    public String toString() {
        StringBuffer buf = new StringBuffer(128);
        buf.append("[GarlicClove: ");
        buf.append("\n\tInstructions: ").append(getInstructions());
        buf.append("\n\tCertificate: ").append(getCertificate());
        buf.append("\n\tClove ID: ").append(getCloveId());
        buf.append("\n\tExpiration: ").append(getExpiration());
        buf.append("\n\tSource route style: ").append(getSourceRouteBlockAction());
        buf.append("\n\tSource route block: ").append(getSourceRouteBlock());
        buf.append("\n\tData: ").append(getData());
        buf.append("]");
        return buf.toString();
    }
}
