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

import net.i2p.crypto.EncType;
import net.i2p.crypto.SessionKeyManager;
import net.i2p.data.Certificate;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.PrivateKey;
import net.i2p.data.i2np.GarlicClove;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.crypto.ratchet.MuxedSKM;
import net.i2p.router.crypto.ratchet.RatchetSKM;
import net.i2p.util.Log;

/**
 *  Read a GarlicMessage, decrypt it, and return the resulting CloveSet.
 *  Thread-safe, does not contain any state.
 *  Public as it's now in the RouterContext.
 */
public class GarlicMessageParser {
    private final Log _log;
    private final RouterContext _context;

    /**
     *  Huge limit just to reduce chance of trouble. Typ. usage is 3.
     *  As of 0.9.12. Was 255.
     */
    private static final int MAX_CLOVES = 32;

    public GarlicMessageParser(RouterContext context) { 
        _context = context;
        _log = _context.logManager().getLog(GarlicMessageParser.class);
    }

    /**
     *  Supports both ELGAMAL_2048 and ECIES_X25519.
     *
     *  @param encryptionKey either type
     *  @param skm use tags from this session key manager
     *  @return null on error
     */
    CloveSet getGarlicCloves(GarlicMessage message, PrivateKey encryptionKey, SessionKeyManager skm) {
        byte encData[] = message.getData();
        byte decrData[];
        try {
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Decrypting with private key " + encryptionKey);
            EncType type = encryptionKey.getType();
            if (type == EncType.ELGAMAL_2048) {
                decrData = _context.elGamalAESEngine().decrypt(encData, encryptionKey, skm);
            } else if (type == EncType.ECIES_X25519) {
                RatchetSKM rskm;
                if (skm instanceof RatchetSKM) {
                    rskm = (RatchetSKM) skm;
                } else if (skm instanceof MuxedSKM) {
                    rskm = ((MuxedSKM) skm).getECSKM();
                } else {
                    if (_log.shouldWarn())
                        _log.warn("No SKM to decrypt ECIES");
                    return null;
                }
                CloveSet rv = _context.eciesEngine().decrypt(encData, encryptionKey, rskm);
                if (rv != null) {
                    if (_log.shouldDebug())
                        _log.debug("ECIES decrypt success, cloves: " + rv.getCloveCount());
                    return rv;
                } else {
                    if (_log.shouldWarn())
                        _log.warn("ECIES decrypt fail");
                    return null;
                }
            } else {
                if (_log.shouldWarn())
                    _log.warn("Can't decrypt with key type " + type);
                return null;
            }
        } catch (DataFormatException dfe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Error decrypting", dfe);
            return null;
        }
        if (decrData == null) {
            // This is the usual error path and it's logged at WARN level in GarlicMessageReceiver
            if (_log.shouldLog(Log.INFO))
                _log.info("Decryption of garlic message failed", new Exception("Decrypt fail"));
            return null;
        } else {
            try {
                CloveSet rv = readCloveSet(decrData, 0); 
                if (_log.shouldDebug())
                    _log.debug("Got cloves: " + rv.getCloveCount());
                return rv; 
            } catch (DataFormatException dfe) {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("Unable to read cloveSet", dfe);
                return null;
            }
        }
    }

    /**
     *  Supports both ELGAMAL_2048 and ECIES_X25519.
     *
     *  @param elgKey must be ElG, non-null
     *  @param ecKey must be EC, non-null
     *  @param skm use tags from this session key manager
     *  @return null on error
     *  @since 0.9.44
     */
    CloveSet getGarlicCloves(GarlicMessage message, PrivateKey elgKey, PrivateKey ecKey, SessionKeyManager skm) {
        byte encData[] = message.getData();
        CloveSet rv;
        try {
            if (skm instanceof MuxedSKM) {
                MuxedSKM mskm = (MuxedSKM) skm;
                rv = _context.eciesEngine().decrypt(encData, elgKey, ecKey, mskm);
            } else if (skm instanceof RatchetSKM) {
                // unlikely, if we have two keys we should have a MuxedSKM
                RatchetSKM rskm = (RatchetSKM) skm;
                rv = _context.eciesEngine().decrypt(encData, ecKey, rskm);
            } else {
                // unlikely, if we have two keys we should have a MuxedSKM
                byte[] decrData = _context.elGamalAESEngine().decrypt(encData, elgKey, skm);
                if (decrData != null) {
                    rv = readCloveSet(decrData, 0); 
                } else {
                    rv = null; 
                }
            }
        } catch (DataFormatException dfe) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Muxed decrypt fail", dfe);
            rv = null;
        }
        if (rv == null &&_log.shouldWarn())
            _log.warn("Muxed decrypt fail");
        return rv;
    }

    /**
     *  ElGamal only
     *
     *  @param offset where in data to start
     *  @return non-null, throws on all errors
     *  @since public since 0.9.44
     */
    public CloveSet readCloveSet(byte data[], int offset) throws DataFormatException {
        int numCloves = data[offset] & 0xff;
        offset++;
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("# cloves to read: " + numCloves);
        if (numCloves <= 0 || numCloves > MAX_CLOVES)
            throw new DataFormatException("bad clove count " + numCloves);
        GarlicClove[] cloves = new GarlicClove[numCloves];
        for (int i = 0; i < numCloves; i++) {
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Reading clove " + i);
                GarlicClove clove = new GarlicClove(_context);
                offset += clove.readBytes(data, offset);
                cloves[i] = clove;
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("After reading clove " + i);
        }
        //Certificate cert = new Certificate();
        //offset += cert.readBytes(data, offset);
        Certificate cert = Certificate.create(data, offset);
        offset += cert.size();
        long msgId = DataHelper.fromLong(data, offset, 4);
        offset += 4;
        //Date expiration = DataHelper.fromDate(data, offset);
        long expiration = DataHelper.fromLong(data, offset, 8);

        CloveSet set = new CloveSet(cloves, cert, msgId, expiration);
        return set;
    }
}
