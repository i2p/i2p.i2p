package net.i2p.router.message;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Date;

import net.i2p.data.Certificate;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.PrivateKey;
import net.i2p.data.i2np.GarlicClove;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Read a GarlicMessage, decrypt it, and return the resulting CloveSet
 *
 */
public class GarlicMessageParser {
    private Log _log;
    private RouterContext _context;
    
    public GarlicMessageParser(RouterContext context) { 
        _context = context;
        _log = _context.logManager().getLog(GarlicMessageParser.class);
    }
    
    public CloveSet getGarlicCloves(GarlicMessage message, PrivateKey encryptionKey) {
        byte encData[] = message.getData();
        byte decrData[] = null;
        try {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Decrypting with private key " + encryptionKey);
            decrData = _context.elGamalAESEngine().decrypt(encData, encryptionKey);
        } catch (DataFormatException dfe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error decrypting", dfe);
        }
        if (decrData == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Decryption of garlic message failed (data = " + encData + ")", new Exception("Decrypt fail"));
            return null;
        } else {
            try {
                return readCloveSet(decrData); 
            } catch (DataFormatException dfe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Unable to read cloveSet", dfe);
                return null;
            }
        }
    }
    
    private CloveSet readCloveSet(byte data[]) throws DataFormatException {
        int offset = 0;
        
        CloveSet set = new CloveSet();

        int numCloves = (int)DataHelper.fromLong(data, offset, 1);
        offset++;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("# cloves to read: " + numCloves);
        for (int i = 0; i < numCloves; i++) {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Reading clove " + i);
                GarlicClove clove = new GarlicClove(_context);
                offset += clove.readBytes(data, offset);
                set.addClove(clove);
            if (_log.shouldLog(Log.WARN))
                _log.debug("After reading clove " + i);
        }
        Certificate cert = new Certificate();
        offset += cert.readBytes(data, offset);
        long msgId = DataHelper.fromLong(data, offset, 4);
        offset += 4;
        Date expiration = DataHelper.fromDate(data, offset);
        offset += DataHelper.DATE_LENGTH;

        set.setCertificate(cert);
        set.setMessageId(msgId);
        set.setExpiration(expiration.getTime());
        return set;
    }
}
