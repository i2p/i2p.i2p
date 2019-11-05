package net.i2p.router.crypto.ratchet;

import java.io.IOException;
import java.io.Writer;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.crypto.EncType;
import net.i2p.crypto.TagSetHandle;
import net.i2p.crypto.SessionKeyManager;
import net.i2p.data.PublicKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SessionTag;
import net.i2p.router.crypto.TransientSessionKeyManager;

/**
 * Both.
 *
 * @since 0.9.44
 */
public class MuxedSKM extends SessionKeyManager {

    private final TransientSessionKeyManager _elg;
    private final RatchetSKM _ec;

    public MuxedSKM(TransientSessionKeyManager elg, RatchetSKM ec) {
        _elg = elg;
        _ec = ec;
    }

    public TransientSessionKeyManager getElgSKM() { return _elg; }

    public RatchetSKM getECSKM() { return _ec; }

    /**
     *  ElG only
     */
    @Override
    public SessionKey getCurrentKey(PublicKey target) {
        EncType type = target.getType();
        if (type == EncType.ELGAMAL_2048)
            return _elg.getCurrentKey(target);
        return null;
    }

    /**
     *  ElG only
     */
    @Override
    public SessionKey getCurrentOrNewKey(PublicKey target) {
        EncType type = target.getType();
        if (type == EncType.ELGAMAL_2048)
            return _elg.getCurrentOrNewKey(target);
        return null;
    }

    /**
     *  ElG only
     */
    @Override
    public void createSession(PublicKey target, SessionKey key) {
        EncType type = target.getType();
        if (type == EncType.ELGAMAL_2048)
            _elg.createSession(target, key);
        else
            throw new IllegalArgumentException();
    }

    /**
     *  ElG only
     */
    @Override
    public SessionKey createSession(PublicKey target) {
        EncType type = target.getType();
        if (type == EncType.ELGAMAL_2048)
            return _elg.createSession(target);
        return null;
    }

    /**
     *  ElG only
     */
    @Override
    public SessionTag consumeNextAvailableTag(PublicKey target, SessionKey key) {
        EncType type = target.getType();
        if (type == EncType.ELGAMAL_2048)
            return _elg.consumeNextAvailableTag(target, key);
        return null;
    }

    /**
     *  EC only
     */
    public RatchetEntry consumeNextAvailableTag(PublicKey target) {
        EncType type = target.getType();
        if (type == EncType.ECIES_X25519)
            return _ec.consumeNextAvailableTag(target);
        return null;
    }

    @Override
    public int getTagsToSend() { return 0; };

    @Override
    public int getLowThreshold() { return 0; };

    /**
     *  ElG only
     */
    @Override
    public boolean shouldSendTags(PublicKey target, SessionKey key) {
        EncType type = target.getType();
        if (type == EncType.ELGAMAL_2048)
            return _elg.shouldSendTags(target, key);
        return false;
    }

    /**
     *  ElG only
     */
    @Override
    public boolean shouldSendTags(PublicKey target, SessionKey key, int lowThreshold) {
        EncType type = target.getType();
        if (type == EncType.ELGAMAL_2048)
            return _elg.shouldSendTags(target, key, lowThreshold);
        return false;
    }

    @Override
    public int getAvailableTags(PublicKey target, SessionKey key) {
        EncType type = target.getType();
        if (type == EncType.ELGAMAL_2048)
            return _elg.getAvailableTags(target, key);
        if (type == EncType.ECIES_X25519)
            return _ec.getAvailableTags(target, key);
        return 0;
    }

    @Override
    public long getAvailableTimeLeft(PublicKey target, SessionKey key) {
        EncType type = target.getType();
        if (type == EncType.ELGAMAL_2048)
            return _elg.getAvailableTimeLeft(target, key);
        if (type == EncType.ECIES_X25519)
            return _ec.getAvailableTimeLeft(target, key);
        return 0;
    }

    @Override
    public TagSetHandle tagsDelivered(PublicKey target, SessionKey key, Set<SessionTag> sessionTags) {
        EncType type = target.getType();
        if (type == EncType.ELGAMAL_2048)
            return _elg.tagsDelivered(target, key, sessionTags);
        if (type == EncType.ECIES_X25519)
            return _ec.tagsDelivered(target, key, sessionTags);
         return null;
    }

    /**
     *  ElG only
     */
    @Override
    public void tagsReceived(SessionKey key, Set<SessionTag> sessionTags) {
        _elg.tagsReceived(key, sessionTags);
    }

    /**
     *  ElG only
     */
    @Override
    public void tagsReceived(SessionKey key, Set<SessionTag> sessionTags, long expire) {
        _elg.tagsReceived(key, sessionTags, expire);
    }

    @Override
    public SessionKey consumeTag(SessionTag tag) {
        SessionKey rv = _elg.consumeTag(tag);
        if (rv == null) {
            long stag = RatchetPayload.fromLong8(tag.getData(), 0);
            RatchetSessionTag rstag = new RatchetSessionTag(stag);
            rv = _ec.consumeTag(rstag);
        }
        return rv;
    }

    @Override
    public void shutdown() {
        _elg.shutdown();
        _ec.shutdown();
    }

    @Override
    public void renderStatusHTML(Writer out) throws IOException {
        _elg.renderStatusHTML(out);
        _ec.renderStatusHTML(out);
    }

    @Override
    public void failTags(PublicKey target, SessionKey key, TagSetHandle ts) {
        EncType type = target.getType();
        if (type == EncType.ELGAMAL_2048)
            _elg.failTags(target, key, ts);
        else if (type == EncType.ECIES_X25519)
            _ec.failTags(target, key, ts);
    }

    @Override
    public void tagsAcked(PublicKey target, SessionKey key, TagSetHandle ts) {
        EncType type = target.getType();
        if (type == EncType.ELGAMAL_2048)
            _elg.tagsAcked(target, key, ts);
        else if (type == EncType.ECIES_X25519)
            _ec.tagsAcked(target, key, ts);
    }
}
