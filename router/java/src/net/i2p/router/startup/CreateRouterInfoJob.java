package net.i2p.router.startup;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;

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
        RouterInfo info = createRouterInfo();
        _context.jobQueue().addJob(_next);
    }
    
    RouterInfo createRouterInfo() {
        RouterInfo info = new RouterInfo();
        FileOutputStream fos1 = null;
        FileOutputStream fos2 = null;
        try {
            info.setAddresses(_context.commSystem().createAddresses());
            info.setOptions(_context.statPublisher().publishStatistics());
            info.setPeers(new HashSet());
            info.setPublished(getCurrentPublishDate(_context));
            RouterIdentity ident = new RouterIdentity();
            Certificate cert = new Certificate();
            cert.setCertificateType(Certificate.CERTIFICATE_TYPE_NULL);
            cert.setPayload(null);
            ident.setCertificate(cert);
            PublicKey pubkey = null;
            PrivateKey privkey = null;
            SigningPublicKey signingPubKey = null;
            SigningPrivateKey signingPrivKey = null;
            Object keypair[] = _context.keyGenerator().generatePKIKeypair();
            pubkey = (PublicKey)keypair[0];
            privkey = (PrivateKey)keypair[1];
            Object signingKeypair[] = _context.keyGenerator().generateSigningKeypair();
            signingPubKey = (SigningPublicKey)signingKeypair[0];
            signingPrivKey = (SigningPrivateKey)signingKeypair[1];
            ident.setPublicKey(pubkey);
            ident.setSigningPublicKey(signingPubKey);
            info.setIdentity(ident);
            
            info.sign(signingPrivKey);
            
            String infoFilename = _context.router().getConfigSetting(Router.PROP_INFO_FILENAME);
            if (infoFilename == null)
                infoFilename = Router.PROP_INFO_FILENAME_DEFAULT;
            fos1 = new FileOutputStream(infoFilename);
            info.writeBytes(fos1);
            
            String keyFilename = _context.router().getConfigSetting(Router.PROP_KEYS_FILENAME);
            if (keyFilename == null)
                keyFilename = Router.PROP_KEYS_FILENAME_DEFAULT;
            fos2 = new FileOutputStream(keyFilename);
            privkey.writeBytes(fos2);
            signingPrivKey.writeBytes(fos2);
            pubkey.writeBytes(fos2);
            signingPubKey.writeBytes(fos2);
            
            _context.keyManager().setSigningPrivateKey(signingPrivKey);
            _context.keyManager().setSigningPublicKey(signingPubKey);
            _context.keyManager().setPrivateKey(privkey);
            _context.keyManager().setPublicKey(pubkey);
            
            _log.info("Router info created and stored at " + infoFilename + " with private keys stored at " + keyFilename + " [" + info + "]");
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
