package net.i2p.myi2p.address;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import net.i2p.data.DataHelper;
import net.i2p.data.DataFormatException;
import net.i2p.util.Log;

import net.i2p.myi2p.Service;
import net.i2p.myi2p.ServiceImpl;
import net.i2p.myi2p.Node;
import net.i2p.myi2p.MyI2PMessage;

/**
 * Main service handler / coordinator for the MyI2P address book.
 *
 */
public class AddressBookService extends ServiceImpl {
    private Log _log;
    private AddressBook _addressBook;
    /** contains a mapping of event time (Long) to description (String) */
    private Map _activityLog;
    private String _addressBookFile;
    
    private static String PROP_ADDRESSBOOK_FILE = "datafile";
    private static String DEFAULT_ADDRESSBOOK_FILE = "addressbook.dat";
    
    public static final String SERVICE_TYPE = "AddressBook";
    public String getType() { return SERVICE_TYPE; }
    
    public void startup() {
        _log = getContext().logManager().getLog(AddressBookService.class);
        
        _addressBookFile = getOptions().getProperty(PROP_ADDRESSBOOK_FILE, DEFAULT_ADDRESSBOOK_FILE);
        File file = new File(_addressBookFile);
    
        if (file.exists()) {
            loadData(file);
        } else {
            _addressBook = new AddressBook(getContext());
            _activityLog = new HashMap(16);
        }
    }
    
    public void shutdown() {
        File file = new File(_addressBookFile);
        storeData(file);
    }
    
    public void receiveMessage(MyI2PMessage msg) {
        _log.info("Received a " + msg.getMessageType() + " from " 
                  + msg.getPeer().calculateHash().toBase64() 
                  + new String(msg.getPayload()));
    }
    
    /** load everything from disk */
    private void loadData(File dataFile) {
        AddressBookServiceData data = new AddressBookServiceData(getContext());
        data.load(dataFile);
        if (data.getErrorMessage() != null) {
            _log.warn(data.getErrorMessage(), data.getError());
            _addressBook = new AddressBook(getContext());
            _activityLog = new HashMap(16);
        } else {
            _addressBook = data.getAddressBook();
            _activityLog = data.getActivityLog();
        }
    }
    
    /** persist everything to disk */
    private void storeData(File dataFile) {
        AddressBookServiceData data = new AddressBookServiceData(getContext());
        data.setActivityLog(_activityLog);
        data.setAddressBook(_addressBook);
        data.store(dataFile);
        if (data.getErrorMessage() != null) {
            _log.warn(data.getErrorMessage(), data.getError());
        }
    }
}
