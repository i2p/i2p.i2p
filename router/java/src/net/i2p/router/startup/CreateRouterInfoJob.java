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
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Properties;

import net.i2p.data.Certificate;
import net.i2p.data.DataFormatException;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.RouterIdentity;
import net.i2p.data.RouterInfo;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.router.Job;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

public class CreateRouterInfoJob extends JobImpl {
    private static Log _log = new Log(CreateRouterInfoJob.class);
    private Job _next;
    
    public CreateRouterInfoJob(RouterContext ctx, Job next) {
        super(ctx);
        _next = next;
    }
    
    public String getName() { return "Create New Router Info"; }
    
    public void runJob() {
        _log.debug("Creating the new router info");
        // create a new router info and store it where LoadRouterInfoJob looks
        createRouterInfo();
        getContext().jobQueue().addJob(_next);
    }
    
    RouterInfo createRouterInfo() {
        RouterInfo info = new RouterInfo();
        FileOutputStream fos1 = null;
        FileOutputStream fos2 = null;
        try {
            info.setAddresses(getContext().commSystem().createAddresses());
            Properties stats = getContext().statPublisher().publishStatistics();
            stats.setProperty(RouterInfo.PROP_NETWORK_ID, Router.NETWORK_ID+"");
            getContext().router().addCapabilities(info);
            info.setOptions(stats);
            info.setPeers(new HashSet());
            info.setPublished(getCurrentPublishDate(getContext()));
            RouterIdentity ident = new RouterIdentity();
            Certificate cert = getContext().router().createCertificate();
            ident.setCertificate(cert);
            PublicKey pubkey = null;
            PrivateKey privkey = null;
            SigningPublicKey signingPubKey = null;
            SigningPrivateKey signingPrivKey = null;
            Object keypair[] = getContext().keyGenerator().generatePKIKeypair();
            pubkey = (PublicKey)keypair[0];
            privkey = (PrivateKey)keypair[1];
            Object signingKeypair[] = getContext().keyGenerator().generateSigningKeypair();
            signingPubKey = (SigningPublicKey)signingKeypair[0];
            signingPrivKey = (SigningPrivateKey)signingKeypair[1];
            ident.setPublicKey(pubkey);
            ident.setSigningPublicKey(signingPubKey);
            info.setIdentity(ident);
            
            info.sign(signingPrivKey);
            
            String infoFilename = getContext().getProperty(Router.PROP_INFO_FILENAME, Router.PROP_INFO_FILENAME_DEFAULT);
            File ifile = new File(getContext().getRouterDir(), infoFilename);
            fos1 = new FileOutputStream(ifile);
            info.writeBytes(fos1);
            
            String keyFilename = getContext().getProperty(Router.PROP_KEYS_FILENAME, Router.PROP_KEYS_FILENAME_DEFAULT);
            File kfile = new File(getContext().getRouterDir(), keyFilename);
            fos2 = new FileOutputStream(kfile);
            privkey.writeBytes(fos2);
            signingPrivKey.writeBytes(fos2);
            pubkey.writeBytes(fos2);
            signingPubKey.writeBytes(fos2);
            
            getContext().keyManager().setSigningPrivateKey(signingPrivKey);
            getContext().keyManager().setSigningPublicKey(signingPubKey);
            getContext().keyManager().setPrivateKey(privkey);
            getContext().keyManager().setPublicKey(pubkey);
            
            _log.info("Router info created and stored at " + ifile.getAbsolutePath() + " with private keys stored at " + kfile.getAbsolutePath() + " [" + info + "]");
        } catch (DataFormatException dfe) {
            _log.error("Error building the new router information", dfe);
        } catch (IOException ioe) {
            _log.error("Error writing out the new router information", ioe);
        } finally {
            if (fos1 != null) try { fos1.close(); } catch (IOException ioe) {}
            if (fos2 != null) try { fos2.close(); } catch (IOException ioe) {}
        }
        return info;
    }
    
    
    /**
     * We probably don't want to expose the exact time at which a router published its info.
     * perhaps round down to the nearest minute?  10 minutes?  30 minutes?  day?
     *
     */
    static long getCurrentPublishDate(RouterContext context) {
        _log.info("Setting published date to /now/");
        return context.clock().now();
    }
}
