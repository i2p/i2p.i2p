package net.i2p.syndie;

import java.io.*;
import java.util.*;
import net.i2p.I2PAppContext;
import net.i2p.data.*;
import net.i2p.syndie.data.*;

/**
 * Lazy loading wrapper for an entry, pulling data out of a cached & extracted dir,
 * rather than dealing with the crypto, zip, etc.
 *
 */
class CachedEntry extends EntryContainer {
    private File _entryDir;
    
    private int _format;
    private int _size;
    private BlogURI _blog;
    private Properties _headers;
    private Entry _entry;
    private Attachment _attachments[];
    
    public CachedEntry(File entryDir) {
        _entryDir = entryDir;
        importMeta();
        _entry = new CachedEntryDetails();
        _attachments = null;
    }
    
    // always available, loaded from meta
    public int getFormat() { return _format; }
    public BlogURI getURI() { return _blog; }
    public int getCompleteSize() { return _size; }
    
    // dont need to override it, as it works off getHeader
    //public String[] getTags() { return super.getTags(); }
    
    public Entry getEntry() { return _entry; }
    public Attachment[] getAttachments() {
        importAttachments();
        return _attachments; 
    }
    public String getHeader(String key) {
        importHeaders();
        return _headers.getProperty(key);
    }
    
    public String toString() { return getURI().toString(); }
    public boolean verifySignature(I2PAppContext ctx, BlogInfo info) { return true; }
    
    // not supported...
    public void parseRawData(I2PAppContext ctx) throws IOException { 
        throw new IllegalStateException("Not supported on cached entries"); 
    }
    public void parseRawData(I2PAppContext ctx, SessionKey zipKey) throws IOException {
        throw new IllegalStateException("Not supported on cached entries"); 
    }
    public void setHeader(String name, String val) {
        throw new IllegalStateException("Not supported on cached entries"); 
    }
    public void addAttachment(byte data[], String name, String description, String mimeType) {
        throw new IllegalStateException("Not supported on cached entries"); 
    }
    public void write(OutputStream out, boolean includeRealSignature) throws IOException { 
        throw new IllegalStateException("Not supported on cached entries"); 
    }
    public Signature getSignature() { 
        throw new IllegalStateException("Not supported on cached entries"); 
    }
    
    // now the actual lazy loading code
    private void importMeta() {
        Properties meta = readProps(new File(_entryDir, EntryExtractor.META));
        _format = getInt(meta, "format");
        _size = getInt(meta, "size");
        _blog = new BlogURI(new Hash(Base64.decode(meta.getProperty("blog"))), getLong(meta, "entry"));
    }
    
    private Properties importHeaders() {
        if (_headers == null) 
            _headers = readProps(new File(_entryDir, EntryExtractor.HEADERS));
        return _headers;
    }
    
    private void importAttachments() {
        if (_attachments == null) {
            List attachments = new ArrayList();
            int i = 0;
            while (true) {
                File meta = new File(_entryDir, EntryExtractor.ATTACHMENT_PREFIX + i + EntryExtractor.ATTACHMENT_META_SUFFIX);
                if (meta.exists())
                    attachments.add(new CachedAttachment(i, meta));
                else
                    break;
                i++;
            }
            Attachment a[] = new Attachment[attachments.size()];
            for (i = 0; i < a.length; i++)
                a[i] = (Attachment)attachments.get(i);
            _attachments = a;
        }
        return;
    }
    
    private static Properties readProps(File propsFile) {
        Properties rv = new Properties();
        BufferedReader in = null;
        try {
            in = new BufferedReader(new FileReader(propsFile));
            String line = null;
            while ( (line = in.readLine()) != null) {
                int split = line.indexOf('=');
                if ( (split <= 0) || (split >= line.length()) ) continue;
                rv.setProperty(line.substring(0, split).trim(), line.substring(split+1).trim());
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
        } finally {
            if (in != null) try { in.close(); } catch (IOException ioe) {}
        }
        return rv;
    }
    
    private static final int getInt(Properties props, String key) {
        String val = props.getProperty(key);
        try { return Integer.parseInt(val); } catch (NumberFormatException nfe) {}
        return -1;
    }
    private static final long getLong(Properties props, String key) {
        String val = props.getProperty(key);
        try { return Long.parseLong(val); } catch (NumberFormatException nfe) {}
        return -1l;
    }

    
    private class CachedEntryDetails extends Entry {
        private String _text;
        public CachedEntryDetails() {
            super(null);
        }
        public String getText() { 
            importText();
            return _text; 
        }
        private void importText() {
            if (_text == null) {
                InputStream in = null;
                try {
                    File f = new File(_entryDir, EntryExtractor.ENTRY);
                    byte buf[] = new byte[(int)f.length()]; // hmm
                    in = new FileInputStream(f);
                    int read = DataHelper.read(in, buf);
                    if (read != buf.length) throw new IOException("read: " + read + " file size: " + buf.length + " for " + f.getPath());
                    _text = new String(buf);
                } catch (IOException ioe) {
                    ioe.printStackTrace();
                } finally {
                    if (in != null) try { in.close(); } catch (IOException ioe) {}
                }
            }
        }
    }
    
    private class CachedAttachment extends Attachment {
        private int _attachmentId;
        private File _metaFile;
        private Properties _attachmentHeaders;
        private int _dataSize;
        
        public CachedAttachment(int id, File meta) {
            super(null, null);
            _attachmentId = id;
            _metaFile = meta;
            _attachmentHeaders = null;
        }

        public int getDataLength() { 
            importAttachmentHeaders();
            return _dataSize; 
        }
        
        public byte[] getData() { 
            throw new IllegalStateException("Not supported on cached entries"); 
        }
        public InputStream getDataStream() throws IOException { 
            String name = EntryExtractor.ATTACHMENT_PREFIX + _attachmentId + EntryExtractor.ATTACHMENT_DATA_SUFFIX;
            File f = new File(_entryDir, name);
            return new FileInputStream(f); 
        }

        public byte[] getRawMetadata() { 
            throw new IllegalStateException("Not supported on cached entries"); 
        }

        public String getMeta(String key) { 
            importAttachmentHeaders();
            return _attachmentHeaders.getProperty(key);
        }

        //public String getName() { return getMeta(NAME); }
        //public String getDescription() { return getMeta(DESCRIPTION); }
        //public String getMimeType() { return getMeta(MIMETYPE); }

        public void setMeta(String key, String val) {
            throw new IllegalStateException("Not supported on cached entries"); 
        }

        public Map getMeta() {
            importAttachmentHeaders();
            return _attachmentHeaders;
        }

        public String toString() { 
            importAttachmentHeaders();
            int len = _dataSize;
            return getName() 
                   + (getDescription() != null ? ": " + getDescription() : "") 
                   + (getMimeType() != null ? ", type: " + getMimeType() : "") 
                   + ", size: " + len; 
        }

        private void importAttachmentHeaders() {
            if (_attachmentHeaders == null) {
                Properties props = readProps(_metaFile);
                String sz = (String)props.remove(EntryExtractor.ATTACHMENT_DATA_SIZE);
                if (sz != null) {
                    try { 
                        _dataSize = Integer.parseInt(sz);
                    } catch (NumberFormatException nfe) {}
                }
                
                _attachmentHeaders = props;
            }
        }
    }
}
