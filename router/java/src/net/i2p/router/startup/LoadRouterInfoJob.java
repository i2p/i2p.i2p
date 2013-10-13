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
import net.i2p.data.DataFormatException;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.RouterInfo;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

public class LoadRouterInfoJob extends JobImpl {
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
            getContext().jobQueue().addJob(this);
            return;
        } else {
            getContext().router().setRouterInfo(_us);
            getContext().messageHistory().initialize(true);
            getContext().jobQueue().addJob(new BootCommSystemJob(getContext()));
        }
    }
    
    private void loadRouterInfo() {
        String routerInfoFile = getContext().getProperty(Router.PROP_INFO_FILENAME, Router.PROP_INFO_FILENAME_DEFAULT);
        RouterInfo info = null;
        String keyFilename = getContext().getProperty(Router.PROP_KEYS_FILENAME, Router.PROP_KEYS_FILENAME_DEFAULT);
        
        File rif = new File(getContext().getRouterDir(), routerInfoFile);
        boolean infoExists = rif.exists();
        File rkf = new File(getContext().getRouterDir(), keyFilename);
        boolean keysExist = rkf.exists();
        
        InputStream fis1 = null;
        InputStream fis2 = null;
        try {
            // if we have a routerinfo but no keys, things go bad in a hurry:
            // CRIT   ...rkdb.PublishLocalRouterInfoJob: Internal error - signing private key not known?  rescheduling publish for 30s
            // CRIT      net.i2p.router.Router         : Internal error - signing private key not known?  wtf
            // CRIT   ...sport.udp.EstablishmentManager: Error in the establisher java.lang.NullPointerException
            // at net.i2p.router.transport.udp.PacketBuilder.buildSessionConfirmedPacket(PacketBuilder.java:574)
            // so pretend the RI isn't there if there is no keyfile
            if (infoExists && keysExist) {
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
            
            if (keysExist) {
                fis2 = new BufferedInputStream(new FileInputStream(rkf));
                PrivateKey privkey = new PrivateKey();
                privkey.readBytes(fis2);
                if (shouldRebuild(privkey)) {
                    _us = null;
                    // windows... close before deleting
                    if (fis1 != null) {
                        try { fis1.close(); } catch (IOException ioe) {}
                        fis1 = null;
                    }
                    try { fis2.close(); } catch (IOException ioe) {}
                    fis2 = null;
                    rif.delete();
                    rkf.delete();
                    return;
                }
                SigningPrivateKey signingPrivKey = new SigningPrivateKey();
                signingPrivKey.readBytes(fis2);
                PublicKey pubkey = new PublicKey();
                pubkey.readBytes(fis2);
                SigningPublicKey signingPubKey = new SigningPublicKey();
                signingPubKey.readBytes(fis2);
                
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
            if (fis2 != null) {
                try { fis2.close(); } catch (IOException ioe2) {}
                fis2 = null;
            }
            rif.delete();
            rkf.delete();
        } catch (DataFormatException dfe) {
            _log.log(Log.CRIT, "Corrupt router info or keys at " + rif.getAbsolutePath() + " / " + rkf.getAbsolutePath(), dfe);
            _us = null;
            // windows... close before deleting
            if (fis1 != null) {
                try { fis1.close(); } catch (IOException ioe) {}
                fis1 = null;
            }
            if (fis2 != null) {
                try { fis2.close(); } catch (IOException ioe) {}
                fis2 = null;
            }
            rif.delete();
            rkf.delete();
        } finally {
            if (fis1 != null) try { fis1.close(); } catch (IOException ioe) {}
            if (fis2 != null) try { fis2.close(); } catch (IOException ioe) {}
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
}
