package net.i2p.router.message;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

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
            _log.debug("Decrypting with private key " + encryptionKey);
            decrData = _context.elGamalAESEngine().decrypt(encData, encryptionKey);
        } catch (DataFormatException dfe) {
            _log.warn("Error decrypting", dfe);
        }
        if (decrData == null) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Decryption of garlic message failed (data = " + encData + ")", new Exception("Decrypt fail"));
            return null;
        } else {
            return readCloveSet(decrData);
        }
    }
    
    private CloveSet readCloveSet(byte data[]) {
        Set cloves = new HashSet();
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        try {
            CloveSet set = new CloveSet();
            
            int numCloves = (int)DataHelper.readLong(bais, 1);
            _log.debug("# cloves to read: " + numCloves);
            for (int i = 0; i < numCloves; i++) {
                _log.debug("Reading clove " + i);
                try {
                    GarlicClove clove = new GarlicClove(_context);
                    clove.readBytes(bais);
                    set.addClove(clove);
                } catch (DataFormatException dfe) {
                    _log.warn("Unable to read clove " + i, dfe);
                } catch (IOException ioe) {
                    _log.warn("Unable to read clove " + i, ioe);
                }
                _log.debug("After reading clove " + i);
            }
            Certificate cert = new Certificate();
            cert.readBytes(bais);
            long msgId = DataHelper.readLong(bais, 4);
            Date expiration = DataHelper.readDate(bais);
            
            set.setCertificate(cert);
            set.setMessageId(msgId);
            set.setExpiration(expiration.getTime());
            
            return set;
        } catch (IOException ioe) {
            _log.error("Error reading clove set", ioe);
            return null;
        } catch (DataFormatException dfe) {
            _log.error("Error reading clove set", dfe);
            return null;
        }
    }
}
