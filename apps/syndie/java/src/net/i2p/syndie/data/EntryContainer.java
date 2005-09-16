package net.i2p.syndie.data;

import java.io.*;
import java.util.*;
import java.util.zip.*;

import net.i2p.data.*;
import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * Securely wrap up an entry and any attachments.  Container format:<pre>
 * $format\n
 * [$key: $val\n]*
 * \n
 * Signature: $base64(DSA signature)\n
 * Size: sizeof(data)\n
 * [data bytes]
 * </pre>
 *
 * Required keys: 
 *  BlogKey: base64 of the SHA256 of the blog's public key
 *  BlogTags: tab delimited list of tags under which this entry should be organized
 *  BlogEntryId: base10 unique identifier of this entry within the key/path.  Typically starts
 *               as the current day (in unix time, milliseconds) plus further milliseconds for
 *               each entry within the day.
 *
 * The data bytes contains zip file, either in the clear or encrypted.  If the format
 * is encrypted, the BlogPath key will (likely) be encrypted as well.
 * 
 */
public class EntryContainer {
    private List _rawKeys;
    private List _rawValues;
    private Signature _signature;
    private byte _rawData[];
    
    private BlogURI _entryURI;
    private int _format;
    private Entry _entryData;
    private Attachment _attachments[];
    private int _completeSize;
    
    public static final int FORMAT_ZIP_UNENCRYPTED = 0;
    public static final int FORMAT_ZIP_ENCRYPTED = 1;
    public static final String FORMAT_ZIP_UNENCRYPTED_STR = "syndie.entry.zip-unencrypted";
    public static final String FORMAT_ZIP_ENCRYPTED_STR = "syndie.entry.zip-encrypted";
    
    public static final String HEADER_BLOGKEY = "BlogKey";
    public static final String HEADER_BLOGTAGS = "BlogTags";
    public static final String HEADER_ENTRYID = "BlogEntryId";
    
    public EntryContainer() {
        _rawKeys = new ArrayList();
        _rawValues = new ArrayList();
        _completeSize = -1;
    }
    
    public EntryContainer(BlogURI uri, String tags[], byte smlData[]) {
        this();
        _entryURI = uri;
        _entryData = new Entry(DataHelper.getUTF8(smlData));
        setHeader(HEADER_BLOGKEY, Base64.encode(uri.getKeyHash().getData()));
        StringBuffer buf = new StringBuffer();
        for (int i = 0; tags != null && i < tags.length; i++)
            buf.append(tags[i]).append('\t');
        setHeader(HEADER_BLOGTAGS, buf.toString());
        if (uri.getEntryId() < 0)
            uri.setEntryId(System.currentTimeMillis());
        setHeader(HEADER_ENTRYID, Long.toString(uri.getEntryId()));
    }
    
    public int getFormat() { return _format; }
    
    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
        int i = 0;
        while (true) {
            int c = in.read();
            if ( (c == (int)'\n') || (c == (int)'\r') ) {
                break;
            } else if (c == -1) {
                if (i == 0)
                    return null;
                else
                    break;
            } else {
                baos.write(c);
            }
            i++;
        }
        
        return DataHelper.getUTF8(baos.toByteArray());
        //BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"), 1);
        //String line = r.readLine();
        //return line;
    }
    
    public void load(InputStream source) throws IOException {
        String line = readLine(source);
        if (line == null) throw new IOException("No format line in the entry");
        //System.err.println("read container format line [" + line + "]");
        String fmt = line.trim();
        if (FORMAT_ZIP_UNENCRYPTED_STR.equals(fmt)) {
            _format = FORMAT_ZIP_UNENCRYPTED;
        } else if (FORMAT_ZIP_ENCRYPTED_STR.equals(fmt)) {
            _format = FORMAT_ZIP_ENCRYPTED;
        } else {
            throw new IOException("Unsupported entry format: " + fmt);
        }
        
        while ( (line = readLine(source)) != null) {
            //System.err.println("read container header line [" + line + "]");
            line = line.trim();
            int len = line.length();
            if (len <= 0)
                break;
            int split = line.indexOf(':');
            if (split <= 0) {
                throw new IOException("Invalid format of the syndie entry: line=" + line);
            } else if (split >= len - 2) {
                // foo:\n
                String key = line.substring(0, split);
                _rawKeys.add(key);
                _rawValues.add("");
            } else {
                String key = line.substring(0, split);
                String val = line.substring(split+1);
                _rawKeys.add(key);
                _rawValues.add(val);
            }
        }
        
        parseHeaders();
        
        String sigStr = readLine(source); 
        //System.err.println("read container signature line [" + line + "]");
        if ( (sigStr == null) || (sigStr.indexOf("Signature:") == -1) )
            throw new IOException("No signature line");
        sigStr = sigStr.substring("Signature:".length()+1).trim();
        
        _signature = new Signature(Base64.decode(sigStr));
        //System.out.println("Sig: " + _signature.toBase64());
        
        line = readLine(source); 
        //System.err.println("read container size line [" + line + "]");
        if (line == null)
            throw new IOException("No size line");
        line = line.trim();
        int dataSize = -1;
        try {
            int index = line.indexOf("Size:");
            if (index == 0)
                dataSize = Integer.parseInt(line.substring("Size:".length()+1).trim());
            else
                throw new IOException("Invalid size line");
        } catch (NumberFormatException nfe) {
            throw new IOException("Invalid entry size: " + line);
        }
        
        byte data[] = new byte[dataSize];
        int read = DataHelper.read(source, data);
        if (read != dataSize)
            throw new IOException("Incomplete entry: read " + read + " expected " + dataSize);
        
        _rawData = data;
    }
    
    public void seal(I2PAppContext ctx, SigningPrivateKey signingKey, SessionKey entryKey) throws IOException {
        Log l = ctx.logManager().getLog(getClass());
        if (l.shouldLog(Log.DEBUG))
            l.debug("Sealing " + _entryURI);
        if (entryKey == null)
            _format = FORMAT_ZIP_UNENCRYPTED;
        else
            _format = FORMAT_ZIP_ENCRYPTED;
        setHeader(HEADER_BLOGKEY, Base64.encode(_entryURI.getKeyHash().getData()));
        if (_entryURI.getEntryId() < 0)
            _entryURI.setEntryId(ctx.clock().now());
        setHeader(HEADER_ENTRYID, Long.toString(_entryURI.getEntryId()));
        _rawData = createRawData(ctx, entryKey);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
        write(baos, false);
        byte data[] = baos.toByteArray();
        _signature = ctx.dsa().sign(data, signingKey);
    }
    
    private byte[] createRawData(I2PAppContext ctx, SessionKey entryKey) throws IOException {
        byte raw[] = createRawData();
        if (entryKey != null) {
            byte iv[] = new byte[16];
            ctx.random().nextBytes(iv);
            byte rv[] = new byte[raw.length + iv.length];
            ctx.aes().encrypt(raw, 0, rv, iv.length, entryKey, iv, raw.length);
            System.arraycopy(iv, 0, rv, 0, iv.length);
            return rv;
        } else {
            return raw;
        }
    }
    
    private byte[] createRawData() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ZipOutputStream out = new ZipOutputStream(baos);
        ZipEntry ze = new ZipEntry(ZIP_ENTRY);
        byte data[] = DataHelper.getUTF8(_entryData.getText());
        ze.setTime(0);
        out.putNextEntry(ze);
        out.write(data);
        out.closeEntry();
        for (int i = 0; (_attachments != null) && (i < _attachments.length); i++) {
            ze = new ZipEntry(ZIP_ATTACHMENT_PREFIX + i + ZIP_ATTACHMENT_SUFFIX);
            data = _attachments[i].getData();
            out.putNextEntry(ze);
            out.write(data);
            out.closeEntry();
            ze = new ZipEntry(ZIP_ATTACHMENT_META_PREFIX + i + ZIP_ATTACHMENT_META_SUFFIX);
            data = _attachments[i].getRawMetadata();
            out.putNextEntry(ze);
            out.write(data);
            out.closeEntry();
        }
        out.finish();
        out.close();
        return baos.toByteArray();
    }
    
    public static final String ZIP_ENTRY = "entry.sml";
    public static final String ZIP_ATTACHMENT_PREFIX = "attachmentdata";
    public static final String ZIP_ATTACHMENT_SUFFIX = ".szd";
    public static final String ZIP_ATTACHMENT_META_PREFIX = "attachmentmeta";
    public static final String ZIP_ATTACHMENT_META_SUFFIX = ".szm";
    
    public void parseRawData(I2PAppContext ctx) throws IOException { parseRawData(ctx, null); }
    public void parseRawData(I2PAppContext ctx, SessionKey zipKey) throws IOException {
        int dataOffset = 0;
        if (zipKey != null) {
            byte iv[] = new byte[16];
            System.arraycopy(_rawData, 0, iv, 0, iv.length);
            ctx.aes().decrypt(_rawData, iv.length, _rawData, iv.length, zipKey, iv, _rawData.length - iv.length);
            dataOffset = iv.length;
        }
        
        ByteArrayInputStream in = new ByteArrayInputStream(_rawData, dataOffset, _rawData.length - dataOffset);
        ZipInputStream zi = new ZipInputStream(in);
        Map attachments = new HashMap();
        Map attachmentMeta = new HashMap();
        while (true) {
            ZipEntry entry = zi.getNextEntry();
            if (entry == null)
                break;
            
            ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
            byte buf[] = new byte[1024];
            int read = -1;
            while ( (read = zi.read(buf)) != -1) 
                out.write(buf, 0, read);
            
            byte entryData[] = out.toByteArray();
            
            String name = entry.getName();
            if (ZIP_ENTRY.equals(name)) {
                _entryData = new Entry(DataHelper.getUTF8(entryData));
            } else if (name.startsWith(ZIP_ATTACHMENT_PREFIX)) {
                attachments.put(name, (Object)entryData);
            } else if (name.startsWith(ZIP_ATTACHMENT_META_PREFIX)) {
                attachmentMeta.put(name, (Object)entryData);
            }
            
            //System.out.println("Read entry [" + name + "] with size=" + entryData.length);
        }
        
        _attachments = new Attachment[attachments.size()];
        
        for (int i = 0; i < attachments.size(); i++) {
            byte data[] = (byte[])attachments.get(ZIP_ATTACHMENT_PREFIX + i + ZIP_ATTACHMENT_SUFFIX);
            byte metadata[] = (byte[])attachmentMeta.get(ZIP_ATTACHMENT_META_PREFIX + i + ZIP_ATTACHMENT_META_SUFFIX);
            if ( (data != null) && (metadata != null) ) {
                _attachments[i] = new Attachment(data, metadata);
            } else {
                Log l = ctx.logManager().getLog(getClass());
                if (l.shouldLog(Log.WARN))
                    l.warn("Unable to get " + i + ": " + data + "/" + metadata);
            }
        }
        
        //System.out.println("Attachments: " + _attachments.length + "/" + attachments.size() + ": " + attachments);
    }
    
    public BlogURI getURI() { return _entryURI; }
    public static final String NO_TAGS_TAG = "[none]";
    private static final String NO_TAGS[] = new String[] { NO_TAGS_TAG };
    public String[] getTags() { 
        String tags = getHeader(HEADER_BLOGTAGS);
        if ( (tags == null) || (tags.trim().length() <= 0) ) {
            return NO_TAGS;
        } else {
            StringTokenizer tok = new StringTokenizer(tags, "\t");
            String rv[] = new String[tok.countTokens()];
            for (int i = 0; i < rv.length; i++)
                rv[i] = tok.nextToken().trim();
            return rv;
        }
    }
    public Signature getSignature() { return _signature; }
    public Entry getEntry() { return _entryData; }
    public Attachment[] getAttachments() { return _attachments; }
    
    public void setCompleteSize(int bytes) { _completeSize = bytes; }
    public int getCompleteSize() { return _completeSize; }
    
    public String getHeader(String key) {
        for (int i = 0; i < _rawKeys.size(); i++) {
            String k = (String)_rawKeys.get(i);
            if (k.equals(key))
                return (String)_rawValues.get(i);
        }
        return null;
    }
    
    public Map getHeaders() {
        Map rv = new HashMap(_rawKeys.size());
        for (int i = 0; i < _rawKeys.size(); i++) {
            String k = (String)_rawKeys.get(i);
            String v = (String)_rawValues.get(i);
            rv.put(k,v);
        }
        return rv;
    }
    
    public void setHeader(String name, String val) {
        int index = _rawKeys.indexOf(name);
        if (index < 0) {
            _rawKeys.add(name);
            _rawValues.add(val);
        } else {
            _rawValues.set(index, val);
        }
    }
    
    public void addAttachment(byte data[], String name, String description, String mimeType) {
        Attachment a = new Attachment(data, name, description, mimeType);
        int old = (_attachments == null ? 0 : _attachments.length);
        Attachment nv[] = new Attachment[old+1];
        if (old > 0)
            for (int i = 0; i < old; i++)
                nv[i] = _attachments[i];
        nv[old] = a;
        _attachments = nv;
    }
    
    private void parseHeaders() throws IOException {
        String keyHash = getHeader(HEADER_BLOGKEY);
        String idVal = getHeader(HEADER_ENTRYID);
        
        if (keyHash == null) {
            throw new IOException("Missing " + HEADER_BLOGKEY + " header");
        }
        
        long entryId = -1;
        if ( (idVal != null) && (idVal.length() > 0) ) {
            try {
                entryId = Long.parseLong(idVal.trim());
            } catch (NumberFormatException nfe) {
                throw new IOException("Invalid format of entryId (" + idVal + ")");
            }
        }
        
        _entryURI = new BlogURI(new Hash(Base64.decode(keyHash)), entryId);
    }
    
    public boolean verifySignature(I2PAppContext ctx, BlogInfo info) {
        if (_signature == null) throw new NullPointerException("sig is null");
        if (info == null) throw new NullPointerException("info is null");
        if (info.getKey() == null) throw new NullPointerException("info key is null");
        if (info.getKey().getData() == null) throw new NullPointerException("info key data is null");
        //System.out.println("Verifying " + _entryURI + " for " + info);
        
        ByteArrayOutputStream out = new ByteArrayOutputStream(_rawData.length + 512);
        try {
            write(out, false);
            byte dat[] = out.toByteArray();
            //System.out.println("Raw data to verify: " + ctx.sha().calculateHash(dat).toBase64() + " sig: " + _signature.toBase64());
            ByteArrayInputStream in = new ByteArrayInputStream(dat);
            boolean ok = ctx.dsa().verifySignature(_signature, in, info.getKey());
            if (!ok && info.getPosters() != null) {
                for (int i = 0; !ok && i < info.getPosters().length; i++) {
                    in.reset();
                    ok = ctx.dsa().verifySignature(_signature, in, info.getPosters()[i]);
                }
            }
            //System.out.println("Verified ok? " + ok + " key: " + info.getKey().calculateHash().toBase64());
            //new Exception("verifying").printStackTrace();
            return ok;
        } catch (IOException ioe) {
            //System.out.println("Verification failed! " + ioe.getMessage());
            return false;
        }
    }
    
    public void write(OutputStream out, boolean includeRealSignature) throws IOException {
        StringBuffer buf = new StringBuffer(512);
        switch (_format) {
            case FORMAT_ZIP_ENCRYPTED:
                buf.append(FORMAT_ZIP_ENCRYPTED_STR).append('\n');
                break;
            case FORMAT_ZIP_UNENCRYPTED:
                buf.append(FORMAT_ZIP_UNENCRYPTED_STR).append('\n');
                break;
            default:
                throw new IOException("Invalid format " + _format);
        }
        
        for (int i = 0; i < _rawKeys.size(); i++) {
            String k = (String)_rawKeys.get(i);
            buf.append(k.trim());
            buf.append(": ");
            buf.append(((String)_rawValues.get(i)).trim());
            buf.append('\n');
        }
        
        buf.append('\n');
        buf.append("Signature: ");
        if (includeRealSignature) 
            buf.append(Base64.encode(_signature.getData()));
        buf.append("\n");
        buf.append("Size: ").append(_rawData.length).append('\n');
        String str = buf.toString();
        
        //System.out.println("Writing raw: \n[" + str + "] / " + I2PAppContext.getGlobalContext().sha().calculateHash(str.getBytes()) + ", raw data: " + I2PAppContext.getGlobalContext().sha().calculateHash(_rawData).toBase64() + "\n");
        out.write(DataHelper.getUTF8(str));
        out.write(_rawData);
    }
    
    public String toString() { return _entryURI.toString(); }
}
