package net.i2p.crypto;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.util.Log;
import net.i2p.util.SimpleByteCache;

/**
 * Handles the actual ElGamal+AES encryption and decryption scenarios using the
 * supplied keys and data.
 *
 * No, this does not extend AESEngine or CryptixAESEngine.
 */
public class ElGamalAESEngine {
    private final Log _log;
    private final static int MIN_ENCRYPTED_SIZE = 80; // smallest possible resulting size
    private final I2PAppContext _context;
    /** enforced since release 0.6 */
    public static final int MAX_TAGS_RECEIVED = 200;

    public ElGamalAESEngine(I2PAppContext ctx) {
        _context = ctx;
        _log = _context.logManager().getLog(ElGamalAESEngine.class);
        
        _context.statManager().createFrequencyStat("crypto.elGamalAES.encryptNewSession",
                                                   "how frequently we encrypt to a new ElGamal/AES+SessionTag session?",
                                                   "Encryption", new long[] { 60*60*1000l});
        _context.statManager().createFrequencyStat("crypto.elGamalAES.encryptExistingSession",
                                                   "how frequently we encrypt to an existing ElGamal/AES+SessionTag session?",
                                                   "Encryption", new long[] { 60*60*1000l});
        _context.statManager().createFrequencyStat("crypto.elGamalAES.decryptNewSession",
                                                   "how frequently we decrypt with a new ElGamal/AES+SessionTag session?",
                                                   "Encryption", new long[] { 60*60*1000l});
        _context.statManager().createFrequencyStat("crypto.elGamalAES.decryptExistingSession",
                                                   "how frequently we decrypt with an existing ElGamal/AES+SessionTag session?",
                                                   "Encryption", new long[] { 60*60*1000l});
        _context.statManager().createFrequencyStat("crypto.elGamalAES.decryptFailed",
                                                   "how frequently we fail to decrypt with ElGamal/AES+SessionTag?",
                                                   "Encryption", new long[] { 60*60*1000l});
    }

    /**
     * Decrypt the message using the given private key using tags from the default key manager,
     * which is the router's key manager. Use extreme care if you aren't the router.
     *
     * @deprecated specify the key manager!
     */
    public byte[] decrypt(byte data[], PrivateKey targetPrivateKey) throws DataFormatException {
        return decrypt(data, targetPrivateKey, _context.sessionKeyManager());
    }

    /**
     * Decrypt the message using the given private key
     * and using tags from the specified key manager.
     * This works according to the
     * ElGamal+AES algorithm in the data structure spec.
     *
     * Warning - use the correct SessionKeyManager. Clients should instantiate their own.
     * Clients using I2PAppContext.sessionKeyManager() may be correlated with the router,
     * unless you are careful to use different keys.
     *
     * @return decrypted data or null on failure
     */
    public byte[] decrypt(byte data[], PrivateKey targetPrivateKey, SessionKeyManager keyManager) throws DataFormatException {
        if (data == null) {
            if (_log.shouldLog(Log.ERROR)) _log.error("Null data being decrypted?");
            return null;
        } else if (data.length < MIN_ENCRYPTED_SIZE) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Data is less than the minimum size (" + data.length + " < " + MIN_ENCRYPTED_SIZE + ")");
            return null;
        }

        byte tag[] = new byte[32];
        System.arraycopy(data, 0, tag, 0, 32);
        SessionTag st = new SessionTag(tag);
        SessionKey key = keyManager.consumeTag(st);
        SessionKey foundKey = new SessionKey();
        SessionKey usedKey = new SessionKey();
        Set foundTags = new HashSet();
        byte decrypted[] = null;
        boolean wasExisting = false;
        if (key != null) {
            //if (_log.shouldLog(Log.DEBUG)) _log.debug("Key is known for tag " + st);
            long id = _context.random().nextLong();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug(id + ": Decrypting existing session encrypted with tag: " + st.toString() + ": key: " + key.toBase64() + ": " + data.length + " bytes " /* + Base64.encode(data, 0, 64) */ );
            
            decrypted = decryptExistingSession(data, key, targetPrivateKey, foundTags, usedKey, foundKey);
            if (decrypted != null) {
                _context.statManager().updateFrequency("crypto.elGamalAES.decryptExistingSession");
                if ( (!foundTags.isEmpty()) && (_log.shouldLog(Log.DEBUG)) )
                    _log.debug(id + ": ElG/AES decrypt success with " + st + ": found tags: " + foundTags);
                wasExisting = true;
            } else {
                _context.statManager().updateFrequency("crypto.elGamalAES.decryptFailed");
                if (_log.shouldLog(Log.WARN)) {
                    _log.warn(id + ": ElG decrypt fail: known tag [" + st + "], failed decrypt");
                }
            }
        } else {
            if (_log.shouldLog(Log.DEBUG)) _log.debug("Key is NOT known for tag " + st);
            decrypted = decryptNewSession(data, targetPrivateKey, foundTags, usedKey, foundKey);
            if (decrypted != null) {
                _context.statManager().updateFrequency("crypto.elGamalAES.decryptNewSession");
                if ( (!foundTags.isEmpty()) && (_log.shouldLog(Log.DEBUG)) )
                    _log.debug("ElG decrypt success: found tags: " + foundTags);
            } else {
                _context.statManager().updateFrequency("crypto.elGamalAES.decryptFailed");
                if (_log.shouldLog(Log.WARN))
                    _log.warn("ElG decrypt fail: unknown tag: " + st);
            }
        }

        if ((key == null) && (decrypted == null)) {
            //_log.debug("Unable to decrypt the data starting with tag [" + st + "] - did the tag expire recently?", new Exception("Decrypt failure"));
        }

        if (!foundTags.isEmpty()) {
            if (foundKey.getData() != null) {
                if (_log.shouldLog(Log.DEBUG)) 
                    _log.debug("Found key: " + foundKey.toBase64() + " tags: " + foundTags + " wasExisting? " + wasExisting);
                keyManager.tagsReceived(foundKey, foundTags);
            } else if (usedKey.getData() != null) {
                if (_log.shouldLog(Log.DEBUG)) 
                    _log.debug("Used key: " + usedKey.toBase64() + " tags: " + foundTags + " wasExisting? " + wasExisting);
                keyManager.tagsReceived(usedKey, foundTags);
            }
        }
        return decrypted;
    }

    /**
     * scenario 1: 
     * Begin with 222 bytes, ElG encrypted, containing:
     * <pre>
     *  - 32 byte SessionKey
     *  - 32 byte pre-IV for the AES
     *  - 158 bytes of random padding
     * </pre>
     * After encryption, the ElG section is 514 bytes long.
     * Then encrypt with AES using that session key and the first 16 bytes of the SHA256 of the pre-IV, using
     * the decryptAESBlock method & structure.
     *
     * @param foundTags set which is filled with any sessionTags found during decryption
     * @param foundKey  out parameter. Data must be unset when called; may be filled with a new sessionKey found during decryption
     * @param usedKey out parameter. Data must be unset when called; usedKey.setData() will be called by this method on success.
     *
     * @return null if decryption fails
     */
    private byte[] decryptNewSession(byte data[], PrivateKey targetPrivateKey, Set foundTags, SessionKey usedKey,
                                    SessionKey foundKey) throws DataFormatException {
        if (data == null) {
            //if (_log.shouldLog(Log.WARN)) _log.warn("Data is null, unable to decrypt new session");
            return null;
        } else if (data.length < 514) {
            //if (_log.shouldLog(Log.WARN)) _log.warn("Data length is too small (" + data.length + ")");
            return null;
        }
        byte elgEncr[] = new byte[514];
        if (data.length > 514) {
            System.arraycopy(data, 0, elgEncr, 0, 514);
        } else {
            System.arraycopy(data, 0, elgEncr, 514 - data.length, data.length);
        }
        byte elgDecr[] = _context.elGamalEngine().decrypt(elgEncr, targetPrivateKey);
        if (elgDecr == null) {
            //if (_log.shouldLog(Log.WARN))
             //   _log.warn("decrypt returned null", new Exception("decrypt failed"));
            return null;
        }

        int offset = 0;
        byte key[] = new byte[SessionKey.KEYSIZE_BYTES];
        System.arraycopy(elgDecr, offset, key, 0, SessionKey.KEYSIZE_BYTES);
        offset += SessionKey.KEYSIZE_BYTES;
        usedKey.setData(key);
        byte[] preIV = SimpleByteCache.acquire(32);
        System.arraycopy(elgDecr, offset, preIV, 0, 32);
        offset += 32;

        //_log.debug("Pre IV for decryptNewSession: " + DataHelper.toString(preIV, 32));
        //_log.debug("SessionKey for decryptNewSession: " + DataHelper.toString(key.getData(), 32));

        // use alternate calculateHash() method to avoid object churn and caching
        //Hash ivHash = _context.sha().calculateHash(preIV);
        //byte iv[] = new byte[16];
        //System.arraycopy(ivHash.getData(), 0, iv, 0, 16);
        byte[] iv = halfHash(preIV);
        SimpleByteCache.release(preIV);

        // feed the extra bytes into the PRNG
        _context.random().harvester().feedEntropy("ElG/AES", elgDecr, offset, elgDecr.length - offset); 

        byte aesDecr[] = decryptAESBlock(data, 514, data.length-514, usedKey, iv, null, foundTags, foundKey);
        SimpleByteCache.release(iv);

        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Decrypt with a NEW session successfull: # tags read = " + foundTags.size(),
        //               new Exception("Decrypted by"));
        return aesDecr;
    }

    /**
     * scenario 2: 
     * The data begins with 32 byte session tag, which also serves as the preIV.
     * Then decrypt with AES using that session key and the first 16 bytes of the SHA256 of the pre-IV:
     * <pre>
     *  - 2 byte integer specifying the # of session tags
     *  - that many 32 byte session tags
     *  - 4 byte integer specifying data.length
     *  - SHA256 of data
     *  - 1 byte flag that, if == 1, is followed by a new SessionKey
     *  - data
     *  - random bytes, padding the total size to greater than paddedSize with a mod 16 = 0
     * </pre>
     *
     * If anything doesn't match up in decryption, it falls back to decryptNewSession
     *
     * @param foundTags set which is filled with any sessionTags found during decryption
     * @param foundKey  out parameter. Data must be unset when called; may be filled with a new sessionKey found during decryption
     * @param usedKey out parameter. Data must be unset when called; usedKey.setData() will be called by this method on success.
     *
     * @return decrypted data or null on failure
     *
     */
    private byte[] decryptExistingSession(byte data[], SessionKey key, PrivateKey targetPrivateKey, Set foundTags,
                                         SessionKey usedKey, SessionKey foundKey) throws DataFormatException {
        byte preIV[] = SimpleByteCache.acquire(32);
        System.arraycopy(data, 0, preIV, 0, 32);
        // use alternate calculateHash() method to avoid object churn and caching
        //Hash ivHash = _context.sha().calculateHash(preIV);
        //byte iv[] = new byte[16];
        //System.arraycopy(ivHash.getData(), 0, iv, 0, 16);
        byte[] iv = halfHash(preIV);
        SimpleByteCache.release(preIV);

        //_log.debug("Pre IV for decryptExistingSession: " + DataHelper.toString(preIV, 32));
        //_log.debug("SessionKey for decryptNewSession: " + DataHelper.toString(key.getData(), 32));
        byte decrypted[] = decryptAESBlock(data, 32, data.length-32, key, iv, preIV, foundTags, foundKey);
        SimpleByteCache.release(iv);
        if (decrypted == null) {
            // it begins with a valid session tag, but thats just a coincidence.
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("Decrypt with a non session tag, but tags read: " + foundTags.size());
            if (_log.shouldLog(Log.WARN))
                _log.warn("Decrypting looks negative... existing key fails with existing tag, lets try as a new one");
            byte rv[] = decryptNewSession(data, targetPrivateKey, foundTags, usedKey, foundKey);
            if (_log.shouldLog(Log.WARN)) {
                if (rv == null)
                    _log.warn("Decrypting failed with a known existing tag as either an existing message or a new session");
                else
                    _log.warn("Decrypting suceeded as a new session, even though it used an existing tag!");
            }
            return rv;
        }
        // existing session decrypted successfully!
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("Decrypt with an EXISTING session tag successfull, # tags read: " + foundTags.size(),
        //               new Exception("Decrypted by"));
        usedKey.setData(key.getData());
        return decrypted;
    }

    /**
     * Decrypt the AES data with the session key and IV.  The result should be:
     * <pre>
     *  - 2 byte integer specifying the # of session tags
     *  - that many 32 byte session tags
     *  - 4 byte integer specifying data.length
     *  - SHA256 of data
     *  - 1 byte flag that, if == 1, is followed by a new SessionKey
     *  - data
     *  - random bytes, padding the total size to greater than paddedSize with a mod 16 = 0
     * </pre>
     *
     * If anything doesn't match up in decryption, return null.  Otherwise, return
     * the decrypted data and update the session as necessary.  If the sentTag is not null,
     * consume it, but if it is null, record the keys, etc as part of a new session.
     *
     * @param foundTags set which is filled with any sessionTags found during decryption
     * @param foundKey  out parameter. Data must be unset when called; may be filled with a new sessionKey found during decryption
     * @return decrypted data or null on failure
     */
/****
    private byte[] decryptAESBlock(byte encrypted[], SessionKey key, byte iv[], 
                           byte sentTag[], Set foundTags, SessionKey foundKey) throws DataFormatException {
        return decryptAESBlock(encrypted, 0, encrypted.length, key, iv, sentTag, foundTags, foundKey);
    }
****/

    /*
     * Note: package private for ElGamalTest.testAES()
     */
    byte[] decryptAESBlock(byte encrypted[], int offset, int encryptedLen, SessionKey key, byte iv[], 
                           byte sentTag[], Set foundTags, SessionKey foundKey) throws DataFormatException {
        //_log.debug("iv for decryption: " + DataHelper.toString(iv, 16));	
        //_log.debug("decrypting AES block.  encr.length = " + (encrypted == null? -1 : encrypted.length) + " sentTag: " + DataHelper.toString(sentTag, 32));
        byte decrypted[] = new byte[encryptedLen];
        _context.aes().decrypt(encrypted, offset, decrypted, 0, key, iv, encryptedLen);
        //Hash h = _context.sha().calculateHash(decrypted);
        //_log.debug("Hash of entire aes block after decryption: \n" + DataHelper.toString(h.getData(), 32));
        try {
            SessionKey newKey = null;
            List tags = null;

            //ByteArrayInputStream bais = new ByteArrayInputStream(decrypted);
            int cur = 0;
            long numTags = DataHelper.fromLong(decrypted, cur, 2);
            if ((numTags < 0) || (numTags > MAX_TAGS_RECEIVED)) throw new Exception("Invalid number of session tags");
            if (numTags > 0) tags = new ArrayList((int)numTags);
            cur += 2;
            //_log.debug("# tags: " + numTags);
            if (numTags * SessionTag.BYTE_LENGTH > decrypted.length - 2) {
                throw new Exception("# tags: " + numTags + " is too many for " + (decrypted.length - 2));
            }
            for (int i = 0; i < numTags; i++) {
                byte tag[] = new byte[SessionTag.BYTE_LENGTH];
                System.arraycopy(decrypted, cur, tag, 0, SessionTag.BYTE_LENGTH); 
                cur += SessionTag.BYTE_LENGTH;
                tags.add(new SessionTag(tag));
            }
            long len = DataHelper.fromLong(decrypted, cur, 4);
            cur += 4;
            //_log.debug("len: " + len);
            if ((len < 0) || (len > decrypted.length - cur - Hash.HASH_LENGTH - 1)) 
                throw new Exception("Invalid size of payload (" + len + ", remaining " + (decrypted.length-cur) +")");
            //byte hashval[] = new byte[Hash.HASH_LENGTH];
            //System.arraycopy(decrypted, cur, hashval, 0, Hash.HASH_LENGTH);
            //readHash = new Hash();
            //readHash.setData(hashval);
            //readHash = Hash.create(decrypted, cur);
            int hashIndex = cur;
            cur += Hash.HASH_LENGTH;
            byte flag = decrypted[cur++];
            if (flag == 0x01) {
                byte rekeyVal[] = new byte[SessionKey.KEYSIZE_BYTES];
                System.arraycopy(decrypted, cur, rekeyVal, 0, SessionKey.KEYSIZE_BYTES);
                cur += SessionKey.KEYSIZE_BYTES;
                newKey = new SessionKey();
                newKey.setData(rekeyVal);
            }
            byte unencrData[] = new byte[(int) len];
            System.arraycopy(decrypted, cur, unencrData, 0, (int)len);
            cur += (int) len;
            // use alternate calculateHash() method to avoid object churn and caching
            //Hash calcHash = _context.sha().calculateHash(unencrData);
            //boolean eq = calcHash.equals(readHash);
            byte[] calcHash = SimpleByteCache.acquire(32);
            _context.sha().calculateHash(unencrData, 0, (int) len, calcHash, 0);
            boolean eq = DataHelper.eq(decrypted, hashIndex, calcHash, 0, 32);
            SimpleByteCache.release(calcHash);
            
            if (eq) {
                // everything matches.  w00t.
                if (tags != null)
                    foundTags.addAll(tags);
                if (newKey != null) foundKey.setData(newKey.getData());
                return unencrData;
            }

            throw new Exception("Hash does not match");
        } catch (Exception e) {
            if (_log.shouldLog(Log.WARN)) _log.warn("Unable to decrypt AES block", e);
            return null;
        }
    }

    /**
     * Encrypt the unencrypted data to the target.  The total size returned will be 
     * no less than the paddedSize parameter, but may be more.  This method uses the 
     * ElGamal+AES algorithm in the data structure spec.
     *
     * @param target public key to which the data should be encrypted. 
     * @param key session key to use during encryption
     * @param tagsForDelivery session tags to be associated with the key (or newKey if specified), or null;
     *                        200 max enforced at receiver
     * @param currentTag sessionTag to use, or null if it should use ElG (i.e. new session)
     * @param newKey key to be delivered to the target, with which the tagsForDelivery should be associated, or null
     * @param paddedSize minimum size in bytes of the body after padding it (if less than the
     *          body's real size, no bytes are appended but the body is not truncated)
     *
     * Unused externally, only called by below (i.e. newKey is always null)
     */
    public byte[] encrypt(byte data[], PublicKey target, SessionKey key, Set tagsForDelivery,
                                 SessionTag currentTag, SessionKey newKey, long paddedSize) {
        if (currentTag == null) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Current tag is null, encrypting as new session");
            _context.statManager().updateFrequency("crypto.elGamalAES.encryptNewSession");
            return encryptNewSession(data, target, key, tagsForDelivery, newKey, paddedSize);
        }
        //if (_log.shouldLog(Log.INFO))
        //    _log.info("Current tag is NOT null, encrypting as existing session");
        _context.statManager().updateFrequency("crypto.elGamalAES.encryptExistingSession");
        byte rv[] = encryptExistingSession(data, target, key, tagsForDelivery, currentTag, newKey, paddedSize);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Existing session encrypted with tag: " + currentTag.toString() + ": " + rv.length + " bytes and key: " + key.toBase64() /* + ": " + Base64.encode(rv, 0, 64) */);
        return rv;
    }

    /**
     * Encrypt the data to the target using the given key and deliver the specified tags
     * No new session key
     * This is the one called from GarlicMessageBuilder and is the primary entry point.
     *
     * Re: padded size: The AES block adds at least 39 bytes of overhead to the data, and
     * that is included in the minimum size calculation.
     *
     * In the router, we always use garlic messages. A garlic message with a single
     * clove and zero data is about 84 bytes, so that's 123 bytes minimum. So any paddingSize
     * <= 128 is a no-op as every message will be at least 128 bytes
     * (Streaming, if used, adds more overhead).
     *
     * Outside the router, with a client using its own message format, the minimum size
     * is 48, so any paddingSize <= 48 is a no-op.
     *
     * Not included in the minimum is a 32-byte session tag for an existing session,
     * or a 514-byte ElGamal block and several 32-byte session tags for a new session.
     * So the returned encrypted data will be at least 32 bytes larger than paddedSize.
     *
     * @param target public key to which the data should be encrypted. 
     * @param key session key to use during encryption
     * @param tagsForDelivery session tags to be associated with the key or null;
     *                        200 max enforced at receiver
     * @param currentTag sessionTag to use, or null if it should use ElG (i.e. new session)
     * @param paddedSize minimum size in bytes of the body after padding it (if less than the
     *          body's real size, no bytes are appended but the body is not truncated)
     *
     */
    public byte[] encrypt(byte data[], PublicKey target, SessionKey key, Set tagsForDelivery,
                                 SessionTag currentTag, long paddedSize) {
        return encrypt(data, target, key, tagsForDelivery, currentTag, null, paddedSize);
    }

    /**
     * Encrypt the data to the target using the given key and deliver the specified tags
     * No new session key
     * No current tag (encrypt as new session)
     *
     * @deprecated unused
     */
    public byte[] encrypt(byte data[], PublicKey target, SessionKey key, Set tagsForDelivery, long paddedSize) {
        return encrypt(data, target, key, tagsForDelivery, null, null, paddedSize);
    }

    /**
     * Encrypt the data to the target using the given key delivering no tags
     * No new session key
     * No current tag (encrypt as new session)
     *
     * @deprecated unused
     */
    public byte[] encrypt(byte data[], PublicKey target, SessionKey key, long paddedSize) {
        return encrypt(data, target, key, null, null, null, paddedSize);
    }

    /**
     * scenario 1: 
     * Begin with 222 bytes, ElG encrypted, containing:
     * <pre>
     *  - 32 byte SessionKey
     *  - 32 byte pre-IV for the AES
     *  - 158 bytes of random padding
     * </pre>
     * After encryption, the ElG section is 514 bytes long.
     * Then encrypt the following with AES using that session key and the first 16 bytes of the SHA256 of the pre-IV:
     * <pre>
     *  - 2 byte integer specifying the # of session tags
     *  - that many 32 byte session tags
     *  - 4 byte integer specifying data.length
     *  - SHA256 of data
     *  - 1 byte flag that, if == 1, is followed by a new SessionKey
     *  - data
     *  - random bytes, padding the total size to greater than paddedSize with a mod 16 = 0
     * </pre>
     *
     */
    private byte[] encryptNewSession(byte data[], PublicKey target, SessionKey key, Set tagsForDelivery,
                                    SessionKey newKey, long paddedSize) {
        //_log.debug("Encrypting to a NEW session");
        byte elgSrcData[] = new byte[SessionKey.KEYSIZE_BYTES+32+158];
        System.arraycopy(key.getData(), 0, elgSrcData, 0, SessionKey.KEYSIZE_BYTES);
        // get both the preIV and the padding at once, then copy to the preIV array
        _context.random().nextBytes(elgSrcData, SessionKey.KEYSIZE_BYTES, 32 + 158);
        byte preIV[] = SimpleByteCache.acquire(32);
        System.arraycopy(elgSrcData, SessionKey.KEYSIZE_BYTES, preIV, 0, 32);

        //_log.debug("Pre IV for encryptNewSession: " + DataHelper.toString(preIV, 32));
        //_log.debug("SessionKey for encryptNewSession: " + DataHelper.toString(key.getData(), 32));
        long before = _context.clock().now();
        byte elgEncr[] = _context.elGamalEngine().encrypt(elgSrcData, target);
        if (_log.shouldLog(Log.INFO)) {
            long after = _context.clock().now();
            _log.info("elgEngine.encrypt of the session key took " + (after - before) + "ms");
        }
        if (elgEncr.length < 514) {
            // ??? ElGamalEngine.encrypt() always returns 514 bytes
            byte elg[] = new byte[514];
            int diff = elg.length - elgEncr.length;
            //if (_log.shouldLog(Log.DEBUG)) _log.debug("Difference in size: " + diff);
            System.arraycopy(elgEncr, 0, elg, diff, elgEncr.length);
            elgEncr = elg;
        }
        //_log.debug("ElGamal encrypted length: " + elgEncr.length + " elGamal source length: " + elgSrc.toByteArray().length);
        
        // should we also feed the encrypted elG block into the harvester?

        // use alternate calculateHash() method to avoid object churn and caching
        //Hash ivHash = _context.sha().calculateHash(preIV);
        //byte iv[] = new byte[16];
        //System.arraycopy(ivHash.getData(), 0, iv, 0, 16);
        byte[] iv = halfHash(preIV);
        SimpleByteCache.release(preIV);

        byte aesEncr[] = encryptAESBlock(data, key, iv, tagsForDelivery, newKey, paddedSize);
        SimpleByteCache.release(iv);
        //_log.debug("AES encrypted length: " + aesEncr.length);

        byte rv[] = new byte[elgEncr.length + aesEncr.length];
        System.arraycopy(elgEncr, 0, rv, 0, elgEncr.length);
        System.arraycopy(aesEncr, 0, rv, elgEncr.length, aesEncr.length);
        //_log.debug("Return length: " + rv.length);
        //long finish = _context.clock().now();
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug("after the elgEngine.encrypt took a total of " + (finish - after) + "ms");
        return rv;
    }

    /**
     * scenario 2: 
     * Begin with 32 byte session tag, which also serves as the preIV.
     * Then encrypt with AES using that session key and the first 16 bytes of the SHA256 of the pre-IV:
     * <pre>
     *  - 2 byte integer specifying the # of session tags
     *  - that many 32 byte session tags
     *  - 4 byte integer specifying data.length
     *  - SHA256 of data
     *  - 1 byte flag that, if == 1, is followed by a new SessionKey
     *  - data
     *  - random bytes, padding the total size to greater than paddedSize with a mod 16 = 0
     * </pre>
     *
     */
    private byte[] encryptExistingSession(byte data[], PublicKey target, SessionKey key, Set tagsForDelivery,
                                         SessionTag currentTag, SessionKey newKey, long paddedSize) {
        //_log.debug("Encrypting to an EXISTING session");
        byte rawTag[] = currentTag.getData();

        //_log.debug("Pre IV for encryptExistingSession (aka tag): " + currentTag.toString());
        //_log.debug("SessionKey for encryptNewSession: " + DataHelper.toString(key.getData(), 32));
        // use alternate calculateHash() method to avoid object churn and caching
        //Hash ivHash = _context.sha().calculateHash(rawTag);
        //byte iv[] = new byte[16];
        //System.arraycopy(ivHash.getData(), 0, iv, 0, 16);
        byte[] iv = halfHash(rawTag);
        
        byte aesEncr[] = encryptAESBlock(data, key, iv, tagsForDelivery, newKey, paddedSize, SessionTag.BYTE_LENGTH);
        SimpleByteCache.release(iv);
        // that prepended SessionTag.BYTE_LENGTH bytes at the beginning of the buffer
        System.arraycopy(rawTag, 0, aesEncr, 0, rawTag.length);
        return aesEncr;
    }

    /**
     *  Generate the first 16 bytes of the SHA-256 hash of the data.
     *
     *  Here we are careful to use the SHA256Generator method that does not
     *  generate a Hash object or cache the result.
     *
     *  @param preIV the 32 byte pre-IV. Caller should call SimpleByteCache.release(data) after use.
     *  @return a 16 byte array. Caller should call SimpleByteCache.release(rv) after use.
     *  @since 0.8.9
     */
    private byte[] halfHash(byte[] preIV) {
        byte[] ivHash = SimpleByteCache.acquire(32);
        _context.sha().calculateHash(preIV, 0, 32, ivHash, 0);
        byte iv[] = SimpleByteCache.acquire(16);
        System.arraycopy(ivHash, 0, iv, 0, 16);
        SimpleByteCache.release(ivHash);
        return iv;
    }

    /**
     * For both scenarios, this method encrypts the AES area using the given key, iv
     * and making sure the resulting data is at least as long as the paddedSize and 
     * also mod 16 bytes.  The contents of the encrypted data is:
     * <pre>
     *  - 2 byte integer specifying the # of session tags
     *  - that many 32 byte session tags
     *  - 4 byte integer specifying data.length
     *  - SHA256 of data
     *  - 1 byte flag that, if == 1, is followed by a new SessionKey
     *  - data
     *  - random bytes, padding the total size to greater than paddedSize with a mod 16 = 0
     * </pre>
     *
     * Note: package private for ElGamalTest.testAES()
     */
    final byte[] encryptAESBlock(byte data[], SessionKey key, byte[] iv, Set tagsForDelivery, SessionKey newKey,
                                        long paddedSize) {
        return encryptAESBlock(data, key, iv, tagsForDelivery, newKey, paddedSize, 0);
    }

    private final byte[] encryptAESBlock(byte data[], SessionKey key, byte[] iv, Set tagsForDelivery, SessionKey newKey,
                                        long paddedSize, int prefixBytes) {
        //_log.debug("iv for encryption: " + DataHelper.toString(iv, 16));
        //_log.debug("Encrypting AES");
        if (tagsForDelivery == null) tagsForDelivery = Collections.EMPTY_SET;
        int size = 2 // sizeof(tags)
                 + SessionTag.BYTE_LENGTH*tagsForDelivery.size()
                 + 4 // payload length
                 + Hash.HASH_LENGTH
                 + (newKey == null ? 1 : 1 + SessionKey.KEYSIZE_BYTES)
                 + data.length;
        int totalSize = size + getPaddingSize(size, paddedSize);

        byte aesData[] = new byte[totalSize + prefixBytes];

        int cur = prefixBytes;
        DataHelper.toLong(aesData, cur, 2, tagsForDelivery.size());
        cur += 2;
        for (Iterator iter = tagsForDelivery.iterator(); iter.hasNext();) {
            SessionTag tag = (SessionTag) iter.next();
            System.arraycopy(tag.getData(), 0, aesData, cur, SessionTag.BYTE_LENGTH);
            cur += SessionTag.BYTE_LENGTH;
        }
        //_log.debug("# tags created, registered, and written: " + tagsForDelivery.size());
        DataHelper.toLong(aesData, cur, 4, data.length);
        cur += 4;
        //_log.debug("data length: " + data.length);
        // use alternate calculateHash() method to avoid object churn and caching
        //Hash hash = _context.sha().calculateHash(data);
        //System.arraycopy(hash.getData(), 0, aesData, cur, Hash.HASH_LENGTH);
        _context.sha().calculateHash(data, 0, data.length, aesData, cur);
        cur += Hash.HASH_LENGTH;
        
        //_log.debug("hash of data: " + DataHelper.toString(hash.getData(), 32));
        if (newKey == null) {
            aesData[cur++] = 0x00; // don't rekey
            //_log.debug("flag written");
        } else {
            aesData[cur++] = 0x01; // rekey
            System.arraycopy(newKey.getData(), 0, aesData, cur, SessionKey.KEYSIZE_BYTES);
            cur += SessionKey.KEYSIZE_BYTES;
        }
        System.arraycopy(data, 0, aesData, cur, data.length);
        cur += data.length;

        //_log.debug("raw data written: " + len);
        byte padding[] = getPadding(_context, size, paddedSize);
        //_log.debug("padding length: " + padding.length);
        System.arraycopy(padding, 0, aesData, cur, padding.length);
        cur += padding.length;

        //Hash h = _context.sha().calculateHash(data);
        //_log.debug("Hash of entire aes block before encryption: (len=" + data.length + ")\n" + DataHelper.toString(h.getData(), 32));
        _context.aes().encrypt(aesData, prefixBytes, aesData, prefixBytes, key, iv, aesData.length - prefixBytes);
        //_log.debug("Encrypted length: " + aesEncr.length);
        //return aesEncr;
        return aesData;
    }

    /**
     * Return random bytes for padding the data to a mod 16 size so that it is
     * at least minPaddedSize
     *
     */
    final static byte[] getPadding(I2PAppContext context, int curSize, long minPaddedSize) {
        int size = getPaddingSize(curSize, minPaddedSize);
        byte rv[] = new byte[size];
        context.random().nextBytes(rv);
        return rv;
    }

    final static int getPaddingSize(int curSize, long minPaddedSize) {
        int diff = 0;
        if (curSize < minPaddedSize) {
            diff = (int) minPaddedSize - curSize;
        }

        int numPadding = diff;
        if (((curSize + diff) % 16) != 0) numPadding += (16 - ((curSize + diff) % 16));
        return numPadding;
    }

/****
    public static void main(String args[]) {
        I2PAppContext ctx = new I2PAppContext();
        ElGamalAESEngine e = new ElGamalAESEngine(ctx);
        Object kp[] = ctx.keyGenerator().generatePKIKeypair();
        PublicKey pubKey = (PublicKey)kp[0];
        PrivateKey privKey = (PrivateKey)kp[1];
        SessionKey sessionKey = ctx.keyGenerator().generateSessionKey();
        for (int i = 0; i < 10; i++) {
            try {
                Set tags = new HashSet(5);
                if (i == 0) {
                    for (int j = 0; j < 5; j++)
                        tags.add(new SessionTag(true));
                }
                byte encrypted[] = e.encrypt("blah".getBytes(), pubKey, sessionKey, tags, 1024);
                byte decrypted[] = e.decrypt(encrypted, privKey);
                if ("blah".equals(new String(decrypted))) {
                    System.out.println("equal on " + i);
                } else {
                    System.out.println("NOT equal on " + i + ": " + new String(decrypted));
                    break;
                }
                ctx.sessionKeyManager().tagsDelivered(pubKey, sessionKey, tags);
            } catch (Exception ee) {
                ee.printStackTrace();
                break;
            }
        }
    }
****/
}
