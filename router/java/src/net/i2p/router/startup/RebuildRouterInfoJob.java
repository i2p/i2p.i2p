package net.i2p.router.startup;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

import net.i2p.data.Certificate;
import net.i2p.data.DataFormatException;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.RouterIdentity;
import net.i2p.data.RouterInfo;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.networkdb.kademlia.FloodfillNetworkDatabaseFacade;
import net.i2p.util.Log;

/**
 * If the file router.info.rebuild exists, rebuild the router info and republish.
 * This is useful for dhcp or other situations where the router addresses change -
 * simply create the router.info.rebuild file after modifying router.config and within
 * 45 seconds (the current check frequency), the router info will be rebuilt with new
 * addresses and stats, as well as a new version, then republished.  Afterwards, the
 * router.info.rebuild file is deleted
 *
 */
public class RebuildRouterInfoJob extends JobImpl {
    private Log _log;
    
    private final static long REBUILD_DELAY = 45*1000; // every 30 seconds
    
    public RebuildRouterInfoJob(RouterContext context) {
        super(context);
        _log = context.logManager().getLog(RebuildRouterInfoJob.class);
    }
    
    public String getName() { return "Rebuild Router Info"; }
    
    public void runJob() {
        _log.debug("Testing to rebuild router info");
        String infoFile = getContext().router().getConfigSetting(Router.PROP_INFO_FILENAME);
        if (infoFile == null) {
            _log.debug("Info filename not configured, defaulting to " + Router.PROP_INFO_FILENAME_DEFAULT);
            infoFile = Router.PROP_INFO_FILENAME_DEFAULT;
        }
        
        String keyFilename = getContext().router().getConfigSetting(Router.PROP_KEYS_FILENAME);
        if (keyFilename == null)
            keyFilename = Router.PROP_KEYS_FILENAME_DEFAULT;
        File keyFile = new File(keyFilename);
        
        File info = new File(infoFile);
        if (!info.exists() || !keyFile.exists()) {
            _log.info("Router info file [" + info.getAbsolutePath() + "] or private key file [" + keyFile.getAbsolutePath() + "] deleted, rebuilding");
            rebuildRouterInfo();
        } else {
            _log.debug("Router info file [" + info.getAbsolutePath() + "] exists, not rebuilding");
        }
        getTiming().setStartAfter(getContext().clock().now() + REBUILD_DELAY);
        getContext().jobQueue().addJob(this);
    }
    
    void rebuildRouterInfo() {
        rebuildRouterInfo(true);
    }
    void rebuildRouterInfo(boolean alreadyRunning) {
        _log.debug("Rebuilding the new router info");
        boolean fullRebuild = false;
        RouterInfo info = null;
        String infoFilename = getContext().router().getConfigSetting(Router.PROP_INFO_FILENAME);
        if (infoFilename == null)
            infoFilename = Router.PROP_INFO_FILENAME_DEFAULT;
        
        String keyFilename = getContext().router().getConfigSetting(Router.PROP_KEYS_FILENAME);
        if (keyFilename == null)
            keyFilename = Router.PROP_KEYS_FILENAME_DEFAULT;
        File keyFile = new File(keyFilename);
        
        if (keyFile.exists()) {
            // ok, no need to rebuild a brand new identity, just update what we can
            info = getContext().router().getRouterInfo();
            if (info == null) {
                info = new RouterInfo();
                FileInputStream fis = null;
                try {
                    fis = new FileInputStream(keyFile);
                    PrivateKey privkey = new PrivateKey();
                    privkey.readBytes(fis);
                    SigningPrivateKey signingPrivKey = new SigningPrivateKey();
                    signingPrivKey.readBytes(fis);
                    PublicKey pubkey = new PublicKey();
                    pubkey.readBytes(fis);
                    SigningPublicKey signingPubKey = new SigningPublicKey();
                    signingPubKey.readBytes(fis);
                    RouterIdentity ident = new RouterIdentity();
                    ident.setCertificate(new Certificate(Certificate.CERTIFICATE_TYPE_NULL, null));
                    ident.setPublicKey(pubkey);
                    ident.setSigningPublicKey(signingPubKey);
                    info.setIdentity(ident);
                } catch (Exception e) {
                    _log.error("Error reading in the key data from " + keyFile.getAbsolutePath(), e);
                    if (fis != null) try { fis.close(); } catch (IOException ioe) {}
                    fis = null;
                    keyFile.delete();
                    rebuildRouterInfo(alreadyRunning);
                    return;
                } finally {
                    if (fis != null) try { fis.close(); } catch (IOException ioe) {}
                }
            }
            
            try {
                info.setAddresses(getContext().commSystem().createAddresses());
                Properties stats = getContext().statPublisher().publishStatistics();
                stats.setProperty(RouterInfo.PROP_NETWORK_ID, ""+Router.NETWORK_ID);
                info.setOptions(stats);
                if (FloodfillNetworkDatabaseFacade.floodfillEnabled(getContext()))
                    info.addCapability(FloodfillNetworkDatabaseFacade.CAPACITY_FLOODFILL);

                // Set caps=H for hidden mode routers
                if ("true".equalsIgnoreCase(getContext().getProperty(Router.PROP_HIDDEN, "false"))) 
                    info.addCapability(RouterInfo.CAPABILITY_HIDDEN);

                getContext().router().addReachabilityCapability(info);
                // info.setPeers(new HashSet()); // this would have the trusted peers
                info.setPublished(CreateRouterInfoJob.getCurrentPublishDate(getContext()));
                
                info.sign(getContext().keyManager().getSigningPrivateKey());
            } catch (DataFormatException dfe) {
                _log.error("Error rebuilding the new router info", dfe);
                return;
            }
            
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(infoFilename);
                info.writeBytes(fos);
            } catch (DataFormatException dfe) {
                _log.error("Error rebuilding the router information", dfe);
            } catch (IOException ioe) {
                _log.error("Error writing out the rebuilt router information", ioe);
            } finally {
                if (fos != null) try { fos.close(); } catch (IOException ioe) {}
            }
            
        } else {
            _log.warn("Private key file " + keyFile.getAbsolutePath() + " deleted!  Rebuilding a brand new router identity!");
            // this proc writes the keys and info to the file as well as builds the latest and greatest info
            CreateRouterInfoJob j = new CreateRouterInfoJob(getContext(), null);
            info = j.createRouterInfo();
            fullRebuild = true;
        }
        
        //MessageHistory.initialize();
        getContext().router().setRouterInfo(info);
        _log.info("Router info rebuilt and stored at " + infoFilename + " [" + info + "]");
    }
    
}
