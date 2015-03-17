package net.i2p.crypto;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't  make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.HashSet;
import java.util.Set;

import junit.framework.TestCase;
import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;

/**
 *
 * session key management unit tests:
 *
 */
public class SessionEncryptionTest extends TestCase{
    private static I2PAppContext _context = new I2PAppContext();
    
    protected void setUp(){
        _context = new I2PAppContext();
    }
    
    protected void tearDown() {
        System.gc();
    }
    
    
    public void testNoSessions1() throws Exception{
        Object keys[] = KeyGenerator.getInstance().generatePKIKeypair();
        PublicKey pubKey = (PublicKey)keys[0];
        PrivateKey privKey = (PrivateKey)keys[1];
        SessionKey curKey = _context.sessionKeyManager().createSession(pubKey);
        
        byte[] msg = "msg 1".getBytes();
        
        byte emsg[] = _context.elGamalAESEngine().encrypt(msg, pubKey, curKey, 64);
        byte dmsg[] = _context.elGamalAESEngine().decrypt(emsg, privKey);
        assertTrue(DataHelper.eq(dmsg, msg));
    }
    
    public void testNoSessions2() throws Exception{
        Object keys[] = KeyGenerator.getInstance().generatePKIKeypair();
        PublicKey pubKey = (PublicKey)keys[0];
        PrivateKey privKey = (PrivateKey)keys[1];
        SessionKey curKey = _context.sessionKeyManager().createSession(pubKey);
        
        byte[] msg = "msg 2".getBytes();
        
        byte emsg[] = _context.elGamalAESEngine().encrypt(msg, pubKey, curKey, 64);
        byte dmsg[] = _context.elGamalAESEngine().decrypt(emsg, privKey);
        assertTrue(DataHelper.eq(dmsg, msg));
    }
    
    /**
     *  Run     tagsIncluded    useTag  rekey
     *  1       yes (2)         no      no
     *  2       no              yes     no
     *  3       yes (2)         yes     no
     *  4       no              yes     no
     *  5       no              yes     no
     */
    public void testSessions() throws Exception{
        Object keys[] = KeyGenerator.getInstance().generatePKIKeypair();
        PublicKey pubKey = (PublicKey)keys[0];
        PrivateKey privKey = (PrivateKey)keys[1];
        SessionKey curKey = _context.sessionKeyManager().createSession(pubKey);
        
        SessionTag tag1 = new SessionTag(true);
        SessionTag tag2 = new SessionTag(true);
        SessionTag tag3 = new SessionTag(true);
        SessionTag tag4 = new SessionTag(true);
        
        HashSet firstTags = new HashSet();
        firstTags.add(tag1);
        firstTags.add(tag2);
        
        HashSet secondTags = new HashSet();
        secondTags.add(tag3);
        secondTags.add(tag4);
        
        byte[] msg1 = "msg 1".getBytes();
        byte[] msg2 = "msg 2".getBytes();
        byte[] msg3 = "msg 3".getBytes();
        byte[] msg4 = "msg 4".getBytes();
        byte[] msg5 = "msg 5".getBytes();
        
        byte emsg1[] = _context.elGamalAESEngine().encrypt(msg1, pubKey, curKey, firstTags, 64);
        
        byte dmsg1[] = _context.elGamalAESEngine().decrypt(emsg1, privKey);
        assertTrue(DataHelper.eq(dmsg1, msg1));
        
        
        
        TagSetHandle tsh = _context.sessionKeyManager().tagsDelivered(pubKey, curKey, firstTags);
        _context.sessionKeyManager().tagsAcked(pubKey, curKey, tsh);
        
        curKey = _context.sessionKeyManager().getCurrentKey(pubKey);
        SessionTag curTag = _context.sessionKeyManager().consumeNextAvailableTag(pubKey, curKey);
        
        assertNotNull(curTag);
        
        byte emsg2[] = _context.elGamalAESEngine().encrypt(msg2, pubKey, curKey, null, curTag, 64);
        
        byte dmsg2[] = _context.elGamalAESEngine().decrypt(emsg2, privKey);
        assertTrue(DataHelper.eq(dmsg2, msg2));
        
        
        
        
        curKey = _context.sessionKeyManager().getCurrentKey(pubKey);
        curTag = _context.sessionKeyManager().consumeNextAvailableTag(pubKey, curKey);
        
        assertNotNull(curTag);
        assertNotNull(curKey);
        
        byte emsg3[] = _context.elGamalAESEngine().encrypt(msg3, pubKey, curKey, secondTags, curTag, 64);
        
        byte dmsg3[] = _context.elGamalAESEngine().decrypt(emsg3, privKey);
        assertTrue(DataHelper.eq(dmsg3, msg3));
        
        
        
        tsh = _context.sessionKeyManager().tagsDelivered(pubKey, curKey, secondTags);
        _context.sessionKeyManager().tagsAcked(pubKey, curKey, tsh);
        
        curKey = _context.sessionKeyManager().getCurrentKey(pubKey);
        curTag = _context.sessionKeyManager().consumeNextAvailableTag(pubKey, curKey);
        
        assertNotNull(curTag);
        assertNotNull(curKey);
        
        byte emsg4[] = _context.elGamalAESEngine().encrypt(msg4, pubKey, curKey, null, curTag, 64);
        
        byte dmsg4[] = _context.elGamalAESEngine().decrypt(emsg4, privKey);
        assertTrue(DataHelper.eq(dmsg4, msg4));
        
        
        curKey = _context.sessionKeyManager().getCurrentKey(pubKey);
        curTag = _context.sessionKeyManager().consumeNextAvailableTag(pubKey, curKey);
        
        assertNotNull(curTag);
        assertNotNull(curKey);
        
        byte emsg5[] = _context.elGamalAESEngine().encrypt(msg5, pubKey, curKey, null, curTag, 64);
        
        byte dmsg5[] = _context.elGamalAESEngine().decrypt(emsg5, privKey);
        assertTrue(DataHelper.eq(dmsg5, msg5));
        
        
    }
    
    /**
     *  Run tagsIncluded    useTag  rekey
     *  1   yes (2)         no      no
     *  2   no              yes     no
     *  3   yes (2)         yes     yes
     *  4   no              yes     no
     *  5   no              yes     no
     */
    public void testRekeying() throws Exception{
        Object keys[] = KeyGenerator.getInstance().generatePKIKeypair();
        PublicKey pubKey = (PublicKey)keys[0];
        PrivateKey privKey = (PrivateKey)keys[1];
        SessionKey curKey = _context.sessionKeyManager().createSession(pubKey);
        SessionKey nextKey = KeyGenerator.getInstance().generateSessionKey();
        
        SessionTag tag1 = new SessionTag(true);
        SessionTag tag2 = new SessionTag(true);
        SessionTag tag3 = new SessionTag(true);
        SessionTag tag4 = new SessionTag(true);
        
        HashSet firstTags = new HashSet();
        firstTags.add(tag1);
        firstTags.add(tag2);
        
        HashSet secondTags = new HashSet();
        secondTags.add(tag3);
        secondTags.add(tag4);
        
        byte[] msg1 = "msg 1".getBytes();
        byte[] msg2 = "msg 2".getBytes();
        byte[] msg3 = "msg 3".getBytes();
        byte[] msg4 = "msg 4".getBytes();
        byte[] msg5 = "msg 5".getBytes();
        
        byte emsg1[] = _context.elGamalAESEngine().encrypt(msg1, pubKey, curKey, firstTags, 64);
        
        byte dmsg1[] = _context.elGamalAESEngine().decrypt(emsg1, privKey);
        assertTrue(DataHelper.eq(dmsg1, msg1));
        
        
        
        TagSetHandle tsh = _context.sessionKeyManager().tagsDelivered(pubKey, curKey, firstTags);
        _context.sessionKeyManager().tagsAcked(pubKey, curKey, tsh);
        
        curKey = _context.sessionKeyManager().getCurrentKey(pubKey);
        SessionTag curTag = _context.sessionKeyManager().consumeNextAvailableTag(pubKey, curKey);
        
        assertNotNull(curTag);
        
        byte emsg2[] = _context.elGamalAESEngine().encrypt(msg2, pubKey, curKey, null, curTag, 64);
        
        byte dmsg2[] = _context.elGamalAESEngine().decrypt(emsg2, privKey);
        assertTrue(DataHelper.eq(dmsg2, msg2));
        
        
        
        curKey = _context.sessionKeyManager().getCurrentKey(pubKey);
        curTag = _context.sessionKeyManager().consumeNextAvailableTag(pubKey, curKey);
        
        assertNotNull(curTag);
        assertNotNull(curKey);
        
        byte emsg3[] = _context.elGamalAESEngine().encrypt(msg3, pubKey, curKey, secondTags, curTag, nextKey, 64);
        
        byte dmsg3[] = _context.elGamalAESEngine().decrypt(emsg3, privKey);
        assertTrue(DataHelper.eq(dmsg3, msg3));
        
        
        
        tsh = _context.sessionKeyManager().tagsDelivered(pubKey, nextKey, secondTags); // note nextKey not curKey
        _context.sessionKeyManager().tagsAcked(pubKey, nextKey, tsh);
        
        curKey = _context.sessionKeyManager().getCurrentKey(pubKey);
        curTag = _context.sessionKeyManager().consumeNextAvailableTag(pubKey, curKey);
        
        assertNotNull(curTag);
        assertNotNull(curKey);
        
        byte emsg4[] = _context.elGamalAESEngine().encrypt(msg4, pubKey, curKey, null, curTag, 64);
        
        byte dmsg4[] = _context.elGamalAESEngine().decrypt(emsg4, privKey);
        assertTrue(DataHelper.eq(dmsg4, msg4));
        
        
        
        curKey = _context.sessionKeyManager().getCurrentKey(pubKey);
        curTag = _context.sessionKeyManager().consumeNextAvailableTag(pubKey, curKey);
        
        assertNotNull(curTag);
        assertNotNull(curKey);
        
        byte emsg5[] = _context.elGamalAESEngine().encrypt(msg5, pubKey, curKey, null, curTag, 64);
        
        byte dmsg5[] = _context.elGamalAESEngine().decrypt(emsg5, privKey);
        assertTrue(DataHelper.eq(dmsg5, msg5));
        
        
        
    }
    
    
    /**
     *  20 tags every 10 messages, rekey every 50
     */
    public void testLongSession() throws Exception{
        Object keys[] = KeyGenerator.getInstance().generatePKIKeypair();
        PublicKey pubKey = (PublicKey)keys[0];
        PrivateKey privKey = (PrivateKey)keys[1];
        SessionKey curKey = _context.sessionKeyManager().createSession(pubKey);
        
        for (int i = 0; i < 1000; i++) {
            Set tags = null;
            SessionKey nextKey = null;
            curKey = _context.sessionKeyManager().getCurrentKey(pubKey);
            SessionTag curTag = _context.sessionKeyManager().consumeNextAvailableTag(pubKey, curKey);
            
            int availTags = _context.sessionKeyManager().getAvailableTags(pubKey, curKey);
            if ((availTags < 1)) {
                tags = generateNewTags(50);
            } 
            if (i % 50 == 0)
                nextKey = KeyGenerator.getInstance().generateSessionKey();
            
            byte[] msg = ("msg " + i).getBytes();
            
            byte emsg[] = _context.elGamalAESEngine().encrypt(msg, pubKey, curKey, tags, curTag, nextKey, 64);
            
            byte dmsg[] = _context.elGamalAESEngine().decrypt(emsg, privKey);
            assertTrue(DataHelper.eq(dmsg, msg));
            
            if ( (tags != null) && (tags.size() > 0) ) {
                if (nextKey == null) {
                    TagSetHandle tsh = _context.sessionKeyManager().tagsDelivered(pubKey, curKey, tags);
                    _context.sessionKeyManager().tagsAcked(pubKey, curKey, tsh);
                } else {
                    TagSetHandle tsh = _context.sessionKeyManager().tagsDelivered(pubKey, nextKey, tags);
                    _context.sessionKeyManager().tagsAcked(pubKey, nextKey, tsh);
                }
            }
        }
    }
    
    private Set generateNewTags(int numTags) {
        Set tags = new HashSet(numTags);
        for (int i = 0; i < numTags; i++)
            tags.add(new SessionTag(true));
        return tags;
    }
}
