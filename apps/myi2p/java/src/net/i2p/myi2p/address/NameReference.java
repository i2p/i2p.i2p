package net.i2p.myi2p.address;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import net.i2p.I2PAppContext;

import net.i2p.data.DataHelper;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.util.Log;

/**
 * Define a verified and immutable reference to a particular I2P destination.
 *
 */
public class NameReference {
    private I2PAppContext _context;
    private Log _log;
    private Destination _destination;
    private String _preferredName;
    private String _serviceType;
    private long _sequenceNum;
    private Properties _options;
    private Signature _signature;
    
    public static final byte[] VERSION_PREFIX = "MyI2P_NameReference_1.0".getBytes();
    
    public NameReference(I2PAppContext context) {
        _context = context;
        _log = context.logManager().getLog(NameReference.class);
        _destination = null;
        _preferredName = null;
        _serviceType = null;
        _sequenceNum = -1;
        _options = new Properties();
        _signature = null;
    }
    
    /** retrieve the destination this reference points at */
    public Destination getDestination() { return _destination; }
    public void setDestination(Destination dest) { _destination = dest; }
    
    /** retrieve the name this destination would like to be called */
    public String getPreferredName() { return _preferredName; }
    public void setPreferredName(String name) { _preferredName = name; }
    
    /** retrieve the type of service at this destination (eepsite, ircd, etc) */
    public String getServiceType() { return _serviceType; }
    public void setServiceType(String type) { _serviceType = type; }
    
    /** 
     * data point to allow the reference to be updated.  The reference with the
     * larger sequence number should clobber an older reference.
     *
     */
    public long getSequenceNum() { return _sequenceNum; }
    public void setSequenceNum(long num) { _sequenceNum = num; }
    
    /** Get a list of option names (strings) */
    public Set getOptionNames() { return new HashSet(_options.keySet()); }
    
    /** Access any options published in the reference */
    public String getOption(String name) { return (String)_options.getProperty(name); }
    
    /**
     * Specify a particular value for an option.  If the value is null, the 
     * entry is removed.  The name cannot have an '=' or newline, and the 
     * value cannot have a newline.
     *
     * @throws IllegalArgumentException if the name or value is illegal
     */
    public void setOption(String name, String value) { 
        if (name == null) throw new IllegalArgumentException("Missing name");
        if (name.indexOf('=') != -1) 
            throw new IllegalArgumentException("Name cannot have an = sign");
        if (name.indexOf('\n') != -1)
            throw new IllegalArgumentException("Name cannot have a newline");
        
        if (value != null) {
            if (value.indexOf('\n') != -1)
                throw new IllegalArgumentException("Values do not allow newlines");
            _options.setProperty(name, value);
        } else {
            _options.remove(name);
        }
    }

    /** Access the DSA signature authenticating this reference */
    public Signature getSignature() { return _signature; }
    public void setSignature(Signature sig) { _signature = sig; }
    
    /** Verify the DSA signature, returning true if it is valid, false otherwise */
    public boolean validate() {
        try {
            byte raw[] = toSignableByteArray();
            return _context.dsa().verifySignature(_signature, raw, _destination.getSigningPublicKey());
        } catch (IllegalStateException ise) {
            return false;
        }
    }
    
    /**
     * Sign the data
     *
     * @throws IllegalStateException if the data is invalid
     */
    public void sign(SigningPrivateKey key) throws IllegalStateException {
        byte signable[] = toSignableByteArray();
        _signature = _context.dsa().sign(signable, key);
    }
    
    /**
     * Retrieve the full serialized and signed version of this name reference
     * 
     * @throws IllegalStateException if the signature or other data is invalid
     */
    public void write(OutputStream out) throws IllegalStateException, IOException {
        if (_signature == null) throw new IllegalStateException("No signature");
        byte signable[] = toSignableByteArray();
        out.write(signable);
        try {
            _signature.writeBytes(out);
        } catch (DataFormatException dfe) {
            throw new IllegalStateException("Signature was corrupt - " + dfe.getMessage());
        }
    }
    
    /**
     * Retrieve the signable (but not including the signature) name reference
     *
     * @throws IllegalStateException if the data is invalid
     */
    private byte[] toSignableByteArray() throws IllegalStateException {
        if (_sequenceNum < 0) throw new IllegalStateException("Sequence number is invalid");
        if (_preferredName == null) throw new IllegalStateException("Preferred name is invalid");
        if (_serviceType == null) throw new IllegalStateException("Service type is invalid");
        if (_options == null) throw new IllegalStateException("Options not constructed");
        if (_destination == null) throw new IllegalStateException("Destination not specified");
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
        try {
            baos.write(VERSION_PREFIX);
            _destination.writeBytes(baos);
            DataHelper.writeLong(baos, 4, _sequenceNum);
            DataHelper.writeString(baos, _preferredName);
            DataHelper.writeString(baos, _serviceType);
            DataHelper.writeProperties(baos, _options); // sorts alphabetically
            return baos.toByteArray();
        } catch (DataFormatException dfe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("Corrupted trying to write entry", dfe);
            return null;
        } catch (IOException ioe) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("IOError writing to memory?", ioe);
            return null;
        }
    }
    
    /**
     * Read a full signed reference from the given stream
     *
     * @throws DataFormatException if the reference is corrupt
     * @throws IOException if there is an error reading the stream
     */
    public void read(InputStream in) throws DataFormatException, IOException {
        byte versionBuf[] = new byte[VERSION_PREFIX.length];
        int len = DataHelper.read(in, versionBuf);
        if (len != versionBuf.length) 
            throw new IllegalArgumentException("Version length too short ("+ len + ")");
        if (!DataHelper.eq(versionBuf, VERSION_PREFIX))
            throw new IllegalArgumentException("Version mismatch (" + new String(versionBuf) + ")");
        Destination dest = new Destination();
        dest.readBytes(in);
        long seq = DataHelper.readLong(in, 4);
        String name = DataHelper.readString(in);
        String type = DataHelper.readString(in);
        Properties opts = DataHelper.readProperties(in);
        Signature sig = new Signature();
        sig.readBytes(in);
        
        // ok, nothing b0rked
        _destination = dest;
        _sequenceNum = seq;
        _preferredName = name;
        _serviceType = type;
        _options = opts;
        _signature = sig;
    }
}
