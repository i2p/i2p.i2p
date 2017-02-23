package net.i2p.data;


import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException; 
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import com.nettgryppa.security.HashCash;

import gnu.getopt.Getopt;

import net.i2p.I2PException;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.naming.HostTxtEntry;
import net.i2p.crypto.DSAEngine;
import net.i2p.crypto.KeyGenerator;
import net.i2p.crypto.SigType;
import net.i2p.util.OrderedProperties;
import net.i2p.util.RandomSource;
import net.i2p.util.SecureFileOutputStream;

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
 *  - Signing Private key (20 bytes, or length specified by key certificate)
 * Total: 663 or more bytes
 *</pre>
 *
 * @author welterde, zzz
 */

public class PrivateKeyFile {
    
    private static final int HASH_EFFORT = VerifiedDestination.MIN_HASHCASH_EFFORT;
    
    protected final File file;
    private final I2PClient client;
    protected Destination dest;
    protected PrivateKey privKey;
    protected SigningPrivateKey signingPrivKey; 

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
        int hashEffort = HASH_EFFORT;
        String stype = null;
        String hostname = null;
        int mode = 0;
        boolean error = false;
        Getopt g = new Getopt("pkf", args, "t:nuxhse:c:a:");
        int c;
        while ((c = g.getopt()) != -1) {
          switch (c) {
            case 'c':
                stype = g.getOptarg();
                break;

            case 't':
                stype = g.getOptarg();
                // fall thru...

            case 'n':
            case 'u':
            case 'x':
            case 'h':
            case 's':
                if (mode == 0)
                    mode = c;
                else
                    error = true;
                break;

            case 'a':
                hostname = g.getOptarg();
                if (mode == 0)
                    mode = c;
                else
                    error = true;
                break;

            case 'e':
                hashEffort = Integer.parseInt(g.getOptarg());
                break;

            case '?':
            case ':':
            default:
                error = true;
                break;
          }  // switch
        } // while

        int remaining = args.length - g.getOptind();
        int reqd = mode == 's' ? 2 : 1;
        if (error || remaining != reqd) {
            usage();
            System.exit(1);
        }
        String filearg = args[g.getOptind()];

        I2PClient client = I2PClientFactory.createClient();

        try {
            File f = new File(filearg);
            boolean exists = f.exists();
            PrivateKeyFile pkf = new PrivateKeyFile(f, client);
            Destination d;
            if (stype != null) {
                SigType type = SigType.parseSigType(stype);
                if (type == null)
                    throw new IllegalArgumentException("Signature type " + stype + " is not supported");
                d = pkf.createIfAbsent(type);
            } else {
                d = pkf.createIfAbsent();
            }
            if (exists)
                System.out.println("Original Destination:");
            else
                System.out.println("Created Destination:");
            System.out.println(pkf);
            verifySignature(d);
            switch (mode) {
              case 0:
                // we are done
                break;

              case 'n':
                // Cert constructor generates a null cert
                pkf.setCertType(Certificate.CERTIFICATE_TYPE_NULL);
                System.out.println("New destination with null cert is:");
                break;

              case 'u':
                pkf.setCertType(99);
                System.out.println("New destination with unknown cert is:");
                break;

              case 'x':
                pkf.setCertType(Certificate.CERTIFICATE_TYPE_HIDDEN);
                System.out.println("New destination with hidden cert is:");
                break;

              case 'h':
                System.out.println("Estimating hashcash generation time, stand by...");
                System.out.println(estimateHashCashTime(hashEffort));
                pkf.setHashCashCert(hashEffort);
                System.out.println("New destination with hashcash cert is:");
                break;

              case 's':
                // Sign dest1 with dest2's Signing Private Key
                PrivateKeyFile pkf2 = new PrivateKeyFile(args[g.getOptind() + 1]);
                pkf.setSignedCert(pkf2);
                System.out.println("New destination with signed cert is:");
                break;

              case 't':
                // KeyCert
                SigType type = SigType.parseSigType(stype);
                if (type == null)
                    throw new IllegalArgumentException("Signature type " + stype + " is not supported");
                pkf.setKeyCert(type);
                System.out.println("New destination with key cert is:");
                break;

              case 'a':
                // addressbook auth
                OrderedProperties props = new OrderedProperties();
                HostTxtEntry he = new HostTxtEntry(hostname, d.toBase64(), props);
                he.sign(pkf.getSigningPrivKey());
                System.out.println("Addressbook Authentication String:");
                OutputStreamWriter out = new OutputStreamWriter(System.out);
                he.write(out); 
                out.flush();
                System.out.println("");
                return;

              default:
                // shouldn't happen
                usage();
                return;
            }
            if (mode != 0) {
                System.out.println(pkf);
                pkf.write();
                verifySignature(pkf.getDestination());
            }
        } catch (I2PException e) {
            e.printStackTrace();
            System.exit(1);
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void usage() {
        System.err.println("Usage: PrivateKeyFile [-c sigtype] filename (generates if nonexistent, then prints)\n" +
                           "       PrivateKeyFile -a example.i2p filename (generate addressbook authentication string)\n" +
                           "       PrivateKeyFile -h filename (generates if nonexistent, adds hashcash cert)\n" +
                           "       PrivateKeyFile -h -e effort filename (specify HashCash effort instead of default " + HASH_EFFORT + ")\n" +
                           "       PrivateKeyFile -n filename (changes to null cert)\n" +
                           "       PrivateKeyFile -s filename signwithdestfile (generates if nonexistent, adds cert signed by 2nd dest)\n" +
                           "       PrivateKeyFile -t sigtype filename (changes to KeyCertificate of the given sig type)\n" +
                           "       PrivateKeyFile -u filename (changes to unknown cert)\n" +
                           "       PrivateKeyFile -x filename (changes to hidden cert)\n");
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
    }
    
    /** @since 0.8.9 */
    public PrivateKeyFile(File file, I2PSession session) {
        this(file, session.getMyDestination(), session.getDecryptionKey(), session.getPrivateKey());
    }
    
    /**
     *  @throws IllegalArgumentException on mismatch of spubkey and spk types
     *  @since 0.8.9
     */
    public PrivateKeyFile(File file, Destination dest, PrivateKey pk, SigningPrivateKey spk) {
        if (dest.getSigningPublicKey().getType() != spk.getType())
            throw new IllegalArgumentException("Signing key type mismatch");
        this.file = file;
        this.client = null;
        this.dest = dest;
        this.privKey = pk;
        this.signingPrivKey = spk;
    }
    
    /**
     *  @throws IllegalArgumentException on mismatch of spubkey and spk types
     *  @since 0.8.9
     */
    public PrivateKeyFile(File file, PublicKey pubkey, SigningPublicKey spubkey, Certificate cert,
                          PrivateKey pk, SigningPrivateKey spk) {
        this(file, pubkey, spubkey, cert, pk, spk, null);
    }
    
    /**
     *  @param padding null OK, must be non-null if spubkey length &lt; 128
     *  @throws IllegalArgumentException on mismatch of spubkey and spk types
     *  @since 0.9.16
     */
    public PrivateKeyFile(File file, PublicKey pubkey, SigningPublicKey spubkey, Certificate cert,
                          PrivateKey pk, SigningPrivateKey spk, byte[] padding) {
        if (spubkey.getType() != spk.getType())
            throw new IllegalArgumentException("Signing key type mismatch");
        this.file = file;
        this.client = null;
        this.dest = new Destination();
        this.dest.setPublicKey(pubkey);
        this.dest.setSigningPublicKey(spubkey);
        this.dest.setCertificate(cert);
        if (padding != null)
            this.dest.setPadding(padding);
        this.privKey = pk;
        this.signingPrivKey = spk;
    }
    
    /**
     *  Can't be used for writing
     *  @since 0.9.26
     */
    public PrivateKeyFile(InputStream in) throws I2PSessionException {
        this("/dev/null");
        I2PSession s = this.client.createSession(in, new Properties());
        this.dest = s.getMyDestination();
        this.privKey = s.getDecryptionKey();
        this.signingPrivKey = s.getPrivateKey();
    }
    
    /**
     *  Create with the default signature type if nonexistent.
     *
     *  Also reads in the file to get the privKey and signingPrivKey, 
     *  which aren't available from I2PClient.
     */
    public Destination createIfAbsent() throws I2PException, IOException, DataFormatException {
        return createIfAbsent(I2PClient.DEFAULT_SIGTYPE);
    }
    
    /**
     *  Create with the specified signature type if nonexistent.
     *
     *  Also reads in the file to get the privKey and signingPrivKey, 
     *  which aren't available from I2PClient.
     *
     *  @since 0.9.26
     */
    public Destination createIfAbsent(SigType type) throws I2PException, IOException, DataFormatException {
        if(!this.file.exists()) {
            OutputStream out = null;
            try {
                out = new SecureFileOutputStream(this.file);
                if (this.client != null)
                    this.client.createDestination(out, type);
                else
                    write();
            } finally {
                if (out != null) {
                    try { out.close(); } catch (IOException ioe) {}
                }
            }
        }
        return getDestination();
    }
    
    /**
     *  If the destination is not set, read it in from the file.
     *  Also sets the local privKey and signingPrivKey.
     */
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
    
    /**
     * Change cert type - caller must also call write().
     * Side effect - creates new Destination object.
     */
    public Certificate setCertType(int t) {
        if (this.dest == null)
            throw new IllegalArgumentException("Dest is null");
        Certificate c = new Certificate();
        c.setCertificateType(t);
        // dests now immutable, must create new
        Destination newdest = new Destination();
        newdest.setPublicKey(dest.getPublicKey());
        newdest.setSigningPublicKey(dest.getSigningPublicKey());
        newdest.setCertificate(c);
        dest = newdest;
        return c;
    }
    
    /**
     * Change cert type - caller must also call write().
     * Side effect - creates new Destination object.
     * @since 0.9.12
     */
    public Certificate setKeyCert(SigType type) {
        if (type == SigType.DSA_SHA1)
            return setCertType(Certificate.CERTIFICATE_TYPE_NULL);
        if (dest == null)
            throw new IllegalArgumentException("Dest is null");
        KeyCertificate c = new KeyCertificate(type);
        SimpleDataStructure signingKeys[];
        try {
            signingKeys = KeyGenerator.getInstance().generateSigningKeys(type);
        } catch (GeneralSecurityException gse) {
            throw new RuntimeException("keygen fail", gse);
        }
        SigningPublicKey signingPubKey = (SigningPublicKey) signingKeys[0];
        signingPrivKey = (SigningPrivateKey) signingKeys[1];
        // dests now immutable, must create new
        Destination newdest = new Destination();
        newdest.setPublicKey(dest.getPublicKey());
        newdest.setSigningPublicKey(signingPubKey);
        // fix up key certificate or padding
        int len = type.getPubkeyLen();
        if (len < 128) {
            byte[] pad = new byte[128 - len];
            RandomSource.getInstance().nextBytes(pad);
            newdest.setPadding(pad);
        } else if (len > 128) {
            System.arraycopy(signingPubKey.getData(), 128, c.getPayload(), KeyCertificate.HEADER_LENGTH, len - 128);
        }
        newdest.setCertificate(c);
        dest = newdest;
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
        } catch (NoSuchAlgorithmException e) {
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

        c.setPayload(DataHelper.getUTF8(hcs));
        return c;
    }
    
    /** sign this dest by dest found in pkf2 - caller must also call write() */
    public Certificate setSignedCert(PrivateKeyFile pkf2) {
        Certificate c = setCertType(Certificate.CERTIFICATE_TYPE_SIGNED);
        Destination d2;
        try {
            d2 = pkf2.getDestination();
        } catch (I2PException e) {
            return null;
        } catch (IOException e) {
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
        Signature sign = DSAEngine.getInstance().sign(new ByteArrayInputStream(data), spk2);
        if (sign == null)
            return null;
        byte[] sig = sign.getData();
        System.arraycopy(sig, 0, payload, 0, Signature.SIGNATURE_BYTES);
        // Add dest2's Hash for reference
        byte[] h2 = d2.calculateHash().getData();
        System.arraycopy(h2, 0, payload, Signature.SIGNATURE_BYTES, Hash.HASH_LENGTH);
        c.setCertificateType(Certificate.CERTIFICATE_TYPE_SIGNED);
        c.setPayload(payload);
        return c;
    }
    
    /**
     *  @return null on error or if not initialized
     */
    public PrivateKey getPrivKey() {
        try {
            // call this to force initialization
            getDestination();
        } catch (Exception e) {
            return null;
        }
        return this.privKey;
    }

    /**
     *  @return null on error or if not initialized
     */
    public SigningPrivateKey getSigningPrivKey() {
        try {
            // call this to force initialization
            getDestination();
        } catch (Exception e) {
            return null;
        }
        return this.signingPrivKey;
    }
    
    public I2PSession open() throws I2PSessionException, IOException {
        return this.open(new Properties());
    }

    public I2PSession open(Properties opts) throws I2PSessionException, IOException {
        InputStream in = null;
        try {
            in = new BufferedInputStream(new FileInputStream(this.file));
            I2PSession s = this.client.createSession(in, opts);
            return s;
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ioe) {}
            }
        }
    }
    
    /**
     *  Copied from I2PClientImpl.createDestination()
     */
    public void write() throws IOException, DataFormatException {
        OutputStream out = null;
        try {
            out = new SecureFileOutputStream(this.file);
            this.dest.writeBytes(out);
            this.privKey.writeBytes(out);
            this.signingPrivKey.writeBytes(out);
        } finally {
            if (out != null) {
                try { out.close(); } catch (IOException ioe) {}
            }
        }
    }

    /**
     *  Verify that the PublicKey matches the PrivateKey, and
     *  the SigningPublicKey matches the SigningPrivateKey.
     *
     *  @return success
     *  @since 0.9.16
     */
    public boolean validateKeyPairs() {
        try {
            if (!dest.getPublicKey().equals(KeyGenerator.getPublicKey(privKey)))
                return false;
            return dest.getSigningPublicKey().equals(KeyGenerator.getSigningPublicKey(signingPrivKey));
        } catch (IllegalArgumentException iae) {
            return false;
        }
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder(128);
        s.append("Dest: ");
        s.append(this.dest != null ? this.dest.toBase64() : "null");
        s.append("\nB32: ");
        s.append(this.dest != null ? this.dest.toBase32() : "null");
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
        } catch (NoSuchAlgorithmException e) {}
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
                    
                    for (Map.Entry<Object, Object> entry : hosts.entrySet())  {
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
            } catch (DataFormatException dfe) {
            } catch (IOException ioe) {
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
}
