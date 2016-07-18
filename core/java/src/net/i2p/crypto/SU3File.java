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
import java.security.cert.X509Certificate;
import java.security.cert.X509CRL;
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

import gnu.getopt.Getopt;

import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Signature;
import net.i2p.data.SimpleDataStructure;
import net.i2p.util.SecureFileOutputStream;

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
    private int _signatureLength;
    private int _signerLength;
    private int _fileType = -1;
    private ContentType _contentType;
    private long _contentLength;
    private PublicKey _signerPubkey;
    private boolean _headerVerified;
    private SigType _sigType;
    private boolean _verifySignature = true;
    private File _certFile;

    public static final String MAGIC = "I2Psu3";
    private static final byte[] MAGIC_BYTES = DataHelper.getASCII(MAGIC);
    private static final int FILE_VERSION = 0;
    private static final int MIN_VERSION_BYTES = 16;
    private static final int VERSION_OFFSET = 40; // Signature.SIGNATURE_BYTES; avoid early ctx init

    /**
     *  The file type is advisory and is application-dependent.
     *  The following values are defined but any value 0-255 is allowed.
     */
    public static final int TYPE_ZIP = 0;
    /** @since 0.9.15 */
    public static final int TYPE_XML = 1;
    /** @since 0.9.15 */
    public static final int TYPE_HTML = 2;
    /** @since 0.9.17 */
    public static final int TYPE_XML_GZ = 3;

    public static final int CONTENT_UNKNOWN = 0;
    public static final int CONTENT_ROUTER = 1;
    public static final int CONTENT_PLUGIN = 2;
    public static final int CONTENT_RESEED = 3;
    /** @since 0.9.15 */
    public static final int CONTENT_NEWS = 4;

    /**
     *  The ContentType is the trust domain for the content.
     *  The signer and signature will be checked with the
     *  trusted certificates for that type.
     */
    private enum ContentType {
        UNKNOWN(CONTENT_UNKNOWN, "unknown"),
        ROUTER(CONTENT_ROUTER, "router"),
        PLUGIN(CONTENT_PLUGIN, "plugin"),
        RESEED(CONTENT_RESEED, "reseed"),
        NEWS(CONTENT_NEWS, "news")
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
    private static final int DEFAULT_SIG_CODE = 6;

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

    /**
     *  Should the signature be verified? Default true
     *  @since 0.9.15
     */
    public void setVerifySignature(boolean shouldVerify) {
        _verifySignature = shouldVerify;
    }

    /**
     *  Use this X.509 cert file for verification instead of $I2P/certificates/content_type/foo_at_mail.i2p
     *  @since 0.9.15
     */
    private void setPublicKeyCertificate(File certFile) {
        _certFile = certFile;
    }

    /**
     *  This does not check the signature, but it will fail if the signer is unknown,
     *  unless setVerifySignature(false) has been called.
     */
    public String getVersionString() throws IOException {
        verifyHeader();
        return _version;
    }

    /**
     *  This does not check the signature, but it will fail if the signer is unknown,
     *  unless setVerifySignature(false) has been called.
     */
    public String getSignerString() throws IOException {
        verifyHeader();
        return _signer;
    }

    /**
     *  This does not check the signature, but it will fail if the signer is unknown,
     *  unless setVerifySignature(false) has been called.
     *
     *  @return null if unknown
     *  @since 0.9.9
     */
    public SigType getSigType() throws IOException {
        verifyHeader();
        return _sigType;
    }

    /**
     *  The ContentType is the trust domain for the content.
     *  The signer and signature will be checked with the
     *  trusted certificates for that type.
     *
     *  This does not check the signature, but it will fail if the signer is unknown,
     *  unless setVerifySignature(false) has been called.
     *
     *  @return -1 if unknown
     *  @since 0.9.9
     */
    public int getContentType() throws IOException {
        verifyHeader();
        return _contentType != null ? _contentType.getCode() : -1;
    }

    /**
     *  The file type is advisory and is application-dependent.
     *  The following values are defined but any value 0-255 is allowed.
     *
     *  This does not check the signature, but it will fail if the signer is unknown,
     *  unless setVerifySignature(false) has been called.
     *
     *  @return 0-255 or -1 if unknown
     *  @since 0.9.15
     */
    public int getFileType() throws IOException {
        verifyHeader();
        return _fileType;
    }

    /**
     *  This does not check the signature, but it will fail if the signer is unknown,
     *  unless setVerifySignature(false) has been called.
     *
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
        byte[] magic = new byte[MAGIC_BYTES.length];
        DataHelper.read(in, magic);
        if (!DataHelper.eq(magic, MAGIC_BYTES))
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
        _signatureLength = (int) DataHelper.readLong(in, 2);
        if (_signatureLength != _sigType.getSigLen())
            throw new IOException("bad sig length");
        skip(in, 1);
        int _versionLength = in.read();
        if (_versionLength < MIN_VERSION_BYTES)
            throw new IOException("bad version length");
        skip(in, 1);
        _signerLength = in.read();
        if (_signerLength <= 0)
            throw new IOException("bad signer length");
        _contentLength = DataHelper.readLong(in, 8);
        if (_contentLength <= 0)
            throw new IOException("bad content length");
        skip(in, 1);
        _fileType = in.read();
        // Allow any file type
        //if (_fileType != TYPE_ZIP && _fileType != TYPE_XML)
        //    throw new IOException("bad file type");
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

        data = new byte[_signerLength];
        bytesRead = DataHelper.read(in, data);
        if (bytesRead != _signerLength)
            throw new EOFException();
        _signer = DataHelper.getUTF8(data);

        if (_verifySignature) {
            if (_certFile != null) {
                _signerPubkey = loadKey(_certFile);
            } else {
                // look in both install dir and config dir for the signer cert
                KeyRing ring = new DirKeyRing(new File(_context.getBaseDir(), "certificates"));
                try {
                    _signerPubkey = ring.getKey(_signer, _contentType.getName(), _sigType);
                } catch (GeneralSecurityException gse) {
                    IOException ioe = new IOException("keystore error");
                    ioe.initCause(gse);
                    throw ioe;
                }
                if (_signerPubkey == null) {
                    boolean diff = true;
                    try {
                        diff = !_context.getBaseDir().getCanonicalPath().equals(_context.getConfigDir().getCanonicalPath());
                    } catch (IOException ioe) {}
                    if (diff) {
                        ring = new DirKeyRing(new File(_context.getConfigDir(), "certificates"));
                        try {
                            _signerPubkey = ring.getKey(_signer, _contentType.getName(), _sigType);
                        } catch (GeneralSecurityException gse) {
                            IOException ioe = new IOException("keystore error");
                            ioe.initCause(gse);
                            throw ioe;
                        }
                    }
                    if (_signerPubkey == null)
                        throw new IOException("unknown signer: " + _signer + " for content type: " + _contentType.getName());
                }
            }
        }
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
            byte[] magic = new byte[MAGIC_BYTES.length];
            DataHelper.read(in, magic);
            if (!DataHelper.eq(magic, MAGIC_BYTES))
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
            if (_verifySignature) {
                if (_signerPubkey == null)
                    throw new IOException("unknown signer: " + _signer + " for content type: " + _contentType.getName());
            }
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
            if (_verifySignature) {
                byte[] sha = md.digest();
                din.on(false);
                Signature signature = new Signature(_sigType);
                signature.readBytes(in);
                int avail = in.available();
                if (avail > 0)
                    throw new IOException(avail + " bytes data after sig");
                SimpleDataStructure hash = _sigType.getHashInstance();
                hash.setData(sha);
                //System.out.println("hash\n" + HexDump.dump(sha));
                //System.out.println("sig\n" + HexDump.dump(signature.getData()));
                rv = _context.dsa().verifySignature(signature, hash, _signerPubkey);
            } else {
                rv = true;
            }
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
     *  @param fileType 0-255, 0 for zip
     *  @param contentType 0-255
     *  @param version 1-255 bytes when converted to UTF-8
     *  @param signer ID of the public key, 1-255 bytes when converted to UTF-8
     */
    public void write(File content, int fileType, int contentType, String version,
                      String signer, PrivateKey privkey, SigType sigType) throws IOException {
        InputStream in = null;
        DigestOutputStream out = null;
        boolean ok = false;
        try {
            in = new BufferedInputStream(new FileInputStream(content));
            MessageDigest md = sigType.getDigestInstance();
            out = new DigestOutputStream(new BufferedOutputStream(new FileOutputStream(_file)), md);
            out.write(MAGIC_BYTES);
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
            if (fileType < 0 || fileType > 255)
                throw new IllegalArgumentException("bad content type");
            out.write((byte) fileType);
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
        try {
            // defaults
            String stype = null;
            String ctype = null;
            String ftype = null;
            String kfile = null;
            String crlfile = null;
            String kspass = KeyStoreUtil.DEFAULT_KEYSTORE_PASSWORD;
            boolean error = false;
            boolean shouldVerify = true;
            Getopt g = new Getopt("SU3File", args, "t:c:f:k:xp:r:");
            int c;
            while ((c = g.getopt()) != -1) {
              switch (c) {
                case 't':
                    stype = g.getOptarg();
                    break;

                case 'c':
                    ctype = g.getOptarg();
                    break;

                case 'f':
                    ftype = g.getOptarg();
                    break;

                case 'k':
                    kfile = g.getOptarg();
                    break;

                case 'r':
                    crlfile = g.getOptarg();
                    break;

                case 'x':
                    shouldVerify = false;
                    break;

                case 'p':
                    kspass = g.getOptarg();
                    break;

                case '?':
                case ':':
                default:
                  error = true;
              }
            }

            int idx = g.getOptind();
            String cmd = args[idx];
            List<String> a = new ArrayList<String>(Arrays.asList(args).subList(idx + 1, args.length));

            if (error) {
                showUsageCLI();
            } else if ("showversion".equals(cmd)) {
                ok = showVersionCLI(a.get(0));
            } else if ("sign".equals(cmd)) {
                // speed things up by specifying a small PRNG buffer size
                Properties props = new Properties();
                props.setProperty("prng.bufferSize", "16384");
                new I2PAppContext(props);
                ok = signCLI(stype, ctype, ftype, a.get(0), a.get(1), a.get(2), a.get(3), a.get(4), "", kspass);
            } else if ("bulksign".equals(cmd)) {
                Properties props = new Properties();
                props.setProperty("prng.bufferSize", "16384");
                new I2PAppContext(props);
                ok = bulkSignCLI(stype, ctype, a.get(0), a.get(1), a.get(2), a.get(3), kspass);
            } else if ("verifysig".equals(cmd)) {
                ok = verifySigCLI(a.get(0), kfile);
            } else if ("keygen".equals(cmd)) {
                Properties props = new Properties();
                props.setProperty("prng.bufferSize", "16384");
                new I2PAppContext(props);
                ok = genKeysCLI(stype, a.get(0), a.get(1), crlfile, a.get(2), kspass);
            } else if ("extract".equals(cmd)) {
                ok = extractCLI(a.get(0), a.get(1), shouldVerify, kfile);
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
        System.err.println("Usage: SU3File keygen       [-t type|code] [-p keystorepw] [-r crlFile.crl] publicKeyFile.crt keystore.ks you@mail.i2p\n" +
                           "       SU3File sign         [-t type|code] [-c type|code] [-f type|code] [-p keystorepw] inputFile.zip signedFile.su3 keystore.ks version you@mail.i2p\n" +
                           "       SU3File bulksign     [-t type|code] [-c type|code] [-p keystorepw] directory keystore.ks version you@mail.i2p\n" +
                           "                            (signs all .zip, .xml, and .xml.gz files in the directory)\n" +
                           "       SU3File showversion  signedFile.su3\n" +
                           "       SU3File verifysig    [-k file.crt] signedFile.su3  ## -k use this pubkey cert for verification\n" +
                           "       SU3File extract      [-x] [-k file.crt] signedFile.su3 outFile   ## -x don't check sig");
        System.err.println("Default keystore password: \"" + KeyStoreUtil.DEFAULT_KEYSTORE_PASSWORD + '"');
        System.err.println(dumpTypes());
    }

    /** @since 0.9.9 */
    private static String dumpTypes() {
        StringBuilder buf = new StringBuilder(256);
        buf.append("Available signature types (-t):\n");
        for (SigType t : EnumSet.allOf(SigType.class)) {
            if (!t.isAvailable())
                continue;
            if (t == SigType.EdDSA_SHA512_Ed25519)
                continue; // not supported by keytool, and does double hashing right now
            buf.append("      ").append(t).append("\t(code: ").append(t.getCode()).append(')');
            if (t.getCode() == DEFAULT_SIG_CODE)
                buf.append(" DEFAULT");
            if (!t.isAvailable())
                buf.append(" UNAVAILABLE");
            buf.append('\n');
        }
        buf.append("Available content types (-c):\n");
        for (ContentType t : EnumSet.allOf(ContentType.class)) {
            buf.append("      ").append(t).append("\t(code: ").append(t.getCode()).append(')');
            if (t == DEFAULT_CONTENT_TYPE)
                buf.append(" DEFAULT");
            buf.append('\n');
        }
        buf.append("Available file types (-f):\n");
        buf.append("      ZIP\t(code: 0) DEFAULT\n");
        buf.append("      XML\t(code: 1)\n");
        buf.append("      HTML\t(code: 2)\n");
        buf.append("      XML_GZ\t(code: 3)\n");
        buf.append("      (user defined)\t(code: 4-255)\n");
        return buf.toString();
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
            file.setVerifySignature(false);
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
            if (file._contentType != null)
                System.out.println("Content:  " + file._contentType);
            String ftype;
            if (file._fileType == TYPE_ZIP)
                ftype = "ZIP";
            else if (file._fileType == TYPE_XML)
                ftype = "XML";
            else if (file._fileType == TYPE_HTML)
                ftype = "HTML";
            else if (file._fileType == TYPE_XML_GZ)
                ftype = "XML_GZ";
            else
                ftype = Integer.toString(file._fileType);
                System.out.println("FileType: " + ftype);
            return !versionString.equals("");
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return false;
        }
    }

    /**
     *  Zip, xml, and xml.gz only
     *  @return success
     *  @since 0.9.9
     */
    private static final boolean bulkSignCLI(String stype, String ctype, String dir,
                                     String privateKeyFile, String version, String signerName, String kspass) {
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
                keypw = DataHelper.readLine(System.in);
                if (keypw == null) {
                    System.out.println("\nEOF reading password");
                    return false;
                }
                keypw = keypw.trim();
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
            int len;
            String ftype;
            if (inputFile.endsWith(".zip")) {
                len = 4;
                ftype = "ZIP";
            } else if (inputFile.endsWith(".xml")) {
                len = 4;
                ftype = "XML";
            } else if (inputFile.endsWith(".xml.gz")) {
                len = 7;
                ftype = "XML_GZ";
            } else {
                continue;
            }
            String signedFile = inputFile.substring(0, inputFile.length() - len) + ".su3";
            boolean rv = signCLI(stype, ctype, ftype, inputFile, signedFile,
                                 privateKeyFile, version, signerName, keypw, kspass);
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
    private static final boolean signCLI(String stype, String ctype, String ftype, String inputFile, String signedFile,
                                         String privateKeyFile, String version, String signerName, String keypw, String kspass) {
        SigType type = stype == null ? SigType.getByCode(Integer.valueOf(DEFAULT_SIG_CODE)) : SigType.parseSigType(stype);
        if (type == null) {
            System.out.println("Signature type " + stype + " is not supported");
            return false;
        }
        ContentType ct = ctype == null ? DEFAULT_CONTENT_TYPE : parseContentType(ctype);
        if (ct == null) {
            System.out.println("Content type " + ctype + " is not supported");
            return false;
        }
        int ft = TYPE_ZIP;
        if (ftype != null) {
            if (ftype.equalsIgnoreCase("ZIP")) {
                ft = TYPE_ZIP;
            } else if (ftype.equalsIgnoreCase("XML")) {
                ft = TYPE_XML;
            } else if (ftype.equalsIgnoreCase("HTML")) {
                ft = TYPE_HTML;
            } else if (ftype.equalsIgnoreCase("XML_GZ")) {
                ft = TYPE_XML_GZ;
            } else {
                try {
                    ft = Integer.parseInt(ftype);
                } catch (NumberFormatException nfe) {
                    ft = -1;
                }
                if (ft < 0 || ft > 255) {
                    System.out.println("File type " + ftype + " is not supported");
                    return false;
                }
                if (ft  > TYPE_XML_GZ)
                    System.out.println("Warning: File type " + ftype + " is undefined");
            }
        }
        return signCLI(type, ct, ft, inputFile, signedFile, privateKeyFile, version, signerName, keypw, kspass);
    }

    /**
     *  @return success
     *  @since 0.9.9
     */
    private static final boolean signCLI(SigType type, ContentType ctype, int ftype, String inputFile, String signedFile,
                                         String privateKeyFile, String version, String signerName, String keypw, String kspass) {
        try {
            while (keypw.length() < 6) {
                System.out.print("Enter password for key \"" + signerName + "\": ");
                keypw = DataHelper.readLine(System.in);
                if (keypw == null) {
                    System.out.println("\nEOF reading password");
                    return false;
                }
                keypw = keypw.trim();
                if (keypw.length() > 0 && keypw.length() < 6)
                    System.out.println("Key password must be at least 6 characters");
            }
            File pkfile = new File(privateKeyFile);
            PrivateKey pk = KeyStoreUtil.getPrivateKey(pkfile, kspass, signerName, keypw);
            if (pk == null) {
                System.out.println("Private key for " + signerName + " not found in keystore " + privateKeyFile);
                return false;
            }
            // now fix the sig type based on the private key
            SigType oldType = type;
            type = SigUtil.fromJavaKey(pk).getType();
            if (oldType != type)
                System.out.println("Warning: Using private key type " + type + ", ignoring specified type " + oldType);
            SU3File file = new SU3File(signedFile);
            file.write(new File(inputFile), ftype, ctype.getCode(), version, signerName, pk, type);
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
    private static final boolean verifySigCLI(String signedFile, String pkFile) {
        InputStream in = null;
        try {
            SU3File file = new SU3File(signedFile);
            if (pkFile != null)
                file.setPublicKeyCertificate(new File(pkFile));
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
    private static final boolean extractCLI(String signedFile, String outFile, boolean verifySig, String pkFile) {
        InputStream in = null;
        try {
            SU3File file = new SU3File(signedFile);
            if (pkFile != null)
                file.setPublicKeyCertificate(new File(pkFile));
            file.setVerifySignature(verifySig);
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
     *  @param crlFile may be null; non-null to save
     *  @return success
     *  @since 0.9.9
     */
    private static final boolean genKeysCLI(String stype, String publicKeyFile, String privateKeyFile,
                                            String crlFile, String alias, String kspass) {
        SigType type = stype == null ? SigType.getByCode(Integer.valueOf(DEFAULT_SIG_CODE)) : SigType.parseSigType(stype);
        if (type == null) {
            System.out.println("Signature type " + stype + " is not supported");
            return false;
        }
        return genKeysCLI(type, publicKeyFile, privateKeyFile, crlFile, alias, kspass);
    }

    /**
     *  Writes Java-encoded keys (X.509 for public and PKCS#8 for private)
     *
     *  @param crlFile may be null; non-null to save
     *  @return success
     *  @since 0.9.9
     */
    private static final boolean genKeysCLI(SigType type, String publicKeyFile, String privateKeyFile,
                                            String crlFile, String alias, String kspass) {
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
                alias = DataHelper.readLine(System.in);
                if (alias == null) {
                    System.out.println("\nEOF reading key name");
                    return false;
                }
                alias = alias.trim();
            }
            while (keypw.length() < 6) {
                System.out.print("Enter new key password: ");
                keypw = DataHelper.readLine(System.in);
                if (keypw == null) {
                    System.out.println("\nEOF reading password");
                    return false;
                }
                keypw = keypw.trim();
                if (keypw.length() > 0 && keypw.length() < 6)
                    System.out.println("Key password must be at least 6 characters");
            }
        } catch (IOException ioe) {
            return false;
        }
        OutputStream out = null;
        try {
            Object[] rv =  KeyStoreUtil.createKeysAndCRL(ksFile, kspass, alias,
                                                         alias, "I2P", 3652, type, keypw);
            X509Certificate cert = (X509Certificate) rv[2];
            out = new SecureFileOutputStream(publicKeyFile);
            CertUtil.exportCert(cert, out);
            if (crlFile != null) {
                out.close();
                X509CRL crl = (X509CRL) rv[3];
                out = new SecureFileOutputStream(crlFile);
                CertUtil.exportCRL(crl, out);
            }
        } catch (GeneralSecurityException gse) {
            System.err.println("Error creating keys for " + alias);
            gse.printStackTrace();
            return false;
        } catch (IOException ioe) {
            System.err.println("Error creating keys for " + alias);
            ioe.printStackTrace();
            return false;
        } finally {
            if (out != null) try { out.close(); } catch (IOException ioe) {}
        }
        return true;
    }

    /**
     *  For the -k CLI option
     *  @return non-null, throws IOE on all errors
     *  @since 0.9.15
     */
    private static PublicKey loadKey(File kd) throws IOException {
        try {
            return CertUtil.loadKey(kd);
        } catch (GeneralSecurityException gse) {
            IOException ioe = new IOException("cert error");
            ioe.initCause(gse);
            throw ioe;
        }
    }
}
