package net.i2p.syndie.data;

import java.io.*;
import java.util.*;
import net.i2p.data.DataHelper;

/**
 *
 */
public class Attachment {
    private byte _data[];
    private byte _rawMetadata[];
    private List _keys;
    private List _values;
    
    public Attachment(byte data[], byte metadata[]) {
        _data = data;
        _rawMetadata = metadata;
        _keys = new ArrayList();
        _values = new ArrayList();
        parseMeta();
    }
    
    public static final String NAME = "Name";
    public static final String DESCRIPTION = "Description";
    public static final String MIMETYPE = "MimeType";
    
    public Attachment(byte data[], String name, String description, String mimeType) {
        _data = data;
        _keys = new ArrayList();
        _values = new ArrayList();
        _keys.add(NAME);
        _values.add(name);
        if ( (description != null) && (description.trim().length() > 0) ) {
            _keys.add(DESCRIPTION);
            _values.add(description);
        }
        if ( (mimeType != null) && (mimeType.trim().length() > 0) ) {
            _keys.add(MIMETYPE);
            _values.add(mimeType);
        }
        createMeta();
    }
    
    public byte[] getData() { return _data; }
    public int getDataLength() { return _data.length; }
    public byte[] getRawMetadata() { return _rawMetadata; }
    
    public InputStream getDataStream() throws IOException { return new ByteArrayInputStream(_data); }
    
    public String getMeta(String key) { 
        for (int i = 0; i < _keys.size(); i++) {
            if (key.equals(_keys.get(i)))
                return (String)_values.get(i);
        }
        return null;
    }
    
    public String getName() { return getMeta(NAME); }
    public String getDescription() { return getMeta(DESCRIPTION); }
    public String getMimeType() { return getMeta(MIMETYPE); }
    
    public void setMeta(String key, String val) {
        for (int i = 0; i < _keys.size(); i++) {
            if (key.equals(_keys.get(i))) {
                _values.set(i, val);
                return;
            }
        }
        _keys.add(key);
        _values.add(val);
    }
    
    public Map getMeta() {
        Map rv = new HashMap(_keys.size());
        for (int i = 0; i < _keys.size(); i++) {
            String k = (String)_keys.get(i);
            String v = (String)_values.get(i);
            rv.put(k,v);
        }
        return rv;
    }
    
    private void createMeta() {
        StringBuffer meta = new StringBuffer(64);
        for (int i = 0; i < _keys.size(); i++) {
            meta.append(_keys.get(i)).append(':').append(_values.get(i)).append('\n');
        }
        _rawMetadata = DataHelper.getUTF8(meta);
    }
    
    private void parseMeta() {
        if (_rawMetadata == null) return;
        String key = null;
        String val = null;
        int keyBegin = 0;
        int valBegin = -1;
        for (int i = 0; i < _rawMetadata.length; i++) {
            if (_rawMetadata[i] == ':') {
                key = DataHelper.getUTF8(_rawMetadata, keyBegin, i - keyBegin);
                valBegin = i + 1;
            } else if (_rawMetadata[i] == '\n') {
                val = DataHelper.getUTF8(_rawMetadata, valBegin, i - valBegin);
                _keys.add(key);
                _values.add(val);
                keyBegin = i + 1;
                key = null;
                val = null;
            }
        }
    }
    
    public String toString() { 
        int len = 0;
        if (_data != null)
            len = _data.length;
        return getName() 
               + (getDescription() != null ? ": " + getDescription() : "") 
               + (getMimeType() != null ? ", type: " + getMimeType() : "") 
               + ", size: " + len; 
    }
}
