package net.i2p.router.startup;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import net.i2p.crypto.KeyGenerator;
import net.i2p.crypto.SigType;
import net.i2p.data.Certificate;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.router.RouterPrivateKeyFile;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 *  Run once or twice at startup by StartupJob,
 *  and then runs BootCommSystemJob
 */
class LoadRouterInfoJob extends JobImpl {
    private final Log _log;
    private RouterInfo _us;
    private static final AtomicBoolean _keyLengthChecked = new AtomicBoolean();
    
    public LoadRouterInfoJob(RouterContext ctx) {
        super(ctx);
        _log = ctx.logManager().getLog(LoadRouterInfoJob.class);
    }
    
    public String getName() { return "Load Router Info"; }
    
    public void runJob() {
        synchronized (getContext().router().routerInfoFileLock) {
            loadRouterInfo();
        }
        if (_us == null) {
            RebuildRouterInfoJob r = new RebuildRouterInfoJob(getContext());
            r.rebuildRouterInfo(false);
            // run a second time
            getContext().jobQueue().addJob(this);
            return;
        } else {
            getContext().router().setRouterInfo(_us);
            getContext().messageHistory().initialize(true);
            getContext().jobQueue().addJob(new BootCommSystemJob(getContext()));
        }
    }
    
    /**
     *  Loads router.info and either router.keys.dat or router.keys.
     *
     *  See CreateRouterInfoJob for file formats
     */
    private void loadRouterInfo() {
        RouterInfo info = null;
        File rif = new File(getContext().getRouterDir(), CreateRouterInfoJob.INFO_FILENAME);
        boolean infoExists = rif.exists();
        File rkf = new File(getContext().getRouterDir(), CreateRouterInfoJob.KEYS_FILENAME);
        boolean keysExist = rkf.exists();
        File rkf2 = new File(getContext().getRouterDir(), CreateRouterInfoJob.KEYS2_FILENAME);
        boolean keys2Exist = rkf2.exists();
        
        InputStream fis1 = null;
        try {
            // if we have a routerinfo but no keys, things go bad in a hurry:
            // CRIT   ...rkdb.PublishLocalRouterInfoJob: Internal error - signing private key not known?  rescheduling publish for 30s
            // CRIT      net.i2p.router.Router         : Internal error - signing private key not known?  wtf
            // CRIT   ...sport.udp.EstablishmentManager: Error in the establisher java.lang.NullPointerException
            // at net.i2p.router.transport.udp.PacketBuilder.buildSessionConfirmedPacket(PacketBuilder.java:574)
            // so pretend the RI isn't there if there is no keyfile
            if (infoExists && (keys2Exist || keysExist)) {
                fis1 = new BufferedInputStream(new FileInputStream(rif));
                info = new RouterInfo();
                info.readBytes(fis1);
                // Catch this here before it all gets worse
                if (!info.isValid())
                    throw new DataFormatException("Our RouterInfo has a bad signature");
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Reading in routerInfo from " + rif.getAbsolutePath() + " and it has " + info.getAddresses().size() + " addresses");
                _us = info;
            }
            
            if (keys2Exist || keysExist) {
                KeyData kd = readKeyData(rkf, rkf2);
                PublicKey pubkey = kd.routerIdentity.getPublicKey();
                SigningPublicKey signingPubKey = kd.routerIdentity.getSigningPublicKey();
                PrivateKey privkey = kd.privateKey;
                SigningPrivateKey signingPrivKey = kd.signingPrivateKey;
                SigType stype = signingPubKey.getType();

                // check if the sigtype config changed
                SigType cstype = CreateRouterInfoJob.getSigTypeConfig(getContext());
                boolean sigTypeChanged = stype != cstype;

                if (sigTypeChanged || shouldRebuild(privkey)) {
                    if (sigTypeChanged)
                        _log.logAlways(Log.WARN, "Rebuilding RouterInfo with new signature type " + cstype);
                    _us = null;
                    // windows... close before deleting
                    if (fis1 != null) {
                        try { fis1.close(); } catch (IOException ioe) {}
                        fis1 = null;
                    }
                    rif.delete();
                    rkf.delete();
                    rkf2.delete();
                    return;
                }
                
                getContext().keyManager().setKeys(pubkey, privkey, signingPubKey, signingPrivKey);
            }
        } catch (IOException ioe) {
            _log.log(Log.CRIT, "Error reading the router info from " + rif.getAbsolutePath() + " and the keys from " + rkf.getAbsolutePath(), ioe);
            _us = null;
            // windows... close before deleting
            if (fis1 != null) {
                try { fis1.close(); } catch (IOException ioe2) {}
                fis1 = null;
            }
            rif.delete();
            rkf.delete();
            rkf2.delete();
        } catch (DataFormatException dfe) {
            _log.log(Log.CRIT, "Corrupt router info or keys at " + rif.getAbsolutePath() + " / " + rkf.getAbsolutePath(), dfe);
            _us = null;
            // windows... close before deleting
            if (fis1 != null) {
                try { fis1.close(); } catch (IOException ioe) {}
                fis1 = null;
            }
            rif.delete();
            rkf.delete();
            rkf2.delete();
        } finally {
            if (fis1 != null) try { fis1.close(); } catch (IOException ioe) {}
        }
    }

    /**
     *  Does our RI private key length match the configuration?
     *  If not, return true.
     *  @since 0.9.8
     */
    private boolean shouldRebuild(PrivateKey privkey) {
        // Prevent returning true more than once, ever.
        // If we are called a second time, it's probably because we failed
        // to delete router.keys for some reason.
        if (!_keyLengthChecked.compareAndSet(false, true))
            return false;
        byte[] pkd = privkey.getData();
        boolean haslong = false;
        for (int i = 0; i < 8; i++) {
            if (pkd[i] != 0) {
                haslong = true;
                break;
            }
        }
        boolean uselong = getContext().keyGenerator().useLongElGamalExponent();
        // transition to a longer key (update to 0.9.8)
        if (uselong && !haslong)
            _log.logAlways(Log.WARN, "Rebuilding RouterInfo with longer key");
        // transition to a shorter key, should be rare (copy files to different hardware,
        // jbigi broke, user overrides in advanced config, ...)
        if (!uselong && haslong)
            _log.logAlways(Log.WARN, "Rebuilding RouterInfo with faster key");
        return uselong != haslong;
    }

    /** @since 0.9.16 */
    public static class KeyData {
        public final RouterIdentity routerIdentity;
        public final PrivateKey privateKey;
        public final SigningPrivateKey signingPrivateKey;

        public KeyData(RouterIdentity ri, PrivateKey pk, SigningPrivateKey spk) {
            routerIdentity = ri;
            privateKey = pk;
            signingPrivateKey = spk;
        }
    }

    /**
     *  @param rkf1 in router.keys format, tried second
     *  @param rkf2 in eepPriv.dat format, tried first
     *  @return non-null, throws IOE if neither exisits
     *  @since 0.9.16
     */
    public static KeyData readKeyData(File rkf1, File rkf2) throws DataFormatException, IOException {
        RouterIdentity ri;
        PrivateKey privkey;
        SigningPrivateKey signingPrivKey;
        if (rkf2.exists()) {
            RouterPrivateKeyFile pkf = new RouterPrivateKeyFile(rkf2);
            ri = pkf.getRouterIdentity();
            if (!pkf.validateKeyPairs())
                throw new DataFormatException("Key pairs invalid");
            privkey = pkf.getPrivKey();
            signingPrivKey = pkf.getSigningPrivKey();
        } else {
            InputStream fis = null;
            try {
                fis = new BufferedInputStream(new FileInputStream(rkf1));
                privkey = new PrivateKey();
                privkey.readBytes(fis);
                signingPrivKey = new SigningPrivateKey();
                signingPrivKey.readBytes(fis);
                PublicKey pubkey = new PublicKey();
                pubkey.readBytes(fis);
                SigningPublicKey signingPubKey = new SigningPublicKey();
                signingPubKey.readBytes(fis);

                // validate
                try {
                    if (!pubkey.equals(KeyGenerator.getPublicKey(privkey)))
                        throw new DataFormatException("Key pairs invalid");
                    if (!signingPubKey.equals(KeyGenerator.getSigningPublicKey(signingPrivKey)))
                        throw new DataFormatException("Key pairs invalid");
                } catch (IllegalArgumentException iae) {
                    throw new DataFormatException("Key pairs invalid", iae);
                }

                ri = new RouterIdentity();
                ri.setPublicKey(pubkey);
                ri.setSigningPublicKey(signingPubKey);
                ri.setCertificate(Certificate.NULL_CERT);
            } finally {
                if (fis != null) try { fis.close(); } catch (IOException ioe) {}
            }
        }
        return new KeyData(ri, privkey, signingPrivKey);
    }
}
