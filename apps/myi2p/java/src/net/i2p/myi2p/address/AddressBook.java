package net.i2p.myi2p.address;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.DataFormatException;

/**
 * Main lookup component for maintaining references to other I2P destinations.
 *
 */
public class AddressBook {
    private I2PAppContext _context;
    /** Name (String) to AddressBookEntry */
    private Map _entries;
    /** 
     * List of NameReference that has been received but whose preferred 
     * name conflicts with an existing entry.
     */
    private List _conflictingReferences;
    
    public AddressBook(I2PAppContext context) {
        _context = context;
        _entries = new HashMap(16);
        _conflictingReferences = new ArrayList(0);
    }
    
    /** retrieve a list of entry names (strings) */
    public Set getEntryNames() { 
        synchronized (_entries) { 
            return new HashSet(_entries.keySet()); 
        }
    }
    public AddressBookEntry getEntry(String name) { 
        synchronized (_entries) {
            return (AddressBookEntry)_entries.get(name); 
        }
    }
    public AddressBookEntry addEntry(AddressBookEntry entry) {
        synchronized (_entries) {
            return (AddressBookEntry)_entries.put(entry.getLocalName(), entry);
        }
    }
    public void removeEntry(String name) { 
        synchronized (_entries) {
            _entries.remove(name); 
        }
    }
    
    public int getConflictingReferenceCount() { 
        synchronized (_conflictingReferences) {
            return _conflictingReferences.size(); 
        }
    }
    public NameReference getConflictingReference(int index) { 
        synchronized (_conflictingReferences) {
            return (NameReference)_conflictingReferences.get(index); 
        }
    }
    public void addConflictingReference(NameReference ref) { 
        synchronized (_conflictingReferences) {
            _conflictingReferences.add(ref); 
        }
    }
    public void removeConflictingReference(int index) { 
        synchronized (_conflictingReferences) {
            _conflictingReferences.remove(index);
        }
    }
    
    public void read(InputStream in) throws IOException {
        try {
            int numEntries = (int)DataHelper.readLong(in, 2);
            if (numEntries < 0) throw new IOException("Corrupt AddressBook - " + numEntries + " entries?");
            for (int i = 0; i < numEntries; i++) {
                AddressBookEntry entry = new AddressBookEntry(_context);
                entry.read(in);
                addEntry(entry);
            }
            int numConflicting = (int)DataHelper.readLong(in, 2);
            if (numConflicting < 0) throw new IOException("Corrupt AddressBook - " + numConflicting + " conflicting?");
            for (int i = 0; i < numConflicting; i++) {
                NameReference ref = new NameReference(_context);
                ref.read(in);
                addConflictingReference(ref);
            }
        } catch (DataFormatException dfe) {
            throw new IOException("Corrupt address book - " + dfe.getMessage());
        }
    }
    public void write(OutputStream out) throws IOException {
        try {
            synchronized (_entries) {
                DataHelper.writeLong(out, 2, _entries.size());
                for (Iterator iter = _entries.values().iterator(); iter.hasNext(); ) {
                    AddressBookEntry entry = (AddressBookEntry)iter.next();
                    entry.write(out);
                }
            }
            synchronized (_conflictingReferences) {
                DataHelper.writeLong(out, 2, _conflictingReferences.size());
                for (int i = 0; i < _conflictingReferences.size(); i++) {
                    NameReference ref = (NameReference)_conflictingReferences.get(i);
                    ref.write(out);
                }
            }
        } catch (DataFormatException dfe) {
            throw new IOException("Corrupt address book - " + dfe.getMessage());
        }
    }
    
    public String toString() {
        return "Entries: " + _entries.size() + " conflicting: " + _conflictingReferences.size();
    }
}
