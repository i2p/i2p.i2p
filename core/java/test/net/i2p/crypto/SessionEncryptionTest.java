package net.i2p.crypto;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.PublicKey;
import net.i2p.data.PrivateKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.data.DataHelper;

import net.i2p.util.Log;
import net.i2p.util.Clock;

import java.util.HashSet;
import java.util.Set;

/**
 *
 * session key management unit tests:
 *
 *		Run	tagsIncluded	useTag	rekey
 * // no sessions
 * 		1	no		no	no
 *		2	no		no	no
 * // session
 * 		3	yes (2)		no	no
 * 		4	no		yes	no
 * 		5	yes (2)		yes	no
 * 		6	no		yes	no
 * 		7	no		yes	no
 * // rekeying
 * 		8	yes (2)		no	no
 * 		9	no		yes	no
 * 		10	yes (2)		yes	yes
 * 		11	no		yes	no
 * 		12	no		yes	no
 * // long session
 *		13-1000	20 tags every 10 messages, rekey every 50
 */
public class SessionEncryptionTest {
    private final static Log _log = new Log(SessionEncryptionTest.class);
    public static void main(String args[]) {
	SessionEncryptionTest test = new SessionEncryptionTest();
	try {
	    //test.testNoSessions();
	    //test.testSessions();
	    //test.testRekeying();
	    test.testLongSession();
	} catch (Throwable t) {
	    _log.error("Error running tests", t);
	}
	try { Thread.sleep(60*1000); } catch (InterruptedException ie) {}
    }
   
    /**
     *		Run	tagsIncluded	useTag	rekey
     * 		1	no		no	no
     *		2	no		no	no
     */
    public void testNoSessions() throws Exception {
	Object keys[] = KeyGenerator.getInstance().generatePKIKeypair();
	PublicKey pubKey = (PublicKey)keys[0];
	PrivateKey privKey = (PrivateKey)keys[1];
	SessionKey curKey = SessionKeyManager.getInstance().createSession(pubKey);
	
	byte[] msg1 = "msg 1".getBytes();
	byte[] msg2 = "msg 2".getBytes();

	byte emsg1[] = ElGamalAESEngine.encrypt(msg1, pubKey, curKey, 64);
	byte dmsg1[] = ElGamalAESEngine.decrypt(emsg1, privKey);
	if (DataHelper.eq(dmsg1, msg1)) 
	    _log.info("PASSED: No sessions msg 1");
	else
	    _log.error("FAILED: No sessions msg 1");
	
	byte emsg2[] = ElGamalAESEngine.encrypt(msg2, pubKey, curKey, 64);
	byte dmsg2[] = ElGamalAESEngine.decrypt(emsg2, privKey);
	if (DataHelper.eq(dmsg2, msg2)) 
	    _log.info("PASSED: No sessions msg 2");
	else
	    _log.error("FAILED: No sessions msg 2");
   }
   
    /**
     *		Run	tagsIncluded	useTag	rekey
     * 		1	yes (2)		no	no
     * 		2	no		yes	no
     * 		3	yes (2)		yes	no
     * 		4	no		yes	no
     * 		5	no		yes	no
     */
    public void testSessions() throws Exception {
	Object keys[] = KeyGenerator.getInstance().generatePKIKeypair();
	PublicKey pubKey = (PublicKey)keys[0];
	PrivateKey privKey = (PrivateKey)keys[1];
	SessionKey curKey = SessionKeyManager.getInstance().createSession(pubKey);
	
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

	byte emsg1[] = ElGamalAESEngine.encrypt(msg1, pubKey, curKey, firstTags, 64);
	byte dmsg1[] = ElGamalAESEngine.decrypt(emsg1, privKey);
	if (DataHelper.eq(dmsg1, msg1)) 
	    _log.info("PASSED: Sessions msg 1");
	else {
	    _log.error("FAILED: Sessions msg 1");
	    return;
	}
	
	SessionKeyManager.getInstance().tagsDelivered(pubKey, curKey, firstTags);
	
	curKey = SessionKeyManager.getInstance().getCurrentKey(pubKey);
	SessionTag curTag = SessionKeyManager.getInstance().consumeNextAvailableTag(pubKey, curKey);
	if (curTag == null) {
	    _log.error("Not able to consume next tag for message 2");
	    return;
	}
	
	byte emsg2[] = ElGamalAESEngine.encrypt(msg2, pubKey, curKey, null, curTag, 64);
	byte dmsg2[] = ElGamalAESEngine.decrypt(emsg2, privKey);
	if (DataHelper.eq(dmsg2, msg2)) 
	    _log.info("PASSED: Sessions msg 2");
	else {
	    _log.error("FAILED: Sessions msg 2");
	    return;
	}
	
	curKey = SessionKeyManager.getInstance().getCurrentKey(pubKey);
	curTag = SessionKeyManager.getInstance().consumeNextAvailableTag(pubKey, curKey);
	
	if (curTag == null) {
	    _log.error("Not able to consume next tag for message 3");
	    return;
	}
	if (curKey == null) {
	    _log.error("Not able to consume next KEY for message 3");
	    return;
	}
	
	byte emsg3[] = ElGamalAESEngine.encrypt(msg3, pubKey, curKey, secondTags, curTag, 64);
	byte dmsg3[] = ElGamalAESEngine.decrypt(emsg3, privKey);
	if (DataHelper.eq(dmsg3, msg3)) 
	    _log.info("PASSED: Sessions msg 3");
	else {
	    _log.error("FAILED: Sessions msg 3");
	    return;
	}
	
	SessionKeyManager.getInstance().tagsDelivered(pubKey, curKey, secondTags);
	
	curKey = SessionKeyManager.getInstance().getCurrentKey(pubKey);
	curTag = SessionKeyManager.getInstance().consumeNextAvailableTag(pubKey, curKey);
	
	if (curTag == null) {
	    _log.error("Not able to consume next tag for message 4");
	    return;
	}
	if (curKey == null) {
	    _log.error("Not able to consume next KEY for message 4");
	    return;
	}
	
	byte emsg4[] = ElGamalAESEngine.encrypt(msg4, pubKey, curKey, null, curTag, 64);
	byte dmsg4[] = ElGamalAESEngine.decrypt(emsg4, privKey);
	if (DataHelper.eq(dmsg4, msg4)) 
	    _log.info("PASSED: Sessions msg 4");
	else {
	    _log.error("FAILED: Sessions msg 4");
	    return;
	}
	
	curKey = SessionKeyManager.getInstance().getCurrentKey(pubKey);
	curTag = SessionKeyManager.getInstance().consumeNextAvailableTag(pubKey, curKey);
	
	if (curTag == null) {
	    _log.error("Not able to consume next tag for message 5");
	    return;
	}
	if (curKey == null) {
	    _log.error("Not able to consume next KEY for message 5");
	    return;
	}
	
	byte emsg5[] = ElGamalAESEngine.encrypt(msg5, pubKey, curKey, null, curTag, 64);
	byte dmsg5[] = ElGamalAESEngine.decrypt(emsg5, privKey);
	if (DataHelper.eq(dmsg5, msg5)) 
	    _log.info("PASSED: Sessions msg 5");
	else {
	    _log.error("FAILED: Sessions msg 5");
	    return;
	}
   }
    
    /**
     *		Run	tagsIncluded	useTag	rekey
     * 		1	yes (2)		no	no
     * 		2	no		yes	no
     * 		3	yes (2)		yes	yes
     * 		4	no		yes	no
     * 		5	no		yes	no
     */
    public void testRekeying() throws Exception {
	Object keys[] = KeyGenerator.getInstance().generatePKIKeypair();
	PublicKey pubKey = (PublicKey)keys[0];
	PrivateKey privKey = (PrivateKey)keys[1];
	SessionKey curKey = SessionKeyManager.getInstance().createSession(pubKey);
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

	byte emsg1[] = ElGamalAESEngine.encrypt(msg1, pubKey, curKey, firstTags, 64);
	byte dmsg1[] = ElGamalAESEngine.decrypt(emsg1, privKey);
	if (DataHelper.eq(dmsg1, msg1)) 
	    _log.info("PASSED: Sessions msg 1");
	else {
	    _log.error("FAILED: Sessions msg 1");
	    return;
	}
	
	SessionKeyManager.getInstance().tagsDelivered(pubKey, curKey, firstTags);
	
	curKey = SessionKeyManager.getInstance().getCurrentKey(pubKey);
	SessionTag curTag = SessionKeyManager.getInstance().consumeNextAvailableTag(pubKey, curKey);
	if (curTag == null) {
	    _log.error("Not able to consume next tag for message 2");
	    return;
	}
	
	byte emsg2[] = ElGamalAESEngine.encrypt(msg2, pubKey, curKey, null, curTag, 64);
	byte dmsg2[] = ElGamalAESEngine.decrypt(emsg2, privKey);
	if (DataHelper.eq(dmsg2, msg2)) 
	    _log.info("PASSED: Sessions msg 2");
	else {
	    _log.error("FAILED: Sessions msg 2");
	    return;
	}
	
	curKey = SessionKeyManager.getInstance().getCurrentKey(pubKey);
	curTag = SessionKeyManager.getInstance().consumeNextAvailableTag(pubKey, curKey);
	
	if (curTag == null) {
	    _log.error("Not able to consume next tag for message 3");
	    return;
	}
	if (curKey == null) {
	    _log.error("Not able to consume next KEY for message 3");
	    return;
	}
	
	byte emsg3[] = ElGamalAESEngine.encrypt(msg3, pubKey, curKey, secondTags, curTag, nextKey, 64);
	byte dmsg3[] = ElGamalAESEngine.decrypt(emsg3, privKey);
	if (DataHelper.eq(dmsg3, msg3)) 
	    _log.info("PASSED: Sessions msg 3");
	else {
	    _log.error("FAILED: Sessions msg 3");
	    return;
	}
	
	SessionKeyManager.getInstance().tagsDelivered(pubKey, nextKey, secondTags); // note nextKey not curKey
	
	curKey = SessionKeyManager.getInstance().getCurrentKey(pubKey);
	curTag = SessionKeyManager.getInstance().consumeNextAvailableTag(pubKey, curKey);
	
	if (curTag == null) {
	    _log.error("Not able to consume next tag for message 4");
	    return;
	}
	if (curKey == null) {
	    _log.error("Not able to consume next KEY for message 4");
	    return;
	}
	
	byte emsg4[] = ElGamalAESEngine.encrypt(msg4, pubKey, curKey, null, curTag, 64);
	byte dmsg4[] = ElGamalAESEngine.decrypt(emsg4, privKey);
	if (DataHelper.eq(dmsg4, msg4)) 
	    _log.info("PASSED: Sessions msg 4");
	else {
	    _log.error("FAILED: Sessions msg 4");
	    return;
	}
	
	curKey = SessionKeyManager.getInstance().getCurrentKey(pubKey);
	curTag = SessionKeyManager.getInstance().consumeNextAvailableTag(pubKey, curKey);
	
	if (curTag == null) {
	    _log.error("Not able to consume next tag for message 5");
	    return;
	}
	if (curKey == null) {
	    _log.error("Not able to consume next KEY for message 5");
	    return;
	}
	
	byte emsg5[] = ElGamalAESEngine.encrypt(msg5, pubKey, curKey, null, curTag, 64);
	byte dmsg5[] = ElGamalAESEngine.decrypt(emsg5, privKey);
	if (DataHelper.eq(dmsg5, msg5)) 
	    _log.info("PASSED: Sessions msg 5");
	else {
	    _log.error("FAILED: Sessions msg 5");
	    return;
	}
   }
 
    
    /**
     *	20 tags every 10 messages, rekey every 50
     */
    public void testLongSession() throws Exception {
	int num = 1000;
	long start = Clock.getInstance().now();
	testLongSession(num);
	long end = Clock.getInstance().now();
	long time = end - start;
	float msEach = (float)num / time;
	_log.error("Test long session duration: " + num + " messages in " + time + "ms (or " + msEach + "ms each)");
    }
    
    public void testLongSession(int numMsgs) throws Exception {
	Object keys[] = KeyGenerator.getInstance().generatePKIKeypair();
	PublicKey pubKey = (PublicKey)keys[0];
	PrivateKey privKey = (PrivateKey)keys[1];
	SessionKey curKey = SessionKeyManager.getInstance().createSession(pubKey);
	
	for (int i = 0; i < numMsgs; i++) {
	    Set tags = null;
	    SessionKey nextKey = null;
	    curKey = SessionKeyManager.getInstance().getCurrentKey(pubKey);
	    SessionTag curTag = SessionKeyManager.getInstance().consumeNextAvailableTag(pubKey, curKey);
	    
	    int availTags = SessionKeyManager.getInstance().getAvailableTags(pubKey, curKey);
	    if ((availTags < 1)) {
		tags = generateNewTags(50);
		_log.info("Generating new tags");
	    } else {
		_log.info("Tags already available: " + availTags + " curTag: " + curTag);
	    }
	    if (i % 50 == 0)
		nextKey = KeyGenerator.getInstance().generateSessionKey();
	
	    byte[] msg = ("msg " + i).getBytes();

	    byte emsg[] = ElGamalAESEngine.encrypt(msg, pubKey, curKey, tags, curTag, nextKey, 64);
	    byte dmsg[] = ElGamalAESEngine.decrypt(emsg, privKey);
	    if (DataHelper.eq(dmsg, msg)) 
		_log.info("PASSED: Long session msg " + i);
	    else {
		_log.error("FAILED: Long session msg " + i);
		return;
	    }
	
	    if ( (tags != null) && (tags.size() > 0) ) {
		if (nextKey == null) {
		    SessionKeyManager.getInstance().tagsDelivered(pubKey, curKey, tags);
		} else {
		    SessionKeyManager.getInstance().tagsDelivered(pubKey, nextKey, tags);
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
