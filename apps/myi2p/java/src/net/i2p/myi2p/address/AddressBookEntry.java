package net.i2p.myi2p.address;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.util.Date;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.DataFormatException;

/**
 * Implements a local address book entry, pointing at a known secure 
 * NameReference as well as an optional Subscription.
 *
 */
public class AddressBookEntry {
    private I2PAppContext _context;
    private String _localName;
    private Properties _options;
    private NameReference _reference;
    private Subscription _subscription;
    private long _addedOn;
    
    public AddressBookEntry(I2PAppContext context) {
        _context = context;
        _localName = null;
        _options = new Properties();
        _reference = null;
        _subscription = null;
        _addedOn = context.clock().now();
    }
    
    /** Local (unique) name we use to reference the given destination */
    public String getLocalName() { return _localName; }
    public void setLocalName(String name) { _localName = name; }
    
    public Properties getOptions() { 
        synchronized (_options) {
            return new Properties(_options);
        }
    }
    public void setOptions(Properties props) { 
        synchronized (_options) {
            _options.clear();
            if (props != null)
                _options.putAll(props);
        }
    }
    
    /** Secure name reference, provided by the destination */
    public NameReference getNameReference() { return _reference; }
    public void setNameReference(NameReference ref) { _reference = ref; }
    
    /** 
     * If specified, the details of our subscription to the MyI2P address
     * book at the referenced destination.
     *
     */
    public Subscription getSubscription() { return _subscription; }
    public void setSubscription(Subscription sub) { _subscription = sub; }
    
    /** When this entry was added */
    public long getAddedOn() { return _addedOn; }
    public void setAddedOn(long when) { _addedOn = when; }
    
    /** load the data from the stream */
    public void read(InputStream in) throws IOException {
        try {
            Boolean localNameDefined = DataHelper.readBoolean(in);
            if ( (localNameDefined != null) && (localNameDefined.booleanValue()) ) 
                _localName = DataHelper.readString(in);
            else
                _localName = null;
            
            Date when = DataHelper.readDate(in);
            if (when == null)
                _addedOn = -1;
            else
                _addedOn = when.getTime();
            
            Properties props = DataHelper.readProperties(in);
            setOptions(props);
            
            Boolean refDefined = DataHelper.readBoolean(in);
            if ( (refDefined != null) && (refDefined.booleanValue()) ) {
                _reference = new NameReference(_context);
                _reference.read(in);
            } else {
                _reference = null;
            }
            
            Boolean subDefined = DataHelper.readBoolean(in);
            if ( (subDefined != null) && (subDefined.booleanValue()) ) {
                Subscription sub = new Subscription(_context);
                sub.read(in);
                _subscription = sub;
            } else {
                _subscription = null;
            }
            
        } catch (DataFormatException dfe) {
            throw new IOException("Corrupt subscription: " + dfe.getMessage());
        }
    }
    
    /** persist the data to the stream */
    public void write(OutputStream out) throws IOException {
        try {
            if ( (_localName != null) && (_localName.trim().length() > 0) ) {
                DataHelper.writeBoolean(out, Boolean.TRUE);
                DataHelper.writeString(out, _localName);
            } else {
                DataHelper.writeBoolean(out, Boolean.FALSE);
            }
            
            DataHelper.writeDate(out, new Date(_addedOn));
            
            synchronized (_options) {
                DataHelper.writeProperties(out, _options);
            }
            
            if (_reference != null) {
                DataHelper.writeBoolean(out, Boolean.TRUE);
                _reference.write(out);
            } else {
                DataHelper.writeBoolean(out, Boolean.FALSE);
            }
            
            if (_subscription != null) {
                DataHelper.writeBoolean(out, Boolean.TRUE);
                _subscription.write(out);
            } else {
                DataHelper.writeBoolean(out, Boolean.FALSE);
            }
        } catch (DataFormatException dfe) {
            throw new IOException("Corrupt subscription: " + dfe.getMessage());
        }
    }
}
