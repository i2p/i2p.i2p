package net.i2p.crypto;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.stat.StatManager;
import net.i2p.util.Clock;
import net.i2p.util.Log;
import net.i2p.util.RandomSource;
import net.i2p.I2PAppContext;

/**
 * Handles the actual ElGamal+AES encryption and decryption scenarios using the
 * supplied keys and data.
 */
public class ElGamalAESEngine {
    private final static Log _log = new Log(ElGamalAESEngine.class);
    private final static int MIN_ENCRYPTED_SIZE = 80; // smallest possible resulting size
    private I2PAppContext _context;

    private ElGamalAESEngine() {}
    public ElGamalAESEngine(I2PAppContext ctx) {
        _context = ctx;
        
        _context.statManager().createFrequencyStat("crypto.elGamalAES.encryptNewSession",
                                                   "how frequently we encrypt to a new ElGamal/AES+SessionTag session?",
                                                   "Encryption", new long[] { 60*1000l, 60*60*1000l, 24*60*60*1000l});
        _context.statManager().createFrequencyStat("crypto.elGamalAES.encryptExistingSession",
                                                   "how frequently we encrypt to an existing ElGamal/AES+SessionTag session?",
                                                   "Encryption", new long[] { 60 * 1000l, 60 * 60 * 1000l, 24 * 60 * 60 * 1000l});
        _context.statManager().createFrequencyStat("crypto.elGamalAES.decryptNewSession",
                                                   "how frequently we decrypt with a new ElGamal/AES+SessionTag session?",
                                                   "Encryption", new long[] { 60 * 1000l, 60 * 60 * 1000l, 24 * 60 * 60 * 1000l});
        _context.statManager().createFrequencyStat("crypto.elGamalAES.decryptExistingSession",
                                                   "how frequently we decrypt with an existing ElGamal/AES+SessionTag session?",
                                                   "Encryption", new long[] { 60 * 1000l, 60 * 60 * 1000l, 24 * 60 * 60 * 1000l});
        _context.statManager().createFrequencyStat("crypto.elGamalAES.decryptFail",
                                                   "how frequently we fail to decrypt with ElGamal/AES+SessionTag?", "Encryption",
                                                   new long[] { 60 * 60 * 1000l, 24 * 60 * 60 * 1000l});
    }

    /**
     * Decrypt the message using the given private key.  This works according to the
     * ElGamal+AES algorithm in the data structure spec.
     *
     */
    public byte[] decrypt(byte data[], PrivateKey targetPrivateKey) throws DataFormatException {
        if (data == null) {
            if (_log.shouldLog(Log.WARN)) _log.warn("Null data being decrypted?");
            return null;
        } else if (data.length < MIN_ENCRYPTED_SIZE) {
            if (_log.shouldLog(Log.WARN))
                _log.warn("Data is less than the minimum size (" + data.length + " < " + MIN_ENCRYPTED_SIZE + ")");
            return null;
        }

        byte tag[] = new byte[32];
        System.arraycopy(data, 0, tag, 0, tag.length);
        SessionTag st = new SessionTag(tag);
        SessionKey key = _context.sessionKeyManager().consumeTag(st);
        SessionKey foundKey = new SessionKey();
        foundKey.setData(null);
        SessionKey usedKey = new SessionKey();
        Set foundTags = new HashSet();
        byte decrypted[] = null;
        if (key != null) {
            if (_log.shouldLog(Log.DEBUG)) _log.debug("Key is known for tag " + st);
            usedKey.setData(key.getData());
            decrypted = decryptExistingSession(data, key, targetPrivateKey, foundTags, usedKey, foundKey);
            if (decrypted != null)
                _context.statManager().updateFrequency("crypto.elGamalAES.decryptExistingSession");
            else
                _context.statManager().updateFrequency("crypto.elGamalAES.decryptFailed");
        } else {
            if (_log.shouldLog(Log.DEBUG)) _log.debug("Key is NOT known for tag " + st);
            decrypted = decryptNewSession(data, targetPrivateKey, foundTags, usedKey, foundKey);
            if (decrypted != null)
                _context.statManager().updateFrequency("crypto.elGamalAES.decryptNewSession");
            else
                _context.statManager().updateFrequency("crypto.elGamalAES.decryptFailed");
        }

        if ((key == null) && (decrypted == null)) {
            //_log.debug("Unable to decrypt the data starting with tag [" + st + "] - did the tag expire recently?", new Exception("Decrypt failure"));
        }

        if (foundTags.size() > 0) {
            if (foundKey.getData() != null) {
                if (_log.shouldLog(Log.DEBUG)) _log.debug("Found key: " + foundKey);
                _context.sessionKeyManager().tagsReceived(foundKey, foundTags);
            } else {
                if (_log.shouldLog(Log.DEBUG)) _log.debug("Used key: " + usedKey);
                _context.sessionKeyManager().tagsReceived(usedKey, foundTags);
            }
        }
        return decrypted;
    }

    /**
     * scenario 1: 
     * Begin with 222 bytes, ElG encrypted, containing:
     *  - 32 byte SessionKey
     *  - 32 byte pre-IV for the AES
     *  - 158 bytes of random padding
     * Then encrypt with AES using that session key and the first 16 bytes of the SHA256 of the pre-IV, using
     * the decryptAESBlock method & structure.
     *
     * @param foundTags set which is filled with any sessionTags found during decryption
     * @param foundKey  session key which may be filled with a new sessionKey found during decryption
     *
     * @return null if decryption fails
     */
    byte[] decryptNewSession(byte data[], PrivateKey targetPrivateKey, Set foundTags, SessionKey usedKey,
                                    SessionKey foundKey) throws DataFormatException {
        if (data == null) {
            if (_log.shouldLog(Log.WARN)) _log.warn("Data is null, unable to decrypt new session");
            return null;
        } else if (data.length < 514) {
            if (_log.shouldLog(Log.WARN)) _log.warn("Data length is too small (" + data.length + ")");
            return null;
        }
        byte elgEncr[] = new byte[514];
        if (data.length > 514) {
            System.arraycopy(data, 0, elgEncr, 0, 514);
        } else {
            System.arraycopy(data, 0, elgEncr, 514 - data.length, data.length);
        }
        byte elgDecr[] = _context.elGamalEngine().decrypt(elgEncr, targetPrivateKey);
        if (elgDecr == null) return null;

        ByteArrayInputStream bais = new ByteArrayInputStream(elgDecr);
        byte preIV[] = null;

        try {
            usedKey.readBytes(bais);
            preIV = new byte[32];
            int read = bais.read(preIV);
            if (read != preIV.length) {
            // hmm, this can't really happen...
            throw new DataFormatException("Somehow ElGamal broke and 256 bytes is less than 32 bytes..."); }
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.ERROR)) _log.error("Error decrypting the new session", ioe);
            return null;
        }
        // ignore the next 192 bytes
        byte aesEncr[] = new byte[data.length - 514];
        System.arraycopy(data, 514, aesEncr, 0, aesEncr.length);

        //_log.debug("Pre IV for decryptNewSession: " + DataHelper.toString(preIV, 32));
        //_log.debug("SessionKey for decryptNewSession: " + DataHelper.toString(key.getData(), 32));
        Hash ivHash = _context.sha().calculateHash(preIV);
        byte iv[] = new byte[16];
        System.arraycopy(ivHash.getData(), 0, iv, 0, 16);

        byte aesDecr[] = decryptAESBlock(aesEncr, usedKey, iv, null, foundTags, foundKey);

        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Decrypt with a NEW session successfull: # tags read = " + foundTags.size(),
                       new Exception("Decrypted by"));
        return aesDecr;
    }

    /**
     * scenario 2: 
     * The data begins with 32 byte session tag, which also serves as the preIV.
     * Then decrypt with AES using that session key and the first 16 bytes of the SHA256 of the pre-IV:
     *  - 2 byte integer specifying the # of session tags
     *  - that many 32 byte session tags
     *  - 4 byte integer specifying data.length
     *  - SHA256 of data
     *  - 1 byte flag that, if == 1, is followed by a new SessionKey
     *  - data
     *  - random bytes, padding the total size to greater than paddedSize with a mod 16 = 0
     *
     * If anything doesn't match up in decryption, it falls back to decryptNewSession
     *
     * @param foundTags set which is filled with any sessionTags found during decryption
     * @param foundKey  session key which may be filled with a new sessionKey found during decryption
     *
     */
    byte[] decryptExistingSession(byte data[], SessionKey key, PrivateKey targetPrivateKey, Set foundTags,
                                         SessionKey usedKey, SessionKey foundKey) throws DataFormatException {
        byte preIV[] = new byte[32];
        System.arraycopy(data, 0, preIV, 0, preIV.length);
        byte encr[] = new byte[data.length - 32];
        System.arraycopy(data, 32, encr, 0, encr.length);
        Hash ivHash = _context.sha().calculateHash(preIV);
        byte iv[] = new byte[16];
        System.arraycopy(ivHash.getData(), 0, iv, 0, 16);

        usedKey.setData(key.getData());

        //_log.debug("Pre IV for decryptExistingSession: " + DataHelper.toString(preIV, 32));
        //_log.debug("SessionKey for decryptNewSession: " + DataHelper.toString(key.getData(), 32));
        byte decrypted[] = decryptAESBlock(encr, key, iv, preIV, foundTags, foundKey);
        if (decrypted == null) {
            // it begins with a valid session tag, but thats just a coincidence.
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Decrypt with a non session tag, but tags read: " + foundTags.size());
            return decryptNewSession(data, targetPrivateKey, foundTags, usedKey, foundKey);
        } else {
            // existing session decrypted successfully!
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Decrypt with an EXISTING session tag successfull, # tags read: " + foundTags.size(),
                           new Exception("Decrypted by"));
            return decrypted;
        }
    }

    /**
     * Decrypt the AES data with the session key and IV.  The result should be:
     *  - 2 byte integer specifying the # of session tags
     *  - that many 32 byte session tags
     *  - 4 byte integer specifying data.length
     *  - SHA256 of data
     *  - 1 byte flag that, if == 1, is followed by a new SessionKey
     *  - data
     *  - random bytes, padding the total size to greater than paddedSize with a mod 16 = 0
     *
     * If anything doesn't match up in decryption, return null.  Otherwise, return
     * the decrypted data and update the session as necessary.  If the sentTag is not null,
     * consume it, but if it is null, record the keys, etc as part of a new session.
     *
     * @param foundTags set which is filled with any sessionTags found during decryption
     * @param foundKey  session key which may be filled with a new sessionKey found during decryption
     */
    byte[] decryptAESBlock(byte encrypted[], SessionKey key, byte iv[], byte sentTag[], Set foundTags,
                                  SessionKey foundKey) throws DataFormatException {
        //_log.debug("iv for decryption: " + DataHelper.toString(iv, 16));	
        //_log.debug("decrypting AES block.  encr.length = " + (encrypted == null? -1 : encrypted.length) + " sentTag: " + DataHelper.toString(sentTag, 32));
        byte decrypted[] = _context.AESEngine().decrypt(encrypted, key, iv);
        Hash h = _context.sha().calculateHash(decrypted);
        //_log.debug("Hash of entire aes block after decryption: \n" + DataHelper.toString(h.getData(), 32));
        try {
            SessionKey newKey = null;
            Hash readHash = null;
            List tags = new ArrayList();

            ByteArrayInputStream bais = new ByteArrayInputStream(decrypted);
            long numTags = DataHelper.readLong(bais, 2);
            //_log.debug("# tags: " + numTags);
            if ((numTags < 0) || (numTags > 65535)) throw new Exception("Invalid number of session tags");
            for (int i = 0; i < numTags; i++) {
                byte tag[] = new byte[32];
                int read = bais.read(tag);
                if (read != 32)
                    throw new Exception("Invalid session tag - # tags: " + numTags + " curTag #: " + i + " read: "
                                        + read);
                tags.add(new SessionTag(tag));
            }
            long len = DataHelper.readLong(bais, 4);
            //_log.debug("len: " + len);
            if ((len < 0) || (len > encrypted.length)) throw new Exception("Invalid size of payload");
            byte hashval[] = new byte[32];
            int read = bais.read(hashval);
            if (read != hashval.length) throw new Exception("Invalid size of hash");
            readHash = new Hash();
            readHash.setData(hashval);
            byte flag = (byte) bais.read();
            if (flag == 0x01) {
                byte rekeyVal[] = new byte[32];
                read = bais.read(rekeyVal);
                if (read != rekeyVal.length) throw new Exception("Invalid size of the rekeyed session key");
                newKey = new SessionKey();
                newKey.setData(rekeyVal);
            }
            byte unencrData[] = new byte[(int) len];
            read = bais.read(unencrData);
            if (read != unencrData.length) throw new Exception("Invalid size of the data read");
            Hash calcHash = _context.sha().calculateHash(unencrData);
            if (calcHash.equals(readHash)) {
                // everything matches.  w00t.
                foundTags.addAll(tags);
                if (newKey != null) foundKey.setData(newKey.getData());
                return unencrData;
            } else {
                throw new Exception("Hash does not match");
            }
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
     * @param tagsForDelivery session tags to be associated with the key (or newKey if specified), or null
     * @param currentTag sessionTag to use, or null if it should use ElG
     * @param newKey key to be delivered to the target, with which the tagsForDelivery should be associated
     * @param paddedSize minimum size in bytes of the body after padding it (if less than the
     *          body's real size, no bytes are appended but the body is not truncated)
     */
    public byte[] encrypt(byte data[], PublicKey target, SessionKey key, Set tagsForDelivery,
                                 SessionTag currentTag, SessionKey newKey, long paddedSize) {
        if (currentTag == null) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Current tag is null, encrypting as new session", new Exception("encrypt new"));
            _context.statManager().updateFrequency("crypto.elGamalAES.encryptNewSession");
            return encryptNewSession(data, target, key, tagsForDelivery, newKey, paddedSize);
        } else {
            if (_log.shouldLog(Log.INFO))
                _log.info("Current tag is NOT null, encrypting as existing session", new Exception("encrypt existing"));
            _context.statManager().updateFrequency("crypto.elGamalAES.encryptExistingSession");
            return encryptExistingSession(data, target, key, tagsForDelivery, currentTag, newKey, paddedSize);
        }
    }

    /**
     * Encrypt the data to the target using the given key and deliver the specified tags
     */
    public byte[] encrypt(byte data[], PublicKey target, SessionKey key, Set tagsForDelivery,
                                 SessionTag currentTag, long paddedSize) {
        return encrypt(data, target, key, tagsForDelivery, currentTag, null, paddedSize);
    }

    /**
     * Encrypt the data to the target using the given key and deliver the specified tags
     */
    public byte[] encrypt(byte data[], PublicKey target, SessionKey key, Set tagsForDelivery, long paddedSize) {
        return encrypt(data, target, key, tagsForDelivery, null, null, paddedSize);
    }

    /**
     * Encrypt the data to the target using the given key delivering no tags
     */
    public byte[] encrypt(byte data[], PublicKey target, SessionKey key, long paddedSize) {
        return encrypt(data, target, key, null, null, null, paddedSize);
    }

    /**
     * scenario 1: 
     * Begin with 222 bytes, ElG encrypted, containing:
     *  - 32 byte SessionKey
     *  - 32 byte pre-IV for the AES
     *  - 158 bytes of random padding
     * Then encrypt with AES using that session key and the first 16 bytes of the SHA256 of the pre-IV:
     *  - 2 byte integer specifying the # of session tags
     *  - that many 32 byte session tags
     *  - 4 byte integer specifying data.length
     *  - SHA256 of data
     *  - 1 byte flag that, if == 1, is followed by a new SessionKey
     *  - data
     *  - random bytes, padding the total size to greater than paddedSize with a mod 16 = 0
     *
     */
    byte[] encryptNewSession(byte data[], PublicKey target, SessionKey key, Set tagsForDelivery,
                                    SessionKey newKey, long paddedSize) {
        //_log.debug("Encrypting to a NEW session");
        try {
            ByteArrayOutputStream elgSrc = new ByteArrayOutputStream(64);
            key.writeBytes(elgSrc);
            byte preIV[] = new byte[32];
            _context.random().nextBytes(preIV);
            elgSrc.write(preIV);
            byte rnd[] = new byte[158];
            _context.random().nextBytes(rnd);
            elgSrc.write(rnd);
            elgSrc.flush();

            //_log.debug("Pre IV for encryptNewSession: " + DataHelper.toString(preIV, 32));
            //_log.debug("SessionKey for encryptNewSession: " + DataHelper.toString(key.getData(), 32));
            long before = _context.clock().now();
            byte elgEncr[] = _context.elGamalEngine().encrypt(elgSrc.toByteArray(), target);
            long after = _context.clock().now();
            if (_log.shouldLog(Log.INFO))
                _log.info("elgEngine.encrypt of the session key took " + (after - before) + "ms");
            if (elgEncr.length < 514) {
                byte elg[] = new byte[514];
                int diff = elg.length - elgEncr.length;
                if (_log.shouldLog(Log.DEBUG)) _log.debug("Difference in size: " + diff);
                System.arraycopy(elgEncr, 0, elg, diff, elgEncr.length);
                elgEncr = elg;
            }
            //_log.debug("ElGamal encrypted length: " + elgEncr.length + " elGamal source length: " + elgSrc.toByteArray().length);

            Hash ivHash = _context.sha().calculateHash(preIV);
            byte iv[] = new byte[16];
            System.arraycopy(ivHash.getData(), 0, iv, 0, 16);
            byte aesEncr[] = encryptAESBlock(data, key, iv, tagsForDelivery, newKey, paddedSize);
            //_log.debug("AES encrypted length: " + aesEncr.length);

            byte rv[] = new byte[elgEncr.length + aesEncr.length];
            System.arraycopy(elgEncr, 0, rv, 0, elgEncr.length);
            System.arraycopy(aesEncr, 0, rv, elgEncr.length, aesEncr.length);
            //_log.debug("Return length: " + rv.length);
            long finish = _context.clock().now();
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("after the elgEngine.encrypt took a total of " + (finish - after) + "ms");
            return rv;
        } catch (IOException ioe) {
            _log.error("Error encrypting the new session", ioe);
            return null;
        } catch (DataFormatException dfe) {
            _log.error("Error writing out the bytes for the new session", dfe);
            return null;
        }
    }

    /**
     * scenario 2: 
     * Begin with 32 byte session tag, which also serves as the preIV.
     * Then encrypt with AES using that session key and the first 16 bytes of the SHA256 of the pre-IV:
     *  - 2 byte integer specifying the # of session tags
     *  - that many 32 byte session tags
     *  - 4 byte integer specifying data.length
     *  - SHA256 of data
     *  - 1 byte flag that, if == 1, is followed by a new SessionKey
     *  - data
     *  - random bytes, padding the total size to greater than paddedSize with a mod 16 = 0
     *
     */
    byte[] encryptExistingSession(byte data[], PublicKey target, SessionKey key, Set tagsForDelivery,
                                         SessionTag currentTag, SessionKey newKey, long paddedSize) {
        //_log.debug("Encrypting to an EXISTING session");
        byte rawTag[] = currentTag.getData();

        //_log.debug("Pre IV for encryptExistingSession (aka tag): " + currentTag.toString());
        //_log.debug("SessionKey for encryptNewSession: " + DataHelper.toString(key.getData(), 32));
        Hash ivHash = _context.sha().calculateHash(rawTag);
        byte iv[] = new byte[16];
        System.arraycopy(ivHash.getData(), 0, iv, 0, 16);

        byte aesEncr[] = encryptAESBlock(data, key, iv, tagsForDelivery, newKey, paddedSize);
        byte rv[] = new byte[rawTag.length + aesEncr.length];
        System.arraycopy(rawTag, 0, rv, 0, rawTag.length);
        System.arraycopy(aesEncr, 0, rv, rawTag.length, aesEncr.length);
        return rv;
    }

    private final static Set EMPTY_SET = new HashSet();

    /**
     * For both scenarios, this method encrypts the AES area using the given key, iv
     * and making sure the resulting data is at least as long as the paddedSize and 
     * also mod 16 bytes.  The contents of the encrypted data is:
     *  - 2 byte integer specifying the # of session tags
     *  - that many 32 byte session tags
     *  - 4 byte integer specifying data.length
     *  - SHA256 of data
     *  - 1 byte flag that, if == 1, is followed by a new SessionKey
     *  - data
     *  - random bytes, padding the total size to greater than paddedSize with a mod 16 = 0
     *
     */
    final byte[] encryptAESBlock(byte data[], SessionKey key, byte[] iv, Set tagsForDelivery, SessionKey newKey,
                                        long paddedSize) {
        //_log.debug("iv for encryption: " + DataHelper.toString(iv, 16));
        //_log.debug("Encrypting AES");
        try {
            ByteArrayOutputStream aesSrc = new ByteArrayOutputStream((int) paddedSize);
            if (tagsForDelivery == null) tagsForDelivery = EMPTY_SET;
            DataHelper.writeLong(aesSrc, 2, tagsForDelivery.size());
            for (Iterator iter = tagsForDelivery.iterator(); iter.hasNext();) {
                SessionTag tag = (SessionTag) iter.next();
                aesSrc.write(tag.getData());
            }
            //_log.debug("# tags created, registered, and written: " + tags.size());
            DataHelper.writeLong(aesSrc, 4, data.length);
            //_log.debug("data length: " + data.length);
            Hash hash = _context.sha().calculateHash(data);
            hash.writeBytes(aesSrc);
            //_log.debug("hash of data: " + DataHelper.toString(hash.getData(), 32));
            if (newKey == null) {
                byte flag = 0x00; // don't rekey
                aesSrc.write(flag);
                //_log.debug("flag written");
            } else {
                byte flag = 0x01; // rekey
                aesSrc.write(flag);
                aesSrc.write(newKey.getData());
            }
            aesSrc.write(data);
            int len = aesSrc.toByteArray().length;
            //_log.debug("raw data written: " + len);
            byte padding[] = getPadding(_context, len, paddedSize);
            //_log.debug("padding length: " + padding.length);
            aesSrc.write(padding);

            byte aesUnencr[] = aesSrc.toByteArray();
            Hash h = _context.sha().calculateHash(aesUnencr);
            //_log.debug("Hash of entire aes block before encryption: (len=" + aesUnencr.length + ")\n" + DataHelper.toString(h.getData(), 32));
            byte aesEncr[] = _context.AESEngine().encrypt(aesUnencr, key, iv);
            //_log.debug("Encrypted length: " + aesEncr.length);
            return aesEncr;
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.ERROR)) _log.error("Error encrypting AES chunk", ioe);
            return null;
        } catch (DataFormatException dfe) {
            if (_log.shouldLog(Log.ERROR)) _log.error("Error formatting the bytes to write the AES chunk", dfe);
            return null;
        }
    }

    /**
     * Return random bytes for padding the data to a mod 16 size so that it is
     * at least minPaddedSize
     *
     */
    final static byte[] getPadding(I2PAppContext context, int curSize, long minPaddedSize) {
        int diff = 0;
        if (curSize < minPaddedSize) {
            diff = (int) minPaddedSize - curSize;
        }

        int numPadding = diff;
        if (((curSize + diff) % 16) != 0) numPadding += (16 - ((curSize + diff) % 16));
        byte rv[] = new byte[numPadding];
        context.random().nextBytes(rv);
        return rv;
    }

}