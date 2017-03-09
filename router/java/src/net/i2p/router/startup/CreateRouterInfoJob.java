package net.i2p.router.startup;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import net.i2p.crypto.SigType;
import net.i2p.data.Base64;
import net.i2p.data.Certificate;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.KeyCertificate;
import net.i2p.data.PrivateKey;
import net.i2p.data.PrivateKeyFile;
import net.i2p.data.PublicKey;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.SimpleDataStructure;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.util.EventLog;
import net.i2p.util.Log;
import net.i2p.util.SecureFileOutputStream;
import net.i2p.util.SystemVersion;

/**
 *  Warning - misnamed. This creates a new RouterIdentity, i.e.
 *  new router keys and hash. It then builds a new RouterInfo
 *  and saves all the keys. This is generally run only once, on a new install.
 *
 */
public class CreateRouterInfoJob extends JobImpl {
    private final Log _log;
    private final Job _next;
    
    public static final String INFO_FILENAME = "router.info";
    public static final String KEYS_FILENAME = "router.keys";
    public static final String KEYS2_FILENAME = "router.keys.dat";
    static final String PROP_ROUTER_SIGTYPE = "router.sigType";
    /** TODO make everybody Ed */
    private static final SigType DEFAULT_SIGTYPE = SystemVersion.isAndroid() ?
                                                   SigType.DSA_SHA1 : SigType.EdDSA_SHA512_Ed25519;

    CreateRouterInfoJob(RouterContext ctx, Job next) {
        super(ctx);
        _next = next;
        _log = ctx.logManager().getLog(CreateRouterInfoJob.class);
    }
    
    public String getName() { return "Create New Router Info"; }
    
    public void runJob() {
        _log.debug("Creating the new router info");
        // create a new router info and store it where LoadRouterInfoJob looks
        synchronized (getContext().router().routerInfoFileLock) {
            createRouterInfo();
        }
        getContext().jobQueue().addJob(_next);
    }
    
    /**
     *  Writes 6 files: router.info (standard RI format),
     *  router.keys.dat, and 4 individual key files under keyBackup/
     *
     *  router.keys.dat file format: This is the
     *  same "eepPriv.dat" format used by the client code,
     *  as documented in PrivateKeyFile.
     *
     *  Old router.keys file format: Note that this is NOT the
     *  same "eepPriv.dat" format used by the client code.
     *<pre>
     *   - Private key (256 bytes)
     *   - Signing Private key (20 bytes)
     *   - Public key (256 bytes)
     *   - Signing Public key (128 bytes)
     *  Total 660 bytes
     *</pre>
     *
     *  Caller must hold Router.routerInfoFileLock.
     */
    RouterInfo createRouterInfo() {
        SigType type = getSigTypeConfig(getContext());
        RouterInfo info = new RouterInfo();
        OutputStream fos1 = null;
        try {
            info.setAddresses(getContext().commSystem().createAddresses());
            // not necessary, in constructor
            //info.setPeers(new HashSet());
            info.setPublished(getCurrentPublishDate(getContext()));
            Object keypair[] = getContext().keyGenerator().generatePKIKeypair();
            PublicKey pubkey = (PublicKey)keypair[0];
            PrivateKey privkey = (PrivateKey)keypair[1];
            SimpleDataStructure signingKeypair[] = getContext().keyGenerator().generateSigningKeys(type);
            SigningPublicKey signingPubKey = (SigningPublicKey)signingKeypair[0];
            SigningPrivateKey signingPrivKey = (SigningPrivateKey)signingKeypair[1];
            RouterIdentity ident = new RouterIdentity();
            Certificate cert = createCertificate(getContext(), signingPubKey);
            ident.setCertificate(cert);
            ident.setPublicKey(pubkey);
            ident.setSigningPublicKey(signingPubKey);
            byte[] padding;
            int padLen = SigningPublicKey.KEYSIZE_BYTES - signingPubKey.length();
            if (padLen > 0) {
                padding = new byte[padLen];
                getContext().random().nextBytes(padding);
                ident.setPadding(padding);
            } else {
                padding = null;
            }
            info.setIdentity(ident);
            Properties stats = getContext().statPublisher().publishStatistics(ident.getHash());
            info.setOptions(stats);
            
            info.sign(signingPrivKey);

            if (!info.isValid())
                throw new DataFormatException("RouterInfo we just built is invalid: " + info);
            
            // remove router.keys
            (new File(getContext().getRouterDir(), KEYS_FILENAME)).delete();

            // write router.info
            File ifile = new File(getContext().getRouterDir(), INFO_FILENAME);
            fos1 = new BufferedOutputStream(new SecureFileOutputStream(ifile));
            info.writeBytes(fos1);
            
            // write router.keys.dat
            File kfile = new File(getContext().getRouterDir(), KEYS2_FILENAME);
            PrivateKeyFile pkf = new PrivateKeyFile(kfile, pubkey, signingPubKey, cert,
                                                    privkey, signingPrivKey, padding);
            pkf.write();
            
            // set or overwrite old random keys
            Map<String, String> map = new HashMap<String, String>(2);
            byte rk[] = new byte[32];
            getContext().random().nextBytes(rk);
            map.put(Router.PROP_IB_RANDOM_KEY, Base64.encode(rk));
            getContext().random().nextBytes(rk);
            map.put(Router.PROP_OB_RANDOM_KEY, Base64.encode(rk));
            getContext().router().saveConfig(map, null);

            getContext().keyManager().setKeys(pubkey, privkey, signingPubKey, signingPrivKey);
            
            if (_log.shouldLog(Log.INFO))
                _log.info("Router info created and stored at " + ifile.getAbsolutePath() + " with private keys stored at " + kfile.getAbsolutePath() + " [" + info + "]");
            getContext().router().eventLog().addEvent(EventLog.REKEYED, ident.calculateHash().toBase64());
        } catch (GeneralSecurityException gse) {
            _log.log(Log.CRIT, "Error building the new router information", gse);
        } catch (DataFormatException dfe) {
            _log.log(Log.CRIT, "Error building the new router information", dfe);
        } catch (IOException ioe) {
            _log.log(Log.CRIT, "Error writing out the new router information", ioe);
        } finally {
            if (fos1 != null) try { fos1.close(); } catch (IOException ioe) {}
        }
        return info;
    }
    
    /**
     *  The configured SigType to expect on read-in
     *  @since 0.9.16
     */
    public static SigType getSigTypeConfig(RouterContext ctx) {
        SigType cstype = DEFAULT_SIGTYPE;
        String sstype = ctx.getProperty(PROP_ROUTER_SIGTYPE);
        if (sstype != null) {
            SigType ntype = SigType.parseSigType(sstype);
            if (ntype != null)
                cstype = ntype;
        }
        // fallback?
        if (cstype != SigType.DSA_SHA1 && !cstype.isAvailable())
            cstype = SigType.DSA_SHA1;
        return cstype;
    }
    
    /**
     * We probably don't want to expose the exact time at which a router published its info.
     * perhaps round down to the nearest minute?  10 minutes?  30 minutes?  day?
     *
     */
    static long getCurrentPublishDate(RouterContext context) {
        //_log.info("Setting published date to /now/");
        return context.clock().now();
    }

    /**
     *  Only called at startup via LoadRouterInfoJob and RebuildRouterInfoJob.
     *  Not called by periodic RepublishLocalRouterInfoJob.
     *  We don't want to change the cert on the fly as it changes the router hash.
     *  RouterInfo.isHidden() checks the capability, but RouterIdentity.isHidden() checks the cert.
     *  There's no reason to ever add a hidden cert?
     *
     *  @return the certificate for a new RouterInfo - probably a null cert.
     *  @since 0.9.16 moved from Router
     */
    static Certificate createCertificate(RouterContext ctx, SigningPublicKey spk) {
        if (spk.getType() != SigType.DSA_SHA1)
            return new KeyCertificate(spk);
        if (ctx.getBooleanProperty(Router.PROP_HIDDEN))
            return new Certificate(Certificate.CERTIFICATE_TYPE_HIDDEN, null);
        return Certificate.NULL_CERT;
    }
}
