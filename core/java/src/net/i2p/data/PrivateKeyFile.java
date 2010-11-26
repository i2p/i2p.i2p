package net.i2p.data;


import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;

import com.nettgryppa.security.HashCash;

import net.i2p.I2PException;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.crypto.DSAEngine;

/**
 * This helper class reads and writes files in the
 * same "eepPriv.dat" format used by the client code.
 * The format is:
 *<pre>
 *  - Destination (387 bytes if no certificate, otherwise longer)
 *     - Public key (256 bytes)
 *     - Signing Public key (128 bytes)
 *     - Cert. type (1 byte)
 *     - Cert. length (2 bytes)
 *     - Certificate if length != 0
 *  - Private key (256 bytes)
 *  - Signing Private key (20 bytes)
 * Total 663 bytes
 *</pre>
 *
 * @author welterde, zzz
 */

public class PrivateKeyFile {
    /**
     *  Create a new PrivateKeyFile, or modify an existing one, with various
     *  types of Certificates.
     *  
     *  Changing a Certificate does not change the public or private keys.
     *  But it does change the Destination Hash, which effectively makes it
     *  a new Destination. In other words, don't change the Certificate on
     *  a Destination you've already registered in a hosts.txt key add form.
     *  
     *  Copied and expanded from that in Destination.java
     */
    public static void main(String args[]) {
        if (args.length == 0) {
            System.err.println("Usage: PrivateKeyFile filename (generates if nonexistent, then prints)");
            System.err.println("       PrivateKeyFile -h filename (generates if nonexistent, adds hashcash cert)");
            System.err.println("       PrivateKeyFile -h effort filename (specify HashCash effort instead of default " + HASH_EFFORT + ")");
            System.err.println("       PrivateKeyFile -n filename (changes to null cert)");
            System.err.println("       PrivateKeyFile -s filename signwithdestfile (generates if nonexistent, adds cert signed by 2nd dest)");
            System.err.println("       PrivateKeyFile -u filename (changes to unknown cert)");
            System.err.println("       PrivateKeyFile -x filename (changes to hidden cert)");
            return;
        }
        I2PClient client = I2PClientFactory.createClient();

        int filearg = 0;
        if (args.length > 1) {
            if (args.length >= 2 && args[0].equals("-h"))
                filearg = args.length - 1;
            else
                filearg = 1;
        }
        try {
            File f = new File(args[filearg]);
            PrivateKeyFile pkf = new PrivateKeyFile(f, client);
            Destination d = pkf.createIfAbsent();
            System.out.println("Original Destination:");
            System.out.println(pkf);
            verifySignature(d);
            if (args.length == 1)
                return;
            if (args[0].equals("-n")) {
                // Cert constructor generates a null cert
                pkf.setCertType(Certificate.CERTIFICATE_TYPE_NULL);
                System.out.println("New destination with null cert is:");
            } else if (args[0].equals("-u")) {
                pkf.setCertType(99);
                System.out.println("New destination with unknown cert is:");
            } else if (args[0].equals("-x")) {
                pkf.setCertType(Certificate.CERTIFICATE_TYPE_HIDDEN);
                System.out.println("New destination with hidden cert is:");
            } else if (args[0].equals("-h")) {
                int hashEffort = HASH_EFFORT;
                if (args.length == 3)
                    hashEffort = Integer.parseInt(args[1]);
                System.out.println("Estimating hashcash generation time, stand by...");
                System.out.println(estimateHashCashTime(hashEffort));
                pkf.setHashCashCert(hashEffort);
                System.out.println("New destination with hashcash cert is:");
            } else if (args.length == 3 && args[0].equals("-s")) {
                // Sign dest1 with dest2's Signing Private Key
                PrivateKeyFile pkf2 = new PrivateKeyFile(args[2]);
                pkf.setSignedCert(pkf2);
                System.out.println("New destination with signed cert is:");
            }
            System.out.println(pkf);
            pkf.write();
            verifySignature(d);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public PrivateKeyFile(String file) {
        this(new File(file), I2PClientFactory.createClient());
    }

    public PrivateKeyFile(File file) {
        this(file, I2PClientFactory.createClient());
    }

    public PrivateKeyFile(File file, I2PClient client) {
        this.file = file;
        this.client = client;
        this.dest = null;
        this.privKey = null;
        this.signingPrivKey = null;
    }
    
    
    /** Also reads in the file to get the privKey and signingPrivKey, 
     *  which aren't available from I2PClient.
     */
    public Destination createIfAbsent() throws I2PException, IOException, DataFormatException {
        if(!this.file.exists()) {
            FileOutputStream out = new FileOutputStream(this.file);
            this.client.createDestination(out);
            out.close();
        }
        return getDestination();
    }
    
    /** Also sets the local privKey and signingPrivKey */
    public Destination getDestination() throws I2PSessionException, IOException, DataFormatException {
        if (dest == null) {
            I2PSession s = open();
            if (s != null) {
                this.dest = new VerifiedDestination(s.getMyDestination());
                this.privKey = s.getDecryptionKey();
                this.signingPrivKey = s.getPrivateKey();
            }
        }
        return this.dest;
    }

    public void setDestination(Destination d) {
        this.dest = d;
    }
    
    /** change cert type - caller must also call write() */
    public Certificate setCertType(int t) {
        if (this.dest == null)
            throw new IllegalArgumentException("Dest is null");
        Certificate c = new Certificate();
        c.setCertificateType(t);
        this.dest.setCertificate(c);
        return c;
    }
    
    /** change to hashcash cert - caller must also call write() */
    public Certificate setHashCashCert(int effort) {
        Certificate c = setCertType(Certificate.CERTIFICATE_TYPE_HASHCASH);
        long begin = System.currentTimeMillis();
        System.out.println("Starting hashcash generation now...");
        String resource = this.dest.getPublicKey().toBase64() + this.dest.getSigningPublicKey().toBase64();
        HashCash hc;
        try {
            hc = HashCash.mintCash(resource, effort);
        } catch (Exception e) {
            return null;
        }
        System.out.println("Generation took: " + DataHelper.formatDuration(System.currentTimeMillis() - begin));
        System.out.println("Full Hashcash is: " + hc);
        // Take the resource out of the stamp
        String hcs = hc.toString();
        int end1 = 0;
        for (int i = 0; i < 3; i++) {
            end1 = 1 + hcs.indexOf(':', end1);
            if (end1 < 0) {
                System.out.println("Bad hashcash");
                return null;
            }
        }
        int start2 = hcs.indexOf(':', end1);
        if (start2 < 0) {
            System.out.println("Bad hashcash");
            return null;
        }
        hcs = hcs.substring(0, end1) + hcs.substring(start2);
        System.out.println("Short Hashcash is: " + hcs);

        c.setPayload(hcs.getBytes());
        return c;
    }
    
    /** sign this dest by dest found in pkf2 - caller must also call write() */
    public Certificate setSignedCert(PrivateKeyFile pkf2) {
        Certificate c = setCertType(Certificate.CERTIFICATE_TYPE_SIGNED);
        Destination d2;
        try {
            d2 = pkf2.getDestination();
        } catch (Exception e) {
            return null;
        }
        if (d2 == null)
            return null;
        SigningPrivateKey spk2 = pkf2.getSigningPrivKey();
        System.out.println("Signing With Dest:");
        System.out.println(pkf2.toString());

        int len = PublicKey.KEYSIZE_BYTES + SigningPublicKey.KEYSIZE_BYTES; // no cert 
        byte[] data = new byte[len];
        System.arraycopy(this.dest.getPublicKey().getData(), 0, data, 0, PublicKey.KEYSIZE_BYTES);
        System.arraycopy(this.dest.getSigningPublicKey().getData(), 0, data, PublicKey.KEYSIZE_BYTES, SigningPublicKey.KEYSIZE_BYTES);
        byte[] payload = new byte[Hash.HASH_LENGTH + Signature.SIGNATURE_BYTES];
        byte[] sig = DSAEngine.getInstance().sign(new ByteArrayInputStream(data), spk2).getData();
        System.arraycopy(sig, 0, payload, 0, Signature.SIGNATURE_BYTES);
        // Add dest2's Hash for reference
        byte[] h2 = d2.calculateHash().getData();
        System.arraycopy(h2, 0, payload, Signature.SIGNATURE_BYTES, Hash.HASH_LENGTH);
        c.setCertificateType(Certificate.CERTIFICATE_TYPE_SIGNED);
        c.setPayload(payload);
        return c;
    }
    
    public PrivateKey getPrivKey() {
        return this.privKey;
    }
    public SigningPrivateKey getSigningPrivKey() {
        return this.signingPrivKey;
    }
    
    public I2PSession open() throws I2PSessionException, IOException {
        return this.open(new Properties());
    }
    public I2PSession open(Properties opts) throws I2PSessionException, IOException {
        // open input file
        FileInputStream in = new FileInputStream(this.file);
        
        // create sesssion
        I2PSession s = this.client.createSession(in, opts);
        
        // close file
        in.close();
        
        return s;
    }
    
    /**
     *  Copied from I2PClientImpl.createDestination()
     */
    public void write() throws IOException, DataFormatException {
        FileOutputStream out = new FileOutputStream(this.file);
        this.dest.writeBytes(out);
        this.privKey.writeBytes(out);
        this.signingPrivKey.writeBytes(out);
        out.flush();
        out.close();
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder(128);
        s.append("Dest: ");
        s.append(this.dest != null ? this.dest.toBase64() : "null");
        s.append("\nB32: ");
        s.append(this.dest != null ? Base32.encode(this.dest.calculateHash().getData()) + ".b32.i2p" : "null");
        s.append("\nContains: ");
        s.append(this.dest);
        s.append("\nPrivate Key: ");
        s.append(this.privKey);
        s.append("\nSigining Private Key: ");
        s.append(this.signingPrivKey);
        s.append("\n");
        return s.toString();
    }
    
    public static String estimateHashCashTime(int hashEffort) {
        if (hashEffort <= 0 || hashEffort > 160)
            return "Bad HashCash value: " + hashEffort;
        long low = Long.MAX_VALUE;
        try {
            low = HashCash.estimateTime(hashEffort);
        } catch (Exception e) {}
        // takes a lot longer than the estimate usually...
        // maybe because the resource string is much longer than used in the estimate?
        return "It is estimated that generating a HashCash Certificate with value " + hashEffort +
               " for the Destination will take " +
               ((low < 1000l * 24l * 60l * 60l * 1000l)
                 ?
                   "approximately " + DataHelper.formatDuration(low) +
                   " to " + DataHelper.formatDuration(4*low)
                 :
                   "longer than three years!"
               );
    }    
    
    /**
     *  Sample code to verify a 3rd party signature.
     *  This just goes through all the hosts.txt files and tries everybody.
     *  You need to be in the $I2P directory or have a local hosts.txt for this to work.
     *  Doubt this is what you want as it is super-slow, and what good is
     *  a signing scheme where anybody is allowed to sign?
     *
     *  In a real application you would make a list of approved signers,
     *  do a naming lookup to get their Destinations, and try those only.
     *  Or do a netDb lookup of the Hash in the Certificate, do a reverse
     *  naming lookup to see if it is allowed, then verify the Signature.
     */
    public static boolean verifySignature(Destination d) {
        if (d.getCertificate().getCertificateType() != Certificate.CERTIFICATE_TYPE_SIGNED)
            return false;
        int len = PublicKey.KEYSIZE_BYTES + SigningPublicKey.KEYSIZE_BYTES; // no cert 
        byte[] data = new byte[len];
        System.arraycopy(d.getPublicKey().getData(), 0, data, 0, PublicKey.KEYSIZE_BYTES);
        System.arraycopy(d.getSigningPublicKey().getData(), 0, data, PublicKey.KEYSIZE_BYTES, SigningPublicKey.KEYSIZE_BYTES);
        Signature sig = new Signature();
        byte[] payload = d.getCertificate().getPayload();
        Hash signerHash = null;
        if (payload == null) {
            System.out.println("Bad signed cert - no payload");
            return false;
        } else if (payload.length == Signature.SIGNATURE_BYTES) {
            sig.setData(payload);
        } else if (payload.length == Certificate.CERTIFICATE_LENGTH_SIGNED_WITH_HASH) {
            byte[] pl = new byte[Signature.SIGNATURE_BYTES];
            System.arraycopy(payload, 0, pl, 0, Signature.SIGNATURE_BYTES);
            sig.setData(pl);
            byte[] hash = new byte[Hash.HASH_LENGTH];
            System.arraycopy(payload, Signature.SIGNATURE_BYTES, hash, 0, Hash.HASH_LENGTH);
            signerHash = new Hash(hash);
            System.out.println("Destination is signed by " + Base32.encode(hash) + ".b32.i2p");
        } else {
            System.out.println("Bad signed cert - length = " + payload.length);
            return false;
        }
     
        String[] filenames = new String[] {"privatehosts.txt", "userhosts.txt", "hosts.txt"};
        int tried = 0;
        for (int i = 0; i < filenames.length; i++) { 
            Properties hosts = new Properties();
            try {
                File f = new File(filenames[i]);
                if ( (f.exists()) && (f.canRead()) ) {
                    DataHelper.loadProps(hosts, f, true);
                    int sz = hosts.size();
                    if (sz > 0) {
                        tried += sz;
                        if (signerHash == null)
                            System.out.println("Attempting to verify using " + sz + " hosts, this may take a while");
                    }
                    
                    for (Iterator iter = hosts.entrySet().iterator(); iter.hasNext(); )  {
                        Map.Entry entry = (Map.Entry)iter.next();
                        String s = (String) entry.getValue();
                        Destination signer = new Destination(s);
                        // make it go faster if we have the signerHash hint
                        if (signerHash == null || signer.calculateHash().equals(signerHash)) {
                            if (checkSignature(sig, data, signer.getSigningPublicKey())) {
                                System.out.println("Good signature from: " + entry.getKey());
                                return true;
                            }
                            if (signerHash != null) {
                                System.out.println("Bad signature from: " + entry.getKey());
                                // could probably return false here but keep going anyway
                            }
                        }
                    }
                }
            } catch (Exception ioe) {
            }
            // not found, continue to the next file
        }
        if (tried > 0)
            System.out.println("No valid signer found");
        else
            System.out.println("No addressbooks found to valididate signer");
        return false;
    }

    public static boolean checkSignature(Signature s, byte[] data, SigningPublicKey spk) {
        return DSAEngine.getInstance().verifySignature(s, data, spk);
    }
    
    
    private static final int HASH_EFFORT = VerifiedDestination.MIN_HASHCASH_EFFORT;
    
    
    
    private File file;
    private I2PClient client;
    private Destination dest;
    private PrivateKey privKey;
    private SigningPrivateKey signingPrivKey; 
}
