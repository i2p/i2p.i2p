package net.i2p.myi2p.address;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.data.Destination;
import net.i2p.util.Log;

/**
 * CreateEntryCLI addressBookFile referenceFile localName subscriptionFrequencyHours [ key=value]*
 */
public class CreateEntryCLI {
    private I2PAppContext _context;
    private String _args[];
    private String _addressBook;
    private String _referenceFile;
    private String _localName;
    private int _subscriptionFrequencyHours;
    private Properties _options;
    
    public CreateEntryCLI(String args[]) {
        _context = new I2PAppContext();
        _args = args;
        _options = new Properties();
    }
    
    public void execute() {
        if (parseArgs())
            doExecute();
        else
            System.err.println("Usage: CreateEntryCLI addressBookFile referenceFile localName subscriptionFrequencyHours[ key=value]*");
    }
    
    private boolean parseArgs() {
        if ( (_args == null) || (_args.length < 3) ) 
            return false;
        _addressBook = _args[0];
        _referenceFile = _args[1];
        _localName = _args[2];
        try {
            _subscriptionFrequencyHours = Integer.parseInt(_args[3]);
        } catch (NumberFormatException nfe) {
            return false;
        }
        for (int i = 4; i < _args.length; i++) {
            int eq = _args[i].indexOf('=');
            if ( (eq <= 0) || (eq >= _args[i].length() - 1) )
                continue;
            String key = _args[i].substring(0,eq);
            String val = _args[i].substring(eq+1);
            _options.setProperty(key, val);
        }
        return true;
    }
    
    private void doExecute() {
        AddressBookServiceData data = new AddressBookServiceData(_context);
        File f = new File(_addressBook);
        if (f.exists()) {
            data.load(f);
            if (data.getError() != null) {
                if (data.getErrorMessage() != null)
                    System.err.println(data.getErrorMessage());
                data.getError().printStackTrace();
                return;
            }
        } else {
            data.setAddressBook(new AddressBook(_context));
            data.setActivityLog(new HashMap());
        }
        NameReference ref = null;
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(_referenceFile); 
            ref = new NameReference(_context);
            ref.read(fis);
        } catch (Exception e) {
            System.err.println("Name reference under " + _referenceFile + " is corrupt");
            e.printStackTrace();
            return;
        } finally {
            if (fis != null) try { fis.close(); } catch (IOException ioe) {}
        }
        
        AddressBook book = data.getAddressBook();
        Map activityLog = data.getActivityLog();
        
        AddressBookEntry oldEntry = book.getEntry(_localName);
        
        AddressBookEntry entry = new AddressBookEntry(_context);
        entry.setLocalName(_localName);
        entry.setNameReference(ref);
        
        Subscription sub = new Subscription(_context);
        sub.setQueryFrequencyMinutes(60*_subscriptionFrequencyHours);
        
        entry.setSubscription(sub);
        entry.setOptions(_options);

        if (oldEntry == null) {
            book.addEntry(entry);
            System.out.println("New address book entry added for " + entry.getLocalName());
            activityLog.put(new Long(_context.clock().now()), "New address book entry added for " + entry.getLocalName());
        } else {
            Destination oldDest = oldEntry.getNameReference().getDestination();
            if (oldDest.equals(ref.getDestination())) {
                if (ref.getSequenceNum() < oldEntry.getNameReference().getSequenceNum()) {
                    System.err.println("Not updating the address book - newer reference for " + entry.getLocalName() + " exists");
                    return;
                } else {
                    // same or newer rev
                    if (null != entry.getSubscription()) {
                        if (null != oldEntry.getSubscription()) {
                            entry.getSubscription().setLastQueryAttempt(oldEntry.getSubscription().getLastQueryAttempt());
                            entry.getSubscription().setLastQuerySuccess(oldEntry.getSubscription().getLastQuerySuccess());
                        }
                    }
                    book.addEntry(entry);
                    System.err.println("Updating the options and subscription for an existing reference to " + entry.getLocalName());
                    activityLog.put(new Long(_context.clock().now()), "Updating options and subscription for " + entry.getLocalName());
                }
            } else {
                book.addConflictingReference(ref);
                System.out.println("Old entry exists for " + _localName + " - adding a conflicting reference");
                System.out.println("Existing entry points to " + oldEntry.getNameReference().getDestination().calculateHash().toBase64());
                System.out.println("New entry points to " + entry.getNameReference().getDestination().calculateHash().toBase64());
                
                activityLog.put(new Long(_context.clock().now()), "Adding conflicting reference for " + entry.getLocalName());
            }
        }

        data.setAddressBook(book);
        data.setActivityLog(activityLog);
        
        data.store(f);
        if (data.getError() != null) {
            if (data.getErrorMessage() != null)
                System.err.println(data.getErrorMessage());
            data.getError().printStackTrace();
            return;
        }
    }
    
    public static void main(String args[]) {
        CreateEntryCLI cli = new CreateEntryCLI(args);
        cli.execute();
    }
}
