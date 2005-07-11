package net.i2p.crypto;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.util.Log;

/**
 * Expose the functionality to allow people to write out and read in the 
 * session key and session tag information via streams.  This implementation
 * does not write anywhere except where its told.
 *
 */
public class PersistentSessionKeyManager extends TransientSessionKeyManager {
    private Log _log;
    private Object _yk = YKGenerator.class;

    
    /** 
     * The session key manager should only be constructed and accessed through the 
     * application context.  This constructor should only be used by the 
     * appropriate application context itself.
     *
     */
    public PersistentSessionKeyManager(I2PAppContext context) {
        super(context);
        _log = context.logManager().getLog(PersistentSessionKeyManager.class);
    }
    private PersistentSessionKeyManager() {
        this(null);
    }
    /**
     * Write the session key data to the given stream
     *
     */
    public void saveState(OutputStream out) throws IOException, DataFormatException {
        if (true) return;
        
        Set tagSets = getInboundTagSets();
        Set sessions = getOutboundSessions();
        if (_log.shouldLog(Log.INFO))
            _log.info("Saving state with " + tagSets.size() + " inbound tagSets and " 
                      + sessions.size() + " outbound sessions");

        DataHelper.writeLong(out, 4, tagSets.size());
        for (Iterator iter = tagSets.iterator(); iter.hasNext();) {
            TagSet ts = (TagSet) iter.next();
            writeTagSet(out, ts);
        }
        DataHelper.writeLong(out, 4, sessions.size());
        for (Iterator iter = sessions.iterator(); iter.hasNext();) {
            OutboundSession sess = (OutboundSession) iter.next();
            writeOutboundSession(out, sess);
        }
    }

    /**
     * Load the session key data from the given stream
     *
     */
    public void loadState(InputStream in) throws IOException, DataFormatException {
        int inboundSets = (int) DataHelper.readLong(in, 4);
        Set tagSets = new HashSet(inboundSets);
        for (int i = 0; i < inboundSets; i++) {
            TagSet ts = readTagSet(in);
            tagSets.add(ts);
        }
        int outboundSessions = (int) DataHelper.readLong(in, 4);
        Set sessions = new HashSet(outboundSessions);
        for (int i = 0; i < outboundSessions; i++) {
            OutboundSession sess = readOutboundSession(in);
            sessions.add(sess);
        }

        if (_log.shouldLog(Log.INFO))
            _log.info("Loading state with " + tagSets.size() + " inbound tagSets and " 
                      + sessions.size() + " outbound sessions");
        setData(tagSets, sessions);
    }

    private void writeOutboundSession(OutputStream out, OutboundSession sess) throws IOException, DataFormatException {
        sess.getTarget().writeBytes(out);
        sess.getCurrentKey().writeBytes(out);
        DataHelper.writeDate(out, new Date(sess.getEstablishedDate()));
        DataHelper.writeDate(out, new Date(sess.getLastUsedDate()));
        List sets = sess.getTagSets();
        DataHelper.writeLong(out, 2, sets.size());
        for (Iterator iter = sets.iterator(); iter.hasNext();) {
            TagSet set = (TagSet) iter.next();
            writeTagSet(out, set);
        }
    }

    private void writeTagSet(OutputStream out, TagSet ts) throws IOException, DataFormatException {
        ts.getAssociatedKey().writeBytes(out);
        DataHelper.writeDate(out, new Date(ts.getDate()));
        DataHelper.writeLong(out, 2, ts.getTags().size());
        for (Iterator iter = ts.getTags().iterator(); iter.hasNext();) {
            SessionTag tag = (SessionTag) iter.next();
            out.write(tag.getData());
        }
    }

    private OutboundSession readOutboundSession(InputStream in) throws IOException, DataFormatException {
        PublicKey key = new PublicKey();
        key.readBytes(in);
        SessionKey skey = new SessionKey();
        skey.readBytes(in);
        Date established = DataHelper.readDate(in);
        Date lastUsed = DataHelper.readDate(in);
        int tagSets = (int) DataHelper.readLong(in, 2);
        ArrayList sets = new ArrayList(tagSets);
        for (int i = 0; i < tagSets; i++) {
            TagSet ts = readTagSet(in);
            sets.add(ts);
        }

        return new OutboundSession(key, skey, established.getTime(), lastUsed.getTime(), sets);
    }

    private TagSet readTagSet(InputStream in) throws IOException, DataFormatException {
        SessionKey key = new SessionKey();
        key.readBytes(in);
        Date date = DataHelper.readDate(in);
        int numTags = (int) DataHelper.readLong(in, 2);
        Set tags = new HashSet(numTags);
        for (int i = 0; i < numTags; i++) {
            SessionTag tag = new SessionTag();
            byte val[] = new byte[SessionTag.BYTE_LENGTH];
            int read = DataHelper.read(in, val);
            if (read != SessionTag.BYTE_LENGTH)
                throw new IOException("Unable to fully read a session tag [" + read + " not " + SessionTag.BYTE_LENGTH
                                      + ")");
            tag.setData(val);
            tags.add(tag);
        }
        TagSet ts = new TagSet(tags, key, _context.clock().now());
        ts.setDate(date.getTime());
        return ts;
    }

    public static void main(String args[]) {
        I2PAppContext ctx = new I2PAppContext();
        Log log = ctx.logManager().getLog(PersistentSessionKeyManager.class);
        PersistentSessionKeyManager mgr = (PersistentSessionKeyManager)ctx.sessionKeyManager();
        try {
            mgr.loadState(new FileInputStream("sessionKeys.dat"));
            String state = mgr.renderStatusHTML();
            FileOutputStream fos = new FileOutputStream("sessionKeysBeforeExpire.html");
            fos.write(state.getBytes());
            fos.close();
            int expired = mgr.aggressiveExpire();
            log.error("Expired: " + expired);
            String stateAfter = mgr.renderStatusHTML();
            FileOutputStream fos2 = new FileOutputStream("sessionKeysAfterExpire.html");
            fos2.write(stateAfter.getBytes());
            fos2.close();
        } catch (Throwable t) {
            log.error("Error loading/storing sessionKeys", t);
        }
        try {
            Thread.sleep(3000);
        } catch (Throwable t) { // nop
        }
    }
}