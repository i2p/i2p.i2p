package net.i2p.router.crypto.ratchet;

import java.io.IOException;
import java.io.Writer;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final AtomicInteger _elgCounter = new AtomicInteger();
    private final AtomicInteger _ecCounter = new AtomicInteger();
    // ElG is about this much slower than EC
    private static final int ELG_SLOW_FACTOR = 5;
    private static final int RESTART_COUNTERS = 500;

    public MuxedSKM(TransientSessionKeyManager elg, RatchetSKM ec) {
        _elg = elg;
        _ec = ec;
    }

    public TransientSessionKeyManager getElgSKM() { return _elg; }

    public RatchetSKM getECSKM() { return _ec; }

    /**
     *  Should we try the Ratchet slow decrypt before ElG slow decrypt?
     *  Adaptive test based on previous mix of traffic for this SKM,
     *  as reported by reportDecryptResult().
     *
     *  @since 0.9.46
     */
    boolean preferRatchet() {
        int ec = _ecCounter.get();
        int elg = _elgCounter.get();
        if (ec > RESTART_COUNTERS / 10 &&
            elg > RESTART_COUNTERS / 10 &&
            ec + elg > RESTART_COUNTERS) {
            _ecCounter.set(0);
            _elgCounter.set(0);
            return true;
        }
        return ec >= elg / ELG_SLOW_FACTOR;
    }

    /**
     *  Report the result of a slow decrypt attempt.
     *
     *  @param isRatchet true for EC, false for ElG
     *  @param success true for successful decrypt
     *  @since 0.9.46
     */
    void reportDecryptResult(boolean isRatchet, boolean success) {
        if (success) {
            if (isRatchet)
                _ecCounter.incrementAndGet();
            else
                _elgCounter.incrementAndGet();
        }
    }

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

    /**
     *  ElG only
     */
    @Override
    public TagSetHandle tagsDelivered(PublicKey target, SessionKey key, Set<SessionTag> sessionTags) {
        EncType type = target.getType();
        if (type == EncType.ELGAMAL_2048)
            return _elg.tagsDelivered(target, key, sessionTags);
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
            RatchetSessionTag rstag = new RatchetSessionTag(tag.getData());
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

    /**
     *  ElG only
     */
    @Override
    public void failTags(PublicKey target, SessionKey key, TagSetHandle ts) {
        EncType type = target.getType();
        if (type == EncType.ELGAMAL_2048)
            _elg.failTags(target, key, ts);
    }

    /**
     *  ElG only
     */
    @Override
    public void tagsAcked(PublicKey target, SessionKey key, TagSetHandle ts) {
        EncType type = target.getType();
        if (type == EncType.ELGAMAL_2048)
            _elg.tagsAcked(target, key, ts);
    }
}
