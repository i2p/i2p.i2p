package net.i2p.crypto;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't  make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.I2PAppContext;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.data.PublicKey;
import net.i2p.data.PrivateKey;
import net.i2p.data.DataHelper;
import net.i2p.util.RandomSource;
import net.i2p.util.Log;
import net.i2p.util.Clock;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import java.util.Set;
import java.util.HashSet;

class ElGamalAESEngineTest {
    private final static Log _log = new Log(ElGamalAESEngineTest.class);
    private I2PAppContext _context;
    public ElGamalAESEngineTest(I2PAppContext ctx) {
        _context = ctx;
    }
    public void runRoundtripTest() {
        try {
            Object keys[] = KeyGenerator.getInstance().generatePKIKeypair();
            PublicKey pubKey = (PublicKey)keys[0];
            PrivateKey privKey = (PrivateKey)keys[1];
            
            String msg = "Hello world";
            Set toBeDelivered = new HashSet();
            SessionKey key = _context.sessionKeyManager().getCurrentKey(pubKey);
            if (key == null)
                key = _context.sessionKeyManager().createSession(pubKey);
            byte[] encrypted = _context.elGamalAESEngine().encrypt(msg.getBytes(), pubKey, key, 64);
            byte[] decrypted = _context.elGamalAESEngine().decrypt(encrypted, privKey);
            if (decrypted == null)
                throw new Exception("Failed to decrypt");
            String read = new String(decrypted);
            _log.debug("read: " + read);
            _log.debug("Match? " + msg.equals(read));
        } catch (Exception e) {
            _log.error("Error", e);
            try { Thread.sleep(5000); } catch (InterruptedException ie) {}
            System.exit(0);
        }
    }
    
    public void runLoopTest(int runs) {
        try {
            Object keys[] = KeyGenerator.getInstance().generatePKIKeypair();
            PublicKey pubKey = (PublicKey)keys[0];
            PrivateKey privKey = (PrivateKey)keys[1];
            
            long e0 = 0;
            long d0 = 0;
            long eTot = 0;
            long dTot = 0;
            for (int i = 0; i < runs; i++) {
                long times[] = runMessage(pubKey, privKey);
                _log.debug("E[" + i + "] time: " + times[0] + "ms");
                _log.debug("D["+i+"] time: " + times[1] + "ms");
                if (i == 0) {
                    e0 = times[0];
                    d0 = times[1];
                }
                eTot += times[0];
                dTot += times[1];
            }
            _log.debug("E average time: " + eTot/runs + "ms");
            _log.debug("D average time: " + dTot/runs + "ms");
            _log.debug("Total time to send and receive " + (runs) + "Kb: " + (eTot+dTot)+"ms");
            
        } catch (Exception e) {
            _log.error("Error", e);
            try { Thread.sleep(5000); } catch (InterruptedException ie) {}
            System.exit(0);
        }
    }
    
    private long[] runMessage(PublicKey pubKey, PrivateKey privKey) throws Exception {
        byte[] msg = new byte[400];
        RandomSource.getInstance().nextBytes(msg);
        SessionKey key = _context.sessionKeyManager().getCurrentKey(pubKey);
        if (key == null)
            key = _context.sessionKeyManager().createSession(pubKey);
        
        long beforeE = Clock.getInstance().now();
        byte[] encrypted = _context.elGamalAESEngine().encrypt(msg, pubKey, key, 1024);
        long afterE = Clock.getInstance().now();
        byte[] decrypted = _context.elGamalAESEngine().decrypt(encrypted, privKey);
        long afterD = Clock.getInstance().now();
        if (!DataHelper.eq(msg, decrypted)) {
            _log.error("WTF, D(E(val)) != val");
            return null;
        }
        
        long rv[] = new long[2];
        rv[0] = afterE - beforeE;
        rv[1] = afterD - afterE;
        return rv;
    }
    
    public void runAESTest() {
        try {
            SessionKey sessionKey = KeyGenerator.getInstance().generateSessionKey();
            Hash h = SHA256Generator.getInstance().calculateHash(sessionKey.getData());
            byte iv[] = new byte[16];
            System.arraycopy(h.getData(), 0, iv, 0, 16);
            
            String msg = "Hello world";
            
            byte encrypted[] = _context.elGamalAESEngine().encryptAESBlock(msg.getBytes(), sessionKey, iv, null, null, 64);
            _log.debug("** Encryption complete.  Beginning decryption");
            Set foundTags = new HashSet();
            SessionKey foundKey = new SessionKey();
            byte decrypted[] = _context.elGamalAESEngine().decryptAESBlock(encrypted, 0, encrypted.length, sessionKey, iv, null, foundTags, foundKey);
            if (decrypted == null) throw new Exception("Decryption failed");
            String read = new String(decrypted);
            _log.debug("read: " + read);
            _log.debug("Match? " + msg.equals(read));
        } catch (Exception e) {
            _log.error("Error", e);
            try { Thread.sleep(5000); } catch (InterruptedException ie) {}
            System.exit(0);
        }
    }
    
    public void runBasicAESTest() {
        try {
            SessionKey sessionKey = KeyGenerator.getInstance().generateSessionKey();
            Hash h = SHA256Generator.getInstance().calculateHash(sessionKey.getData());
            byte iv[] = new byte[16];
            System.arraycopy(h.getData(), 0, iv, 0, 16);
            
            String msg = "Hello world01234012345678901234501234567890123450123456789012345";
            h = SHA256Generator.getInstance().calculateHash(msg.getBytes());
            _log.debug("Hash of entire aes block before encryption: \n" + DataHelper.toString(h.getData(), 32));
            byte aesEncr[] = new byte[msg.getBytes().length];
            byte aesDecr[] = new byte[aesEncr.length];
            _context.aes().encrypt(msg.getBytes(), 0, aesEncr, 0, sessionKey, iv, aesEncr.length);
            _context.aes().decrypt(aesEncr, 0, aesDecr, 0, sessionKey, iv, aesEncr.length);
            h = SHA256Generator.getInstance().calculateHash(aesDecr);
            _log.debug("Hash of entire aes block after decryption: \n" + DataHelper.toString(h.getData(), 32));
            if (msg.equals(new String(aesDecr))) {
                _log.debug("**AES Basic test passed!\n\n");
            }
        } catch (Exception e) {
            _log.error("Error", e);
            try { Thread.sleep(5000); } catch (InterruptedException ie) {}
            System.exit(0);
        }
    }
    
    public void runElGamalTest(int numLoops) {
        
        for (int i = 0; i < numLoops; i++) {
            Object keys[] = KeyGenerator.getInstance().generatePKIKeypair();
            PublicKey pubKey = (PublicKey)keys[0];
            PrivateKey privKey = (PrivateKey)keys[1];
            SessionKey key = KeyGenerator.getInstance().generateSessionKey();
            
            runBasicElGamalTest(key, pubKey, privKey);
        }
    }
    
    public void runBasicElGamalTest(SessionKey key, PublicKey pubKey, PrivateKey privKey) {
        try {
            ByteArrayOutputStream elgSrc = new ByteArrayOutputStream(256);
            key.writeBytes(elgSrc);
            byte preIV[] = new byte[32];
            RandomSource.getInstance().nextBytes(preIV);
            elgSrc.write(preIV);
            //	    byte rnd[] = new byte[191];
            //	    RandomSource.getInstance().nextBytes(rnd);
            //	    elgSrc.write(rnd);
            elgSrc.flush();
            
            byte elgEncr[] = _context.elGamalEngine().encrypt(elgSrc.toByteArray(), pubKey);
            byte elgDecr[] = _context.elGamalEngine().decrypt(elgEncr, privKey);
            
            ByteArrayInputStream bais = new ByteArrayInputStream(elgDecr);
            SessionKey nk = new SessionKey();
            
            nk.readBytes(bais);
            byte postpreIV[] = new byte[32];
            int read = bais.read(postpreIV);
            if (read != postpreIV.length) {
                // hmm, this can't really happen...
                throw new Exception("Somehow ElGamal broke and 256 bytes is less than 32 bytes...");
            }
            // ignore the next 192 bytes
            boolean eq = (DataHelper.eq(preIV, postpreIV) && DataHelper.eq(key, nk));
            if (!eq) {
                _log.error("elgEncr.length: " + elgEncr.length + " elgDecr.length: " + elgDecr.length);
                _log.error("Pre IV.................: " + DataHelper.toString(preIV, 32));
                _log.error("Pre IV after decryption: " + DataHelper.toString(postpreIV, 32));
                _log.error("SessionKey.................: " + DataHelper.toString(key.getData(), 32));
                _log.error("SessionKey after decryption: " + DataHelper.toString(nk.getData(), 32));
                _log.error("PublicKey: " + DataHelper.toDecimalString(pubKey.getData(), pubKey.getData().length));
                _log.error("PrivateKey: " + DataHelper.toDecimalString(privKey.getData(), privKey.getData().length));
                
                throw new Exception("Not equal!");
            } else {
                _log.debug("Basic ElG D(E(val)) == val");
            }
            
        } catch (Exception e) {
            _log.error("Error", e);
            try { Thread.sleep(5000); } catch (InterruptedException ie) {}
            System.exit(0);
        }
    }
    
    public static void main(String args[]) {
        I2PAppContext context = new I2PAppContext();
        ElGamalAESEngineTest tst = new ElGamalAESEngineTest(context);
        Object o = YKGenerator.class;
        try { Thread.sleep(120*1000); } catch (InterruptedException ie) {}
        
        tst.runBasicAESTest();
        tst.runAESTest();
        tst.runRoundtripTest();
        tst.runElGamalTest(2);
        // test bug
        for (int i = 0; i < 3; i++)
            tst.runLoopTest(1);
        // test throughput
        tst.runLoopTest(5);
        
        net.i2p.stat.SimpleStatDumper.dumpStats(context, Log.CRIT);
        try { Thread.sleep(5*1000); } catch (InterruptedException ie) {}
    }
}
