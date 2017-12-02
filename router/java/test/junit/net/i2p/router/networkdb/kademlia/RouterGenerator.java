package net.i2p.router.networkdb.kademlia;
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
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import net.i2p.crypto.KeyGenerator;
import net.i2p.data.Certificate;
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterIdentity;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.util.Clock;
import net.i2p.util.OrderedProperties;

public class RouterGenerator {
    public static void main(String args[]) {
	RouterGenerator gen = new RouterGenerator();
	switch (args.length) {
	    case 0:
		gen.createRouters(10000, "dummyRouters");
		break;
	    case 1:
		gen.createRouters(10000, args[0]);
		break;
	    case 2:
		try { gen.createRouters(Integer.parseInt(args[1]), args[0]); } catch (NumberFormatException nfe) { nfe.printStackTrace(); }
		break;
	}
    }
    
    private void createRouters(int numRouters, String outDir) {
	File dir = new File(outDir);
	if (!dir.exists())
	    dir.mkdirs();
	int numSuccess = 0;
	for (int i = 1; numSuccess < numRouters; i++) {
	    RouterInfo ri = createRouterInfo(i);
	    String hash = ri.getIdentity().getHash().toBase64();
	    if (!hash.startsWith("fwI")) {
		System.out.print(".");
		if ( (i % 100) == 0) System.out.println();
		continue;
	    }
	    
	    System.out.println("Router " + i + " created: \t" + hash);
	    numSuccess++;
	    
	    FileOutputStream fos = null;
	    try {
		fos = new FileOutputStream(new File(dir, "routerInfo-" + hash + ".dat"));
		ri.writeBytes(fos);
	    } catch (Exception e) {
		System.err.println("Error writing router - " + e.getMessage());
		e.printStackTrace();
		return;
	    } finally {
		if (fos != null) try { fos.close(); } catch (Exception e) {}
	    }
	}
    }
    
    private static PublicKey pubkey = null;
    private static PrivateKey privkey = null;
    private static SigningPublicKey signingPubKey = null;
    private static SigningPrivateKey signingPrivKey = null;
    private static Object keypair[] = KeyGenerator.getInstance().generatePKIKeypair();
    private static Object signingKeypair[] = KeyGenerator.getInstance().generateSigningKeypair();
    
    static {
	pubkey = (PublicKey)keypair[0];
	privkey = (PrivateKey)keypair[1];
	signingPubKey = (SigningPublicKey)signingKeypair[0];
	signingPrivKey = (SigningPrivateKey)signingKeypair[1];
    }
    
    
    static RouterInfo createRouterInfo(int num) {
	RouterInfo info = new RouterInfo();
	try {
	    info.setAddresses(createAddresses(num));
            // not necessary, in constructor
	    //info.setOptions(new Properties());
	    //info.setPeers(new HashSet());
	    info.setPublished(Clock.getInstance().now());
	    RouterIdentity ident = new RouterIdentity();
	    BigInteger bv = new BigInteger(""+num);
	    Certificate cert = new Certificate(Certificate.CERTIFICATE_TYPE_NULL, bv.toByteArray());
	    ident.setCertificate(cert);
	    ident.setPublicKey(pubkey);
	    ident.setSigningPublicKey(signingPubKey);
	    info.setIdentity(ident);
	    
	    info.sign(signingPrivKey);
	} catch (Exception e) {
	    System.err.println("Error building router " + num + ": " + e.getMessage());
	    e.printStackTrace();
	}
	return info;
    }
    
    static Set<RouterAddress> createAddresses(int num) {
	Set<RouterAddress> addresses = new HashSet<RouterAddress>();
	RouterAddress addr = createTCPAddress(num);
	if (addr != null)
	    addresses.add(addr);
	return addresses;
    }
    
    private static RouterAddress createTCPAddress(int num) {
	OrderedProperties props = new OrderedProperties();
	String name = "blah.random.host.org";
	String port = "" + (1024+num);
	props.setProperty("host", name);
	props.setProperty("port", port);
	RouterAddress addr = new RouterAddress("TCP", props, 10);
	return addr;
    }
    
}
