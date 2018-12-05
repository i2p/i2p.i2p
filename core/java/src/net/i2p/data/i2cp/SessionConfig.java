package net.i2p.data.i2cp;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;
import java.util.Map;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.crypto.DSAEngine;
import net.i2p.crypto.SigType;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.DataStructureImpl;
import net.i2p.data.Destination;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.util.Clock;
import net.i2p.util.Log;
import net.i2p.util.OrderedProperties;

/**
 * Defines the information a client must provide to create a session
 *
 * @author jrandom
 */
public class SessionConfig extends DataStructureImpl {
    private Destination _destination;
    private Signature _signature;
    private Date _creationDate;
    private Properties _options;

    /**
     *  Seconds since epoch, NOT ms
     *  @since 0.9.38
     */
    public static final String PROP_OFFLINE_EXPIRATION = "i2cp.leaseSetOfflineExpiration";

    /**
     *  Base 64, optionally preceded by sig type and ':', default DSA-SHA1
     *  @since 0.9.38
     */
    public static final String PROP_TRANSIENT_KEY = "i2cp.leaseSetTransientPublicKey";

    /**
     *  Base 64, optionally preceded by sig type and ':', default DSA-SHA1
     *  @since 0.9.38
     */
    public static final String PROP_OFFLINE_SIGNATURE = "i2cp.leaseSetOfflineSignature";

    /** 
     * If the client authorized this session more than the specified period ago, 
     * refuse it, since it may be a replay attack.
     *
     * Really? See also ClientManager.REQUEST_LEASESET_TIMEOUT.
     * If I2CP replay attacks are a thing, there's a lot more to do.
     */
    private final static long OFFSET_VALIDITY = 3*60*1000;

    public SessionConfig() {
        this(null);
    }

    public SessionConfig(Destination dest) {
        _destination = dest;
        _creationDate = new Date(Clock.getInstance().now());
    }

    /**
     * Retrieve the destination for which this session is supposed to connect
     *
     * @return Destination for this session
     */
    public Destination getDestination() {
        return _destination;
    }

    /**
     * Determine when this session was authorized by the destination (so we can
     * prevent replay attacks)
     *
     * @return Date
     */
    public Date getCreationDate() {
        return _creationDate;
    }

    public void setCreationDate(Date date) {
        _creationDate = date;
    }

    /**
     * Retrieve any configuration options for the session
     * 
     * @return Properties of this session
     */
    public Properties getOptions() {
        return _options;
    }

    /**
     * Configure the session with the given options;
     * keys and values 255 bytes (not chars) max each
     *
     * Defaults in SessionConfig options are, in general, NOT honored.
     * Defaults are not serialized out-of-JVM, and the router does not recognize defaults in-JVM.
     * Client side must promote defaults to the primary map.
     *
     * Does NOT make a copy.
     *
     * @param options Properties for this session
     */
    public void setOptions(Properties options) {
        _options = options;
    }

    public Signature getSignature() {
        return _signature;
    }

    public void setSignature(Signature sig) {
        _signature = sig;
    }

    /**
     *  Set the offline signing data.
     *  Does NOT validate the signature.
     *  Must be called AFTER setOptions(). Will throw ISE otherwise.
     *  Side effect - modifies options.
     *
     *  @throws IllegalStateException
     *  @since 0.9.38
     */
    public void setOfflineSignature(long expires, SigningPublicKey transientSPK, Signature offlineSig) {
        if (_options == null)
            throw new IllegalStateException();
        _options.setProperty(PROP_OFFLINE_EXPIRATION, Long.toString(expires / 1000));
        _options.setProperty(PROP_TRANSIENT_KEY,
                             transientSPK.getType().getCode() + ":" + transientSPK.toBase64());
        _options.setProperty(PROP_OFFLINE_SIGNATURE, offlineSig.toBase64());
    }

    /**
     *  Get the offline expiration
     *  @return Java time (ms) or 0 if not initialized or does not have offline keys
     *  @since 0.9.38
     */
    public long getOfflineExpiration() {
        if (_options == null)
            return 0;
        String s = _options.getProperty(PROP_OFFLINE_EXPIRATION);
        if (s == null)
            return 0;
        try {
            return Long.parseLong(s) * 1000;
        } catch (NumberFormatException nfe) {
            return 0;
        }
    }

    /**
     *  @return null on error or if not initialized or does not have offline keys
     *  @since 0.9.38
     */
    public SigningPublicKey getTransientSigningPublicKey() {
        if (_options == null || _destination == null)
            return null;
        String s = _options.getProperty(PROP_TRANSIENT_KEY);
        if (s == null)
            return null;
        int colon = s.indexOf(':');
        SigType type;
        if (colon > 0) {
            String stype = s.substring(0, colon);
            type = SigType.parseSigType(stype);
            if (type == null)
                return null;
            s = s.substring(colon + 1);
        } else {
            type = SigType.DSA_SHA1;
        }
        SigningPublicKey rv = new SigningPublicKey(type);
        try {
            rv.fromBase64(s);
            return rv;
        } catch (DataFormatException dfe) {
            return null;
        }
    }

    /**
     *  @return null on error or if not initialized or does not have offline keys
     *  @since 0.9.38
     */
    public Signature getOfflineSignature() {
        if (_options == null || _destination == null)
            return null;
        String s = _options.getProperty(PROP_OFFLINE_SIGNATURE);
        if (s == null)
            return null;
        Signature rv = new Signature(_destination.getSigningPublicKey().getType());
        try {
            rv.fromBase64(s);
            return rv;
        } catch (DataFormatException dfe) {
            return null;
        }
    }

    /**
     * Sign the structure using the supplied private key 
     *
     * @param signingKey SigningPrivateKey to sign with.
     *                   If offline data is set, must be with the transient key.
     * @throws DataFormatException
     */
    public void signSessionConfig(SigningPrivateKey signingKey) throws DataFormatException {
        byte data[] = getBytes();
        if (data == null) throw new DataFormatException("Unable to retrieve bytes for signing");
        if (signingKey == null)
            throw new DataFormatException("No signing key");
        _signature = DSAEngine.getInstance().sign(data, signingKey);
        if (_signature == null)
            throw new DataFormatException("Signature failed with " + signingKey.getType() + " key");
    }

    /**
     * Verify that the signature matches the destination's signing public key.
     *
     * Note that this also returns false if the creation date is too far in the
     * past or future. See tooOld() and getCreationDate().
     *
     * As of 0.9.38, validates the offline signature if included.
     *
     * @return true only if the signature matches
     */
    public boolean verifySignature() {
        if (getSignature() == null) {
            //if (_log.shouldLog(Log.WARN)) _log.warn("Signature is null!");
            return false;
        }
        if (getDestination() == null) {
            //if (_log.shouldLog(Log.WARN)) _log.warn("Destination is null!");
            return false;
        }
        if (getCreationDate() == null) {
            //if (_log.shouldLog(Log.WARN)) _log.warn("Date is null!");
            return false;
        }
        if (tooOld()) {
            //if (_log.shouldLog(Log.WARN)) _log.warn("Too old!");
            return false;
        }
        byte data[] = getBytes();
        if (data == null) {
            //if (_log.shouldLog(Log.WARN)) _log.warn("Bytes could not be found");
            return false;
        }

        SigningPublicKey spk = getTransientSigningPublicKey();
        if (spk != null) {
            // validate offline sig
            long expires = getOfflineExpiration();
            if (expires < _creationDate.getTime())
                return false;
            Signature sig = getOfflineSignature();
            if (sig == null)
                return false;
            ByteArrayOutputStream baos = new ByteArrayOutputStream(128);
            try {
                DataHelper.writeLong(baos, 4, expires / 1000);
                DataHelper.writeLong(baos, 2, spk.getType().getCode());
                spk.writeBytes(baos);
            } catch (IOException ioe) {
                return false;
            } catch (DataFormatException dfe) {
                return false;
            }
            byte[] odata = baos.toByteArray();
            boolean ok = DSAEngine.getInstance().verifySignature(sig, odata, 0, odata.length,
                                                                 _destination.getSigningPublicKey());
            if (!ok)
                return false;
        } else {
            spk = getDestination().getSigningPublicKey();
        }

        boolean ok = DSAEngine.getInstance().verifySignature(getSignature(), data, spk);
        if (!ok) {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(SessionConfig.class);
            if (log.shouldLog(Log.WARN)) log.warn("DSA signature failed!");
        }
        return ok;
    }

    /**
     *  Misnamed, could be too old or too far in the future.
     */
    public boolean tooOld() {
        long now = Clock.getInstance().now();
        long earliestValid = now - OFFSET_VALIDITY;
        long latestValid = now + OFFSET_VALIDITY;
        if (_creationDate == null) return true;
        if (_creationDate.getTime() < earliestValid) return true;
        if (_creationDate.getTime() > latestValid) return true;
        return false;
    }

    private byte[] getBytes() {
        if (_destination == null) return null;
        if (_options == null) return null;
        if (_creationDate == null) return null;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            //_log.debug("PubKey size for destination: " + _destination.getPublicKey().getData().length);
            //_log.debug("SigningKey size for destination: " + _destination.getSigningPublicKey().getData().length);
            _destination.writeBytes(out);
            DataHelper.writeProperties(out, _options, true);  // UTF-8
            DataHelper.writeDate(out, _creationDate);
        } catch (IOException ioe) {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(SessionConfig.class);
            log.error("IOError signing", ioe);
            return null;
        } catch (DataFormatException dfe) {
            Log log = I2PAppContext.getGlobalContext().logManager().getLog(SessionConfig.class);
            log.error("Error writing out the bytes for signing/verification", dfe);
            return null;
        }
        return out.toByteArray();
    }

    public void readBytes(InputStream rawConfig) throws DataFormatException, IOException {
        _destination = Destination.create(rawConfig);
        _options = DataHelper.readProperties(rawConfig);
        _creationDate = DataHelper.readDate(rawConfig);
        _signature = new Signature(_destination.getSigningPublicKey().getType());
        _signature.readBytes(rawConfig);
    }

    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if ((_destination == null) || (_options == null) || (_signature == null) || (_creationDate == null))
            throw new DataFormatException("Not enough data to create the session config");
        _destination.writeBytes(out);
        DataHelper.writeProperties(out, _options, true);  // UTF-8
        DataHelper.writeDate(out, _creationDate);
        _signature.writeBytes(out);
    }

    @Override
    public boolean equals(Object object) {
        if ((object != null) && (object instanceof SessionConfig)) {
            SessionConfig cfg = (SessionConfig) object;
            return DataHelper.eq(getSignature(), cfg.getSignature())
                   && DataHelper.eq(getDestination(), cfg.getDestination())
                   && DataHelper.eq(getCreationDate(), cfg.getCreationDate())
                   && DataHelper.eq(getOptions(), cfg.getOptions());
        }
         
        return false;
    }

    @Override
    public int hashCode() {
        return _signature != null ? _signature.hashCode() : 0;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder("[SessionConfig: ");
        buf.append("\n\tDestination: ").append(getDestination());
        buf.append("\n\tSignature: ").append(getSignature());
        buf.append("\n\tCreation Date: ").append(getCreationDate());
        buf.append("\n\tOptions: #: ").append(_options.size());
        Properties sorted = new OrderedProperties();
        sorted.putAll(_options);
        for (Map.Entry<Object, Object> e : sorted.entrySet()) {
            String key = (String) e.getKey();
            String val = (String) e.getValue();
            buf.append("\n\t\t[").append(key).append("] = [").append(val).append("]");
        }
        buf.append("]");
        return buf.toString();
    }
}
