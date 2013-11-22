package net.i2p.crypto;

import java.io.EOFException;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Signature;
import net.i2p.data.SimpleDataStructure;

/**
 *  Succesor to the ".sud" format used in TrustedUpdate.
 *  Format specified in http://www.i2p2.de/updates
 * 
 *  @since 0.9.8
 */
public class SU3File {

    private final I2PAppContext _context;

    private final File _file;
    private String _version;
    private int _versionLength;
    private String _signer;
    private int _signerLength;
    private ContentType _contentType;
    private long _contentLength;
    private PublicKey _signerPubkey;
    private boolean _headerVerified;
    private SigType _sigType;

    private static final byte[] MAGIC = DataHelper.getUTF8("I2Psu3");
    private static final int FILE_VERSION = 0;
    private static final int MIN_VERSION_BYTES = 16;
    private static final int VERSION_OFFSET = 40; // Signature.SIGNATURE_BYTES; avoid early ctx init

    private static final int TYPE_ZIP = 0;

    public static final int CONTENT_UNKNOWN = 0;
    public static final int CONTENT_ROUTER = 1;
    public static final int CONTENT_PLUGIN = 2;
    public static final int CONTENT_RESEED = 3;

    private enum ContentType {
        UNKNOWN(CONTENT_UNKNOWN, "unknown"),
        ROUTER(CONTENT_ROUTER, "router"),
        PLUGIN(CONTENT_PLUGIN, "plugin"),
        RESEED(CONTENT_RESEED, "reseed")
        ;

        private final int code;
        private final String name;

        ContentType(int code, String name) {
            this.code = code;
            this.name = name;
        }
        public int getCode() { return code; }
        public String getName() { return name; }

        /** @return null if not supported */
        public static ContentType getByCode(int code) {
            return BY_CODE.get(Integer.valueOf(code));
        }
    }

    private static final Map<Integer, ContentType> BY_CODE = new HashMap<Integer, ContentType>();

    static {
        for (ContentType type : ContentType.values()) {
            BY_CODE.put(Integer.valueOf(type.getCode()), type);
        }
    }

    private static final ContentType DEFAULT_CONTENT_TYPE = ContentType.UNKNOWN;
    // avoid early ctx init
    //private static final SigType DEFAULT_SIG_TYPE = SigType.DSA_SHA1;
    private static final int DEFAULT_SIG_CODE = 0;

    /**
     *
     */
    public SU3File(String file) {
        this(new File(file));
    }

    /**
     *
     */
    public SU3File(File file) {
        this(I2PAppContext.getGlobalContext(), file);
    }

    /**
     *
     */
    public SU3File(I2PAppContext context, File file) {
        _context = context;
        _file = file;
    }

    public String getVersionString() throws IOException {
        verifyHeader();
        return _version;
    }

    public String getSignerString() throws IOException {
        verifyHeader();
        return _signer;
    }

    /**
     *  @return null if unknown
     *  @since 0.9.9
     */
    public SigType getSigType() throws IOException {
        verifyHeader();
        return _sigType;
    }

    /**
     *  @return -1 if unknown
     *  @since 0.9.9
     */
    public int getContentType() throws IOException {
        verifyHeader();
        return _contentType != null ? _contentType.getCode() : -1;
    }

    /**
     *  Throws IOE if verify vails.
     */
    public void verifyHeader() throws IOException {
        if (_headerVerified)
            return;
        InputStream in = null;
        try {
            in = new FileInputStream(_file);
            verifyHeader(in);
        } catch (DataFormatException dfe) {
            IOException ioe = new IOException("foo");
            ioe.initCause(dfe);
            throw ioe;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
        }
    }

    /**
     *  Throws if verify vails.
     */
    private void verifyHeader(InputStream in) throws IOException, DataFormatException {
        byte[] magic = new byte[MAGIC.length];
        DataHelper.read(in, magic);
        if (!DataHelper.eq(magic, MAGIC))
            throw new IOException("Not an su3 file");
        skip(in, 1);
        int foo = in.read();
        if (foo != FILE_VERSION)
            throw new IOException("bad file version");
        int sigTypeCode = (int) DataHelper.readLong(in, 2);
        _sigType = SigType.getByCode(sigTypeCode);
        // In verifyAndMigrate it reads this far then rewinds, but we don't need to here
        if (_sigType == null)
            throw new IOException("unknown sig type: " + sigTypeCode);
        _signerLength = (int) DataHelper.readLong(in, 2);
        if (_signerLength != _sigType.getSigLen())
            throw new IOException("bad sig length");
        skip(in, 1);
        int _versionLength = in.read();
        if (_versionLength < MIN_VERSION_BYTES)
            throw new IOException("bad version length");
        skip(in, 1);
        int signerLen = in.read();
        if (signerLen <= 0)
            throw new IOException("bad signer length");
        _contentLength = DataHelper.readLong(in, 8);
        if (_contentLength <= 0)
            throw new IOException("bad content length");
        skip(in, 1);
        foo = in.read();
        if (foo != TYPE_ZIP)
            throw new IOException("bad type");
        skip(in, 1);
        int cType = in.read();
        _contentType = BY_CODE.get(Integer.valueOf(cType));
        if (_contentType == null)
            throw new IOException("unknown content type " + cType);
        skip(in, 12);

        byte[] data = new byte[_versionLength];
        int bytesRead = DataHelper.read(in, data);
        if (bytesRead != _versionLength)
            throw new EOFException();
        int zbyte;
        for (zbyte = 0; zbyte < _versionLength; zbyte++) {
            if (data[zbyte] == 0x00)
                break;
        }
        _version = new String(data, 0, zbyte, "UTF-8");

        data = new byte[signerLen];
        bytesRead = DataHelper.read(in, data);
        if (bytesRead != signerLen)
            throw new EOFException();
        _signer = DataHelper.getUTF8(data);

        KeyRing ring = new DirKeyRing(new File(_context.getBaseDir(), "certificates"));
        try {
            _signerPubkey = ring.getKey(_signer, _contentType.getName(), _sigType);
        } catch (GeneralSecurityException gse) {
            IOException ioe = new IOException("keystore error");
            ioe.initCause(gse);
            throw ioe;
        }

        if (_signerPubkey == null)
            throw new IOException("unknown signer: " + _signer);
        _headerVerified = true;
    }

    /** skip but update digest */
    private static void skip(InputStream in, int cnt) throws IOException {
        for (int i = 0; i < cnt; i++) {
            if (in.read() < 0)
                throw new EOFException();
        }
    }

    private int getContentOffset() throws IOException {
        verifyHeader();
        return VERSION_OFFSET + _versionLength + _signerLength;
    }

    /**
     *  One-pass verify.
     *  Throws IOE on all format errors.
     *
     *  @return true if signature is good
     *  @since 0.9.9
     */
    public boolean verify() throws IOException {
        return verifyAndMigrate(null);
    }

    /**
     *  One-pass verify and extract the content.
     *  Recommend extracting to a temp location as the sig is not checked until
     *  after extraction. This will delete the file if the sig does not verify.
     *  Throws IOE on all format errors.
     *
     *  @param migrateTo the output file, probably in zip format. Null for verify only.
     *  @return true if signature is good
     */
    public boolean verifyAndMigrate(File migrateTo) throws IOException {
        InputStream in = null;
        OutputStream out = null;
        boolean rv = false;
        try {
            in = new BufferedInputStream(new FileInputStream(_file));
            // read 10 bytes to get the sig type
            in.mark(10);
            // following is a dup of that in verifyHeader()
            byte[] magic = new byte[MAGIC.length];
            DataHelper.read(in, magic);
            if (!DataHelper.eq(magic, MAGIC))
                throw new IOException("Not an su3 file");
            skip(in, 1);
            int foo = in.read();
            if (foo != FILE_VERSION)
                throw new IOException("bad file version");
            skip(in, 1);
            int sigTypeCode = in.read();
            _sigType = SigType.getByCode(sigTypeCode);
            if (_sigType == null)
                throw new IOException("unknown sig type: " + sigTypeCode);
            // end duplicate code
            // rewind
            in.reset();
            MessageDigest md = _sigType.getDigestInstance();
            DigestInputStream din = new DigestInputStream(in, md);
            in = din;
            if (!_headerVerified)
                verifyHeader(in);
            else
                skip(in, getContentOffset());
            if (_signerPubkey == null)
                throw new IOException("unknown signer: " + _signer);
            if (migrateTo != null)  // else verify only
                out = new FileOutputStream(migrateTo);
            byte[] buf = new byte[16*1024];
            long tot = 0;
            while (tot < _contentLength) {
                int read = in.read(buf, 0, (int) Math.min(buf.length, _contentLength - tot));
                if (read < 0)
                    throw new EOFException();
                if (migrateTo != null)  // else verify only
                    out.write(buf, 0, read);
                tot += read;
            }
            byte[] sha = md.digest();
            din.on(false);
            Signature signature = new Signature(_sigType);
            signature.readBytes(in);
            SimpleDataStructure hash = _sigType.getHashInstance();
            hash.setData(sha);
            //System.out.println("hash\n" + HexDump.dump(sha));
            //System.out.println("sig\n" + HexDump.dump(signature.getData()));
            rv = _context.dsa().verifySignature(signature, hash, _signerPubkey);
        } catch (DataFormatException dfe) {
            IOException ioe = new IOException("foo");
            ioe.initCause(dfe);
            throw ioe;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
            if (out != null) try { out.close(); } catch (IOException ioe) {}
            if (migrateTo != null && !rv)
                migrateTo.delete();
        }
        return rv;
    }

    /**
     *  One-pass wrap and sign the content.
     *  Writes to the file specified in the constructor.
     *  Throws on all errors.
     *
     *  @param content the input file, probably in zip format
     *  @param contentType 0-255, 0 for zip
     *  @param version 1-255 bytes when converted to UTF-8
     *  @param signer ID of the public key, 1-255 bytes when converted to UTF-8
     */
    public void write(File content, int contentType, String version,
                      String signer, PrivateKey privkey, SigType sigType) throws IOException {
        InputStream in = null;
        DigestOutputStream out = null;
        boolean ok = false;
        try {
            in = new BufferedInputStream(new FileInputStream(content));
            MessageDigest md = sigType.getDigestInstance();
            out = new DigestOutputStream(new BufferedOutputStream(new FileOutputStream(_file)), md);
            out.write(MAGIC);
            out.write((byte) 0);
            out.write((byte) FILE_VERSION);
            DataHelper.writeLong(out, 2, sigType.getCode());
            DataHelper.writeLong(out, 2, sigType.getSigLen());
            out.write((byte) 0);
            byte[] verBytes = DataHelper.getUTF8(version);
            if (verBytes.length == 0 || verBytes.length > 255)
                throw new IllegalArgumentException("bad version length");
            int verLen = Math.max(verBytes.length, MIN_VERSION_BYTES);
            out.write((byte) verLen);
            out.write((byte) 0);
            byte[] signerBytes = DataHelper.getUTF8(signer);
            if (signerBytes.length == 0 || signerBytes.length > 255)
                throw new IllegalArgumentException("bad signer length");
            out.write((byte) signerBytes.length);
            long contentLength = content.length();
            if (contentLength <= 0)
                throw new IllegalArgumentException("No content");
            DataHelper.writeLong(out, 8, contentLength);
            out.write((byte) 0);
            out.write((byte) TYPE_ZIP);
            out.write((byte) 0);
            if (contentType < 0 || contentType > 255)
                throw new IllegalArgumentException("bad content type");
            out.write((byte) contentType);
            out.write(new byte[12]);
            out.write(verBytes);
            if (verBytes.length < MIN_VERSION_BYTES)
                out.write(new byte[MIN_VERSION_BYTES - verBytes.length]);
            out.write(signerBytes);

            byte[] buf = new byte[16*1024];
            long tot = 0;
            while (tot < contentLength) {
                int read = in.read(buf, 0, (int) Math.min(buf.length, contentLength - tot));
                if (read < 0)
                    throw new EOFException();
                out.write(buf, 0, read);
                tot += read;
            }

            byte[] sha = md.digest();
            out.on(false);
            SimpleDataStructure hash = sigType.getHashInstance();
            hash.setData(sha);
            Signature signature = _context.dsa().sign(hash, privkey, sigType);
            if (signature == null)
                throw new IOException("sig fail");
            //System.out.println("hash\n" + HexDump.dump(sha));
            //System.out.println("sig\n" + HexDump.dump(signature.getData()));
            signature.writeBytes(out);
            ok = true;
        } catch (DataFormatException dfe) {
            IOException ioe = new IOException("foo");
            ioe.initCause(dfe);
            throw ioe;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
            if (out != null) try { out.close(); } catch (IOException ioe) {}
            if (!ok)
                _file.delete();
        }
    }

    /**
     * Parses command line arguments when this class is used from the command
     * line.
     * Exits 1 on failure so this can be used in scripts.
     * 
     * @param args Command line parameters.
     */
    public static void main(String[] args) {
        boolean ok = false;
        List<String> a = new ArrayList<String>(Arrays.asList(args));
        try {
            // defaults
            String stype = null;
            String ctype = null;
            Iterator<String> iter = a.iterator();
            String cmd = iter.next();
            iter.remove();
            for ( ; iter.hasNext(); ) {
                String arg = iter.next();
                if (arg.equals("-t")) {
                    iter.remove();
                    stype = iter.next();
                    iter.remove();
                } else if (arg.equals("-c")) {
                    iter.remove();
                    ctype = iter.next();
                    iter.remove();
                }
            }
            if ("showversion".equals(cmd)) {
                ok = showVersionCLI(a.get(0));
            } else if ("sign".equals(cmd)) {
                // speed things up by specifying a small PRNG buffer size
                Properties props = new Properties();
                props.setProperty("prng.bufferSize", "16384");
                new I2PAppContext(props);
                ok = signCLI(stype, ctype, a.get(0), a.get(1), a.get(2), a.get(3), a.get(4), "");
            } else if ("bulksign".equals(cmd)) {
                Properties props = new Properties();
                props.setProperty("prng.bufferSize", "16384");
                new I2PAppContext(props);
                ok = bulkSignCLI(stype, ctype, a.get(0), a.get(1), a.get(2), a.get(3));
            } else if ("verifysig".equals(cmd)) {
                ok = verifySigCLI(a.get(0));
            } else if ("keygen".equals(cmd)) {
                ok = genKeysCLI(stype, a.get(0), a.get(1), a.get(2));
            } else if ("extract".equals(cmd)) {
                ok = extractCLI(a.get(0), a.get(1));
            } else {
                showUsageCLI();
            }
        } catch (NoSuchElementException nsee) {
            showUsageCLI();
        } catch (IndexOutOfBoundsException ioobe) {
            showUsageCLI();
        }
        if (!ok)
            System.exit(1);
    }

    private static final void showUsageCLI() {
        System.err.println("Usage: SU3File keygen       [-t type|code] publicKeyFile keystore.ks you@mail.i2p");
        System.err.println("       SU3File sign         [-c type|code] [-t type|code] inputFile.zip signedFile.su3 keystore.ks version you@mail.i2p");
        System.err.println("       SU3File bulksign     [-c type|code] [-t type|code] directory keystore.ks version you@mail.i2p");
        System.err.println("       SU3File showversion  signedFile.su3");
        System.err.println("       SU3File verifysig    signedFile.su3");
        System.err.println("       SU3File extract      signedFile.su3 outFile.zip");
        System.err.println(dumpTypes());
    }

    /** @since 0.9.9 */
    private static String dumpTypes() {
        StringBuilder buf = new StringBuilder(256);
        buf.append("Available signature types:\n");
        for (SigType t : EnumSet.allOf(SigType.class)) {
            buf.append("      ").append(t).append("\t(code: ").append(t.getCode()).append(')');
            if (t.getCode() == DEFAULT_SIG_CODE)
                buf.append(" DEFAULT");
            buf.append('\n');
        }
        buf.append("Available content types:\n");
        for (ContentType t : EnumSet.allOf(ContentType.class)) {
            buf.append("      ").append(t).append("\t(code: ").append(t.getCode()).append(')');
            if (t == DEFAULT_CONTENT_TYPE)
                buf.append(" DEFAULT");
            buf.append('\n');
        }
        return buf.toString();
    }

    /**
     *  @param stype number or name
     *  @return null if not found
     *  @since 0.9.9
     */
    private static SigType parseSigType(String stype) {
        try {
            return SigType.valueOf(stype.toUpperCase(Locale.US));
        } catch (IllegalArgumentException iae) {
            try {
                int code = Integer.parseInt(stype);
                return SigType.getByCode(code);
            } catch (NumberFormatException nfe) {
                return null;
             }
        }
    }
    /**
     *  @param stype number or name
     *  @return null if not found
     *  @since 0.9.9
     */
    private static ContentType parseContentType(String ctype) {
        try {
            return ContentType.valueOf(ctype.toUpperCase(Locale.US));
        } catch (IllegalArgumentException iae) {
            try {
                int code = Integer.parseInt(ctype);
                return ContentType.getByCode(code);
            } catch (NumberFormatException nfe) {
                return null;
             }
        }
    }

    /** @return success */
    private static final boolean showVersionCLI(String signedFile) {
        try {
            SU3File file = new SU3File(signedFile);
            String versionString = file.getVersionString();
            if (versionString.equals(""))
                System.out.println("No version string found in file '" + signedFile + "'");
            else
                System.out.println("Version:  " + versionString);
            String signerString = file.getSignerString();
            if (signerString.equals(""))
                System.out.println("No signer string found in file '" + signedFile + "'");
            else
                System.out.println("Signer:   " + signerString);
            if (file._sigType != null)
                System.out.println("SigType:  " + file._sigType);
            return !versionString.equals("");
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        }
    }

    /**
     *  @return success
     *  @since 0.9.9
     */
    private static final boolean bulkSignCLI(String stype, String ctype, String dir,
                                         String privateKeyFile, String version, String signerName) {
        File d = new File(dir);
        if (!d.isDirectory()) {
            System.out.println("Directory does not exist: " + d);
            return false;
        }
        File[] files = d.listFiles();
        if (files == null || files.length == 0) {
            System.out.println("No zip files found in " + d);
            return false;
        }
        String keypw = "";
        try {
            while (keypw.length() < 6) {
                System.out.print("Enter password for key \"" + signerName + "\": ");
                keypw = DataHelper.readLine(System.in).trim();
                if (keypw.length() > 0 && keypw.length() < 6)
                    System.out.println("Key password must be at least 6 characters");
            }
        } catch (IOException ioe) {
            System.out.println("Error asking for password");
            ioe.printStackTrace();
            return false;
        }
        int success = 0;
        for (File in : files) {
            String inputFile = in.getPath();
            if (!inputFile.endsWith(".zip"))
                continue;
            String signedFile = inputFile.substring(0, inputFile.length() - 4) + ".su3";
            boolean rv = signCLI(stype, ctype, inputFile, signedFile, privateKeyFile, version, signerName, keypw);
            if (!rv)
                return false;
            success++;
        }
        if (success == 0)
            System.out.println("No files processed in " + d);
        return success > 0;
    }

    /**
     *  @return success
     *  @since 0.9.9
     */
    private static final boolean signCLI(String stype, String ctype, String inputFile, String signedFile,
                                         String privateKeyFile, String version, String signerName, String keypw) {
        SigType type = stype == null ? SigType.getByCode(Integer.valueOf(DEFAULT_SIG_CODE)) : parseSigType(stype);
        if (type == null) {
            System.out.println("Signature type " + stype + " is not supported");
            return false;
        }
        ContentType ct = ctype == null ? DEFAULT_CONTENT_TYPE : parseContentType(ctype);
        if (ct == null) {
            System.out.println("Content type " + ctype + " is not supported");
            return false;
        }
        return signCLI(type, ct, inputFile, signedFile, privateKeyFile, version, signerName, keypw);
    }

    /**
     *  @return success
     *  @since 0.9.9
     */
    private static final boolean signCLI(SigType type, ContentType ctype, String inputFile, String signedFile,
                                         String privateKeyFile, String version, String signerName, String keypw) {
        try {
            while (keypw.length() < 6) {
                System.out.print("Enter password for key \"" + signerName + "\": ");
                keypw = DataHelper.readLine(System.in).trim();
                if (keypw.length() > 0 && keypw.length() < 6)
                    System.out.println("Key password must be at least 6 characters");
            }
            File pkfile = new File(privateKeyFile);
            PrivateKey pk = KeyStoreUtil.getPrivateKey(pkfile,KeyStoreUtil.DEFAULT_KEYSTORE_PASSWORD, signerName, keypw);
            if (pk == null) {
                System.out.println("Private key for " + signerName + " not found in keystore " + privateKeyFile);
                return false;
            }
            SU3File file = new SU3File(signedFile);
            file.write(new File(inputFile), ctype.getCode(), version, signerName, pk, type);
            System.out.println("Input file '" + inputFile + "' signed and written to '" + signedFile + "'");
            return true;
        } catch (GeneralSecurityException gse) {
            System.out.println("Error signing input file '" + inputFile + "'");
            gse.printStackTrace();
            return false;
        } catch (IOException ioe) {
            System.out.println("Error signing input file '" + inputFile + "'");
            ioe.printStackTrace();
            return false;
        }
    }

    /** @return valid */
    private static final boolean verifySigCLI(String signedFile) {
        InputStream in = null;
        try {
            SU3File file = new SU3File(signedFile);
            boolean isValidSignature = file.verify();
            if (isValidSignature)
                System.out.println("Signature VALID (signed by " + file.getSignerString() + ' ' + file._sigType + ')');
            else
                System.out.println("Signature INVALID (signed by " + file.getSignerString() + ' ' + file._sigType +')');
            return isValidSignature;
        } catch (IOException ioe) {
            System.out.println("Error verifying input file '" + signedFile + "'");
            ioe.printStackTrace();
            return false;
        }
    }

    /**
     *  @return success
     *  @since 0.9.9
     */
    private static final boolean extractCLI(String signedFile, String outFile) {
        InputStream in = null;
        try {
            SU3File file = new SU3File(signedFile);
            File out = new File(outFile);
            boolean ok = file.verifyAndMigrate(out);
            if (ok)
                System.out.println("File extracted (signed by " + file.getSignerString() + ' ' + file._sigType + ')');
            else
                System.out.println("Signature INVALID (signed by " + file.getSignerString() + ' ' + file._sigType +')');
            return ok;
        } catch (IOException ioe) {
            System.out.println("Error extracting from file '" + signedFile + "'");
            ioe.printStackTrace();
            return false;
        }
    }

    /**
     *  @return success
     *  @since 0.9.9
     */
    private static final boolean genKeysCLI(String stype, String publicKeyFile, String privateKeyFile, String alias) {
        SigType type = stype == null ? SigType.getByCode(Integer.valueOf(DEFAULT_SIG_CODE)) : parseSigType(stype);
        if (type == null) {
            System.out.println("Signature type " + stype + " is not supported");
            return false;
        }
        return genKeysCLI(type, publicKeyFile, privateKeyFile, alias);
    }

    /**
     *  Writes Java-encoded keys (X.509 for public and PKCS#8 for private)
     *  @return success
     *  @since 0.9.9
     */
    private static final boolean genKeysCLI(SigType type, String publicKeyFile, String privateKeyFile, String alias) {
        File pubFile = new File(publicKeyFile);
        if (pubFile.exists()) {
            System.out.println("Error: Not overwriting file " + publicKeyFile);
            return false;
        }
        File ksFile = new File(privateKeyFile);
        String keypw = "";
        try {
            while (alias.length() == 0) {
                System.out.print("Enter key name (example@mail.i2p): ");
                alias = DataHelper.readLine(System.in).trim();
            }
            while (keypw.length() < 6) {
                System.out.print("Enter new key password: ");
                keypw = DataHelper.readLine(System.in).trim();
                if (keypw.length() > 0 && keypw.length() < 6)
                    System.out.println("Key password must be at least 6 characters");
            }
        } catch (IOException ioe) {
            return false;
        }
        int keylen = type.getPubkeyLen() * 8;
        if (type.getBaseAlgorithm() == SigAlgo.EC) {
            keylen /= 2;
            if (keylen == 528)
                keylen = 521;
        }
        boolean success = KeyStoreUtil.createKeys(ksFile, KeyStoreUtil.DEFAULT_KEYSTORE_PASSWORD, alias,
                                                  alias, "I2P", 3652, type.getBaseAlgorithm().getName(),
                                                  keylen, keypw);
        if (!success) {
            System.err.println("Error creating keys for " + alias);
            return false;
        }
        File outfile = new File(publicKeyFile);
        success = KeyStoreUtil.exportCert(ksFile, KeyStoreUtil.DEFAULT_KEYSTORE_PASSWORD, alias, outfile);
        if (!success) {
            System.err.println("Error writing public key for " + alias + " to " + outfile);
            return false;
        }
        return true;
    }
}
