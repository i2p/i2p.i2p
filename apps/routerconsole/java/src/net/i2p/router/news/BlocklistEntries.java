package net.i2p.router.news;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.List;
import java.util.ArrayList;

import net.i2p.I2PAppContext;
import net.i2p.crypto.DirKeyRing;
import net.i2p.crypto.KeyRing;
import net.i2p.crypto.KeyStoreUtil;
import net.i2p.crypto.SigType;
import net.i2p.crypto.SigUtil;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.util.Log;

/**
 *  One Blocklist.
 *  Any String fields may be null.
 *
 *  @since 0.9.28
 */
public class BlocklistEntries {
    public final List<String> entries, removes;
    public String signer;
    public String sig;
    public String supdated;
    public long updated;
    private boolean verified;
    public static final int MAX_ENTRIES = 2000;
    private static final String CONTENT_ROUTER = "router";
    public static final long MAX_FUTURE = 2*24*60*60*1000L;

    public BlocklistEntries(int capacity) {
        entries = new ArrayList<String>(capacity);
        removes = new ArrayList<String>(4);
    }

    public synchronized boolean isVerified() {
        return verified;
    }

    public synchronized boolean verify(I2PAppContext ctx) {
        if (verified)
            return true;
        if (signer == null || sig == null || supdated == null)
            return false;
        if (updated > ctx.clock().now() + MAX_FUTURE)
            return false;
        Log log = ctx.logManager().getLog(BlocklistEntries.class);
        String[] ss = DataHelper.split(sig, ":", 2);
        if (ss.length != 2) {
            log.error("blocklist feed bad sig: " + sig);
            return false;
        }
        SigType type = SigType.parseSigType(ss[0]);
        if (type == null) {
            log.error("blocklist feed bad sig: " + sig);
            return false;
        }
        if (!type.isAvailable()) {
            log.error("blocklist feed sigtype unavailable: " + sig);
            return false;
        }
        byte[] bsig = Base64.decode(ss[1]);
        if (bsig == null) {
            log.error("blocklist feed bad sig: " + sig);
            return false;
        }
        Signature ssig;
        try {
            ssig = new Signature(type, bsig);
        } catch (IllegalArgumentException iae) {
            log.error("blocklist feed bad sig: " + sig);
            return false;
        }

        // look in both install dir and config dir for the signer cert
        KeyRing ring = new DirKeyRing(new File(ctx.getBaseDir(), "certificates"));
        PublicKey pubkey;
        try {
            pubkey = ring.getKey(signer, CONTENT_ROUTER, type);
        } catch (IOException ioe) {
            log.error("blocklist feed error", ioe);
            return false;
        } catch (GeneralSecurityException gse) {
            log.error("blocklist feed error", gse);
            return false;
        }
        if (pubkey == null) {
            boolean diff = true;
            try {
                diff = !ctx.getBaseDir().getCanonicalPath().equals(ctx.getConfigDir().getCanonicalPath());
            } catch (IOException ioe) {}
            if (diff) {
                ring = new DirKeyRing(new File(ctx.getConfigDir(), "certificates"));
                try {
                    pubkey = ring.getKey(signer, CONTENT_ROUTER, type);
                } catch (IOException ioe) {
                    log.error("blocklist feed error", ioe);
                    return false;
                } catch (GeneralSecurityException gse) {
                    log.error("blocklist feed error", gse);
                    return false;
                }
            }
            if (pubkey == null) {
                log.error("unknown signer for blocklist feed: " + signer);
                return false;
            }
        }
        SigningPublicKey spubkey;
        try {
            spubkey = SigUtil.fromJavaKey(pubkey, type);
        } catch (GeneralSecurityException gse) {
            log.error("blocklist feed bad sig: " + sig, gse);
            return false;
        }
        StringBuilder buf = new StringBuilder(256);
        buf.append(supdated).append('\n');
        for (String s : entries) {
            buf.append(s).append('\n');
        }
        for (String s : removes) {
            buf.append('!').append(s).append('\n');
        }
        byte[] data = DataHelper.getUTF8(buf.toString());
        boolean rv = ctx.dsa().verifySignature(ssig, data, spubkey);
        if (rv)
            log.info("blocklist feed sig ok");
        else
            log.error("blocklist feed sig verify fail: " + signer);
        verified = rv;
        return rv;
    }

    /**
     *  BlocklistEntries [-p keystorepw] input.txt keystore.ks you@mail.i2p
     *  File format: One entry per line, # starts a comment, ! starts an unblock entry.
     *  Single IPv4 or IPv6 address only (no mask allowed), or 44-char base 64 router hash.
     *  See MAX_ENTRIES above.
     */
    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: BlocklistEntries [-p keystorepw] input.txt keystore.ks you@mail.i2p");
            System.exit(1);
        }
        int st;
        String kspass;
        if (args[0].equals("-p")) {
            kspass = args[1];
            st = 2;
        } else {
            kspass = KeyStoreUtil.DEFAULT_KEYSTORE_PASSWORD;
            st = 0;
        }
        String inputFile = args[st++];
        String privateKeyFile = args[st++];
        String signerName = args[st];

        I2PAppContext ctx = new I2PAppContext();
        List<String> elist = new ArrayList<String>(16);
        List<String> rlist = new ArrayList<String>(4);
        StringBuilder buf = new StringBuilder();
        long now = System.currentTimeMillis();
        String date = RFC3339Date.to3339Date(now);
        buf.append(date).append('\n');
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(inputFile), "UTF-8"));
            String s = null;
            while ((s = br.readLine()) != null) {
                int index = s.indexOf('#');
                if (index == 0)
                    continue;  // comment
                if (index > 0)
                    s = s.substring(0, index);
                s = s.trim();
                if (s.length() < 7) {
                    if (s.length() > 0)
                        System.err.println("Bad line: " + s);
                    continue;
                }
                if (s.startsWith("!")) {
                    rlist.add(s.substring(1));
                } else {
                    elist.add(s);
                    buf.append(s).append('\n');
                }
            }
        } catch (IOException ioe) {
            System.err.println("load error from " + args[0]);
            ioe.printStackTrace();
            System.exit(1);
        } finally {
            if (br != null) try { br.close(); } catch (IOException ioe) {}
        }
        if (elist.isEmpty() && rlist.isEmpty()) {
            System.err.println("nothing to sign");
            System.exit(1);
        }
        if (elist.size() > MAX_ENTRIES) {
            System.err.println("too many blocks, max is " + MAX_ENTRIES);
            System.exit(1);
        }
        for (String s : rlist) {
            buf.append('!').append(s).append('\n');
        }

        SigningPrivateKey spk = null;
        try {
            String keypw = "";
            while (keypw.length() < 6) {
                System.err.print("Enter password for key \"" + signerName + "\": ");
                keypw = DataHelper.readLine(System.in);
                if (keypw == null) {
                    System.out.println("\nEOF reading password");
                    System.exit(1);
                }
                keypw = keypw.trim();
                if (keypw.length() > 0 && keypw.length() < 6)
                    System.out.println("Key password must be at least 6 characters");
            }
            File pkfile = new File(privateKeyFile);
            PrivateKey pk = KeyStoreUtil.getPrivateKey(pkfile, kspass, signerName, keypw);
            if (pk == null) {
                System.out.println("Private key for " + signerName + " not found in keystore " + privateKeyFile);
                System.exit(1);
            }
            spk = SigUtil.fromJavaKey(pk);
        } catch (GeneralSecurityException gse) {
            System.out.println("Error signing input file '" + inputFile + "'");
            gse.printStackTrace();
            System.exit(1);
        } catch (IOException ioe) {
            System.out.println("Error signing input file '" + inputFile + "'");
            ioe.printStackTrace();
            System.exit(1);
        }
        SigType type = spk.getType();
        byte[] data = DataHelper.getUTF8(buf.toString());
        Signature ssig = ctx.dsa().sign(data, spk);
        if (ssig == null) {
            System.err.println("sign failed");
            System.exit(1);
        }
        String bsig = Base64.encode(ssig.getData());

        // verify
        BlocklistEntries ble = new BlocklistEntries(elist.size());
        ble.entries.addAll(elist);
        ble.removes.addAll(rlist);
        ble.supdated = date;
        ble.signer = signerName;
        ble.sig = type.getCode() + ":" + bsig;
        boolean ok = ble.verify(ctx);
        if (!ok) {
            System.err.println("verify failed");
            System.exit(1);
        }

        System.out.println("  <i2p:blocklist signer=\"" + signerName + "\" sig=\"" + type.getCode() + ':' + bsig + "\">");
        System.out.println("    <updated>" + date + "</updated>");
        for (String e : elist) {
            System.out.println("    <i2p:block>" + e + "</i2p:block>");
        }
        for (String e : rlist) {
            System.out.println("    <i2p:unblock>" + e + "</i2p:unblock>");
        }
        System.out.println("  </i2p:blocklist>");
    }
}
