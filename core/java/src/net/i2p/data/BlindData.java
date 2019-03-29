package net.i2p.data;

import net.i2p.I2PAppContext;
import net.i2p.crypto.Blinding;
import net.i2p.crypto.SigType;

/**
 * Cache data for Blinding EdDSA keys.
 * PRELIMINARY - Subject to change - see proposal 123
 *
 * @since 0.9.40
 */
public class BlindData {

    private final I2PAppContext _context;
    private final SigningPublicKey _clearSPK;
    private final String _secret;
    private SigningPublicKey _blindSPK;
    private final SigType _blindType;
    private final int _authType;
    private final PrivateKey _authKey;
    private Hash _blindHash;
    private SigningPrivateKey _alpha;
    private Destination _dest;
    private long _routingKeyGenMod;

    /**
     *  @param secret may be null or zero-length
     *  @throws IllegalArgumentException on various errors
     */
    public BlindData(I2PAppContext ctx, Destination dest, SigType blindType, String secret) {
        this(ctx, dest.getSigningPublicKey(), blindType, secret);
        _dest = dest;
    }

    /**
     *  @param secret may be null or zero-length
     *  @throws IllegalArgumentException on various errors
     */
    public BlindData(I2PAppContext ctx, SigningPublicKey spk, SigType blindType, String secret) {
        _context = ctx;
        _clearSPK = spk;
        _blindType = blindType;
        _secret = secret;
        _authType = -1;
        _authKey = null;
        // defer until needed
        //calculate();
    }

    /**
     *  @return The unblinded SPK, non-null
     */
    public SigningPublicKey getUnblindedPubKey() {
        return _clearSPK;
    }

    /**
     *  @return The type of the blinded key
     */
    public SigType getBlindedSigType() {
        return _blindType;
    }

    /**
     *  @return The blinded key for the current day, non-null
     */
    public synchronized SigningPublicKey getBlindedPubKey() {
        calculate();
        return _blindSPK;
    }

    /**
     *  @return The hash of the destination if known, or null
     */
    public synchronized Hash getDestHash() {
        return _dest != null ? _dest.getHash() : null;
    }

    /**
     *  @return The hash of the blinded key for the current day
     */
    public synchronized Hash getBlindedHash() {
        calculate();
        return _blindHash;
    }

    /**
     *  @return Alpha for the current day
     */
    public synchronized SigningPrivateKey getAlpha() {
        calculate();
        return _alpha;
    }

    /**
     *  @return null if unknown
     */
    public synchronized Destination getDestination() {
        return _dest;
    }

    /**
     *  @throws IllegalArgumentException on SigningPublicKey mismatch
     */
    public synchronized void setDestination(Destination d) {
        if (_dest != null) {
            if (!_dest.equals(d))
                throw new IllegalArgumentException("Dest mismatch");
            return;
        }
        if (!d.getSigningPublicKey().equals(_clearSPK))
            throw new IllegalArgumentException("Dest mismatch");
        _dest = d;
    }

    /**
     *  @return null if none
     */
    public String getSecret() {
        return _secret;
    }

    /**
     *  @return -1 for no client auth
     */
    public int getAuthType() {
        return _authType;
    }

    /**
     *  @return null for no client auth
     */
    public PrivateKey getAuthPrivKey() {
        return _authKey;
    }

    private synchronized void calculate() {
        if (_context.isRouterContext()) {
            RoutingKeyGenerator gen = _context.routingKeyGenerator();
            long mod = gen.getLastChanged();
            if (mod == _routingKeyGenMod)
                return;
            _routingKeyGenMod = mod;
        }
        // For now, always calculate in app context,
        // where we don't have a routingKeyGenerator
        // TODO we could cache based on current day
        _alpha = Blinding.generateAlpha(_context, _clearSPK, _secret);
        _blindSPK = Blinding.blind(_clearSPK, _alpha);
        SigType bsigt2 = _blindSPK.getType();
        if (_blindType != bsigt2) {
            throw new IllegalArgumentException("Requested blinded sig type " + _blindType + " supported type " + bsigt2);
        }
        byte[] hashData = new byte[2 + Hash.HASH_LENGTH];
        DataHelper.toLong(hashData, 0, 2, _blindType.getCode());
        System.arraycopy(_blindSPK.getData(), 0, hashData, 2, _blindSPK.length());
        _blindHash = _context.sha().calculateHash(hashData);
    }
    
    @Override
    public synchronized String toString() {
        calculate();
        StringBuilder buf = new StringBuilder(1024);
        buf.append("[BlindData: ");
        buf.append("\n\tSigningPublicKey: ").append(_clearSPK);
        buf.append("\n\tAlpha           : ").append(_alpha);
        buf.append("\n\tBlindedPublicKey: ").append(_blindSPK);
        buf.append("\n\tBlinded Hash    : ").append(_blindHash);
        if (_secret != null)
            buf.append("\n\tSecret          : \"").append(_secret).append('"');
        buf.append("\n\tAuth Type       : ");
        if (_authType >= 0)
            buf.append(_authType);
        else
            buf.append("none");
        if (_authKey != null)
            buf.append("\n\tAuth Key   : ").append(_authKey);
        if (_dest != null)
            buf.append("\n\tDestination: ").append(_dest);
        else
            buf.append("\n\tDestination: unknown");
        buf.append(']');
        return buf.toString();
    }
}
