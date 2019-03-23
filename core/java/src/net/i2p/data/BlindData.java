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
     *  @throws IllegalArgumentException on various errors
     */
    public BlindData(I2PAppContext ctx, Destination dest, SigType blindType, String secret) {
        this(ctx, dest.getSigningPublicKey(), blindType, secret);
        _dest = dest;
    }

    /**
     *  @throws IllegalArgumentException on various errors
     */
    public BlindData(I2PAppContext ctx, SigningPublicKey spk, SigType blindType, String secret) {
        _context = ctx;
        _clearSPK = spk;
        _blindType = blindType;
        _secret = secret;
        _authType = 0;
        _authKey = null;
        // defer until needed
        //calculate();
    }

    /**
     *  @return The blinded key for the current day
     */
    public synchronized SigningPublicKey getBlindedPubKey() {
        calculate();
        return _blindSPK;
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
     *  @return 0 for no client auth
     */
    public int getAuthType() {
        return _authType;
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
}
